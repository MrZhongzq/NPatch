//
// Signature-bypass apk read redirection + /proc/self/maps filtering (lv3, xHook PLT).
//
// Reworked to hook via xHook (PLT/GOT) instead of a Dobby inline hook.
// Inline-hooking libc's openat rewrites libc's executable segment, which native
// anti-tamper detectors flag ("libc.so executable segment hooked" via disk-vs-memory
// CRC). PLT hooking only rewrites each caller library's GOT (a data segment that the
// linker already relocates at load), so libc's code stays byte-identical on disk and
// in memory.
//
// Stage 2 (this file): xHook is a *cross-library* (PLT) hook, so it only intercepts a
// detector's call into libc when that call crosses the PLT. A detector reading
// /proc/self/maps via fopen()/open() reaches openat through libc-*internal* calls that
// never touch the PLT, so hooking openat alone misses them. We therefore hook the whole
// entry family the detector might call directly — fopen/fopen64, open/open64,
// openat/openat64 — and for each:
//   * redirect reads of our own base.apk to the pristine origin.apk (signature spoofing), and
//   * serve a filtered snapshot of /proc/self/maps|smaps (drop our tooling's lines and
//     anonymous executable mappings), mirroring the lv4 seccomp path.
// NOTE: lv4 (seccomp) remains the complete solution — it is kernel-level and catches every
// openat regardless of origin, and does not rewrite any GOT (so it also passes lib-integrity
// checks). lv3 stays the lightweight fallback for apps where lv4's seccomp filter conflicts
// with the app's own sandbox (e.g. Gecko/Chromium browsers).
//

#include "bypass_sig.h"

#include "common/logging.h"
#include "npatch_compat.h"
#include "utils/jni_helper.hpp"

#include "xhook.h"

#include <fcntl.h>
#include <dlfcn.h>
#include <android/dlext.h>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <string>
#include <sys/types.h>
#include <sys/syscall.h>
#include <unistd.h>

namespace vector::native {

    // The app's own apk path (base.apk); reads of it are redirected to origin.apk so
    // signature/integrity checks see the original, unpatched contents.
    static std::string targetApkPath;
    static std::string redirectApkPath;
    static std::string currentPackageName;

    // ---- /proc/self/maps|smaps filtering (shared shape with lv4 bypass_svc.cpp) --------------
    // Detectors scan /proc/self/maps for injection traces. We serve a filtered copy: drop lines
    // whose pathname names our tooling, and drop anonymous executable mappings (hook trampolines
    // / in-memory dex).

    static bool is_hideable_maps_path(const char *p) {
        if (p == nullptr) return false;
        return strcmp(p, "/proc/self/maps") == 0 || strcmp(p, "/proc/self/smaps") == 0;
    }

    static bool maps_line_is_suspicious(const char *line, size_t len) {
        static const char *kNames[] = {
                "npatch", "lsposed", "riru", "zygisk", "magisk", "frida",
                "/data/adb", "/data/local/tmp", "memfd:", nullptr};
        for (int i = 0; kNames[i] != nullptr; ++i) {
            if (memmem(line, len, kNames[i], strlen(kNames[i])) != nullptr) return true;
        }
        // Parse "addr perms ..."; drop anonymous executable regions (exec perm + no file path).
        const char *sp = static_cast<const char *>(memchr(line, ' ', len));
        if (sp != nullptr && static_cast<size_t>(sp - line) + 5 < len) {
            const char *perms = sp + 1;             // e.g. "r-xp"
            if (perms[2] == 'x') {
                const char *rest = perms + 4;
                size_t restlen = len - static_cast<size_t>(rest - line);
                // A file-backed mapping lists its path (contains '/'); anonymous ones don't.
                if (memmem(rest, restlen, "/", 1) == nullptr) return true;
            }
        }
        return false;
    }

