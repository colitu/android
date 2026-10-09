package com.v2ray.ang.colitu.app

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
import com.v2ray.ang.R
import com.v2ray.ang.colitu.data.ColituNotice
import com.v2ray.ang.colitu.l10n.ColituLoc
import com.v2ray.ang.colitu.ui.ColituMainActivity

/**
 * The one-time system notification for notices with push=true, on its own
 * channel. Never asks for the notification permission: without it (Android
 * 13+) nothing is posted and the notice stays "not notified yet".
 */
object ColituNoticeNotifier {
    const val CHANNEL_ID = "colitu_notices"
    private const val ID_BASE = 0x4E0000

    /** True when a notification can be shown right now without asking the user. */
    fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /** Shows [notice]; false when it could not be posted. */
    fun post(context: Context, notice: ColituNotice): Boolean {
        if (!canPost(context)) return false
        return runCatching {
            ColituLoc.load()
            ensureChannel(context)
            val notificationId = ID_BASE + (notice.id.hashCode() and 0xFFFF)
            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_name)
                .setContentTitle(notice.title)
                .setContentText(notice.body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(notice.body))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
                .setAutoCancel(true)
                .setContentIntent(tapIntent(context, notice, notificationId))
            NotificationManagerCompat.from(context).notify(notificationId, builder.build())
            true
        }.getOrDefault(false)
    }

    private fun tapIntent(context: Context, notice: ColituNotice, requestCode: Int): PendingIntent {
        val intent = notice.url
            ?.let { Intent(Intent.ACTION_VIEW, Uri.parse(it)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            ?: Intent(context, ColituMainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // Creating a channel again only refreshes its name (language change).
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, ColituLoc["notice.channel"], NotificationManager.IMPORTANCE_DEFAULT),
        )
    }
}
