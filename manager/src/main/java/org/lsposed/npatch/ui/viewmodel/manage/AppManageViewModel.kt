package org.lsposed.npatch.ui.viewmodel.manage

import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.util.Base64
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lsposed.npatch.Patcher
import org.lsposed.npatch.lspApp
import org.lsposed.npatch.share.Constants
import org.lsposed.npatch.share.PatchConfig
import org.lsposed.npatch.ui.util.installApk
import org.lsposed.npatch.ui.util.uninstallApkByPackageName
import org.lsposed.npatch.ui.viewstate.ProcessingState
import nkbe.util.NPackageManager
import nkbe.util.NPackageManager.AppInfo
import nkbe.util.ShizukuApi
import org.lsposed.patch.util.Logger
import java.io.File
import java.io.FileNotFoundException
import java.util.zip.ZipFile

class AppManageViewModel : ViewModel() {

    companion object {
        private const val TAG = "ManageViewModel"
        private const val AUTO_REFRESH_INTERVAL = 90_114L
    }

    sealed class ViewAction {
        data class UpdateLoader(val appInfo: AppInfo, val config: PatchConfig) : ViewAction()
        object InstallUpdated : ViewAction()
        object InstallUpdatedForce : ViewAction()
        object ClearUpdateLoaderResult : ViewAction()
        data class PerformOptimize(val appInfo: AppInfo) : ViewAction()
        object ClearOptimizeResult : ViewAction()
        object Refresh : ViewAction()
    }

    // 手動管理狀態，避免實時響應系統廣播導致列表跳動
    var appList: List<Pair<AppInfo, PatchConfig>> by mutableStateOf(emptyList())
        private set

    var isRefreshing by mutableStateOf(false)
        private set

    var updateLoaderState: ProcessingState<Result<Unit>> by mutableStateOf(ProcessingState.Idle)
        private set

    var optimizeState: ProcessingState<Boolean> by mutableStateOf(ProcessingState.Idle)
        private set

    // Extra guidance shown on the re-patch completion screen's install step.
    enum class InstallHint { NONE, SIGNATURE_MISMATCH, NEED_SHIZUKU_SPLIT }
    var installHint: InstallHint by mutableStateOf(InstallHint.NONE)
        private set

    // Package name of the app being re-patched, used by the install step.
    private var updatingPackage: String? = null

    // Live re-patch log lines (level to message), rendered full-screen while re-patching so
    // the user sees real progress/errors instead of a bare spinner. Declared before `logger`
    // because the logger appends to it during construction.
    val updateLogs = mutableStateListOf<Pair<Int, String>>()

    private val logger = object : Logger() {
        override fun d(msg: String) {
            if (verbose) {
                Log.d(TAG, msg)
                updateLogs += Log.DEBUG to msg
            }
        }

        override fun i(msg: String) {
            Log.i(TAG, msg)
            updateLogs += Log.INFO to msg
        }

        override fun e(msg: String) {
            Log.e(TAG, msg)
            updateLogs += Log.ERROR to msg
        }
    }

    init {
        viewModelScope.launch {
            snapshotFlow { NPackageManager.appList }
                .filter { it.isNotEmpty() }
                .first()
            Log.d(TAG, "Initial data ready, starting auto-refresh loop")
            // 啓動立即加载
            loadData()

            while (true) {
                delay(AUTO_REFRESH_INTERVAL)
                Log.d(TAG, "Auto refreshing app list (90s timer)")
                if (!isRefreshing) {
                    loadData(silent = true)
                }
            }
        }
    }

    fun dispatch(action: ViewAction) {
        viewModelScope.launch {
            when (action) {
                is ViewAction.UpdateLoader -> updateLoader(action.appInfo, action.config)
                is ViewAction.InstallUpdated -> installUpdatedApp(uninstallFirst = false)
                is ViewAction.InstallUpdatedForce -> installUpdatedApp(uninstallFirst = true)
                is ViewAction.ClearUpdateLoaderResult -> {
                    updateLoaderState = ProcessingState.Idle
                    installHint = InstallHint.NONE
                }
                is ViewAction.PerformOptimize -> performOptimize(action.appInfo)
                is ViewAction.ClearOptimizeResult -> optimizeState = ProcessingState.Idle
                is ViewAction.Refresh -> {
                    if (!isRefreshing) {
                        isRefreshing = true
                        withContext(Dispatchers.IO) {
                            NPackageManager.fetchAppList()
                        }
                        loadData(silent = true)
                        isRefreshing = false
                    }
                }
            }
        }
    }

