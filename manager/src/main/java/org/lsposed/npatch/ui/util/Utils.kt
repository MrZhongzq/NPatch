package org.lsposed.npatch.ui.util

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.compose.foundation.lazy.LazyListState
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext
import org.lsposed.npatch.BuildConfig
import java.io.File
import java.io.IOException

/**
 * Bridges install results from [InstallResultReceiver] (a plain BroadcastReceiver, off the Compose
 * world) back to whatever screen kicked off the install. The rootless PackageInstaller session
 * reports its final verdict — including failures like INSTALL_FAILED_DEPRECATED_SDK_VERSION for a
 * too-low targetSdk — via the committed PendingIntent broadcast, so this is the only place the UI
 * can learn why an install failed. Emits (status, message).
 */
object InstallEventBus {
    val events = MutableSharedFlow<Pair<Int, String?>>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
}

val LazyListState.lastVisibleItemIndex
    get() = layoutInfo.visibleItemsInfo.lastOrNull()?.index

val LazyListState.lastItemIndex
    get() = layoutInfo.totalItemsCount.let { if (it == 0) null else it }

val LazyListState.isScrolledToEnd
    get() = lastVisibleItemIndex == lastItemIndex

fun checkIsApkFixedByLSP(context: Context, packageName: String): Boolean {
    return try {
        val app =
            context.packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
        (app.metaData?.containsKey("npatch") != true)
    } catch (_: PackageManager.NameNotFoundException) {
        Log.e("NPatch", "Package not found: $packageName")
        false
    } catch (e: Exception) {
        Log.e("NPatch", "Unexpected error in checkIsApkFixedByLSP", e)
        false
    }
}

fun installApk(context: Context, apkFile: File) {
    try {
        val apkUri =
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)

        val intent = Intent(Intent.ACTION_VIEW).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addCategory("android.intent.category.DEFAULT")
            setDataAndType(apkUri, "application/vnd.android.package-archive")
        }
        context.startActivity(intent)
    } catch (_: Exception) {
    }
}

fun uninstallApkByPackageName(context: Context, packageName: String) = try {
    val intent = Intent(Intent.ACTION_DELETE).apply {
        data = "package:$packageName".toUri()
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
} catch (_: Exception) {
}

class InstallResultReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_INSTALL_STATUS = "${BuildConfig.APPLICATION_ID}.INSTALL_STATUS"

        fun createPendingIntent(context: Context, sessionId: Int): PendingIntent {
            val intent = Intent(context, InstallResultReceiver::class.java).apply {
                action = ACTION_INSTALL_STATUS
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            return PendingIntent.getBroadcast(context, sessionId, intent, flags)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INSTALL_STATUS) {
            return
        }

        val status =
            intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Not a final verdict yet: hand the user the system confirmation UI. The session
                // will broadcast again (SUCCESS / failure) once they act, and that lands below.
                val confirmIntent = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirmIntent != null) {
                    context.startActivity(confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }

            else -> {
                // Final verdict (SUCCESS or any failure). Surface it so the UI can react instead of
                // silently swallowing failures (previously both branches were empty -> "no feedback").
                InstallEventBus.events.tryEmit(status to message)
            }
        }
    }
}

/**
 * Pre-flight check: modern Android refuses to install apps whose targetSdk is below a per-release
 * floor (API 34 = 23, API 35+ = 24), and the rootless installer often surfaces this as a bare
 * "app not installed" with no reason. Returns a human-readable explanation when the apk is below the
 * floor, or null when it is installable. This is advisory — the session install still runs and its
 * real failure message is reported too — but it lets us fail fast with a clear reason.
 */
fun checkTargetSdkTooLow(context: Context, apkFile: File): String? {
    return try {
        val info = context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
        val target = info?.applicationInfo?.targetSdkVersion ?: return null
        val floor = when {
            Build.VERSION.SDK_INT >= 35 -> 24  // Android 15+
            Build.VERSION.SDK_INT >= 34 -> 23  // Android 14
            else -> 0
        }
        if (target in 1 until floor) {
            "该应用 targetSdk=$target 低于当前系统的安装门槛(需 ≥$floor),系统会拒绝安装。" +
                "常见于旧的加固样本(如 360 加固原包 targetSdk=23)。"
        } else null
    } catch (e: Exception) {
        Log.w("NPatch", "targetSdk pre-check failed for ${apkFile.name}", e)
        null
    }
}

suspend fun installApks(context: Context, apkFiles: List<File>): Boolean {
    if (!context.packageManager.canRequestPackageInstalls()) {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = "package:${context.packageName}".toUri()
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
        return false
    }

    apkFiles.forEach {
        if (!it.exists()) {
            return false
        }
    }

    return withContext(Dispatchers.IO) {
        val packageInstaller = context.packageManager.packageInstaller
        var session: PackageInstaller.Session? = null
        try {
            val params =
                PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            val sessionId = packageInstaller.createSession(params)
            session = packageInstaller.openSession(sessionId)

            apkFiles.forEach { apkFile ->
                session.openWrite(apkFile.name, 0, apkFile.length()).use { outputStream ->
                    apkFile.inputStream().use { inputStream ->
                        inputStream.copyTo(outputStream)
                        session.fsync(outputStream)
                    }
                }
            }

            val pendingIntent = InstallResultReceiver.createPendingIntent(context, sessionId)

            session.commit(pendingIntent.intentSender)
            true
        } catch (_: IOException) {
            session?.abandon()
            false
        } catch (_: Exception) {
            session?.abandon()
            false
        }
    }
}