    // Reads the real maps file and returns a memfd holding the filtered copy (offset 0), or -1.
    // Our own openat/read below go through libc directly; libnpatch.so is xhook_ignore'd, so this
    // does not re-enter the hooks (no recursion).
    static int build_filtered_maps_fd(const char *path) {
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
            const char *line = content.data() + start;
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

    // ---- entry-family hooks -----------------------------------------------------------------

    using OpenFn = int (*)(const char *, int, ...);
    using OpenAtFn = int (*)(int, const char *, int, ...);
    using FopenFn = FILE *(*)(const char *, const char *);
    static OpenFn real_open = nullptr;
    static OpenFn real_open64 = nullptr;
    static OpenAtFn real_openat = nullptr;
    static OpenAtFn real_openat64 = nullptr;
    static FopenFn real_fopen = nullptr;
    static FopenFn real_fopen64 = nullptr;

    static bool needs_mode(int flags) {
        return (flags & O_CREAT) != 0 || (flags & O_TMPFILE) == O_TMPFILE;
    }

    static const char *resolve_redirect(const char *pathname) {
        if (pathname != nullptr && !targetApkPath.empty()
            && strcmp(pathname, targetApkPath.c_str()) == 0) {
            return redirectApkPath.c_str();
        }
        return pathname;
    }

    static int hooked_openat(int dirfd, const char *pathname, int flags, ...) {
        mode_t mode = 0;
        if (needs_mode(flags)) {
            va_list ap;
            va_start(ap, flags);
            mode = static_cast<mode_t>(va_arg(ap, int));
            va_end(ap);
        }
        if (is_hideable_maps_path(pathname)) {
            int mfd = build_filtered_maps_fd(pathname);
            if (mfd >= 0) return mfd;
        }
        const char *path = resolve_redirect(pathname);
        if (real_openat != nullptr) return real_openat(dirfd, path, flags, mode);
        return openat(dirfd, path, flags, mode);
    }

    static int hooked_openat64(int dirfd, const char *pathname, int flags, ...) {
        mode_t mode = 0;
        if (needs_mode(flags)) {
            va_list ap;
            va_start(ap, flags);
            mode = static_cast<mode_t>(va_arg(ap, int));
            va_end(ap);
        }
        if (is_hideable_maps_path(pathname)) {
            int mfd = build_filtered_maps_fd(pathname);
            if (mfd >= 0) return mfd;
        }
        const char *path = resolve_redirect(pathname);
        if (real_openat64 != nullptr) return real_openat64(dirfd, path, flags, mode);
        return openat(dirfd, path, flags, mode);
    }

    static int hooked_open(const char *pathname, int flags, ...) {
        mode_t mode = 0;
        if (needs_mode(flags)) {
            va_list ap;
            va_start(ap, flags);
            mode = static_cast<mode_t>(va_arg(ap, int));
            va_end(ap);
        }
        if (is_hideable_maps_path(pathname)) {
            int mfd = build_filtered_maps_fd(pathname);
            if (mfd >= 0) return mfd;
        }
        const char *path = resolve_redirect(pathname);
        if (real_open != nullptr) return real_open(path, flags, mode);
        return open(path, flags, mode);
    }

    static int hooked_open64(const char *pathname, int flags, ...) {
        mode_t mode = 0;
        if (needs_mode(flags)) {
            va_list ap;
            va_start(ap, flags);
            mode = static_cast<mode_t>(va_arg(ap, int));
            va_end(ap);
        }
        if (is_hideable_maps_path(pathname)) {
            int mfd = build_filtered_maps_fd(pathname);
            if (mfd >= 0) return mfd;
        }
        const char *path = resolve_redirect(pathname);
        if (real_open64 != nullptr) return real_open64(path, flags, mode);
        return open(path, flags, mode);
    }

    static FILE *fopen_common(const char *pathname, const char *mode, FopenFn real) {
        if (is_hideable_maps_path(pathname)) {
            int mfd = build_filtered_maps_fd(pathname);
            if (mfd >= 0) {
                FILE *fp = fdopen(mfd, "r");  // caller's fclose() closes the memfd
                if (fp != nullptr) return fp;
                close(mfd);
            }
        }
        const char *path = resolve_redirect(pathname);
        if (real != nullptr) return real(path, mode);
        return fopen(path, mode);
    }

    static FILE *hooked_fopen(const char *pathname, const char *mode) {
        return fopen_common(pathname, mode, real_fopen);
    }

    static FILE *hooked_fopen64(const char *pathname, const char *mode) {
        return fopen_common(pathname, mode, real_fopen64);
    }

    static bool hooksRegistered = false;

    static void installHooks() {
        // xHook wants registrations set up once; xhook_refresh then (re)applies them to every
        // currently-mapped library.
        if (!hooksRegistered) {
            // Don't hook our own library or the dynamic linker.
            xhook_ignore(".*/libnpatch\\.so$", nullptr);
            xhook_ignore(".*/linker(64)?$", nullptr);

            xhook_register(".*\\.so$", "openat", reinterpret_cast<void *>(hooked_openat),
                           reinterpret_cast<void **>(&real_openat));
            xhook_register(".*\\.so$", "openat64", reinterpret_cast<void *>(hooked_openat64),
                           reinterpret_cast<void **>(&real_openat64));
            xhook_register(".*\\.so$", "open", reinterpret_cast<void *>(hooked_open),
                           reinterpret_cast<void **>(&real_open));
            xhook_register(".*\\.so$", "open64", reinterpret_cast<void *>(hooked_open64),
                           reinterpret_cast<void **>(&real_open64));
            xhook_register(".*\\.so$", "fopen", reinterpret_cast<void *>(hooked_fopen),
                           reinterpret_cast<void **>(&real_fopen));
            xhook_register(".*\\.so$", "fopen64", reinterpret_cast<void *>(hooked_fopen64),
                           reinterpret_cast<void **>(&real_fopen64));
            hooksRegistered = true;
        }
        xhook_refresh(0);
    }

    LSP_DEF_NATIVE_METHOD(void, SigBypass, enableOpenatHook,
                          jstring jOrigApkPath,
                          jstring jCacheApkPath,
                          jstring jPkgName) {
        if (jOrigApkPath == nullptr || jCacheApkPath == nullptr) {
            LOGE("Invalid arguments: paths cannot be null.");
            return;
        }

        lsplant::JUTFString strOrig(env, jOrigApkPath);
        lsplant::JUTFString strRedirect(env, jCacheApkPath);

        targetApkPath = strOrig.get();
        redirectApkPath = strRedirect.get();

        if (jPkgName != nullptr) {
            lsplant::JUTFString strPkg(env, jPkgName);
            currentPackageName = strPkg.get();
        }

        LOGI("Enable OpenAt Hook (xhook PLT, +fopen/open +maps filter): %s -> %s (Pkg: %s)",
             targetApkPath.c_str(), redirectApkPath.c_str(), currentPackageName.c_str());

        installHooks();
    }

    LSP_DEF_NATIVE_METHOD(void, SigBypass, disableOpenatHook) {
        LOGI("Disable OpenAt Hook requested");
        targetApkPath.clear();
        redirectApkPath.clear();
    }

    static JNINativeMethod gMethods[] = {
            LSP_NATIVE_METHOD(SigBypass, enableOpenatHook, "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V"),
            LSP_NATIVE_METHOD(SigBypass, disableOpenatHook, "()V")
    };

    void RegisterBypass(JNIEnv *env) { REGISTER_LSP_NATIVE_METHODS(SigBypass); }

}  // namespace vector::native
