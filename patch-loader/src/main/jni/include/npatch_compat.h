/**
 * Compatibility header that bridges Vector (upstream) namespace/macros
 * with NPatch's existing native code.
 *
 * Upstream renamed:
 *   namespace lspd          -> vector::native
 *   SandHook::ElfImg        -> vector::native::ElfImage
 *   headers: flat           -> subdirectories (common/, core/, elf/, jni/)
 *   macros: LSP_*           -> VECTOR_*
 *   JNI pkg: org.lsposed.lspd.nativebridge -> org.matrix.vector.nativebridge
 */

#pragma once

// Pull in the new upstream headers
#include "common/logging.h"
#include "common/config.h"
#include "core/config_bridge.h"
#include "core/context.h"
#include "core/native_api.h"
#include "elf/elf_image.h"
#include "elf/symbol_cache.h"
#include "jni/jni_bridge.h"

#include <sys/system_properties.h>

// GetAndroidApiLevel was removed from upstream, but NPatch still needs it
namespace vector::native {
    inline int32_t GetAndroidApiLevel() {
        static int32_t api_level = []() {
            char prop_value[PROP_VALUE_MAX];
            __system_property_get("ro.build.version.sdk", prop_value);
            int base = atoi(prop_value);
            __system_property_get("ro.build.version.preview_sdk", prop_value);
            return base + atoi(prop_value);
        }();
        return api_level;
    }
}

// Namespace alias: old lspd -> new vector::native
namespace lspd = vector::native;

// ---- NPatch-specific JNI macros ----
// NPatch's SigBypass/SvcBypass live in org.lsposed.lspd.nativebridge (NPatch's own),
// NOT in org.matrix.vector.nativebridge. Keep the old JNI name mangling for these.

#define LSP_NATIVE_METHOD(className, functionName, signature)                                      \
    {#functionName, signature,                                                                     \
     reinterpret_cast<void *>(                                                                     \
         Java_org_lsposed_lspd_nativebridge_##className##_##functionName)}

#define JNI_START [[maybe_unused]] JNIEnv *env, [[maybe_unused]] jclass clazz

#define LSP_DEF_NATIVE_METHOD(ret, className, functionName, ...)                                   \
    extern "C" ret Java_org_lsposed_lspd_nativebridge_##className##_##functionName(JNI_START,      \
                                                                                   ##__VA_ARGS__)

#define REGISTER_LSP_NATIVE_METHODS(class_name)                                                    \
    vector::native::jni::RegisterNativeMethodsInternal(                                            \
        env, GetNPatchNativeBridgeSignature() + #class_name,                                       \
        gMethods, vector::native::jni::ArraySize(gMethods))

inline std::string GetNPatchNativeBridgeSignature() {
    auto *bridge = vector::native::ConfigBridge::GetInstance();
    if (bridge) {
        const auto &obfs_map = bridge->obfuscation_map();
        auto it = obfs_map.find("org.lsposed.lspd.nativebridge.");
        if (it != obfs_map.end()) {
            return it->second;
        }
    }
    return "org/lsposed/lspd/nativebridge/";
}
