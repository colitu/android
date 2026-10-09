package com.v2ray.ang.colitu.app

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.tencent.mmkv.MMKV
import com.v2ray.ang.AngApplication
import com.v2ray.ang.colitu.api.ColituTokenManager
import com.v2ray.ang.colitu.data.ColituIdSet
import com.v2ray.ang.colitu.data.ColituNotice
import com.v2ray.ang.colitu.data.ColituNotices
import com.v2ray.ang.colitu.l10n.ColituLoc
import com.v2ray.ang.colitu.repository.ColituNoticeRepository
import com.v2ray.ang.core.CoreServiceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Announcements from the panel: the home banner (this process, while the app
 * is in the foreground) and the one-time system notification (also from the
 * VPN process while the tunnel is up, because WorkManager is not a
 * dependency of this app).
 *
 * The dismissed / seen / notified id lists live in multi-process MMKV, so
 * both processes agree on what has been shown.
 */
object ColituNoticeCenter {
    private const val STORE_ID = "COLITU_NOTICES"
    private const val KEY_DISMISSED = "dismissed"
    private const val KEY_SEEN = "seen"
    private const val KEY_NOTIFIED = "notified"
    private const val KEY_LAST_FETCH = "last_fetch_ms"

    /** Foreground refresh: at most this often. */
    private const val FOREGROUND_INTERVAL_MS = 15 * 60_000L
    private const val FORCE_MIN_GAP_MS = 30_000L
    /** Background (tunnel up) check: at most this often. */
    private const val BACKGROUND_INTERVAL_MS = 3 * 60 * 60_000L
    private const val WATCHER_FIRST_DELAY_MS = 30_000L
    private const val WATCHER_STEP_MS = 30 * 60_000L

    private val store by lazy { MMKV.mmkvWithID(STORE_ID, MMKV.MULTI_PROCESS_MODE) }
    private val kv = object : ColituIdSet.Store {
        override fun read(key: String): String? = store.decodeString(key)
        override fun write(key: String, value: String) {
            store.encode(key, value)
        }
    }
    private val dismissed by lazy { ColituIdSet(kv, KEY_DISMISSED) }
    private val seen by lazy { ColituIdSet(kv, KEY_SEEN) }
    private val notified by lazy { ColituIdSet(kv, KEY_NOTIFIED) }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private var all: List<ColituNotice> = emptyList()
    /** SystemClock.elapsedRealtime() of the last foreground fetch; 0 when none yet. */
    private var lastForegroundFetch = 0L
    private var watcher: Job? = null

    /** The notice the home banner shows, or null. Compose state. */
    var current by mutableStateOf<ColituNotice?>(null)
        private set

    // ── Foreground ──────────────────────────────────────────────────────────

    /**
     * Fetches the notices (at most every 15 minutes unless [force]), updates
     * the banner and posts the system notifications that are still due.
     */
    suspend fun refresh(force: Boolean = false) {
        if (!ColituTokenManager.isLoggedIn()) return
        mutex.withLock {
            val now = SystemClock.elapsedRealtime()
            val since = now - lastForegroundFetch
            if (lastForegroundFetch != 0L && since < (if (force) FORCE_MIN_GAP_MS else FOREGROUND_INTERVAL_MS)) return
            ColituLoc.load()
            lastForegroundFetch = now
            val fetched = ColituNoticeRepository.fetch(ColituLoc.language)
            // No answer (older panel, offline, error): nothing new, silently;
            // what is already known stays until the next successful fetch.
            if (fetched != null) {
                all = fetched
                store.encode(KEY_LAST_FETCH, System.currentTimeMillis())
            }
            current = ColituNotices.pickNext(all, dismissed.all().toSet())
            notifyDue(all)
        }
    }

    /** The banner became visible: the panel hears "seen" once per id. */
    fun markSeen(notice: ColituNotice) {
        if (seen.contains(notice.id)) return
        scope.launch {
            if (ColituNoticeRepository.postEvent(notice.id, ColituNoticeRepository.EVENT_SEEN)) seen.add(notice.id)
        }
    }

    /** The banner button was pressed (the caller opens the link). */
    fun markClicked(notice: ColituNotice) {
        scope.launch { ColituNoticeRepository.postEvent(notice.id, ColituNoticeRepository.EVENT_CLICKED) }
    }

    /** The close button: remembered for good, the next notice (if any) takes its place. */
    fun dismiss(notice: ColituNotice) {
        dismissed.add(notice.id)
        current = ColituNotices.pickNext(all, dismissed.all().toSet())
        scope.launch { ColituNoticeRepository.postEvent(notice.id, ColituNoticeRepository.EVENT_DISMISSED) }
    }

    /** Sign-out / session end: nothing of the old account stays on screen. */
    fun reset() {
        all = emptyList()
        current = null
        lastForegroundFetch = 0L
    }

    // ── System notification ─────────────────────────────────────────────────

    private fun notifyDue(notices: List<ColituNotice>) {
        val context = AngApplication.application
        for (notice in ColituNotices.pendingPush(notices, notified.all().toSet())) {
            if (!ColituNoticeNotifier.canPost(context)) return
            // Marked first: the other process may be checking at the same moment.
            if (!notified.add(notice.id)) continue
            if (!ColituNoticeNotifier.post(context, notice)) return
        }
    }

    // ── Background (VPN process) ────────────────────────────────────────────

    /**
     * Called by the VPN process when the tunnel is up. While it stays up, the
     * notices are fetched about every three hours and the push ones are posted;
     * the loop ends by itself when the tunnel goes down.
     */
    @Synchronized
    fun startTunnelWatcher() {
        if (watcher?.isActive == true) return
        watcher = scope.launch {
            delay(WATCHER_FIRST_DELAY_MS)
            while (isActive && CoreServiceManager.isRunning()) {
                val last = store.decodeLong(KEY_LAST_FETCH, 0L)
                val now = System.currentTimeMillis()
                // A clock set back counts as due as well.
                if (now - last >= BACKGROUND_INTERVAL_MS || last > now) checkInBackground()
                delay(WATCHER_STEP_MS)
            }
        }
    }

    private suspend fun checkInBackground() {
        if (!ColituTokenManager.isLoggedIn()) return
        runCatching {
            ColituLoc.load()
            // VPN process: never refreshes the token (it would invalidate the
            // main process's refresh token); 401/403/no token just ends here.
            val fetched = ColituNoticeRepository.fetchPassive(ColituLoc.language) ?: return
            store.encode(KEY_LAST_FETCH, System.currentTimeMillis())
            notifyDue(fetched)
        }
    }
}
