package org.lsposed.npatch.manager

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.lsposed.npatch.R
import org.lsposed.npatch.config.Configs
import org.lsposed.npatch.ui.activity.MainActivity

class KeepAliveService : Service() {

    companion object {
        private const val CHANNEL_ID = "npatch_keepalive"
        private const val NOTIFICATION_ID = 1
        private const val NOTIFICATION_CHECK_INTERVAL_MS = 15_000L
        private const val MIRROR_SYNC_INTERVAL_MS = 30_000L

        // Whether the user has allowed notifications for the manager. On Android 13+ this needs
        // the POST_NOTIFICATIONS runtime permission; when it's off the keep-alive foreground
        // notification is silently suppressed (the service may still run but is invisible and
        // easier for the system to kill), so callers should surface this to the user.
        fun notificationsEnabled(context: Context): Boolean =
            NotificationManagerCompat.from(context).areNotificationsEnabled()

        fun start(context: Context) {
            if (!notificationsEnabled(context)) {
                Log.w("NPatch-KeepAlive", "Notifications are disabled — the keep-alive notification won't be shown; keep-alive may be unreliable.")
            }
            try {
                context.startForegroundService(Intent(context, KeepAliveService::class.java))
            } catch (e: Throwable) {
                // startForegroundService() is disallowed when the manager process is cold-started
                // in the background — e.g. an embedded-mode patched app querying our ConfigProvider
                // boots this Application in the background, where Android 12+ throws
                // ForegroundServiceStartNotAllowedException. KeepAlive is best-effort; it will be
                // (re)started when the user opens the app or on boot, so just swallow it here
                // instead of crashing the whole manager process.
                Log.w("NPatch-KeepAlive", "Unable to start KeepAliveService from background", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, KeepAliveService::class.java))
        }

        fun refresh(context: Context) {
            if (Configs.keepAlive || MirrorSyncManager.hasMirrorTargets(context)) {
                start(context)
            } else {
                stop(context)
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val notificationChecker = object : Runnable {
        override fun run() {
            val nm = getSystemService(NotificationManager::class.java)
            val active = nm.activeNotifications.any { it.id == NOTIFICATION_ID }
            if (!active) {
                nm.notify(NOTIFICATION_ID, buildNotification())
            }
            handler.postDelayed(this, NOTIFICATION_CHECK_INTERVAL_MS)
        }
    }
    private val mirrorSyncRunner = object : Runnable {
        override fun run() {
            if (!Configs.keepAlive && !MirrorSyncManager.hasMirrorTargets(this@KeepAliveService)) {
                stopSelf()
                return
            }
            serviceScope.launch {
                MirrorSyncManager.syncConfiguredApps(this@KeepAliveService)
            }
            handler.postDelayed(this, MIRROR_SYNC_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        enterForeground()
        handler.postDelayed(notificationChecker, NOTIFICATION_CHECK_INTERVAL_MS)
        handler.post(mirrorSyncRunner)
    }

    private fun enterForeground() {
        try {
            // Android 14+ requires a foregroundServiceType to be supplied for typed
            // services (we declare specialUse in the manifest); pass it explicitly.
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                    startForeground(
                        NOTIFICATION_ID,
                        buildNotification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    )
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                    startForeground(NOTIFICATION_ID, buildNotification(), 0)
                else ->
                    startForeground(NOTIFICATION_ID, buildNotification())
            }
        } catch (e: Throwable) {
            // e.g. ForegroundServiceStartNotAllowedException if the service was somehow
            // created from a background-restricted state. Keep-alive is best-effort, so
            // stop rather than crash the manager process.
            Log.w("NPatch-KeepAlive", "Unable to enter foreground", e)
            stopSelf()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(notificationChecker)
        handler.removeCallbacks(mirrorSyncRunner)
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_keepalive),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_keepalive_title))
            .setContentText(getString(R.string.notification_keepalive_text))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
}
