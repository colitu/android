package com.v2ray.ang.colitu.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.gson.JsonObject
import com.v2ray.ang.colitu.api.ColituApiClient
import com.v2ray.ang.colitu.api.ColituApiClient.ApiResult
import com.v2ray.ang.colitu.api.ColituTokenManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.TimeZone

/**
 * Talks to the passport API (main process): fetches the state, the unseen
 * events (played by [com.v2ray.ang.colitu.screens.PassportRevealHost], then
 * acknowledged), sends profile changes and the Story share, and imports the
 * v1 on-device passport once per device.
 */
object PassportSync {
    const val PATH = "/client/passport"
    const val EVENTS_PATH = "/client/passport/events"
    const val UNSEEN_PATH = "/client/passport/events?unseen=1"
    const val ACK_PATH = "/client/passport/events/ack"
    const val PROFILE_PATH = "/client/passport/profile"
    const val IMPORT_PATH = "/client/passport/import"

    /** Foreground refreshes closer together than this are skipped. */
    private const val MIN_GAP_MS = 15_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val importMutex = Mutex()
    private val queue = PassportRevealQueue()
    @Volatile private var lastRefresh = 0L

    /** Bumped once per fetched batch in which an admin took something away; the shell shows a short notice. */
    var updatedNotice by androidx.compose.runtime.mutableIntStateOf(0)
        private set

    /** The reveal to play now (oldest unseen server event). Compose state. */
    var current: PassportServerEvent? by mutableStateOf(null)
        private set

    /** App start / back in the foreground, and right after sign-in. */
    fun onForeground() {
        scope.launch {
            importOnce()
            refresh(force = false)
        }
    }

    /**
     * Every minute while the app is visible (the controller's poll loop).
     * While connected the server keeps earning rewards, so the state and its
     * unseen events are fetched again every [PassportJson.FOREGROUND_REFRESH_MS];
     * in between, what the VPN process cached is shown.
     */
    fun foregroundTick(connected: Boolean) {
        if (PassportJson.foregroundRefreshDue(System.currentTimeMillis(), lastRefresh, connected)) {
            scope.launch { refresh(force = true) }
        } else {
            ColituPassport.reloadIfChanged()
        }
    }

    /** Refresh soon (after a connect, the passport screen opening). */
    fun refreshLater(delayMs: Long = 0L) {
        scope.launch {
            if (delayMs > 0) delay(delayMs)
            refresh(force = true)
        }
    }

    /** GET /client/passport; true when a state came back. Unseen events are fetched and queued. */
    suspend fun refresh(force: Boolean = true): Boolean {
        if (!ColituTokenManager.isLoggedIn()) return false
        val now = System.currentTimeMillis()
        if (!force && now - lastRefresh in 0 until MIN_GAP_MS) return false
        lastRefresh = now
        val state = mutex.withLock {
            val result = runCatching { ColituApiClient.get(PATH) }.getOrNull() as? ApiResult.Success ?: return false
            store(result.data) ?: return false
        }
        syncTimeZone(state)
        if (state.unseenEvents > 0) fetchEvents()
        return true
    }

    private suspend fun fetchEvents() {
        val result = runCatching { ColituApiClient.get(UNSEEN_PATH) }.getOrNull() as? ApiResult.Success ?: return
        val events = PassportJson.parseEvents(result.data)
        val ack = withContext(Dispatchers.Main) {
            if (queue.hasNewRevoke(events)) updatedNotice++
            val through = queue.offer(events)
            current = queue.current
            through
        }
        ack?.let { postAck(it) }
    }

    /** The current reveal finished: show the next one, acknowledge what was shown. */
    fun revealDone() {
        val ack = queue.done()
        current = queue.current
        ack?.let { scope.launch { postAck(it) } }
    }

    private suspend fun postAck(through: Long) {
        runCatching { ColituApiClient.post(ACK_PATH, JsonObject().apply { addProperty("through_id", through) }) }
    }

    /** Saves and shows a state answer; null when it is not one. */
    private suspend fun store(answer: JsonObject): PassportState? {
        val state = PassportJson.parseState(answer) ?: return null
        // Signed out while the call was in flight: keep nothing.
        if (!ColituTokenManager.isLoggedIn()) return null
        ColituPassport.cache.save(answer.toString())
        withContext(Dispatchers.Main) { ColituPassport.apply(state) }

        return state
    }

    /**
     * PUT /client/passport/profile with only the changed fields ("" clears
     * one). A refused change (title not earned, bad nickname) goes back to the
     * last server state.
     */
    fun updateProfile(nickname: String? = null, titleId: String? = null, coverId: String? = null) {
        val body = PassportJson.profileBody(nickname, titleId, coverId)
        scope.launch {
            val result = runCatching { ColituApiClient.put(PROFILE_PATH, body) }.getOrNull()
            val stored = (result as? ApiResult.Success)?.let { store(it.data) }
            if (stored == null) {
                withContext(Dispatchers.Main) { ColituPassport.reload() }
                // 204 or an empty answer: read the state instead.
                if (result is ApiResult.Success) refresh(force = true)
            }
        }
    }

    /** A Story was shared: the server counts it for the weekly share goal. */
    fun reportShare() {
        scope.launch {
            val result = runCatching { ColituApiClient.post(EVENTS_PATH, JsonObject().apply { addProperty("kind", "share") }) }.getOrNull()
            if (result is ApiResult.Success) refresh(force = true)
        }
    }

    private suspend fun syncTimeZone(state: PassportState) {
        val offset = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000
        val cache = ColituPassport.cache
        if (state.tzOffsetMin == offset) {
            cache.tzSent = offset
            return
        }
        if (cache.tzSent == offset) return
        val result = runCatching { ColituApiClient.put(PROFILE_PATH, PassportJson.profileBody(tzOffsetMin = offset)) }.getOrNull()
        if (result is ApiResult.Success) {
            cache.tzSent = offset
            store(result.data)
        }
    }

    /**
     * POST /client/passport/import once per device with the v1 progress.
     * Marked done on success, 409 and other permanent refusals; tried again
     * on the next start after network errors.
     */
    suspend fun importOnce() {
        // App start and sign-in may both ask at once: one import is enough.
        if (!importMutex.tryLock()) return
        try {
            importV1()
        } finally {
            importMutex.unlock()
        }
    }

    private suspend fun importV1() {
        val cache = ColituPassport.cache
        if (cache.imported || !ColituTokenManager.isLoggedIn()) return
        val v1 = ColituPassport.v1Snapshot()
        if (!v1.hasProgress) {
            cache.imported = true
            return
        }
        val deviceId = ColituTokenManager.getDeviceId() ?: return
        val result = runCatching { ColituApiClient.post(IMPORT_PATH, PassportJson.importBody(deviceId, v1)) }.getOrNull() ?: return
        // Session ended (revoked device, disabled user): the next account on this device still imports.
        if (result is ApiResult.Error && result.isAuthError) return
        val (status, code) = when (result) {
            is ApiResult.Success -> result.statusCode to null
            is ApiResult.Error -> result.code to result.message
        }
        if (!PassportImportPolicy.finished(status, code)) return
        cache.imported = true
        if (result is ApiResult.Success) store(result.data)
    }

    /** Sign-out: the cached passport and the queued reveals go. */
    fun clearAccount() {
        ColituPassport.cache.clear()
        queue.reset()
        current = null
        lastRefresh = 0L
        ColituPassport.apply(null)
    }
}
