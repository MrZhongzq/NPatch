//
// hideLibs: hide NPatch's own injected native libraries from library enumeration.
//
// Anti-tamper code commonly walks the loaded-library list via dl_iterate_phdr and inspects each
// library's name/contents for injection markers ("lsposed"/"npatch"/...). We wrap the enumeration
// callback so our own libraries are skipped, mirroring how riru/zygisk-style solutions conceal
// themselves. This is genuine footprint reduction (not tied to any one detector): our libs simply
// stop appearing to in-process enumerators. Their /proc/self/maps lines are already dropped by the
// seccomp maps filter.
//

#ifndef NPATCH_HIDE_LIBS_H
#define NPATCH_HIDE_LIBS_H

#include "utils/hook_helper.hpp"

#include <link.h>
#include <cstring>

using namespace lsplant;

namespace vector {

class HideLibs {
private:
    struct WrapCtx {
        int (*cb)(struct dl_phdr_info *, size_t, void *);
        void *data;
    };

    static bool is_own_lib(const char *name) {
        if (name == nullptr || name[0] == '\0') return false;
        return strstr(name, "libnpatch") != nullptr
               || strstr(name, "/npatch/") != nullptr
               || strstr(name, "lsposed") != nullptr
               || strstr(name, "riru") != nullptr;
    }

    static int filter_cb(struct dl_phdr_info *info, size_t size, void *data) {
        auto *w = static_cast<WrapCtx *>(data);
        if (is_own_lib(info->dlpi_name)) {
            return 0;  // skip: keep enumerating, but hide this entry
        }
        return w->cb(info, size, w->data);
    }

    inline static auto dl_iterate_phdr_ =
        "dl_iterate_phdr"_sym.hook->*
        []<Backup auto backup>(int (*callback)(struct dl_phdr_info *, size_t, void *),
                               void *data) static -> int {
            WrapCtx wrap{callback, data};
            return backup(filter_cb, &wrap);  // dl_iterate_phdr is synchronous; &wrap stays valid
        };

public:
    static void Install(const HookHandler &handler) { handler(dl_iterate_phdr_); }
};

}  // namespace vector

#endif  // NPATCH_HIDE_LIBS_H
