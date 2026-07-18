//
// Signature-bypass apk read redirection.
//
// Reworked to hook openat via xHook (PLT/GOT) instead of a Dobby inline hook.
// Inline-hooking libc's openat rewrites libc's executable segment, which native
// anti-tamper detectors flag ("libc.so executable segment hooked" via disk-vs-memory
// CRC). PLT hooking only rewrites each caller library's GOT (a data segment that the
// linker already relocates at load), so libc's code stays byte-identical on disk and
// in memory.
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
#include <cstring>
#include <string>
#include <sys/types.h>

namespace vector::native {

    // The app's own apk path (base.apk); reads of it are redirected to origin.apk so
    // signature/integrity checks see the original, unpatched contents.
    static std::string targetApkPath;
    static std::string redirectApkPath;
    static std::string currentPackageName;

    using OpenAtFn = int (*)(int, const char *, int, ...);
    static OpenAtFn real_openat = nullptr;
    static OpenAtFn real_openat64 = nullptr;

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
        const char *path = resolve_redirect(pathname);
        if (real_openat != nullptr) {
            return real_openat(dirfd, path, flags, mode);
        }
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
        const char *path = resolve_redirect(pathname);
        if (real_openat64 != nullptr) {
            return real_openat64(dirfd, path, flags, mode);
        }
        return openat(dirfd, path, flags, mode);
    }

    static bool hooksRegistered = false;

    static void installOpenatHooks() {
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

        LOGI("Enable OpenAt Hook (xhook PLT): %s -> %s (Pkg: %s)",
             targetApkPath.c_str(), redirectApkPath.c_str(), currentPackageName.c_str());

        installOpenatHooks();
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
