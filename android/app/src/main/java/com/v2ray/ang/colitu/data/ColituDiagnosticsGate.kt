package com.v2ray.ang.colitu.data

/**
 * When the app asks the panel for a connection-report request and when it
 * answers one. Pure (the clock is injected) so it is unit tested.
 *
 *  - the panel is asked at most once per [CHECK_INTERVAL_MS];
 *  - a pending request is answered silently; after a failed upload it is tried
 *    again no sooner than [RETRY_INTERVAL_MS];
 *  - a request that was sent, or that the panel says is no longer pending, is
 *    never answered again in this run;
 *  - an expired request is skipped; only one upload runs at a time.
 */
class ColituDiagnosticsGate(private val clock: () -> Long = System::currentTimeMillis) {
    private var lastCheck = 0L
    private var checked = false
    private val lastAttempt = HashMap<String, Long>()
    private val answered = HashSet<String>()
    private var inFlight: String? = null

    /** True (and the check is recorded) when the panel may be asked now. A clock set back counts as due. */
    @Synchronized
    fun tryStartCheck(): Boolean {
        val now = clock()
        if (checked && now >= lastCheck && now - lastCheck < CHECK_INTERVAL_MS) return false
        checked = true
        lastCheck = now
        return true
    }

    /** True when [request] should be answered now. */
    @Synchronized
    fun shouldSend(request: ColituDiagnosticsRequest): Boolean {
        if (!request.pending || request.id in answered || inFlight != null) return false
        val now = clock()
        request.expiresAt?.let { if (it.toEpochMilli() <= now) return false }
        val last = lastAttempt[request.id] ?: return true
        return now < last || now - last >= RETRY_INTERVAL_MS
    }

    /** The upload for [id] starts. */
    @Synchronized
    fun markAttempt(id: String) {
        inFlight = id
        lastAttempt[id] = clock()
    }

    /** The upload failed: the retry wait starts now. */
    @Synchronized
    fun markFailed(id: String) {
        if (inFlight == id) inFlight = null
        lastAttempt[id] = clock()
    }

    /** Sent, or no longer pending: never answered again in this run. */
    @Synchronized
    fun markAnswered(id: String) {
        if (inFlight == id) inFlight = null
        answered.add(id)
    }

    @Synchronized
    fun reset() {
        lastCheck = 0L
        checked = false
        lastAttempt.clear()
        answered.clear()
        inFlight = null
    }

    companion object {
        const val CHECK_INTERVAL_MS = 60_000L
        const val RETRY_INTERVAL_MS = 10L * 60_000L
    }
}
