package com.v2ray.ang.colitu.app

/**
 * A transport start that got no answer within the start timeout. When the VPN
 * service process never acknowledged the start command (AppConfig.MSG_COLITU_SERVICE_ALIVE),
 * Android did not start the process at all (seen as "Pending, app=null" in
 * ActivityManager): every transport and server would time out the same way, so
 * the connect stops with [ENGINE_NOT_STARTED] and no server is blamed.
 */
object ColituEngineStart {
    const val ENGINE_NOT_STARTED = "ENGINE_NOT_STARTED"
    const val TUNNEL_TIMEOUT = "TUNNEL_TIMEOUT"

    /** The code of a start that timed out; [serviceSeen]: the service process answered the command. */
    fun timeoutCode(serviceSeen: Boolean): String = if (serviceSeen) TUNNEL_TIMEOUT else ENGINE_NOT_STARTED

    /** False when the failure is the phone's (the service never started): no server vote, no whitelist count. */
    fun blamesServer(code: String?): Boolean = !code.equals(ENGINE_NOT_STARTED, ignoreCase = true)
}
