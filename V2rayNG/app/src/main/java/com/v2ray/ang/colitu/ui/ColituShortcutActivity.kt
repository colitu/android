package com.v2ray.ang.colitu.ui

import android.app.Activity
import android.os.Bundle
import com.v2ray.ang.colitu.app.ColituQuickStart
import com.v2ray.ang.core.CoreServiceManager

/**
 * Invisible target of the launcher shortcuts and the home-screen widget.
 * Connecting goes through [ColituQuickStart]; when it may not start the
 * tunnel by itself (signed out, plan over, no VPN consent yet) the app opens
 * instead. Not exported: only the launcher (shortcuts) and this app's own
 * widget PendingIntent can start it.
 */
class ColituShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (intent?.action) {
            ACTION_STOP -> CoreServiceManager.stopVService(this)
            ACTION_TOGGLE -> if (CoreServiceManager.isRunning()) CoreServiceManager.stopVService(this) else connect()
            else -> connect()
        }
        finish()
    }

    private fun connect() {
        if (CoreServiceManager.isRunning()) return
        if (!ColituQuickStart.start(this)) startActivity(ColituQuickStart.openAppIntent(this))
    }

    companion object {
        const val ACTION_START = "com.colitulu.shortcut.START"
        const val ACTION_STOP = "com.colitulu.shortcut.STOP"
        const val ACTION_TOGGLE = "com.colitulu.shortcut.TOGGLE"
    }
}
