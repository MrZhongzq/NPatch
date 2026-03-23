/*
 * This file is part of LSPosed.
 *
 * LSPosed is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LSPosed is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LSPosed.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2022 LSPosed Contributors
 */

//
// Created by Nullptr on 2022/3/17.
//

#include "patch_loader.h"

#include "art/runtime/jit/profile_saver.h"
#include "art/runtime/oat_file_manager.h"
#include "elf/elf_image.h"
#include "jni/bypass_sig.h"
#include "jni/bypass_svc.h"
#include "npatch_compat.h"
#include "elf/symbol_cache.h"
#include "utils/jni_helper.hpp"

#include <sys/mman.h>
#include <sys/syscall.h>
#include <unistd.h>

using namespace lsplant;

namespace vector::native {

    void PatchLoader::LoadDex(JNIEnv* env, Context::PreloadedDex&& dex) {
        auto class_activity_thread = JNI_FindClass(env, "android/app/ActivityThread");
        auto class_activity_thread_app_bind_data = JNI_FindClass(env, "android/app/ActivityThread$AppBindData");
        auto class_loaded_apk = JNI_FindClass(env, "android/app/LoadedApk");

        auto mid_current_activity_thread = JNI_GetStaticMethodID(env, class_activity_thread, "currentActivityThread", "()Landroid/app/ActivityThread;");
        auto mid_get_classloader = JNI_GetMethodID(env, class_loaded_apk, "getClassLoader", "()Ljava/lang/ClassLoader;");
        auto fid_m_bound_application = JNI_GetFieldID(env, class_activity_thread, "mBoundApplication", "Landroid/app/ActivityThread$AppBindData;");
        auto fid_info = JNI_GetFieldID(env, class_activity_thread_app_bind_data, "info", "Landroid/app/LoadedApk;");

        auto activity_thread = JNI_CallStaticObjectMethod(env, class_activity_thread, mid_current_activity_thread);
        auto m_bound_application = JNI_GetObjectField(env, activity_thread, fid_m_bound_application);
        auto info = JNI_GetObjectField(env, m_bound_application, fid_info);
        auto stub_classloader = JNI_CallObjectMethod(env, info, mid_get_classloader);

        if (!stub_classloader) {
            LOGE("getStubClassLoader failed!!!");
            return;
        }

        auto in_memory_classloader = JNI_FindClass(env, "dalvik/system/InMemoryDexClassLoader");
        auto mid_init = JNI_GetMethodID(env, in_memory_classloader, "<init>", "(Ljava/nio/ByteBuffer;Ljava/lang/ClassLoader;)V");
        auto dex_buffer = env->NewDirectByteBuffer(dex.data(), dex.size());

        ScopedLocalRef<jobject> my_cl(JNI_NewObject(env, in_memory_classloader, mid_init, dex_buffer, stub_classloader));
        if (!my_cl) {
            LOGE("InMemoryDexClassLoader creation failed!!!");
            return;
        }

        inject_class_loader_ = JNI_NewGlobalRef(env, my_cl.get());
    }

    void PatchLoader::InitArtHooker(JNIEnv* env, const InitInfo& initInfo) {
        Context::InitArtHooker(env, initInfo);
        handler = initInfo;
        art::ProfileSaver::DisableInline(initInfo);
        art::FileManager::DisableBackgroundVerification(initInfo);
    }

    void PatchLoader::InitHooks(JNIEnv* env) {
        Context::InitHooks(env);
        RegisterBypass(env);
        RegisterSvcBypass(env);
    }

    void PatchLoader::SetupEntryClass(JNIEnv* env) {
        ScopedLocalRef<jclass> entry_class(FindClassFromLoader(env, GetCurrentClassLoader(), "org.lsposed.npatch.loader.LSPApplication"));
        if (entry_class) {
            entry_class_ = JNI_NewGlobalRef(env, entry_class.get());
        } else {
            LOGE("Failed to find entry class.");
        }
    }

    void PatchLoader::Load(JNIEnv* env) {
        lsplant::InitInfo initInfo{
                .inline_hooker =
                [](auto t, auto r) {
                    void* bk = nullptr;
                    return HookInline(t, r, &bk) == 0 ? bk : nullptr;
                },
                .inline_unhooker = [](auto t) { return UnhookInline(t) == 0; },
                .art_symbol_resolver = [](auto symbol) { return ElfSymbolCache::GetArt()->getSymbAddress(symbol); },
                .art_symbol_prefix_resolver =
                [](auto symbol) { return ElfSymbolCache::GetArt()->getSymbPrefixFirstAddress(symbol); },
        };

        auto stub = JNI_FindClass(env, "org/lsposed/npatch/metaloader/LSPAppComponentFactoryStub");
        auto dex_field = JNI_GetStaticFieldID(env, stub, "dex", "[B");
        ScopedLocalRef<jbyteArray> array = JNI_GetStaticObjectField(env, stub, dex_field);

        if (!array) {
            LOGE("Failed to get dex byte array from stub.");
            return;
        }

        auto dex_bytes = env->GetByteArrayElements(array.get(), nullptr);
        auto dex_size = static_cast<size_t>(JNI_GetArrayLength(env, array.get()));

        // Create memfd and write dex bytes into it for PreloadedDex (which now requires an fd)
        int memfd = syscall(__NR_memfd_create, "npatch_dex", 0);
        if (memfd < 0 || write(memfd, dex_bytes, dex_size) != static_cast<ssize_t>(dex_size)) {
            LOGE("Failed to create memfd for dex.");
            env->ReleaseByteArrayElements(array.get(), dex_bytes, JNI_ABORT);
            if (memfd >= 0) close(memfd);
            return;
        }
        env->ReleaseByteArrayElements(array.get(), dex_bytes, JNI_ABORT);

        auto dex = PreloadedDex{memfd, dex_size};
        close(memfd);

        InitArtHooker(env, initInfo);
        LoadDex(env, std::move(dex));
        InitHooks(env);

        ElfSymbolCache::ClearCache();

        SetupEntryClass(env);
        FindAndCall(env, "onLoad", "()V");
    }
}  // namespace vector::native
