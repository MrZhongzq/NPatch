package org.lsposed.npatch.manager

import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.runBlocking
import org.lsposed.npatch.config.ConfigManager
import org.lsposed.npatch.lspApp
import org.lsposed.lspd.models.Module
import org.lsposed.lspd.service.ILSPApplicationService

object ManagerService : ILSPApplicationService.Stub() {

    private const val TAG = "ManagerService"

    override fun isLogMuted(): Boolean {
        return false
    }

    override fun getLegacyModulesList(): List<Module> {
        val app = lspApp.packageManager.getNameForUid(Binder.getCallingUid())
        // Split by ModuleLoader's pipeline classification (file.legacy). Previously this returned ALL
        // modules and getModulesList() returned empty, so modern (libxposed api 10x) modules landed in
        // the legacy bucket and got rejected by the IXposedMod check. file is set in getModuleFilesForApp.
        val list = modulesForCaller(app).filter { it.file?.legacy == true }
        Log.d(TAG, "$app calls getLegacyModulesList: $list")
        return list
    }

    override fun getModulesList(): List<Module> {
        val app = lspApp.packageManager.getNameForUid(Binder.getCallingUid())
        val list = modulesForCaller(app).filter { it.file?.legacy == false }
        Log.d(TAG, "$app calls getModulesList: $list")
        return list
    }

    // Modules cached by ConfigManager.loadedModules, so calling this for both buckets is cheap (no
    // duplicate ModuleLoader.loadModule zip/dex work). Modules whose apk can't be parsed (file == null)
    // are excluded from both — they'd fail to load anyway.
    private fun modulesForCaller(app: String?): List<Module> =
        app?.let { runBlocking { ConfigManager.getModuleFilesForApp(it) } }.orEmpty()

    override fun getPrefsPath(packageName: String): String {
        TODO("Not yet implemented")
    }

    override fun requestInjectedManagerBinder(binder: List<IBinder>?): ParcelFileDescriptor? {
        return null
    }
}
