package com.v2ray.ang.contracts

import android.app.Service

interface ServiceControl {
    /**
     * Gets the service instance.
     * @return The service instance.
     */
    fun getService(): Service

    /**
     * Starts the service.
     */
    fun startService()

    /**
     * Stops the service.
     */
    fun stopService()

    /**
     * Stops the core but keeps the VPN interface established (Colitu server
     * or transport switch): traffic is dropped, never sent around the tunnel.
     */
    fun holdService()

    /**
     * Protects the VPN socket.
     * @param socket The socket to protect.
     * @return True if the socket is protected, false otherwise.
     */
    fun vpnProtect(socket: Int): Boolean
}
