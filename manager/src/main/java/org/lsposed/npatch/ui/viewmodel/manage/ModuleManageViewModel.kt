package org.lsposed.npatch.ui.viewmodel.manage

import android.util.Log
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nkbe.util.NPackageManager

class ModuleManageViewModel : ViewModel() {

    companion object {
        private const val TAG = "ModuleManageViewModel"
    }

    var isRefreshing by mutableStateOf(false)
        private set

    class XposedInfo(
        val api: Int,
        val description: String,
        val scope: List<String>
    )

    val appList: List<Pair<NPackageManager.AppInfo, XposedInfo>> by derivedStateOf {
        NPackageManager.appList.mapNotNull { appInfo ->
            // isXposedModule now covers BOTH legacy (manifest xposedminversion) and modern
            // (libxposed api 101/102, declared via META-INF/xposed/ with no manifest field);
            // it and the module metadata (api/description/scope) are precomputed in fetchAppList.
            if (!appInfo.isXposedModule) return@mapNotNull null
            // A patched host apk carries the "npatch"/"lspatch" patch config; it is not an
            // Xposed module even if it also declares module markers (e.g. patched module apps
            // or LSPatch/ReVanced outputs) — don't mistake it for one.
            val metaData = appInfo.app.metaData
            if (metaData?.getString("npatch") != null || metaData?.getString("lspatch") != null) {
                return@mapNotNull null
            }
            appInfo to XposedInfo(
                appInfo.moduleApi,
                appInfo.moduleDescription,
                appInfo.moduleScope
            )
        }.also {
            Log.d(TAG, "Loaded ${it.size} Xposed modules")
        }
    }

    fun refresh() {
        if (isRefreshing) return
        viewModelScope.launch {
            isRefreshing = true
            withContext(Dispatchers.IO) {
                NPackageManager.fetchAppList()
            }
            isRefreshing = false
        }
    }
}
