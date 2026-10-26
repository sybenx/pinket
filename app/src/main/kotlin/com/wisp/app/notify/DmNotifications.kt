package com.wisp.app.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.wisp.app.MainActivity
import com.wisp.app.R
import java.util.concurrent.TimeUnit

/**
 * Lazy DM notifications: no push service, just a periodic background check (Android runs it
 * about every 15 minutes, later when the phone is dozing). Off by default.
 */
object DmNotifications {
    private const val PREFS = "wisp_settings"
    private const val KEY_ENABLED = "dm_notifications_enabled"
    private const val WORK_NAME = "dm-notify"
    const val CHANNEL_ID = "dms"
    /** Notification ids are derived from the sender so each sender has one at a time. */
    private const val NOTIF_GROUP = "wisp-dms"

    /** Set by MainActivity: the in-app indicators cover DMs while the app is on screen. */
    @Volatile var appVisible = false

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (enabled) schedule(context) else WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    /** Re-asserts the schedule. Safe to call on every start; keeps an existing schedule. */
    fun schedule(context: Context) {
        if (!isEnabled(context)) return
        val request = PeriodicWorkRequestBuilder<DmNotificationWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun hasPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.dm_notif_channel), NotificationManager.IMPORTANCE_HIGH)
        )
    }

    /** One notification per sender; [lines] are that sender's new messages, oldest first. */
    fun show(context: Context, senderPubkey: String, title: String, lines: List<String>) {
        if (!hasPermission(context) || lines.isEmpty()) return
        ensureChannel(context)
        val open = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setData(Uri.parse("$DM_URI_SCHEME:$senderPubkey"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(
            context, senderPubkey.hashCode(), open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = lines.last()
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setGroup(NOTIF_GROUP)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
        if (lines.size > 1) {
            builder.setNumber(lines.size)
                .setStyle(NotificationCompat.InboxStyle().also { s -> lines.takeLast(6).forEach { s.addLine(it) } })
        }
        try {
            NotificationManagerCompat.from(context).notify(senderPubkey.hashCode(), builder.build())
        } catch (_: SecurityException) {
            // Permission revoked between the check and the post
        }
    }

    /** Clear DM notifications once the user is looking at the app. */
    fun clearAll(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.activeNotifications.filter { it.notification.group == NOTIF_GROUP }.forEach { nm.cancel(it.id) }
    }

    /** Deep link a DM notification opens: "wisp-dm:<peer pubkey hex>". */
    const val DM_URI_SCHEME = "wisp-dm"
}
