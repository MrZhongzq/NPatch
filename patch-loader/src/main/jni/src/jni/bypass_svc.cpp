#include "bypass_svc.h"
#include "common/logging.h"
#include "npatch_compat.h"
#include <unistd.h>
#include <sys/syscall.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <signal.h>
#include <ucontext.h>
#include <pthread.h>
#include <fcntl.h>
#include <limits.h>
#include <cstddef>
#include <cstring>
#include <cstdlib>
#include <cerrno>
#include <atomic>
#include <mutex>
#include <string>

#include <linux/seccomp.h>
#include <linux/filter.h>
#include <linux/audit.h>
#include <linux/futex.h>

namespace vector::native {

    // Ported from the upstream "seccomp v2" (funpatch_seccomp): the previous implementation
    // trapped only openat AND re-executed the syscall WITHOUT rewriting the path, so lv4 was
    // effectively a no-op that only added the Gecko/seccomp conflict. This version actually
    // redirects the target apk path across the full set of path-taking syscalls, catching
    // direct syscalls that bypass the libc/PLT openat hook.
    static bool g_is_hook_active = false;

#if defined(__aarch64__)

    struct SeccompRequest {
        long sys_no;
        long args[6];
        long result;
        std::atomic<int> state;
    };

    static pthread_t g_trusted_thread;
    static int g_req_pipe[2] = {-1, -1};
    static bool g_trusted_thread_ready = false;
    static bool g_filter_enabled = false;
    static char g_target_path[PATH_MAX] = {0};
    static char g_redirect_path[PATH_MAX] = {0};
    static std::mutex g_path_mutex;
    static thread_local std::string g_redirect_buffer;

    static void copy_path(char* dest, const char* src) {
        if (src == nullptr) {
            dest[0] = '\0';
            return;
        }
        strncpy(dest, src, PATH_MAX - 1);
        dest[PATH_MAX - 1] = '\0';
    }

    static inline void futex_wait(std::atomic<int>* uaddr, int val) {
        syscall(__NR_futex, uaddr, FUTEX_WAIT_PRIVATE, val, nullptr, nullptr, 0);
    }

    static inline void futex_wake(std::atomic<int>* uaddr) {
        syscall(__NR_futex, uaddr, FUTEX_WAKE_PRIVATE, 1, nullptr, nullptr, 0);
    }

    static bool is_redirected_syscall(long sys_no) {
        switch (sys_no) {
            case __NR_openat:
                return true;
#ifdef __NR_readlinkat
            case __NR_readlinkat:
                return true;
#endif
#ifdef __NR_faccessat
            case __NR_faccessat:
                return true;
#endif
#ifdef __NR_faccessat2
            case __NR_faccessat2:
                return true;
#endif
#ifdef __NR_statx
            case __NR_statx:
                return true;
#endif
#ifdef __NR_newfstatat
            case __NR_newfstatat:
                return true;
#endif
#ifdef __NR_openat2
            case __NR_openat2:
                return true;
#endif
            default:
                return false;
        }
    }

    // All redirected syscalls take the path as the second argument (dirfd, path, ...).
    static const char* resolve_redirect_path(const char* pathname) {
        if (pathname == nullptr) {
            return nullptr;
        }

        std::scoped_lock lock(g_path_mutex);
        if (g_target_path[0] == '\0'
                || g_redirect_path[0] == '\0'
                || pathname[0] != g_target_path[0]
                || strcmp(pathname, g_target_path) != 0) {
            return pathname;
        }

        g_redirect_buffer = g_redirect_path;
        return g_redirect_buffer.c_str();
    }