    // silent 参数用于区分是否显示 loading 状态
    private fun loadData(silent: Boolean = false) {
        if (!silent) isRefreshing = true
        val currentList = NPackageManager.appList.mapNotNull { appInfo ->
            runCatching {
                appInfo.app.metaData?.getString("npatch")?.let {
                    val json = Base64.decode(it, Base64.DEFAULT).toString(Charsets.UTF_8)
                    val config = Gson().fromJson(json, PatchConfig::class.java)
                    if (config?.lspConfig == null) null else appInfo to config
                }
            }.getOrNull()
        }

        Log.d(TAG, "Loaded ${currentList.size} patched apps")
        appList = currentList

        if (!silent) isRefreshing = false
    }

    private suspend fun updateLoader(appInfo: AppInfo, config: PatchConfig) {
        Log.i(TAG, "Update loader for ${appInfo.app.packageName}")
        updateLogs.clear()
        updatingPackage = appInfo.app.packageName
        installHint = InstallHint.NONE
        logger.i("Re-patching ${appInfo.label} (${appInfo.app.packageName})")
        updateLoaderState = ProcessingState.Processing
        val result = runCatching {
            withContext(Dispatchers.IO) {
                NPackageManager.apply {
                    cleanTmpApkDir()
                    cleanExternalTmpApkDir()
                }
                val basePath = appInfo.app.sourceDir
                val splitPaths = (appInfo.app.splitSourceDirs ?: emptyArray()).toList()
                val patchPaths = mutableListOf<String>()
                val embeddedModulePaths = mutableListOf<String>()
                // Only the BASE apk embeds the original apk (assets/npatch/origin.apk). Splits
                // are never given an embedded origin, so requiring one in every apk broke
                // re-patching split apps ("Original apk entry not found"). Extract origin from
                // the base; for splits, feed the installed split back directly — it is the
                // original split content (re-signed), which the patcher re-processes (skipSplit)
                // and re-signs consistently with the base.
                ZipFile(basePath).use { zip ->
                    var entry = zip.getEntry(Constants.ORIGINAL_APK_ASSET_PATH)
                    if (entry == null) entry = zip.getEntry("assets/npatch/origin_apk.bin")
                    if (entry == null) throw FileNotFoundException("Original apk entry not found for base $basePath")
                    zip.getInputStream(entry).use { input ->
                        val dst = lspApp.tmpApkDir.resolve(basePath.substringAfterLast('/'))
                        patchPaths.add(dst.absolutePath)
                        dst.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }
                splitPaths.forEach { split ->
                    val dst = lspApp.tmpApkDir.resolve(split.substringAfterLast('/'))
                    File(split).inputStream().use { input ->
                        dst.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    patchPaths.add(dst.absolutePath)
                }
                ZipFile(appInfo.app.sourceDir).use { zip ->
                    zip.entries().iterator().forEach { entry ->
                        if (entry.name.startsWith(Constants.EMBEDDED_MODULES_ASSET_PATH)) {
                            val dst = lspApp.tmpApkDir.resolve(entry.name.substringAfterLast('/'))
                            embeddedModulePaths.add(dst.absolutePath)
                            zip.getInputStream(entry).use { input ->
                                dst.outputStream().use { output ->
                                    input.copyTo(output)
                                }
                            }
                        }
                    }
                }
                Patcher.patch(logger, Patcher.Options(appInfo.app.packageName, false, config, patchPaths, embeddedModulePaths))
                // Patch only — installation is a separate, user-triggered step (see
                // installUpdatedApp). The auto-popped installer dialog can fail to show or get
                // dismissed by a stray tap, so the completion screen offers an explicit
                // "Install" button the user can tap (and retry) instead.
                if (lspApp.targetApkFiles.isNullOrEmpty()) {
                    throw RuntimeException("No patched APK files found")
                }
                logger.i("Patch finished. Tap Install to update (app data is kept).")
            }
        }
        result.onSuccess {
            logger.i("Re-patch finished successfully")
        }.onFailure {
            logger.e(it.message ?: it.toString())
            logger.e(it.stackTraceToString())
        }
        updateLoaderState = ProcessingState.Done(result)
    }

    // Triggered by the "Install" button on the re-patch completion screen. Installs the
    // just-patched apk(s) (kept in lspApp.targetApkFiles) as an update, preserving data.
    // Two pre-checks match the two failure modes we hit in the field:
    //  - Split apps: the system installer can't reliably install multiple apks, so require
    //    Shizuku and prompt for it when it isn't granted.
    //  - Signature mismatch: if the installed app was patched with a different key, a
    //    data-preserving update install fails ("signatures do not match"). Detect it up front
    //    by comparing the installed signer with the freshly-patched apk's signer, and offer an
    //    explicit "Uninstall & install" action instead of a cryptic system failure.
    private suspend fun installUpdatedApp(uninstallFirst: Boolean = false) {
        val apkFiles = lspApp.targetApkFiles
        if (apkFiles.isNullOrEmpty()) {
            logger.e("No patched APK files to install")
            return
        }
        val pkg = updatingPackage
        if (apkFiles.size > 1 && !ShizukuApi.isPermissionGranted) {
            installHint = InstallHint.NEED_SHIZUKU_SPLIT
            logger.e("This is a split app; the system installer can't install it reliably. Grant Shizuku, then tap Install again.")
            return
        }
        val signerDiffers = if (!uninstallFirst && pkg != null) {
            withContext(Dispatchers.IO) { installedSignerDiffersFrom(pkg, apkFiles.first().absolutePath) }
        } else false
        if (signerDiffers) {
            installHint = InstallHint.SIGNATURE_MISMATCH
            logger.e("The installed app is signed with a different key than this manager. A data-preserving update isn't possible — choose \"Uninstall & install\" (app data will be lost).")
            return
        }
        installHint = InstallHint.NONE
        logger.i(if (uninstallFirst) "Uninstalling then installing ${apkFiles.size} apk(s)..." else "Installing ${apkFiles.size} apk(s)...")
        runCatching {
            withContext(Dispatchers.IO) {
                if (ShizukuApi.isPermissionGranted) {
                    if (uninstallFirst && pkg != null) {
                        val (uStatus, uMessage) = NPackageManager.uninstall(pkg)
                        logger.i("Uninstall status=$uStatus $uMessage")
                    }
                    val (status, message) = NPackageManager.install()
                    if (status != PackageInstaller.STATUS_SUCCESS) throw RuntimeException(message ?: "install failed")
                    logger.i("Installed via Shizuku")
                } else {
                    // Non-Shizuku, single apk only (splits are gated above).
                    if (uninstallFirst && pkg != null) {
                        uninstallApkByPackageName(lspApp, pkg)
                    }
                    installApk(lspApp, apkFiles.first())
                    logger.i("Handed to the system installer.")
                }
            }
        }.onFailure {
            logger.e("Install failed: ${it.message}")
        }
    }

    // True only when the app IS currently installed AND its signing certificate differs from
    // the freshly-patched apk's — i.e. an update install would be rejected for signature
    // mismatch. Returns false (proceed) when not installed or on any lookup error.
    private fun installedSignerDiffersFrom(pkg: String, patchedApkPath: String): Boolean {
        val pm = lspApp.packageManager
        val installedSigner = try {
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
        } catch (e: PackageManager.NameNotFoundException) {
            return false
        } catch (e: Throwable) {
            return false
        } ?: return false
        val patchedSigner = try {
            pm.getPackageArchiveInfo(patchedApkPath, PackageManager.GET_SIGNING_CERTIFICATES)
                ?.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
        } catch (e: Throwable) {
            return false
        } ?: return false
        return !installedSigner.contentEquals(patchedSigner)
    }

    private suspend fun performOptimize(appInfo: AppInfo) {
        Log.i(TAG, "Perform optimize for ${appInfo.app.packageName}")
        optimizeState = ProcessingState.Processing
        val result = withContext(Dispatchers.IO) {
            ShizukuApi.performDexOptMode(appInfo.app.packageName)
        }
        optimizeState = ProcessingState.Done(result)
    }
}