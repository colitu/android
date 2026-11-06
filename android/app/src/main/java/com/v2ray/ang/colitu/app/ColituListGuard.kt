package com.v2ray.ang.colitu.app

/**
 * Which answers of the panel may speak for the device's own network. The panel names the country and
 * network it sees the request come from: with the VPN on that is the exit, empty for a node it knows,
 * but filled for a bridge exit it does not know yet.
 */
object ColituListGuard {
    /** Nothing of a tunnel is up, held or on its way; connecting and disconnecting count as on. */
    fun vpnOff(status: VpnStatus, serviceUp: Boolean, serviceHeld: Boolean, starting: Boolean, coreUp: Boolean): Boolean =
        status == VpnStatus.Disconnected && !serviceUp && !serviceHeld && !starting && !coreUp

    /** The list's country, network and network record count only when it was both asked and answered with the VPN off. */
    fun trustsPlace(offWhenAsked: Boolean, offWhenAnswered: Boolean): Boolean = offWhenAsked && offWhenAnswered

    /** With the VPN on only the token tells the panel the network; without it the stored model stays. */
    fun fetchesModel(vpnOff: Boolean, token: String?): Boolean = vpnOff || !token.isNullOrBlank()
}