    // Trusted thread: it is NOT subject to the SIGSYS trap (it never triggers the filter for
    // these syscalls because it performs them on behalf of the faulting thread), so it can
    // rewrite the path and execute the real syscall, returning the genuine kernel result.
    static void* trusted_thread_loop(void*) {
        LOGD("SvcBypass: trusted thread started (tid=%d)", gettid());
        while (true) {
            SeccompRequest* req = nullptr;
            ssize_t bytes_read = read(g_req_pipe[0], &req, sizeof(req));
            if (bytes_read == -1 && errno == EINTR) {
                continue;
            }
            if (bytes_read != sizeof(req) || req == nullptr) {
                continue;
            }

            if (is_redirected_syscall(req->sys_no)) {
                const char* pathname = reinterpret_cast<const char*>(req->args[1]);
                const char* redirected_path = resolve_redirect_path(pathname);
                if (redirected_path != pathname && redirected_path != nullptr) {
                    LOGD("SvcBypass: redirect '%s' -> '%s'", pathname, redirected_path);
                    req->args[1] = reinterpret_cast<long>(redirected_path);
                }
            }

            req->result = syscall(req->sys_no, req->args[0], req->args[1], req->args[2],
                                  req->args[3], req->args[4], req->args[5]);
            if (req->result == -1) {
                req->result = -errno;
            }

            req->state.store(1, std::memory_order_release);
            futex_wake(&req->state);
        }
        return nullptr;
    }

    // Async-signal-safe: hands the syscall off to the trusted thread via a pipe write (which
    // is async-signal-safe, unlike mutex/condvar) and blocks on a futex for the result.
    static void sigsys_handler(int signo, siginfo_t*, void* context) {
        if (signo != SIGSYS) return;

        auto* ctx = reinterpret_cast<ucontext_t*>(context);
        SeccompRequest req;
        req.sys_no = ctx->uc_mcontext.regs[8];
        for (int i = 0; i < 6; ++i) {
            req.args[i] = ctx->uc_mcontext.regs[i];
        }
        req.state.store(0, std::memory_order_relaxed);

        SeccompRequest* req_ptr = &req;
        ssize_t written = write(g_req_pipe[1], &req_ptr, sizeof(req_ptr));
        if (written != sizeof(req_ptr)) {
            ctx->uc_mcontext.regs[0] = -EAGAIN;
            return;
        }

        while (req.state.load(std::memory_order_acquire) == 0) {
            futex_wait(&req.state, 0);
        }

        ctx->uc_mcontext.regs[0] = req.result;
    }

    static bool ensure_trusted_thread() {
        if (g_trusted_thread_ready) {
            return true;
        }

        if (pipe2(g_req_pipe, O_CLOEXEC) != 0) {
            LOGE("SvcBypass: failed to create request pipe");
            return false;
        }

        if (pthread_create(&g_trusted_thread, nullptr, trusted_thread_loop, nullptr) != 0) {
            LOGE("SvcBypass: failed to create trusted thread");
            close(g_req_pipe[0]);
            close(g_req_pipe[1]);
            g_req_pipe[0] = -1;
            g_req_pipe[1] = -1;
            return false;
        }

        struct sigaction sa;
        memset(&sa, 0, sizeof(sa));
        sa.sa_sigaction = sigsys_handler;
        sa.sa_flags = SA_SIGINFO | SA_NODEFER;
        if (sigaction(SIGSYS, &sa, nullptr) < 0) {
            LOGE("SvcBypass: failed to register SIGSYS handler");
            close(g_req_pipe[0]);
            close(g_req_pipe[1]);
            g_req_pipe[0] = -1;
            g_req_pipe[1] = -1;
            return false;
        }

        g_trusted_thread_ready = true;
        return true;
    }

