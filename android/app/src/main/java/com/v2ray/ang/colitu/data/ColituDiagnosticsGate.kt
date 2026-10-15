package com.v2ray.ang.colitu.data

/**
 * When the app asks the panel for a connection-report request and when it asks
 * the user about one. Pure (the clock is injected) so it is unit tested.
 *
 *  - the panel is asked at most once per [CHECK_INTERVAL_MS];
 *  - a request is shown once; after "later" (dialog dismissed) or a failed
 *    upload it is shown again no sooner than [REASK_INTERVAL_MS];
 *  - a request that was sent or declined in this run is never shown again;
 *  - an expired request is never shown.
 */
class ColituDiagnosticsGate(private val clock: () -> Long = System::currentTimeMillis) {
    private var lastCheck = 0L
    private var checked = false
    private val lastAsked = HashMap<String, Long>()
    private val answered = HashSet<String>()
    private var showing: String? = null

    /** True (and the check is recorded) when the panel may be asked now. A clock set back counts as due. */
    @Synchronized
    fun tryStartCheck(): Boolean {
        val now = clock()
        if (checked && now >= lastCheck && now - lastCheck < CHECK_INTERVAL_MS) return false
        checked = true
        lastCheck = now
        return true
    }

    /** True when [request] should be put in front of the user now. */
    @Synchronized
    fun shouldAsk(request: ColituDiagnosticsRequest): Boolean {
        if (!request.pending || request.id in answered || showing != null) return false
        val now = clock()
        request.expiresAt?.let { if (it.toEpochMilli() <= now) return false }
        val asked = lastAsked[request.id] ?: return true
        return now < asked || now - asked >= REASK_INTERVAL_MS
    }

    /** The dialog is on screen for [id]. */
    @Synchronized
    fun markShown(id: String) {
        showing = id
        lastAsked[id] = clock()
    }

    /** The dialog went away without an answer, or the upload failed: the 10 minutes start now. */
    @Synchronized
    fun markLater(id: String) {
        if (showing == id) showing = null
        lastAsked[id] = clock()
    }

    /** Sent or declined: never asked about again in this run. */
    @Synchronized
    fun markAnswered(id: String) {
        if (showing == id) showing = null
        answered.add(id)
    }

    @Synchronized
    fun reset() {
        lastCheck = 0L
        checked = false
        lastAsked.clear()
        answered.clear()
        showing = null
    }

    companion object {
        const val CHECK_INTERVAL_MS = 60_000L
        const val REASK_INTERVAL_MS = 10L * 60_000L
    }
}
