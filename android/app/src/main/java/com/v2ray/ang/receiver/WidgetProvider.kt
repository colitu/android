package com.v2ray.ang.receiver

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.v2ray.ang.R
import com.v2ray.ang.colitu.ui.ColituShortcutActivity
import com.v2ray.ang.core.CoreServiceManager

/**
 * Home-screen on/off widget. The button opens the private
 * [ColituShortcutActivity], so the same Colitu checks as the tile and the
 * shortcuts apply; the VPN process redraws the widget through [refresh].
 */
class WidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        draw(context, appWidgetManager, appWidgetIds, CoreServiceManager.isRunning())
    }

    companion object {
        fun refresh(context: Context, running: Boolean) {
            runCatching {
                val manager = AppWidgetManager.getInstance(context) ?: return
                val ids = manager.getAppWidgetIds(ComponentName(context, WidgetProvider::class.java))
                if (ids.isNotEmpty()) draw(context, manager, ids, running)
            }
        }

        private fun draw(context: Context, manager: AppWidgetManager, ids: IntArray, running: Boolean) {
            val views = RemoteViews(context.packageName, R.layout.widget_switch)
            val intent = Intent(context, ColituShortcutActivity::class.java)
                .setAction(ColituShortcutActivity.ACTION_TOGGLE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val pending = PendingIntent.getActivity(
                context,
                R.id.layout_switch,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            views.setOnClickPendingIntent(R.id.layout_switch, pending)
            if (running) {
                views.setInt(R.id.image_switch, "setImageResource", R.drawable.ic_stop_24dp)
                views.setInt(R.id.layout_background, "setBackgroundResource", R.drawable.ic_rounded_corner_active)
            } else {
                views.setInt(R.id.image_switch, "setImageResource", R.drawable.ic_play_24dp)
                views.setInt(R.id.layout_background, "setBackgroundResource", R.drawable.ic_rounded_corner_inactive)
            }
            ids.forEach { manager.updateAppWidget(it, views) }
        }
    }
}