    static bool install_seccomp_filter() {
        if (g_filter_enabled) {
            return true;
        }

        struct sock_filter filter[] = {
                BPF_STMT(BPF_LD + BPF_W + BPF_ABS, offsetof(struct seccomp_data, nr)),
                BPF_JUMP(BPF_JMP + BPF_JEQ + BPF_K, __NR_openat, 0, 1),
                BPF_STMT(BPF_RET + BPF_K, SECCOMP_RET_TRAP),
#ifdef __NR_readlinkat
                BPF_JUMP(BPF_JMP + BPF_JEQ + BPF_K, __NR_readlinkat, 0, 1),
                BPF_STMT(BPF_RET + BPF_K, SECCOMP_RET_TRAP),
#endif
#ifdef __NR_faccessat
                BPF_JUMP(BPF_JMP + BPF_JEQ + BPF_K, __NR_faccessat, 0, 1),
                BPF_STMT(BPF_RET + BPF_K, SECCOMP_RET_TRAP),
#endif
#ifdef __NR_faccessat2
                BPF_JUMP(BPF_JMP + BPF_JEQ + BPF_K, __NR_faccessat2, 0, 1),
                BPF_STMT(BPF_RET + BPF_K, SECCOMP_RET_TRAP),
#endif
#ifdef __NR_statx
                BPF_JUMP(BPF_JMP + BPF_JEQ + BPF_K, __NR_statx, 0, 1),
                BPF_STMT(BPF_RET + BPF_K, SECCOMP_RET_TRAP),
#endif
#ifdef __NR_newfstatat
                BPF_JUMP(BPF_JMP + BPF_JEQ + BPF_K, __NR_newfstatat, 0, 1),
                BPF_STMT(BPF_RET + BPF_K, SECCOMP_RET_TRAP),
#endif
#ifdef __NR_openat2
                BPF_JUMP(BPF_JMP + BPF_JEQ + BPF_K, __NR_openat2, 0, 1),
                BPF_STMT(BPF_RET + BPF_K, SECCOMP_RET_TRAP),
#endif
                BPF_STMT(BPF_RET + BPF_K, SECCOMP_RET_ALLOW),
        };

        struct sock_fprog prog = {
                .len = static_cast<unsigned short>(sizeof(filter) / sizeof(filter[0])),
                .filter = filter,
        };

        if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) != 0) {
            LOGE("SvcBypass: prctl(NO_NEW_PRIVS) failed");
            return false;
        }

        if (prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &prog) != 0) {
            LOGE("SvcBypass: prctl(SECCOMP) failed");
            return false;
        }

        g_filter_enabled = true;
        LOGI("SvcBypass: seccomp v2 filter applied (ARM64)");
        return true;
    }

