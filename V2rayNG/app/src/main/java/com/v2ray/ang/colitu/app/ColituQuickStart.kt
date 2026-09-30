package com.v2ray.ang.colitu.app

import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.tencent.mmkv.MMKV
import com.v2ray.ang.colitu.ui.ColituMainActivity
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.handler.MmkvManager

/**
 * The one rule for starting the tunnel without the app on screen (quick
 * settings tile, home-screen widget, launcher shortcut, Always-on restart):
 * only the Colitu profile the app itself last started, only while the
 * panel's offline grace for that profile lasts, and only when the last
 * policy check did not deny the connection. Everything else opens the app,
 * which checks the account and fetches a fresh profile.
 *
 * Works in every process: the state lives in multi-process MMKV and is
 * written by [ColituController] after a verified start and cleared on
 * sign-out, session end and every denial.
 */
object ColituQuickStart {
    private const val STORE_ID = "COLITU_SERVERS"
    private const val KEY_UNTIL = "quick_start_until"
    private const val KEY_GUID = "quick_start_guid"

    private val store by lazy { MMKV.mmkvWithID(STORE_ID, MMKV.MULTI_PROCESS_MODE) }

    /**
     * Allows quick starts of [guid] until [graceUntilMs] (server time, epoch
     * ms). [skewMs] is server minus device time, so the deadline is stored on
     * the device clock that the other processes read.
     */
    fun allow(guid: String, graceUntilMs: Long, skewMs: Long) {
        store.encode(KEY_GUID, guid)
        store.encode(KEY_UNTIL, graceUntilMs - skewMs)
    }

    fun revoke() {
        store.removeValuesForKeys(arrayOf(KEY_UNTIL, KEY_GUID))
    }

    /** The guid that may be started now, or null. */
    fun startableGuid(now: Long = System.currentTimeMillis()): String? {
        val until = store.decodeLong(KEY_UNTIL, 0L)
        val guid = store.decodeString(KEY_GUID)?.takeIf { it.isNotBlank() } ?: return null
        if (until <= now) return null
        if (MmkvManager.decodeServerConfig(guid) == null) return null
        return guid
    }

    /**
     * Starts the tunnel when the rule allows it and Android's VPN consent is
     * in place; returns false when the caller should open the app instead.
     */
    fun start(context: Context): Boolean {
        val guid = startableGuid() ?: return false
        if (VpnService.prepare(context) != null) return false
        CoreServiceManager.startVService(context, guid)
        return true
    }

    fun openAppIntent(context: Context): Intent =
        Intent(context, ColituMainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
}
