package org.lsposed.npatch.manager

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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

        fun start(context: Context) {
            context.startForegroundService(Intent(context, KeepAliveService::class.java))
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
        startForeground(NOTIFICATION_ID, buildNotification())
        handler.postDelayed(notificationChecker, NOTIFICATION_CHECK_INTERVAL_MS)
        handler.post(mirrorSyncRunner)
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
