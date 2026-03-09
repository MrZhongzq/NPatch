package org.lsposed.npatch.manager

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.util.concurrent.ConcurrentHashMap

class MiPushFallbackReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_POST_FALLBACK) return

        val targetPackage = intent.getStringExtra(EXTRA_TARGET_PACKAGE)?.trim().orEmpty()
        if (targetPackage.isEmpty()) return

        val appLabel = intent.getStringExtra(EXTRA_APP_LABEL)?.takeIf { it.isNotBlank() }
            ?: targetPackage
        val title = intent.getStringExtra(EXTRA_TITLE)?.takeIf { it.isNotBlank() }
            ?: "New message"
        val text = intent.getStringExtra(EXTRA_TEXT)?.takeIf { it.isNotBlank() }
            ?: "Push delivery fallback"
        val dedupeKey = "$targetPackage|$title|$text"
        val now = SystemClock.elapsedRealtime()
        val previous = recentNotifications[dedupeKey]
        if (previous != null && now - previous < DEDUPE_WINDOW_MS) {
            return
        }
        recentNotifications[dedupeKey] = now

        ensureChannel(context)

        val contentIntent = context.packageManager.getLaunchIntentForPackage(targetPackage)?.let {
            PendingIntent.getActivity(
                context,
                targetPackage.hashCode(),
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                pendingIntentFlags()
            )
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("npatch: $appLabel")
            .setContentText("$title: $text")
            .setStyle(NotificationCompat.BigTextStyle().bigText("$title: $text"))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .apply {
                if (contentIntent != null) {
                    setContentIntent(contentIntent)
                }
            }
            .build()

        NotificationManagerCompat.from(context).notify(targetPackage, NOTIFICATION_ID, notification)
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val existing = manager.getNotificationChannel(CHANNEL_ID)
        if (existing != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "MiPush Fallback",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Fallback notifications bridged from MiPush delivery failures"
            }
        )
    }

    private fun pendingIntentFlags(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
    }

    companion object {
        const val ACTION_POST_FALLBACK = "org.lsposed.npatch.action.POST_MIPUSH_FALLBACK"
        const val EXTRA_TARGET_PACKAGE = "target_package"
        const val EXTRA_APP_LABEL = "app_label"
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"

        private const val CHANNEL_ID = "mipush_fallback"
        private const val NOTIFICATION_ID = 0x4D495055
        private const val DEDUPE_WINDOW_MS = 5000L
        private val recentNotifications = ConcurrentHashMap<String, Long>()

        fun componentName(context: Context): ComponentName {
            return ComponentName(context.packageName, MiPushFallbackReceiver::class.java.name)
        }
    }
}