#endif // __aarch64__


    // -------------------------------------------------------------------------
    // JNI interface (unchanged surface; internals upgraded above)
    // -------------------------------------------------------------------------

    LSP_DEF_NATIVE_METHOD(jboolean, SvcBypass, initSvcHook) {
        if (g_is_hook_active) return JNI_TRUE;

#if defined(__aarch64__)
        if (!ensure_trusted_thread()) {
            return JNI_FALSE;
        }
        g_is_hook_active = true;
        LOGI("SvcBypass: Initialized successfully (ARM64)");
        return JNI_TRUE;
#else
        g_is_hook_active = true;
        LOGI("SvcBypass: Skipped on non-ARM64 architecture");
        return JNI_TRUE;
#endif
    }

    LSP_DEF_NATIVE_METHOD(void, SvcBypass, enableSvcRedirect,
            jstring path, jstring orig, jstring pkg) {
        if (!g_is_hook_active) {
            LOGW("SvcBypass: Hook not initialized.");
            return;
        }

#if defined(__aarch64__)
        {
            const char* target = path ? env->GetStringUTFChars(path, nullptr) : nullptr;
            const char* redirect = orig ? env->GetStringUTFChars(orig, nullptr) : nullptr;
            {
                std::scoped_lock lock(g_path_mutex);
                copy_path(g_target_path, target);
                copy_path(g_redirect_path, redirect);
            }
            if (target) env->ReleaseStringUTFChars(path, target);
            if (redirect) env->ReleaseStringUTFChars(orig, redirect);
        }
        LOGI("SvcBypass: redirect target set: %s -> %s", g_target_path, g_redirect_path);
        install_seccomp_filter();
#endif
    }

    LSP_DEF_NATIVE_METHOD(void, SvcBypass, disableSvcRedirect) {
        LOGW("SvcBypass: Cannot disable Seccomp filters once applied.");
    }

    LSP_DEF_NATIVE_METHOD(jboolean, SvcBypass, isSvcHookActive) {
        return g_is_hook_active ? JNI_TRUE : JNI_FALSE;
    }

    LSP_DEF_NATIVE_METHOD(jstring, SvcBypass, getDebugInfo) {
#if defined(__aarch64__)
        return env->NewStringUTF("SvcBypass: Active (ARM64)");
#else
        return env->NewStringUTF("SvcBypass: Stub (Non-ARM64)");
#endif
    }

    LSP_DEF_NATIVE_METHOD(jint, SvcBypass, getCurrentPid) {
        return getpid();
    }

    LSP_DEF_NATIVE_METHOD(jint, SvcBypass, getInitialPid) {
        return getpid();
    }

    LSP_DEF_NATIVE_METHOD(void, SvcBypass, logSvcHookStats) {
    }

    LSP_DEF_NATIVE_METHOD(jboolean, SvcBypass, isChildProcess) {
        return JNI_FALSE;
    }

    LSP_DEF_NATIVE_METHOD(jstring, SvcBypass, checkFd, jint fd) {
        if (fd < 0) return nullptr;
        char path[PATH_MAX];
        char link[64];
        if (snprintf(link, sizeof(link), "/proc/self/fd/%d", fd) >= (int)sizeof(link)) {
            return nullptr;
        }

        ssize_t len = readlink(link, path, sizeof(path) - 1);
        if (len != -1) {
            path[len] = '\0';
            return env->NewStringUTF(path);
        }
        return nullptr;
    }

    LSP_DEF_NATIVE_METHOD(jint, SvcBypass, dupFd, jint fd) {
        return dup(fd);
    }

    LSP_DEF_NATIVE_METHOD(jlong, SvcBypass, getFdInode, jint fd) {
        struct stat st;
        if (fstat(fd, &st) == 0) return (jlong)st.st_ino;
        return -1;
    }

    LSP_DEF_NATIVE_METHOD(jboolean, SvcBypass, isSystemFile, jint fd) {
        return JNI_FALSE;
    }

    LSP_DEF_NATIVE_METHOD(jint, SvcBypass, findSystemApkFd, jstring path) {
        return -1;
    }

    LSP_DEF_NATIVE_METHOD(jobjectArray, SvcBypass, getSystemApkFds) {
        return nullptr;
    }

    LSP_DEF_NATIVE_METHOD(void, SvcBypass, refreshSystemFds) {
    }

    LSP_DEF_NATIVE_METHOD(jbyteArray, SvcBypass, readCertificateFromFd, jint fd) {
        return nullptr;
    }

    LSP_DEF_NATIVE_METHOD(jbyteArray, SvcBypass, readCertificateFromPath, jstring path) {
        return nullptr;
    }

    static JNINativeMethod gMethods[] = {
            LSP_NATIVE_METHOD(SvcBypass, initSvcHook, "()Z"),
            LSP_NATIVE_METHOD(SvcBypass, enableSvcRedirect, "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V"),
            LSP_NATIVE_METHOD(SvcBypass, disableSvcRedirect, "()V"),
            LSP_NATIVE_METHOD(SvcBypass, isSvcHookActive, "()Z"),
            LSP_NATIVE_METHOD(SvcBypass, logSvcHookStats, "()V"),
            LSP_NATIVE_METHOD(SvcBypass, getDebugInfo, "()Ljava/lang/String;"),
            LSP_NATIVE_METHOD(SvcBypass, getCurrentPid, "()I"),
            LSP_NATIVE_METHOD(SvcBypass, getInitialPid, "()I"),
            LSP_NATIVE_METHOD(SvcBypass, isChildProcess, "()Z"),
            LSP_NATIVE_METHOD(SvcBypass, checkFd, "(I)Ljava/lang/String;"),
            LSP_NATIVE_METHOD(SvcBypass, dupFd, "(I)I"),
            LSP_NATIVE_METHOD(SvcBypass, getFdInode, "(I)J"),
            LSP_NATIVE_METHOD(SvcBypass, isSystemFile, "(I)Z"),
            LSP_NATIVE_METHOD(SvcBypass, findSystemApkFd, "(Ljava/lang/String;)I"),
            LSP_NATIVE_METHOD(SvcBypass, getSystemApkFds, "()[[Ljava/lang/String;"),
            LSP_NATIVE_METHOD(SvcBypass, refreshSystemFds, "()V"),
            LSP_NATIVE_METHOD(SvcBypass, readCertificateFromFd, "(I)[B"),
            LSP_NATIVE_METHOD(SvcBypass, readCertificateFromPath, "(Ljava/lang/String;)[B"),
    };

    void RegisterSvcBypass(JNIEnv *env) {
        REGISTER_LSP_NATIVE_METHODS(SvcBypass);
    }
}  // namespace vector::native
