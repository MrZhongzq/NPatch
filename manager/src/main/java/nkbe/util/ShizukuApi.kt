package nkbe.util

import android.content.IntentSender
import android.content.pm.*
import android.os.Build
import android.os.IBinder
import android.os.IInterface
import android.os.Process
import android.os.SystemProperties
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.rikka.tools.refine.Refine
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.ShizukuProvider
import rikka.shizuku.SystemServiceHelper

object ShizukuApi {
    private const val PERMISSION_REQUEST_CODE = 114514
    private var initialized = false

    private fun IBinder.wrap() = ShizukuBinderWrapper(this)
    private fun IInterface.asShizukuBinder() = this.asBinder().wrap()

    // Fetched fresh each use (not `by lazy`): a cached binder goes stale after Shizuku
    // reconnects, which was a source of flaky "service not ready" failures.
    private val iPackageManager: IPackageManager
        get() = IPackageManager.Stub.asInterface(getSystemService("package"))

    private val iPackageInstaller: IPackageInstaller
        get() = IPackageInstaller.Stub.asInterface(iPackageManager.packageInstaller.asShizukuBinder())

    private val packageInstaller: PackageInstaller
        get() {
            val userId = Process.myUserHandle().hashCode()
            // 參考 JingMatrix (2ac407dc50):S+ 以 com.android.shell 作 installer。
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Refine.unsafeCast(PackageInstallerHidden(iPackageInstaller, "com.android.shell", null, userId))
            } else {
                Refine.unsafeCast(PackageInstallerHidden(iPackageInstaller, "com.android.shell", userId))
            }
        }

    var isBinderAvailable by mutableStateOf(false)
    var isPermissionGranted by mutableStateOf(false)

    val isReady: Boolean
        get() = isBinderAvailable && isPermissionGranted

    fun init() {
        if (initialized) {
            refreshState()
            return
        }
        initialized = true
        // Keep the binder alive across the manager's own child processes.
        runCatching { ShizukuProvider.enableMultiProcessSupport(true) }
        Shizuku.addBinderReceivedListenerSticky { refreshState() }
        Shizuku.addBinderDeadListener {
            isBinderAvailable = false
            isPermissionGranted = false
        }
        // Refresh after the user answers the permission dialog so isPermissionGranted (and
        // any Compose UI observing it) updates immediately instead of on the next reconnect.
        Shizuku.addRequestPermissionResultListener { _, _ -> refreshState() }
    }

    fun refreshState() {
        isBinderAvailable = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        isPermissionGranted = isBinderAvailable &&
                runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }
                    .getOrDefault(false)
    }

    fun requestPermission(requestCode: Int = PERMISSION_REQUEST_CODE) {
        refreshState()
        if (!isBinderAvailable) return
        runCatching { Shizuku.requestPermission(requestCode) }
    }

    fun addRequestPermissionResultListener(listener: (Int, Int) -> Unit) {
        Shizuku.addRequestPermissionResultListener(listener)
    }

    fun removeRequestPermissionResultListener(listener: (Int, Int) -> Unit) {
        Shizuku.removeRequestPermissionResultListener(listener)
    }

    private fun ensureReady() {
        refreshState()
        check(isBinderAvailable) { "Shizuku binder is not available" }
        check(isPermissionGranted) { "Shizuku permission is not granted" }
    }

    fun getSystemService(name: String): IBinder {
        ensureReady()
        return SystemServiceHelper.getSystemService(name).wrap()
    }

    fun getInstalledApplications(): List<ApplicationInfo> {
        ensureReady()
        val userId = Process.myUserHandle().hashCode()
        val flags: Long = PackageManager.GET_META_DATA.toLong()
        return iPackageManager.getInstalledApplications(flags, userId).list
    }

    fun createPackageInstallerSession(params: PackageInstaller.SessionParams): PackageInstaller.Session {
        ensureReady()
        val sessionId = packageInstaller.createSession(params)
        val iSession = IPackageInstallerSession.Stub.asInterface(iPackageInstaller.openSession(sessionId).asShizukuBinder())
        return Refine.unsafeCast(PackageInstallerHidden.SessionHidden(iSession))
    }

    fun isPackageInstalledWithoutPatch(packageName: String): Boolean {
        ensureReady()
        val userId = Process.myUserHandle().hashCode()
        val app = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            iPackageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA.toLong(), userId)
        } else {
            iPackageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA, userId)
        }
        return (app != null) && (app.metaData?.containsKey("npatch") != true)
    }

    fun uninstallPackage(packageName: String, intentSender: IntentSender) {
        ensureReady()
        packageInstaller.uninstall(packageName, intentSender)
    }

    fun performDexOptMode(packageName: String): Boolean {
        ensureReady()
        return iPackageManager.performDexOptMode(
            packageName,
            SystemProperties.getBoolean("dalvik.vm.usejitprofiles", false),
            "verify", true, true, null
        )
    }
}
