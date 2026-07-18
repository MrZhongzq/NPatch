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

#include <link.h>
#include <elf.h>

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

    // Records overlay memfds we hand back for library opens, so readlinkat("/proc/self/fd/N")
    // can be spoofed back to the real library path (otherwise the detector sees "/memfd:..." and
    // knows the fd was substituted).
    static std::mutex g_overlay_fd_mutex;
    static int g_overlay_fd[64] = {0};
    static char g_overlay_fd_path[64][PATH_MAX] = {{0}};
    static int g_overlay_fd_seq = 0;

    static void record_overlay_fd(int fd, const char* path) {
        std::scoped_lock lock(g_overlay_fd_mutex);
        int idx = g_overlay_fd_seq % 64;
        g_overlay_fd[idx] = fd;
        strncpy(g_overlay_fd_path[idx], path, PATH_MAX - 1);
        g_overlay_fd_path[idx][PATH_MAX - 1] = '\0';
        g_overlay_fd_seq++;
    }

    static const char* lookup_overlay_fd(int fd) {
        std::scoped_lock lock(g_overlay_fd_mutex);
        for (int i = 0; i < 64; ++i) {
            if (g_overlay_fd[i] == fd && g_overlay_fd_path[i][0] != '\0') return g_overlay_fd_path[i];
        }
        return nullptr;
    }

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

    // ---- /proc/self/maps|smaps filtering (anti-detection) ------------------------------------
    // Detectors scan /proc/self/maps for injection traces. We serve a filtered copy: drop lines
    // whose pathname names our tooling, and drop anonymous executable mappings (hook trampolines
    // / in-memory dex). Runs on the trusted thread, which is created before the seccomp filter is
    // installed and is therefore not itself trapped, so it can freely read the real file.

    static bool is_hideable_maps_path(const char* p) {
        if (p == nullptr) return false;
        return strcmp(p, "/proc/self/maps") == 0 || strcmp(p, "/proc/self/smaps") == 0;
    }

    static bool maps_line_is_suspicious(const char* line, size_t len) {
        static const char* kNames[] = {
                "npatch", "lsposed", "riru", "zygisk", "magisk", "frida",
                "/data/adb", "/data/local/tmp", "memfd:",
                // ART Java heap object spaces: they hold the framework's class-name strings
                // ("xposed"/"lsposed") that memory keyword scanners flag. The app reaches its
                // own heap through object pointers, not through /proc/self/maps, so removing
                // these lines from the maps view doesn't affect it.
                "dalvik-main space", "dalvik-large object space",
                "dalvik-free list large object space", "dalvik-non moving space",
                "dalvik-zygote space",
                nullptr};
        for (int i = 0; kNames[i] != nullptr; ++i) {
            if (memmem(line, len, kNames[i], strlen(kNames[i])) != nullptr) return true;
        }
        // Parse "addr perms ..."; drop anonymous executable regions (exec perm + no file path).
        const char* sp = static_cast<const char*>(memchr(line, ' ', len));
        if (sp != nullptr && static_cast<size_t>(sp - line) + 5 < len) {
            const char* perms = sp + 1;             // e.g. "r-xp"
            if (perms[2] == 'x') {
                const char* rest = perms + 4;
                size_t restlen = len - static_cast<size_t>(rest - line);
                // A file-backed mapping lists its path (contains '/'); anonymous ones don't.
                if (memmem(rest, restlen, "/", 1) == nullptr) return true;
            }
        }
        return false;
    }

    static int build_filtered_proc_fd(const char* path) {
        int real_fd = openat(AT_FDCWD, path, O_RDONLY | O_CLOEXEC);
        if (real_fd < 0) return -1;
        std::string content;
        char buf[8192];
        ssize_t r;
        while ((r = read(real_fd, buf, sizeof(buf))) > 0) content.append(buf, static_cast<size_t>(r));
        close(real_fd);

        std::string out;
        out.reserve(content.size());
        size_t start = 0;
        while (start < content.size()) {
            size_t nl = content.find('\n', start);
            size_t end = (nl == std::string::npos) ? content.size() : nl + 1;
            const char* line = content.data() + start;
            size_t len = end - start;
            if (!maps_line_is_suspicious(line, len)) {
                out.append(line, len);
            }
            start = end;
        }

        int mfd = static_cast<int>(syscall(__NR_memfd_create, "a", 0u));
        if (mfd < 0) return -1;
        size_t written = 0;
        while (written < out.size()) {
            ssize_t w = write(mfd, out.data() + written, out.size() - written);
            if (w <= 0) { close(mfd); return -1; }
            written += static_cast<size_t>(w);
        }
        lseek(mfd, 0, SEEK_SET);
        return mfd;
    }

    // ---- lib integrity spoofing (anti-detection) --------------------------------------------
    // Detectors CRC a library's on-disk .text against its in-memory .text; LSPlant's hooks make
    // libart's in-memory code differ from disk, so the CRCs mismatch ("lib has been hooked").
    // We intercept the detector's read of the on-disk library file and hand back a copy whose
    // executable segments are overwritten with the CURRENT in-memory bytes, so disk==memory.

    static bool is_integrity_checked_lib(const char* p) {
        if (p == nullptr) return false;
        const char* base = strrchr(p, '/');
        base = base ? base + 1 : p;
        static const char* kLibs[] = {
                "libart.so", "libc.so", "libdl.so", "libandroid.so", "liblog.so", "libm.so", nullptr};
        for (int i = 0; kLibs[i] != nullptr; ++i) {
            if (strcmp(base, kLibs[i]) == 0) return true;
        }
        return false;
    }

    struct OverlayCtx {
        const char* soname;   // basename to match
        std::string* content; // disk file image to patch
        bool matched;
    };

    static int overlay_iter_cb(struct dl_phdr_info* info, size_t, void* data) {
        auto* ctx = static_cast<OverlayCtx*>(data);
        const char* name = info->dlpi_name;
        if (name == nullptr || name[0] == '\0') return 0;
        const char* base = strrchr(name, '/');
        base = base ? base + 1 : name;
        if (strcmp(base, ctx->soname) != 0) return 0;

        // Overlay every loadable segment's file-backed bytes with the current in-memory bytes.
        // Detectors CRC both .text (exec) and .data.rel.ro/.got (data) disk-vs-memory; the data
        // segment also legitimately differs after relocation, so we must mirror it too.
        LOGI("SvcBypass: overlay match '{}' dlpi_addr={} phnum={} filesize={}",
             ctx->soname, (void*) info->dlpi_addr, info->dlpi_phnum, ctx->content->size());
        for (int i = 0; i < info->dlpi_phnum; ++i) {
            const ElfW(Phdr)* ph = &info->dlpi_phdr[i];
            if (ph->p_type != PT_LOAD) continue;
            uintptr_t mem_start = info->dlpi_addr + ph->p_vaddr;
            size_t off = static_cast<size_t>(ph->p_offset);
            size_t sz = static_cast<size_t>(ph->p_filesz);
            bool fits = (sz > 0 && off + sz <= ctx->content->size());
            LOGI("SvcBypass:   seg flags={} off={} vaddr={} filesz={} mem={} fits={}",
                 ph->p_flags, off, (void*) ph->p_vaddr, sz, (void*) mem_start, fits ? 1 : 0);
            if (fits) {
                memcpy(&(*ctx->content)[off], reinterpret_cast<const void*>(mem_start), sz);
            }
        }
        ctx->matched = true;
        return 1;  // stop iteration
    }

    static int build_lib_overlay_fd(const char* diskpath) {
        int real_fd = openat(AT_FDCWD, diskpath, O_RDONLY | O_CLOEXEC);
        if (real_fd < 0) return -1;
        std::string content;
        char buf[65536];
        ssize_t r;
        while ((r = read(real_fd, buf, sizeof(buf))) > 0) content.append(buf, static_cast<size_t>(r));
        close(real_fd);
        if (content.empty()) return -1;

        std::string original = content;  // to detect whether any exec byte actually differs
        const char* base = strrchr(diskpath, '/');
        base = base ? base + 1 : diskpath;
        OverlayCtx ctx{base, &content, false};
        dl_iterate_phdr(overlay_iter_cb, &ctx);
        if (!ctx.matched) { LOGI("SvcBypass: overlay '{}' not matched in dl_iterate", base); return -1; }
        if (content == original) { LOGI("SvcBypass: overlay '{}' segments unchanged", base); return -1; }
        LOGI("SvcBypass: overlay '{}' segments DIFFER -> serving", base);

        int mfd = static_cast<int>(syscall(__NR_memfd_create, "a", 0u));
        if (mfd < 0) return -1;
        size_t written = 0;
        while (written < content.size()) {
            ssize_t w = write(mfd, content.data() + written, content.size() - written);
            if (w <= 0) { close(mfd); return -1; }
            written += static_cast<size_t>(w);
        }
        lseek(mfd, 0, SEEK_SET);
        return mfd;
    }

    // Trusted thread: it is NOT subject to the SIGSYS trap (it is created before the filter is
    // installed), so it can rewrite the path and execute the real syscall, returning the genuine
    // kernel result.
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

            bool handled = false;

            // Intercept openat("/proc/self/maps"|smaps) and hand back a filtered snapshot.
            if (req->sys_no == __NR_openat
#ifdef __NR_openat2
                || req->sys_no == __NR_openat2
#endif
                    ) {
                const char* pathname = reinterpret_cast<const char*>(req->args[1]);
                if (pathname != nullptr && (strstr(pathname, "libart") || strstr(pathname, "libc.so")
                        || strstr(pathname, "libcheck") || strstr(pathname, "/proc/self/mem")
                        || strstr(pathname, "map_files") || strstr(pathname, "pagemap"))) {
                    LOGI("SvcBypass: openat probe path='{}'", pathname);
                }
                if (is_hideable_maps_path(pathname)) {
                    int fd = build_filtered_proc_fd(pathname);
                    if (fd >= 0) {
                        req->result = fd;
                        handled = true;
                    }
                } else if (is_integrity_checked_lib(pathname)) {
                    // DIAGNOSTIC: deny the library open to learn whether the detector reads its
                    // disk_crc through this openat at all.
                    LOGI("SvcBypass: denying integrity lib open '{}' (diag)", pathname);
                    req->result = -EACCES;
                    handled = true;
                }
            }

            if (!handled && is_redirected_syscall(req->sys_no)) {
                const char* pathname = reinterpret_cast<const char*>(req->args[1]);
                const char* redirected_path = resolve_redirect_path(pathname);
                if (redirected_path != pathname && redirected_path != nullptr) {
                    LOGD("SvcBypass: redirect '%s' -> '%s'", pathname, redirected_path);
                    req->args[1] = reinterpret_cast<long>(redirected_path);
                }
            }

            if (!handled) {
                req->result = syscall(req->sys_no, req->args[0], req->args[1], req->args[2],
                                      req->args[3], req->args[4], req->args[5]);
            }

            // Anti-detection: an app that inspects /proc/self/fd/<n> for its own loaded dex/apk
            // sees our cached origin apk path (".../cache/npatch/origin/<crc>.apk"), which betrays
            // the patch. Rewrite such readlinkat results back to the real installed apk path so
            // the fd looks normal.
