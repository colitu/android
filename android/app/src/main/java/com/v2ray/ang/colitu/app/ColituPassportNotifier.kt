package com.v2ray.ang.colitu.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.v2ray.ang.R
import com.v2ray.ang.colitu.data.ColituPassport
import com.v2ray.ang.colitu.data.StampEvent
import com.v2ray.ang.colitu.l10n.ColituLoc
import com.v2ray.ang.colitu.ui.ColituMainActivity

/**
 * Local notification when the VPN service earns a passport reward (at most
 * two pieces a day, plus new stamps). Off with [ColituPassport.notify]; never
 * asks for the notification permission.
 */
object ColituPassportNotifier {
    const val CHANNEL_ID = "colitu_passport"
    private const val NOTIFICATION_ID = 0x50A55

    fun post(context: Context, events: List<StampEvent>) {
        if (!ColituPassport.notify) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        // The biggest news of this minute: a new level beats a new stamp beats a piece.
        val event = events.firstOrNull { it is StampEvent.Mastered || it is StampEvent.LevelUp }
            ?: events.firstOrNull { it is StampEvent.Unlocked }
            ?: events.firstOrNull() ?: return
        runCatching {
            ColituLoc.load()
            val loc = ColituLoc
            val country = loc.countryName(event.code)
            val (title, body) = when (event) {
                is StampEvent.Unlocked -> loc.format("passport.n.unlockedTitle", "country" to country) to loc["passport.n.unlockedBody"]
                is StampEvent.Piece -> loc.format("passport.n.pieceTitle", "country" to country) to
                    loc.format("passport.pieces", "n" to event.levelPieces)
                is StampEvent.LevelUp -> loc.format("passport.levelUp", "country" to country, "level" to loc["passport.level.${event.level.key}"]) to
                    loc["passport.n.levelBody"]
                is StampEvent.Mastered -> loc.format("passport.mastered", "country" to country) to loc["passport.n.levelBody"]
                // Sets and achievements are found by the app itself.
                is StampEvent.Achieved, is StampEvent.Collected, is StampEvent.Chapter, is StampEvent.Expedition, is StampEvent.Special -> return@runCatching
            }
            ensureChannel(context)
            val intent = Intent(context, ColituMainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_OPEN_PASSPORT, true)
            val tap = PendingIntent.getActivity(context, NOTIFICATION_ID, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_name)
                .setContentTitle(title)
                .setContentText(body)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
                .setAutoCancel(true)
                .setContentIntent(tap)
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return@runCatching
            try {
                NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build())
            } catch (_: SecurityException) {
            }
        }
    }

    const val EXTRA_OPEN_PASSPORT = "colitu_open_passport"

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, ColituLoc["passport.n.channel"], NotificationManager.IMPORTANCE_DEFAULT),
        )
    }
}
