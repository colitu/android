package com.v2ray.ang.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.colitu.app.ColituQuickStart
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.MessageUtil
import com.v2ray.ang.util.Utils
import java.lang.ref.SoftReference

/**
 * Quick-settings tile. Turning it on goes through [ColituQuickStart]: a
 * valid Colitu profile starts right away, anything else (signed out, plan
 * over, maintenance, no VPN consent) opens the app.
 */
class QSTileService : TileService() {

    fun setState(state: Int) {
        val tile = qsTile ?: return
        tile.icon = Icon.createWithResource(applicationContext, R.drawable.ic_stat_name)
        tile.state = state
        tile.label = getString(R.string.app_name)
        tile.updateTile()
    }

    override fun onStartListening() {
        super.onStartListening()
        setState(if (CoreServiceManager.isRunning()) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE)
        mMsgReceive = ReceiveMessageHandler(this)
        val mFilter = IntentFilter(AppConfig.BROADCAST_ACTION_ACTIVITY)
        ContextCompat.registerReceiver(applicationContext, mMsgReceive, mFilter, Utils.receiverFlags())
        MessageUtil.sendMsg2Service(this, AppConfig.MSG_REGISTER_CLIENT, "")
    }

    override fun onStopListening() {
        super.onStopListening()
        try {
            mMsgReceive?.let { applicationContext.unregisterReceiver(it) }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to unregister receiver", e)
        }
        mMsgReceive = null
    }

    override fun onClick() {
        super.onClick()
        if (qsTile?.state == Tile.STATE_ACTIVE || CoreServiceManager.isRunning()) {
            CoreServiceManager.stopVService(this)
            return
        }
        if (!ColituQuickStart.start(this)) openApp()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val intent = ColituQuickStart.openAppIntent(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private var mMsgReceive: BroadcastReceiver? = null

    private class ReceiveMessageHandler(context: QSTileService) : BroadcastReceiver() {
        private val reference = SoftReference(context)
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val tile = reference.get() ?: return
            when (intent?.getIntExtra("key", 0)) {
                AppConfig.MSG_STATE_RUNNING, AppConfig.MSG_STATE_START_SUCCESS -> tile.setState(Tile.STATE_ACTIVE)
                AppConfig.MSG_STATE_NOT_RUNNING, AppConfig.MSG_STATE_START_FAILURE, AppConfig.MSG_STATE_STOP_SUCCESS ->
                    tile.setState(Tile.STATE_INACTIVE)
            }
        }
    }
}