#ifdef __NR_readlinkat
            if (req->sys_no == __NR_readlinkat && req->result > 0) {
                char* outbuf = reinterpret_cast<char*>(req->args[2]);
                size_t bufsiz = static_cast<size_t>(req->args[3]);
                size_t n = static_cast<size_t>(req->result);
                const char* linkpath = reinterpret_cast<const char*>(req->args[1]);
                if (outbuf != nullptr && n <= bufsiz) {
                    // A) readlinkat("/proc/self/fd/<N>"): if N is an overlay memfd we handed back
                    //    for a library open, report the real library path (not "/memfd:...").
                    if (linkpath != nullptr && strncmp(linkpath, "/proc/self/fd/", 14) == 0) {
                        int fdnum = atoi(linkpath + 14);
                        LOGI("SvcBypass: readlinkat /proc/self/fd/{} -> len={}", fdnum, n);
                        const char* real = lookup_overlay_fd(fdnum);
                        if (real != nullptr) {
                            size_t rlen = strlen(real);
                            if (rlen > 0 && rlen <= bufsiz) {
                                memcpy(outbuf, real, rlen);
                                req->result = static_cast<long>(rlen);
                                n = rlen;
                                LOGI("SvcBypass: spoofed fd link -> '{}'", real);
                            }
                        }
                    }
                    // B) any link resolving into our cached origin apk -> report the installed apk.
                    std::scoped_lock lock(g_path_mutex);
                    if (g_target_path[0] != '\0'
                            && memmem(outbuf, n, "/npatch/origin/", 15) != nullptr) {
                        size_t tlen = strlen(g_target_path);
                        if (tlen > 0 && tlen <= bufsiz) {
                            memcpy(outbuf, g_target_path, tlen);
                            req->result = static_cast<long>(tlen);
                        }
                    }
                }
            }
#endif

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
