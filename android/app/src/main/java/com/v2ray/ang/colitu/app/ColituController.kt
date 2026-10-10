package com.v2ray.ang.colitu.app

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.PowerManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tencent.mmkv.MMKV
import com.v2ray.ang.AppConfig
import com.v2ray.ang.colitu.api.ColituClock
import com.v2ray.ang.colitu.api.ColituAuthEvents
import com.v2ray.ang.colitu.api.ColituTokenManager
import com.v2ray.ang.colitu.data.ClientBootstrapPolicy
import com.v2ray.ang.colitu.data.ColituAdBlock
import com.v2ray.ang.colitu.data.ColituConnectMemory
import com.v2ray.ang.colitu.data.ColituNetworkHintsPolicy
import com.v2ray.ang.colitu.data.ColituNetworkRecord
import com.v2ray.ang.colitu.data.ColituObservation
import com.v2ray.ang.colitu.data.ColituDeviceOutlook
import com.v2ray.ang.colitu.data.ColituDevicePause
import com.v2ray.ang.colitu.data.ColituMultihop
import com.v2ray.ang.colitu.data.ColituPing
import com.v2ray.ang.colitu.data.ColituFeatures
import com.v2ray.ang.colitu.data.ColituRecoverySet
import com.v2ray.ang.colitu.data.ColituRecoveryTestMode
import com.v2ray.ang.colitu.data.ColituRotation
import com.v2ray.ang.colitu.data.ColituRotationPreference
import com.v2ray.ang.colitu.data.ColituRotationStatus
import com.v2ray.ang.colitu.data.ColituSplitTunnel
import com.v2ray.ang.colitu.data.ColituRuBypass
import com.v2ray.ang.colitu.data.ColituServer
import com.v2ray.ang.colitu.data.ColituServerRanking
import com.v2ray.ang.colitu.data.ColituSubscription
import com.v2ray.ang.colitu.data.ColituUser
import com.v2ray.ang.colitu.data.ColituVpnConfig
import com.v2ray.ang.colitu.data.ColituUiMode
import com.v2ray.ang.colitu.data.ColituWarmSpare
import com.v2ray.ang.colitu.data.XrayMobileAdapter
import com.v2ray.ang.colitu.l10n.ColituLoc
import com.v2ray.ang.colitu.l10n.colituErrorMessage
import com.v2ray.ang.colitu.repository.ColituAuthRepository
import com.v2ray.ang.colitu.repository.ColituBillingRepository
import com.v2ray.ang.colitu.repository.ColituRotationRepository
import com.v2ray.ang.colitu.repository.ColituServerRepository
import com.v2ray.ang.colitu.repository.ColituSupportRepository
import com.v2ray.ang.colitu.repository.ColituVpnRepository
import com.v2ray.ang.core.CoreNativeManager
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.MessageUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant

enum class VpnStatus { Disconnected, Connecting, Connected, Disconnecting }

enum class ConnectPhase { Idle, Preparing, Probing, Starting, Verifying, Switching, SwitchingServer, Reconnecting }

enum class ToggleResult { Started, PlanRequired, NeedsPermission, Ignored }

data class ColituToastMessage(val text: String, val error: Boolean, val id: Long = System.nanoTime())

/**
 * Connection state shared by every tab, like the iOS ColituConnectionController:
 * servers and selection, the connect flow (probe every transport the panel
 * offers, start the fastest, verify real traffic, fall back to the next),
 * live speed, the session clock and the account/plan summary.
 */
class ColituController(application: Application) : AndroidViewModel(application) {
    private val store by lazy { MMKV.mmkvWithID("COLITU_SETTINGS", MMKV.MULTI_PROCESS_MODE) }

    var status by mutableStateOf(VpnStatus.Disconnected)
        private set
    var phase by mutableStateOf(ConnectPhase.Idle)
        private set
    var servers by mutableStateOf<List<ColituServer>>(emptyList())
        private set

    /** Multihop (double VPN) routes; their ids are route ids, never node ids. */
    var routes by mutableStateOf<List<ColituServer>>(emptyList())
        private set
    /** Rotating exit IP preference; null until known (an older panel has none). */
    var rotation by mutableStateOf<ColituRotationPreference?>(null)
        private set
    /** Rotation status of the connected node (current exit, next change); null when not rotating. */
    var rotationStatus by mutableStateOf<ColituRotationStatus?>(null)
        private set
    private var rotationPollJob: Job? = null

    /**
     * Last measured ping per server id, with the time and network key it was
     * measured on (only a fresh one on this network counts for the ranking).
     * Measured only while the VPN is off (through the tunnel it would be
     * node-to-node) and kept between runs.
     */
    var pings by mutableStateOf(ColituServerRanking.pingsFromJson(store.decodeString(KEY_PINGS)))
        private set
    private var pingJob: Job? = null

    fun pingOf(server: ColituServer): Int? = pings[server.id]?.ms

    /** Last non-empty client_country / client_network of /servers (empty through the VPN, so never overwritten by that). */
    private var clientCountry by mutableStateOf(store.decodeString(KEY_CLIENT_COUNTRY))
    private var clientNetwork by mutableStateOf(store.decodeString(KEY_CLIENT_NETWORK))
    /** wifi / cellular / ethernet / other / none: the link under the VPN. */
    private var networkLink by mutableStateOf(currentLink())

    /** The panel's token and hinted transports of the last network seen with the VPN off. */
    private var networkRecord: ColituNetworkRecord? = ColituNetworkRecord.fromJson(store.decodeString(KEY_NETWORK_RECORD))

    /**
     * Transports the panel hints as blocked on this network that this phone
     * did not see carry traffic here in the last 24 h: they go last, and the
     * warm spare takes one only when nothing else qualifies.
     */
    private fun hintedBlocked(now: Long = System.currentTimeMillis()): Set<String> {
        val blocked = networkRecord?.blockedFor(clientNetwork, now).orEmpty()
        if (blocked.isEmpty()) return emptySet()
        val network = networkKey
        return ColituNetworkHintsPolicy.demoted(blocked) { memory.workedOnNetwork(network, it, now) }
    }

    /** Memory of one network is never applied on another (see ColituConnectMemory). */
    val networkKey: String get() = ColituServerRanking.networkKey(networkLink, clientNetwork)

    /** What worked and what failed, per network, each with its expiry. */
    private val memory: ColituConnectMemory by lazy { loadMemory() }
    /** Bumped on every memory write so the ranking (Compose readers) follows. */
    private var memoryVersion by mutableIntStateOf(0)

    var loading by mutableStateOf(true)
        private set
    var offline by mutableStateOf(false)
        private set
    var autoSelection by mutableStateOf(store.decodeBool(KEY_AUTO_SELECTION, true))
        private set
    var selectedServerId by mutableStateOf(ColituServerRepository.getSelectedServerId())
        private set
    var connectedServerId by mutableStateOf<String?>(store.decodeString(KEY_CONNECTED_SERVER))
        private set
    var transport by mutableStateOf<String?>(store.decodeString(KEY_LAST_TRANSPORT))
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var toast by mutableStateOf<ColituToastMessage?>(null)
        private set
    var user by mutableStateOf<ColituUser?>(cachedUser())
        private set
    var subscription by mutableStateOf<ColituSubscription?>(null)
        private set
    var denial by mutableStateOf<String?>(null)
        private set
    var autoConnect by mutableStateOf(store.decodeBool(KEY_AUTO_CONNECT, false))
        private set
    var adBlock by mutableStateOf(ColituAdBlock.enabled)
        private set
    /** Advanced mode shows split tunneling, privacy mode, multihop and the rest; Simple hides them (ColituUiMode). */
    var advancedMode by mutableStateOf(ColituUiMode.advanced)
        private set
    /** Warm spare: a second path inside the core takes over when the first dies (ColituWarmSpare). */
    var warmSpare by mutableStateOf(ColituWarmSpare.enabled)
        private set
    /** Privacy mode: no Russian direct-routing exception (ColituRuBypass). */
    var privacyMode by mutableStateOf(ColituRuBypass.privacyMode)
        private set
    /** The one-time notice about the Russian direct rule is on screen. */
    var ruNoticeVisible by mutableStateOf(false)
        private set
    /** User split tunneling (apps, sites, addresses); independent of privacy mode. */
    var splitTunnel by mutableStateOf(ColituSplitTunnel.settings)
        private set
    /** 403 DEVICE_OVER_LIMIT: this device is paused (plan allows fewer devices). */
    var devicePause by mutableStateOf<ColituDevicePause?>(null)
        private set
    /** Plan end and device limit after it, from the bootstrap. */
    var outlook by mutableStateOf<ColituDeviceOutlook?>(null)
        private set
    private var trialBannerDismissedDay by mutableStateOf(store.decodeString(KEY_TRIAL_BANNER_DAY))
    /** Server country the running profile was built for (see ColituRuBypass.profileCountry). */
    private var profileCountry by mutableStateOf(ColituRuBypass.profileCountry)
    var uploadBps by mutableDoubleStateOf(0.0)
        private set
    var downloadBps by mutableDoubleStateOf(0.0)
        private set
    var connectedSeconds by mutableIntStateOf(0)
        private set
    private var connectedAt by mutableLongStateOf(0L)

    /** Unread live-support replies (nav badge). */
    var supportUnread by mutableIntStateOf(0)
        private set
    /** False when the panel has in-app support switched off. */
    var supportAvailable by mutableStateOf(true)
        private set
    /** Set by the shell while the support tab is on screen. */
    var supportOpen = false
    private var supportChecked = false

    private val _permissionRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val permissionRequests: SharedFlow<Unit> = _permissionRequests
    private val _sessionEnded = MutableSharedFlow<String?>(extraBufferCapacity = 1)
    val sessionEnded: SharedFlow<String?> = _sessionEnded

    private var connectJob: Job? = null
    private var verifyJob: Job? = null
    private var pollJob: Job? = null
    private var pendingStart: CompletableDeferred<String?>? = null
    private var pendingStop: CompletableDeferred<Unit>? = null
    /** Running tunnel checks by id; answers to a finished or older check are ignored. */
    private val pendingVerifies = java.util.concurrent.ConcurrentHashMap<Long, CompletableDeferred<Long>>()
    private val pendingIdle = java.util.concurrent.ConcurrentHashMap<Long, CompletableDeferred<Long>>()
    /** Id of the running tunnel check; answers to an older check are ignored. */
    private var verifyId = 0L
    private var receiverRegistered = false
    private var initialized = false
    private var userStopped = false
    /** The user cancelled while the service was still starting. */
    private var cancelledStart = false
    private var updateHintShown = false

    /** The tunnel process reports running (it lives in another process). */
    private var serviceUp = false

    /**
     * The service holds the VPN interface for a switch (core stopped, every
     * packet dropped); the next start replaces it, a stop ends it.
     */
    private var serviceHeld = false

    val connected get() = status == VpnStatus.Connected
    val busy get() = status == VpnStatus.Connecting || status == VpnStatus.Disconnecting
    val planStatus: String get() = planStatusOf(user, subscription)
    val planActive: Boolean get() = planStatus == "active" || planStatus == "trialing"
    val planRequired: Boolean get() = denial == "ENTITLEMENT_INACTIVE" || denial == "ENTITLEMENT_EXPIRED"
    val transportName: String get() = XrayMobileAdapter.transportName(transport)
    /**
     * Nodes in automatic order (ColituServerRanking). The first is both the
     * "Recommended" entry and what automatic mode connects to.
     */
    val rankedServers: List<ColituServer>
        get() {
            @Suppress("UNUSED_VARIABLE") val version = memoryVersion
            val now = System.currentTimeMillis()
            val network = networkKey
            return ColituServerRanking.rank(
                servers, pings, network, memory.lastGoodServer(network, now), memory.penalized(network, now), clientCountry, now,
            )
        }
    val recommendedServer: ColituServer? get() = rankedServers.firstOrNull()
    /** Nodes first, then the multihop routes. */
    val allServers: List<ColituServer> get() = servers + routes
    val selectedServer: ColituServer? get() = allServers.firstOrNull { it.id == selectedServerId }
    /** The next connect uses the automatic choice (always for a route while in Simple mode). */
    val connectsAutomatically: Boolean
        get() = ColituUiMode.connectsAutomatically(advancedMode, autoSelection, selectedServer?.isMultihop == true)
    val effectiveServer: ColituServer? get() = if (connectsAutomatically) recommendedServer else selectedServer ?: recommendedServer

    /**
     * Something only Advanced mode shows is in effect (split tunneling, a
     * chosen multihop route, a rotating exit): Simple mode's home says so.
     */
    val advancedSettingsOn: Boolean
        get() = splitTunnel.active || (!autoSelection && selectedServer?.isMultihop == true) || rotationActive
    val connectedServer: ColituServer? get() = allServers.firstOrNull { it.id == connectedServerId }
    /** The tunnel runs over a multihop route (double VPN). */
    val onMultihopRoute: Boolean get() = connected && connectedServer?.isMultihop == true
    val rotationActive: Boolean get() = rotation?.active == true

    /**
     * Russian sites currently leave outside the VPN: connected, privacy mode
     * off and the server outside Russia. Same rule as the config generator.
     */
    val ruDirectActive: Boolean get() = connected && ColituRuBypass.applies(runningCountry(), privacyMode)

    private fun runningCountry(): String? = when (val country = profileCountry) {
        // A profile imported before the country was recorded.
        null -> (connectedServer ?: effectiveServer)?.countryCode
        else -> country.ifEmpty { null }
    }
    /**
     * The trial ends within three days and the account then moves to the
     * free plan: the home banner (hidden for the rest of the day once closed).
     */
    val trialEndBanner: ColituDeviceOutlook?
        get() = outlook?.takeIf {
            it.trialEndingSoon(user?.entitlementStatus ?: planStatus, ColituClock.now()) &&
                trialBannerDismissedDay != java.time.LocalDate.now().toString()
        }

    fun dismissTrialBanner() {
        val today = java.time.LocalDate.now().toString()
        trialBannerDismissedDay = today
        store.encode(KEY_TRIAL_BANNER_DAY, today)
    }

    val expiresAt: Instant? get() = (user?.expiresAt ?: subscription?.expiresAt)?.let { runCatching { Instant.parse(it) }.getOrNull() }

    fun titleOf(server: ColituServer?): String {
        if (server == null) return ColituLoc["server.auto"]
        if (server.isMultihop) return server.displayName
        val code = server.countryCode
        return if (!code.isNullOrBlank() && ColituLoc.countryName(code) != code.uppercase()) ColituLoc.countryName(code)
        else server.displayName
    }

    fun subtitleOf(server: ColituServer): String = if (server.route != null) {
        ColituLoc.format(
            "multihop.sub",
            "entry" to (server.route.entry.country ?: server.route.entry.label),
            "exit" to (server.route.exit.country ?: server.route.exit.label),
        )
    } else listOfNotNull(
        server.city?.takeIf { it.isNotBlank() },
        server.countryCode?.uppercase(),
    ).joinToString(" · ")

    // ── Lifecycle ──────────────────────────────────────────────────────────

    /** Called whenever the signed-in shell appears (app start or a new sign-in). */
    fun enterShell() {
        if (initialized) {
            user = cachedUser()
            subscription = null
            denial = null
            error = null
            viewModelScope.launch { load(showLoading = true) }
            return
        }
        init()
    }

    private fun init() {
        if (initialized) return
        initialized = true
        if (ColituRecoveryTestMode.enabled) LogUtil.w(AppConfig.TAG, "Colitu: ${ColituRecoveryTestMode.STARTUP_LOG}")
        registerReceiver()
        registerNetworkCallback()
        viewModelScope.launch {
            ColituAuthEvents.authExpired.collect { endSession(ColituLoc["auth.expired"]) }
        }
        viewModelScope.launch {
            while (true) {
                delay(1000)
                if (connected && connectedAt > 0) {
                    connectedSeconds = ((System.currentTimeMillis() - connectedAt) / 1000).toInt().coerceAtLeast(0)
                }
            }
        }
        viewModelScope.launch {
            // The device record carries the transports this build can run. A
            // device signed in with an older build would otherwise never be
            // offered what an update added (Hysteria2, then XHTTP), so the
            // record is re-sent once per app version.
            val version = com.v2ray.ang.BuildConfig.VERSION_CODE
            if (store.decodeInt(KEY_CAPS_VERSION, 0) != version) {
                safeCall { ColituAuthRepository.refreshDeviceCapabilities() }.onSuccess { store.encode(KEY_CAPS_VERSION, version) }
            }
        }
        viewModelScope.launch {
            load(showLoading = true)
            if (autoConnect && status == VpnStatus.Disconnected && !planRequired && denial == null) {
                toggle()
            }
        }
    }

    /** Called from the activity's onStart/onStop: refresh while visible. */
    fun onForeground() {
        MessageUtil.sendMsg2Service(getApplication(), AppConfig.MSG_REGISTER_CLIENT, "")
        pollJob?.cancel()
        startRotationPoll()
        pollJob = viewModelScope.launch {
            if (initialized) load(showLoading = false)
            checkSupport()
            while (true) {
                delay(60_000)
                refreshPolicy()
                checkSupport()
                ColituNoticeCenter.refresh() // throttled to once per 15 minutes
            }
        }
    }

    /** Updates the unread badge; a new reply shows a toast unless support is open. */
    suspend fun checkSupport() {
        if (!ColituTokenManager.isLoggedIn()) return
        safeCall { ColituSupportRepository.unread() }
            .onSuccess { unread ->
                supportAvailable = true
                if (supportChecked && unread > supportUnread && !supportOpen) {
                    showToast(ColituLoc["support.newReply"], error = false)
                }
                supportUnread = unread
                supportChecked = true
            }
            .onFailure { if (it.message == "SUPPORT_UNAVAILABLE") supportAvailable = false }
    }

    fun updateSupportUnread(value: Int) {
        supportUnread = value.coerceAtLeast(0)
    }

    fun onBackground() {
        pollJob?.cancel()
        pollJob = null
        // The rotation status is only asked for while the app is visible.
        rotationPollJob?.cancel()
        rotationPollJob = null
    }

    suspend fun load(showLoading: Boolean = false) {
        if (showLoading && servers.isEmpty()) loading = true
        var listLoaded = false
        coroutineScope {
            val policy = async { refreshPolicy() }
            val list = async { safeCall { ColituServerRepository.fetchServers() } }
            val me = async { runCatching { ColituAuthRepository.fetchMe() } }
            val sub = async { safeCall { ColituBillingRepository.fetchSubscription() } }
            val rotationPref = async { safeCall { ColituRotationRepository.fetch() } }
            list.await().fold(
                onSuccess = { response ->
                    listLoaded = true
                    servers = response.servers
                    routes = response.multihop
                    offline = false
                    // Asked through the VPN both come back empty: keep the last real ones.
                    response.clientCountry?.let {
                        clientCountry = it
                        store.encode(KEY_CLIENT_COUNTRY, it)
                    }
                    // Token and hints belong to the network they were asked on (VPN off only).
                    response.clientNetwork?.let { network ->
                        val record = ColituNetworkRecord(network, response.networkToken, response.networkHints?.blocked.orEmpty(), System.currentTimeMillis(), response.networkHints?.preferred.orEmpty())
                        networkRecord = record
                        store.encode(KEY_NETWORK_RECORD, record.toJson())
                    }
                    response.clientNetwork?.let {
                        clientNetwork = it
                        store.encode(KEY_CLIENT_NETWORK, it)
                    }
                    measurePings()
                    if (!autoSelection && selectedServer == null) {
                        autoSelection = true
                        store.encode(KEY_AUTO_SELECTION, true)
                    }
                },
                onFailure = { e ->
                    if (e.message == "auth_expired") endSession(ColituLoc["auth.expired"])
                    offline = e.message in setOf("network_error", "timeout", "server_unavailable")
                },
            )
            // An older panel has no rotation: the preference stays unknown (off).
            rotationPref.await().onSuccess { rotation = it }
            me.await().onSuccess { user = it }
            sub.await().onSuccess { subscription = it }.onFailure { if (!planActive) subscription = null }
            policy.await()
        }
        loading = false
        // Announcements: after the plan data, off this call's critical path.
        viewModelScope.launch { ColituNoticeCenter.refresh(force = showLoading) }
        // Recovery set (Adaptive Connect 3.0): after the server list answered; never waited for.
        if (listLoaded && ColituFeatures.recoverySet()) viewModelScope.launch { ColituServerRepository.refreshRecoverySetIfDue(clientCountry) }
    }

    /** Re-measures the pings unless a tunnel is up or starting. */
    fun measurePings() {
        if (status != VpnStatus.Disconnected || pingJob?.isActive == true) return
        // A route's ping is measured to its entry node only ("Estimated · +1 hop").
        val list = servers + routes
        pingJob = viewModelScope.launch {
            val network = networkKey
            val measured = com.v2ray.ang.colitu.data.ColituLatency.measureAll(list)
            // Numbers taken while the network changed belong to neither network.
            if (measured.isEmpty() || status != VpnStatus.Disconnected || networkKey != network) return@launch
            val now = System.currentTimeMillis()
            pings = pings + measured.mapValues { (_, ms) -> ColituPing(ms, now, network) }
            store.encode(KEY_PINGS, ColituServerRanking.pingsToJson(pings))
        }
    }

    // ── Network and per-network memory ─────────────────────────────────────

    private fun loadMemory(): ColituConnectMemory {
        val raw = store.decodeString(KEY_CONNECT_MEMORY)
        if (raw == null) {
            // v1 kept one stalled transport for every network and the last
            // good transport per server, both without expiry: not carried over.
            store.removeValueForKey(KEY_STALLED_TRANSPORT)
            runCatching { store.allKeys()?.filter { it.startsWith(KEY_GOOD_PREFIX) }?.forEach { store.removeValueForKey(it) } }
        }
        return ColituConnectMemory.fromJson(raw, System.currentTimeMillis())
    }

    private fun saveMemory() {
        store.encode(KEY_CONNECT_MEMORY, memory.toJson())
        memoryVersion++
    }

    private fun connectivity(): ConnectivityManager? =
        getApplication<Application>().getSystemService(ConnectivityManager::class.java)

    /** Capabilities of the non-VPN networks with internet. */
    private fun underlyingCaps(): List<Pair<Network, NetworkCapabilities>> = runCatching {
        val cm = connectivity() ?: return@runCatching emptyList()
        @Suppress("DEPRECATION")
        cm.allNetworks.mapNotNull { network -> cm.getNetworkCapabilities(network)?.let { network to it } }
            .filter { (_, caps) -> !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) }
    }.getOrDefault(emptyList())

    /** The link part of the network key; Wi-Fi wins over cellular like the system default. */
    private fun currentLink(): String {
        val caps = underlyingCaps().map { it.second }
        return when {
            caps.isEmpty() -> "none"
            caps.any { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) } -> "wifi"
            caps.any { it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) } -> "ethernet"
            caps.any { it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) } -> "cellular"
            else -> "other"
        }
    }

    /**
     * The phone itself reaches the internet outside the tunnel: the system
     * validated a non-VPN network, or (validation is blocked in some
     * countries) a direct connection over one of them works. When it does
     * not, a silent tunnel says nothing about the transport or server.
     */
    private suspend fun deviceOnline(): Boolean = withContext(Dispatchers.IO) {
        val networks = underlyingCaps()
        if (networks.isEmpty()) return@withContext false
        if (networks.any { (_, caps) -> caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) }) return@withContext true
        networks.any { (network, _) -> DIRECT_CHECK_HOSTS.any { (host, port) -> reachableOutside(network, host, port) } }
    }

    private fun reachableOutside(network: Network, host: String, port: Int): Boolean = runCatching {
        network.socketFactory.createSocket().use { socket ->
            socket.connect(java.net.InetSocketAddress(network.getByName(host), port), DIRECT_CHECK_TIMEOUT_MS)
            true
        }
    }.getOrDefault(false)

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = onNetworkChanged()
        override fun onLost(network: Network) = onNetworkChanged()
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) = onNetworkChanged()
    }
    private var networkCallbackRegistered = false

    private fun registerNetworkCallback() {
        if (networkCallbackRegistered) return
        runCatching {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            connectivity()?.registerNetworkCallback(request, networkCallback)
            networkCallbackRegistered = true
        }
    }

    /**
     * Runs on a binder thread: moves to the main thread, updates the network
     * key and, after a drop that could not be repaired, reconnects once the
     * phone is back online.
     */
    private fun onNetworkChanged() {
        viewModelScope.launch {
            val link = currentLink()
            val changed = link != networkLink
            networkLink = link
            forgetClientNetworkIfReplaced()
            if (link == "none") return@launch
            if (changed && status == VpnStatus.Disconnected) measurePings()
            if (!resumeAllowed()) return@launch
            if (!deviceOnline() || !resumeAllowed()) return@launch
            resumeOnNetwork = false
            LogUtil.w(AppConfig.TAG, "Colitu: network is back, reconnecting")
            reconnectAttempt = 0
            scheduleReconnect()
        }
    }

    /** Non-VPN networks seen at the last network callback (Network ids). */
    private var lastUnderlyingIds: Set<String>? = null

    /**
     * The access network (country + ASN) is learned from the panel only while
     * the VPN is off. When every underlying network was replaced (another
     * Wi-Fi, Wi-Fi to mobile), the old ASN would hand this network the last
     * one's memory (stalls, last good transport): forget it, so the network
     * counts as unknown until the next answer names it.
     */
    private fun forgetClientNetworkIfReplaced() {
        val ids = underlyingCaps().map { it.first.toString() }.toSet()
        val previous = lastUnderlyingIds
        if (ids.isEmpty()) return
        lastUnderlyingIds = ids
        if (ColituServerRanking.networkReplaced(previous, ids) && clientNetwork != null) {
            LogUtil.w(AppConfig.TAG, "Colitu: access network replaced, client network ${clientNetwork} unknown until the next server list")
            clientNetwork = null
            store.removeValueForKey(KEY_CLIENT_NETWORK)
        }
    }

    private fun resumeAllowed() =
        resumeOnNetwork && status == VpnStatus.Disconnected && !userStopped && denial == null && !planRequired && devicePause == null

    private suspend fun refreshPolicy() {
        // The poll keeps running on the sign-in screen; without a session the
        // 401 would end up in clear() and could wipe a sign-in in progress.
        if (!ColituTokenManager.isLoggedIn()) return
        val answer = runCatching { ColituVpnRepository.fetchBootstrap() }.getOrNull() ?: return
        if (answer is ColituVpnRepository.Bootstrap.Paused) {
            pauseDevice(answer.pause)
            return
        }
        val bootstrap = (answer as? ColituVpnRepository.Bootstrap.Ok)?.json ?: return
        devicePause = null
        ColituDeviceOutlook.fromJson(bootstrap)?.let { outlook = it }
        val policy = runCatching { ClientBootstrapPolicy.fromJson(bootstrap) }.getOrNull() ?: return
        ColituTokenManager.saveEntitlementStatus(policy.entitlementStatus)
        val entitlement = bootstrap.get("entitlement")?.takeIf { it.isJsonObject }?.asJsonObject
        val expires = entitlement?.get("expires_at")?.takeIf { it.isJsonPrimitive }?.asString
        user = user?.copy(entitlementStatus = policy.entitlementStatus, expiresAt = expires ?: user?.expiresAt)
        if (policy.recommendedVersion != null && !updateHintShown) {
            updateHintShown = true
            showToast("${ColituLoc["update.title"]}: ${policy.recommendedVersion} · colitu.com", error = false)
        }
        val previous = denial
        denial = policy.denial
        if (denial == null) {
            if (previous != null) error = null
            return
        }
        // The tile, widget and shortcuts may no longer start the tunnel.
        ColituQuickStart.revoke()
        if (connected || status == VpnStatus.Connecting) {
            connectJob?.cancel()
            stopService()
            status = VpnStatus.Disconnected
        }
        if (previous != denial) error = colituErrorMessage(denial)
    }

    /**
     * This device is over the plan's device limit: nothing may start the
     * tunnel (the app, the tile, Always-on, auto-connect) until the user makes
     * it the active device or upgrades; a running tunnel stops.
     */
    private suspend fun pauseDevice(pause: ColituDevicePause) {
        devicePause = pause
        denial = ColituDevicePause.CODE
        ColituQuickStart.revoke()
        if (connected || status == VpnStatus.Connecting) {
            connectJob?.cancel()
            verifyJob?.cancel()
            stopService(force = true)
            status = VpnStatus.Disconnected
            phase = ConnectPhase.Idle
        }
    }

    /** "Use this device instead": the panel pauses another device, then everything is fetched again. */
    suspend fun activateThisDevice(): Result<Unit> {
        val result = safeCall { com.v2ray.ang.colitu.repository.ColituAccountRepository.activateThisDevice() }
        if (result.isSuccess) {
            devicePause = null
            denial = null
            error = null
            load(showLoading = false)
        }
        return result
    }

    // ── Connect / disconnect ───────────────────────────────────────────────

    fun toggle(): ToggleResult = when (status) {
        VpnStatus.Connected -> {
            disconnect()
            ToggleResult.Started
        }
        VpnStatus.Connecting -> {
            // Tapping while connecting cancels, like on iOS.
            disconnect()
            ToggleResult.Started
        }
        VpnStatus.Disconnecting -> ToggleResult.Ignored
        VpnStatus.Disconnected -> connect()
    }

    private fun connect(): ToggleResult {
        if (planRequired) {
            showToast(ColituLoc["err.noPlan"], error = true)
            return ToggleResult.PlanRequired
        }
        denial?.let {
            error = colituErrorMessage(it)
            showToast(error!!, error = true)
            return ToggleResult.Ignored
        }
        if (VpnService.prepare(getApplication()) != null) {
            _permissionRequests.tryEmit(Unit)
            return ToggleResult.NeedsPermission
        }
        startConnectFlow()
        return ToggleResult.Started
    }

    fun onPermissionResult(granted: Boolean) {
        if (granted && VpnService.prepare(getApplication()) == null) {
            startConnectFlow()
        } else {
            error = ColituLoc["err.permission"]
            showToast(error!!, error = true)
        }
    }

    /**
     * Transports that came up but carried no traffic during the current
     * connect (on the server being tried). They go last in the next attempt;
     * a new connect by the user starts over.
     */
    private val stalledThisConnect = mutableSetOf<String>()

    /** Transports the server offered on the last fresh profile. */
    private var offeredTransports: Set<String> = emptySet()

    /** Unexpected drop: automatic reconnects made so far ([RECONNECT_DELAYS_MS], then the error). */
    private var reconnectAttempt = 0
    /** The running connect is an automatic reconnect: a failure schedules the next one. */
    private var reconnecting = false
    /** A drop could not be repaired: try again once a network is back (see [onNetworkChanged]). */
    private var resumeOnNetwork = false

    private fun startConnectFlow(fresh: Boolean = false, continuing: Boolean = false) {
        offerFastest = false
        attachedSpare = null
        if (!continuing) {
            deadSpares.clear()
            stalledThisConnect.clear()
            reconnecting = false
            reconnectAttempt = 0
            resumeOnNetwork = false
        }
        connectJob?.cancel()
        verifyJob?.cancel()
        userStopped = false
        cancelledStart = false
        connectJob = viewModelScope.launch {
            try {
                runConnect(fresh)
            } finally {
                flushObservations()
            }
        }
    }

    /** Transports tried since the last report: node, result and the network token of that moment. */
    private val observations = mutableListOf<Triple<String, ColituObservation, String?>>()

    /** Remembers one result for the panel's per-transport statistics (nodes only, never a route). */
    private fun observe(server: ColituServer, protocol: String?, reachable: Boolean, latencyMs: Long?) {
        if (server.isMultihop || protocol.isNullOrBlank()) return
        val token = networkRecord?.tokenFor(clientNetwork, System.currentTimeMillis())
        observations += Triple(server.id, ColituObservation(protocol, reachable, latencyMs), token)
    }

    /**
     * Sends what was observed, one request per node, a few seconds after the
     * connect settled, on its own; a failure is dropped. Never blocks or
     * delays a connect.
     */
    private fun flushObservations() {
        if (observations.isEmpty()) return
        val batch = observations.toList()
        observations.clear()
        viewModelScope.launch(Dispatchers.IO) {
            delay(OBSERVATION_DELAY_MS)
            batch.groupBy { it.first to it.third }.forEach { (key, items) ->
                val body = ColituNetworkHintsPolicy.observationBody(key.first, items.map { it.second }, key.second) ?: return@forEach
                runCatching { com.v2ray.ang.colitu.api.ColituApiClient.post("/client/protocol-observations", body) }
            }
        }
    }

    /**
     * The tunnel went down by itself (not the user, another VPN or the
     * plan): reconnect after [RECONNECT_DELAYS_MS], then show the error.
     */
    private fun onUnexpectedDrop() {
        if (userStopped || denial != null || planRequired || devicePause != null) return
        LogUtil.w(AppConfig.TAG, "Colitu: the tunnel dropped by itself, reconnecting")
        reconnectAttempt = 0
        resumeOnNetwork = false
        if (!scheduleReconnect()) showToast(ColituLoc["info.disconnected"], error = false)
    }

    /** Starts the next automatic reconnect after its delay; false once they are used up. */
    private fun scheduleReconnect(): Boolean {
        val wait = RECONNECT_DELAYS_MS.getOrNull(reconnectAttempt) ?: return false
        reconnectAttempt++
        verifyJob?.cancel()
        stalledThisConnect.clear()
        reconnecting = true
        userStopped = false
        cancelledStart = false
        status = VpnStatus.Connecting
        phase = ConnectPhase.Reconnecting
        error = null
        connectJob?.cancel()
        connectJob = viewModelScope.launch {
            // A transport that failed its check is still held.
            stopService()
            delay(wait)
            if (!deviceOnline()) {
                flushObservations()
                // Nothing to learn while the phone is offline; the network callback resumes.
                reconnecting = false
                resumeOnNetwork = true
                status = VpnStatus.Disconnected
                phase = ConnectPhase.Idle
                error = failureMessage("network_error")
                return@launch
            }
            try {
                runConnect()
            } finally {
                flushObservations()
            }
        }
        return true
    }

    private sealed interface ServerOutcome {
        data object Connected : ServerOutcome
        /** Every transport of the server failed; [cameUp]: one started but carried nothing. */
        data class Failed(val cameUp: Boolean) : ServerOutcome
        /** Not the server's fault (panel, account, the phone offline): stop with [code]. */
        data class Fatal(val code: String?) : ServerOutcome
    }

    private sealed interface StartOutcome {
        data object Connected : StartOutcome
        data class Failed(val cameUp: Boolean) : StartOutcome
        /** A transport came up but the phone itself is offline: nothing is learned. */
        data object Offline : StartOutcome
    }

    /**
     * Connects to the effective server; in automatic mode, when every
     * transport of it failed, it is penalized on this network and the next
     * ranked server follows (at most [MAX_SERVERS_PER_CONNECT], within
     * [CONNECT_BUDGET_MS]). A manual choice or a route is never switched.
     */
    private suspend fun runConnect(fresh: Boolean = false) {
        val context = getApplication<Application>()
        val begin = android.os.SystemClock.elapsedRealtime()
        status = VpnStatus.Connecting
        phase = ConnectPhase.Preparing
        error = null
        ColituServerRepository.clearConfigUnreachable()
        var server = effectiveServer
        if (server == null) {
            // No server list (the API was blocked from the start): the recovery set may still carry the connect.
            if (ColituFeatures.recoverySet() && servers.isEmpty() && connectsAutomatically) {
                safeCall { ColituServerRepository.fetchConfigCandidates() }
                if (apiUnreachable(null) && connectViaRecoverySet(context, emptyList(), begin)) return
            }
            fail(if (servers.isEmpty()) "NO_SERVERS" else "NO_SERVER_SELECTED")
            return
        }
        val automatic = autoSelection && !server.isMultihop
        val connectDeadline = begin + CONNECT_BUDGET_MS
        val failed = mutableListOf<String>()
        var cameUp = false
        var recoveryTried = false
        // Every way an automatic connect ends in failure goes through here: the recovery set gets one run first.
        suspend fun giveUp(code: String?) {
            val unreachable = automatic && apiUnreachable(code)
            if (ColituFeatures.recoveryAtGiveUp(automatic, unreachable, recoveryTried)) {
                recoveryTried = true
                if (connectViaRecoverySet(context, failed, begin)) return
            } else if (automatic && ColituFeatures.recoverySet()) {
                LogUtil.w(AppConfig.TAG, "Colitu: recovery set not tried (API unreachable: $unreachable, already tried: $recoveryTried)")
            }
            fail(code)
        }
        while (server != null) {
            val serverDeadline = if (automatic) minOf(android.os.SystemClock.elapsedRealtime() + SERVER_BUDGET_MS, connectDeadline) else connectDeadline
            when (val outcome = connectServer(context, server, fresh, begin, serverDeadline)) {
                ServerOutcome.Connected -> return
                is ServerOutcome.Fatal -> {
                    giveUp(outcome.code)
                    return
                }
                is ServerOutcome.Failed -> {
                    cameUp = cameUp || outcome.cameUp
                    if (!deviceOnline()) {
                        giveUp("network_error")
                        return
                    }
                    memory.penalize(networkKey, server.id, System.currentTimeMillis())
                    saveMemory()
                    failed += server.id
                    val left = connectDeadline - android.os.SystemClock.elapsedRealtime()
                    server = ColituServerRanking.fallbackServer(automatic, rankedServers, failed, MAX_SERVERS_PER_CONNECT, left >= MIN_TRANSPORT_MS)
                    if (server != null) {
                        LogUtil.w(AppConfig.TAG, "Colitu: every transport failed on the server, trying another one (${failed.size})")
                        phase = ConnectPhase.SwitchingServer
                        showToast(ColituLoc["home.phase.switchingServer"], error = false)
                        // Transports are judged per server; what stalls on every server is in the memory.
                        stalledThisConnect.clear()
                    }
                }
            }
        }
        // A manual choice is never switched: the error offers the fastest server instead.
        if (!automatic) offerFastest = true
        giveUp(if (cameUp) "VERIFY_FAILED" else "UNREACHABLE")
    }

    /**
     * Adaptive Connect 3.0: an API call of this connect got no HTTP answer from
     * any base (or [code] says so for the call that failed) and the phone has a
     * network. Not [deviceOnline]: where the system's validation is blocked it
     * says "offline" although the node addresses of the set are reachable.
     */
    private fun apiUnreachable(code: String?): Boolean =
        (ColituServerRepository.configApiUnreachable || code in setOf("network_error", "timeout")) && underlyingCaps().isNotEmpty()

    /**
     * Last resort of an automatic connect: no API base answered and the cached
     * profile is none, past its grace or failed. Tries the stored recovery set's
     * servers in order (skipping [failed]); each envelope is used like a cached
     * one but valid until the set's `recovery_until`. The next server of the
     * set is the warm spare. True once connected.
     */
    private suspend fun connectViaRecoverySet(context: Application, failed: List<String>, begin: Long): Boolean {
        if (!ColituFeatures.recoverySet()) return false
        val plan = ColituServerRepository.recoveryPlan(failed) ?: return false
        LogUtil.w(AppConfig.TAG, "Colitu: API unreachable and no usable cache: recovery set, ${plan.entries.size} servers (until ${plan.until})")
        val deadline = android.os.SystemClock.elapsedRealtime() + CONNECT_BUDGET_MS
        val entries = plan.entries
        fun serverOf(id: String, configs: List<ColituVpnConfig>) =
            servers.firstOrNull { it.id == id } ?: ColituServer(id, id, configs.firstOrNull()?.serverCountry, null, false, true)
        for ((index, entry) in entries.withIndex()) {
            if (deadline - android.os.SystemClock.elapsedRealtime() < MIN_TRANSPORT_MS) break
            val (id, configs) = entry
            val server = serverOf(id, configs)
            val offered = restrictFor(server, configs)
            if (offered.isEmpty()) continue
            offeredTransports = offered.mapNotNull { it.protocolType }.toSet()
            stalledThisConnect.clear()
            if (serviceUp) {
                stopService(force = true, hold = true)
                delay(TEARDOWN_SETTLE_MS)
            }
            val next = entries.getOrNull(index + 1)?.let { (nextId, nextConfigs) ->
                serverOf(nextId, nextConfigs) to restrictFor(server, nextConfigs)
            }?.takeIf { it.second.isNotEmpty() }
            LogUtil.w(AppConfig.TAG, "Colitu: recovery: trying ${id.take(8)}/${configs.firstOrNull()?.serverCountry ?: "?"}")
            val spare = planSpare(server, recovery = true, recoveryNext = next)
            val serverDeadline = minOf(android.os.SystemClock.elapsedRealtime() + SERVER_BUDGET_MS, deadline)
            when (tryStart(context, server, orderForStart(server, offered, probe = true), begin, fromCache = true, serverDeadline, spare, offered)) {
                StartOutcome.Connected -> {
                    LogUtil.w(AppConfig.TAG, "Colitu: recovery: connected via ${id.take(8)} ${connectedConfig?.protocolType ?: "?"}")
                    // Through the tunnel the API answers again: server list, config and recovery set follow.
                    viewModelScope.launch { load(showLoading = false) }
                    return true
                }
                StartOutcome.Offline -> {
                    LogUtil.w(AppConfig.TAG, "Colitu: recovery: ${id.take(8)} failed (phone offline)")
                    return false
                }
                is StartOutcome.Failed -> {
                    LogUtil.w(AppConfig.TAG, "Colitu: recovery: ${id.take(8)} failed")
                    memory.penalize(networkKey, id, System.currentTimeMillis())
                    saveMemory()
                }
            }
        }
        return false
    }

    /** Every transport of a manually chosen server failed: Home offers "Try the fastest server". */
    var offerFastest by mutableStateOf(false)
        private set

    /** "Try the fastest server": automatic mode, then connect (one tap, never on its own). */
    fun tryFastest() {
        offerFastest = false
        error = null
        selectAuto()
        if (status == VpnStatus.Disconnected) toggle()
    }

    /**
     * Connect to [server] as fast as the last good state allows: the cached
     * profile and the transport that worked last time on this network start
     * right away; probing and a fresh profile are only needed when that
     * fails. [fresh] forces both.
     */
    private suspend fun connectServer(context: Application, server: ColituServer, fresh: Boolean, begin: Long, deadline: Long): ServerOutcome {
        // A route is not a node: it is never stored as the preferred node, so
        // there is nothing to sync and its config comes from its own endpoint.
        val isRoute = server.isMultihop
        val synced = isRoute || store.decodeString(KEY_SYNCED_SERVER) == server.id
        if (!isRoute && !synced) {
            // Without the new preference the panel would hand out the previous
            // node's profile while the app shows this one, so a failed switch
            // stops here instead of connecting somewhere else.
            val switched = safeCall { ColituServerRepository.selectServer(server.id) }
            switched.exceptionOrNull()?.let {
                if (it.message == "auth_expired") endSession(ColituLoc["auth.expired"])
                return ServerOutcome.Fatal(it.message)
            }
            store.encode(KEY_SYNCED_SERVER, server.id)
        }
        if (serviceUp) {
            // Switch: the interface stays up (held) so no app falls back to the
            // plain network while the next transport starts.
            stopService(force = true, hold = true)
            // Probes started while the old tunnel was still closing all failed
            // at once on a real phone (EOF within 300 ms); let it settle first.
            delay(TEARDOWN_SETTLE_MS)
        }

        // Fetched beside the primary's profile; never waited for longer than
        // SPARE_WAIT_MS. A late answer is not cancelled: it is cached for the
        // next start (ColituServerRepository.cachedNodeCandidates).
        val spare = planSpare(server)
        return connectServerWith(context, server, fresh, synced, begin, deadline, spare)
    }

    private suspend fun connectServerWith(
        context: Application,
        server: ColituServer,
        fresh: Boolean,
        synced: Boolean,
        begin: Long,
        deadline: Long,
        spare: SparePlan?,
    ): ServerOutcome {
        val isRoute = server.isMultihop
        var cameUp = false
        val cached = if (fresh || !synced) null else {
            val stored = if (isRoute) ColituServerRepository.cachedRouteCandidates(server.id) else ColituServerRepository.cachedConfigCandidates(server.id)
            stored?.let { restrictFor(server, it) }?.takeIf { it.isNotEmpty() }
        }
        if (cached != null) {
            // Only the first cached transport: a stale profile (credentials
            // rotated, node changed) must not use up the server's budget; the
            // fresh profile below brings the rest.
            when (val outcome = tryStart(context, server, orderForStart(server, cached, probe = false).take(1), begin, fromCache = true, deadline, spare, cached)) {
                StartOutcome.Connected -> return ServerOutcome.Connected
                StartOutcome.Offline -> return ServerOutcome.Fatal("network_error")
                is StartOutcome.Failed -> cameUp = outcome.cameUp
            }
        }

        var candidates = safeCall { fetchCandidates(server) }
        if (candidates.isFailure && candidates.exceptionOrNull()?.message in setOf("network_error", "timeout", "CONFIG_NOT_READY")) {
            delay(1500)
            candidates = safeCall { fetchCandidates(server) }
        }
        val fetched = candidates.getOrElse {
            if (it.message == "auth_expired") endSession(ColituLoc["auth.expired"])
            // The route was removed or renamed: learn the current list.
            if (it.message == "MULTIHOP_ROUTE_NOT_FOUND") viewModelScope.launch { load(showLoading = false) }
            return ServerOutcome.Fatal(it.message)
        }
        // A multihop route and a rotating exit run on VLESS only.
        val offered = restrictFor(server, fetched)
        if (offered.isEmpty()) return ServerOutcome.Fatal(if (isRoute) "MULTIHOP_NEEDS_VLESS" else "ROTATION_NEEDS_VLESS")
        offeredTransports = offered.mapNotNull { it.protocolType }.toSet()

        // What already stalled in this connect goes last (see orderForStart).
        return when (val outcome = tryStart(context, server, orderForStart(server, offered, probe = true), begin, fromCache = false, deadline, spare, offered)) {
            StartOutcome.Connected -> ServerOutcome.Connected
            StartOutcome.Offline -> ServerOutcome.Fatal("network_error")
            is StartOutcome.Failed -> ServerOutcome.Failed(cameUp || outcome.cameUp)
        }
    }

    /**
     * Where the warm spare comes from: [server] itself ([fetch] null, its own
     * transports) or, in automatic mode, the next ranked server, whose
     * transports are [cached] right away and [fetch]ed beside the primary.
     */
    private class SparePlan(
        val server: ColituServer,
        val cached: List<ColituVpnConfig>?,
        val fetch: Deferred<List<ColituVpnConfig>?>?,
        val startedAt: Long,
    ) {
        val sameServer: Boolean get() = fetch == null && cached == null
    }

    /**
     * Automatic mode: the next server of the ranking that is not penalized
     * here; a manual choice keeps its location (spare on the same server).
     * No spare over a multihop route, while the exit IP rotates (VLESS only,
     * one node) or with the setting off.
     */
    private fun planSpare(server: ColituServer, recovery: Boolean = false, recoveryNext: Pair<ColituServer, List<ColituVpnConfig>>? = null): SparePlan? {
        val off = when {
            !ColituUiMode.warmSpareActive(advancedMode, warmSpare) -> "setting off"
            server.isMultihop -> "multihop route"
            rotationActive -> "rotating exit"
            else -> null
        }
        if (off != null) {
            spareNone = off
            return null
        }
        spareNone = null
        val started = android.os.SystemClock.elapsedRealtime()
        // Recovery set: the API cannot be asked, so the set's next server is the spare.
        if (recovery) return recoveryNext?.let { SparePlan(it.first, it.second, null, started) } ?: SparePlan(server, null, null, started)
        if (!connectsAutomatically) return SparePlan(server, null, null, started)
        val other = ColituWarmSpare.spareServer(rankedServers, server.id, memory.penalized(networkKey, System.currentTimeMillis()))
            ?: return SparePlan(server, null, null, started)
        val fetch = viewModelScope.async {
            val result = safeCall { ColituServerRepository.fetchNodeCandidates(other.id) }
            val ms = android.os.SystemClock.elapsedRealtime() - started
            LogUtil.w(
                AppConfig.TAG,
                "Colitu: spare profile ${other.id.take(8)} " +
                    (result.exceptionOrNull()?.let { "failed: ${it.message} ($ms ms)" } ?: "fetched in $ms ms (${result.getOrNull()?.size ?: 0} transports)") +
                    if (ms > SPARE_WAIT_MS) ", after the start budget: cached for the next start" else "",
            )
            result.getOrNull()
        }
        return SparePlan(other, ColituServerRepository.cachedNodeCandidates(other.id), fetch, started)
    }

    /** Why the last plan has no spare at all (logged at the core start). */
    private var spareNone: String? = null

    /**
     * The spare for [primary]: the other transport family where possible
     * (ColituWarmSpare.spareTransport), never one that stalled on this
     * network; null when there is none or its profile is not there within
     * [SPARE_WAIT_MS] of the start of this server's connect.
     */
    private suspend fun spareFor(plan: SparePlan?, primary: ColituVpnConfig, sameServer: List<ColituVpnConfig>): ColituVpnConfig? {
        if (plan == null) {
            LogUtil.w(AppConfig.TAG, "Colitu: warm spare none: ${spareNone ?: "no plan"}")
            return null
        }
        val other = if (plan.sameServer) null else when {
            plan.fetch?.isCompleted == true -> plan.fetch.await() ?: plan.cached
            plan.cached != null -> plan.cached
            else -> {
                val left = SPARE_WAIT_MS - (android.os.SystemClock.elapsedRealtime() - plan.startedAt)
                if (left > 0) withTimeoutOrNull(left) { plan.fetch?.await() } else null
            }
        }?.takeIf { it.isNotEmpty() }
        // The next server's profile is not there (yet): the same server's other family instead.
        val spareServer = if (other != null) plan.server else connectedServerFor(primary)
        val configs = other ?: sameServer
        val onSame = other == null
        val now = System.currentTimeMillis()
        val offeredSpare = configs.mapNotNull { it.protocolType }
        val deadHere = deadSpares.filter { it.first == spareServer.id }.mapTo(mutableSetOf()) { it.second }
        val stalled = ColituTransportOrder.effectiveStalled(
            offeredSpare,
            stalledTransports(spareServer.id) + memory.stalled(networkKey, spareServer.id, now),
            (if (onSame) stalledThisConnect else emptySet()) + deadHere,
        )
        val network = networkKey
        val worked = offeredSpare.filterTo(mutableSetOf()) { memory.workedOnNetwork(network, it, now) }
        val choice = ColituWarmSpare.spareChoice(
            primary.protocolType, offeredSpare, stalled, sameServer = onSame, hinted = hintedBlocked(now), worked = worked,
        )
        val protocol = choice?.first
        val chosen = protocol?.let { p -> configs.firstOrNull { it.protocolType == p } }
        if (chosen == null) {
            val why = if (!plan.sameServer && other == null) "next server's profile not ready, " else ""
            LogUtil.w(AppConfig.TAG, "Colitu: warm spare none: ${why}no other usable transport (offered ${configs.mapNotNull { it.protocolType }}, stalled $stalled)")
            return null
        }
        LogUtil.w(
            AppConfig.TAG,
            "Colitu: warm spare attached: ${if (onSame) "same server" else "next server ${spareServer.id.take(8)}"}/${chosen.protocolType} " +
                "(primary ${primary.protocolType}; spare reason: ${choice?.second}; proven here $worked; stalled $stalled; profile ${if (other == null) "same server" else if (plan.fetch?.isCompleted == true) "fetched" else "cached"})",
        )
        return chosen
    }

    /** The node a primary profile belongs to (for the same-server spare's memory). */
    private fun connectedServerFor(config: ColituVpnConfig): ColituServer =
        servers.firstOrNull { it.id == config.serverId } ?: ColituServer(config.serverId, config.serverId, null, null, false, true)

    private suspend fun fetchCandidates(server: ColituServer): Result<List<ColituVpnConfig>> =
        if (server.isMultihop) ColituServerRepository.fetchRouteCandidates(server.id) else ColituServerRepository.fetchConfigCandidates()

    /** VLESS only while connecting over a multihop route or while the exit IP rotates; the other transports cannot be carried through the mesh. */
    private fun restrictFor(server: ColituServer, configs: List<ColituVpnConfig>): List<ColituVpnConfig> =
        if (server.isMultihop || rotationActive) ColituMultihop.restrictToVless(configs) else configs

    /**
     * The transport that last worked on this server and network first (no
     * probe), unless it is not Hysteria2 and Hysteria2 is on offer; otherwise
     * the probe decides. The rest follow in preference order; stalled ones last.
     */
    private suspend fun orderForStart(server: ColituServer, configs: List<ColituVpnConfig>, probe: Boolean): List<ColituVpnConfig> {
        val now = System.currentTimeMillis()
        val hinted = hintedBlocked(now)
        // Hinted transports rank like stalled ones: last, and never the remembered shortcut.
        val remembered = stalledTransports(server.id) + memory.stalled(networkKey, server.id, now) + hinted
        val offered = configs.mapNotNull { it.protocolType }
        val stalled = ColituTransportOrder.effectiveStalled(offered, remembered, stalledThisConnect)
        if (stalled != remembered + stalledThisConnect) {
            val network = networkKey
            val worked = offered.filterTo(mutableSetOf()) { memory.workedOnNetwork(network, it, now) }
            val order = ColituTransportOrder.lockedOrder(configs, remembered, stalledThisConnect, worked)
            LogUtil.w(
                AppConfig.TAG,
                "Colitu: stall marks cover (almost) every transport ($remembered): a network problem, ignored for this round; order ${order.mapNotNull { it.protocolType }}",
            )
            return order
        }
        val lastGood = memory.lastGoodTransport(networkKey, server.id, now)
        LogUtil.w(AppConfig.TAG, "Colitu: transports ${configs.mapNotNull { it.protocolType }} on ${networkKey.substringBefore('|')}, stalled $stalled (hinted $hinted), last good $lastGood")
        ColituTransportOrder.remembered(configs, lastGood, stalled)?.let { return it }
        // No memory of this network: what worked for most phones here goes first.
        if (ColituFeatures.hintedStart()) {
            val preferred = networkRecord?.preferredFor(clientNetwork, now).orEmpty()
            ColituNetworkHintsPolicy.hintedStart(configs, { it.protocolType }, preferred, stalled, lastGood) { ColituTransportOrder.byRank(it, stalled) }?.let {
                LogUtil.w(AppConfig.TAG, "Colitu: no memory of this network, starting with the hinted ${it.mapNotNull { c -> c.protocolType }}")
                return it
            }
        }
        if (!probe) return ColituTransportOrder.byRank(configs, stalled)
        phase = ConnectPhase.Probing
        return rank(configs, stalled)
    }

    /**
     * On [serverId], every transport whose mid-session stall from
     * [watchTransport] is younger than [MID_SESSION_STALL_PENALTY_MS]. Stalls
     * found while connecting are in the per-network [memory].
     */
    private fun stalledTransports(serverId: String): Set<String> {
        val now = System.currentTimeMillis()
        return XrayMobileAdapter.supportedProtocols.filterTo(mutableSetOf()) { protocol ->
            val key = ColituTransportOrder.midSessionStallKey(serverId, protocol)
            val until = store.decodeLong(key, 0L)
            val midSession = until > 0L && now < until
            if (until > 0L && !midSession) store.removeValueForKey(key)
            midSession
        }
    }

    private fun rankOf(protocol: String?, stalled: Set<String>) = ColituTransportOrder.rankOf(protocol, stalled)

    /**
     * Starts the transports in [order] and checks real traffic through each;
     * "connected" is shown only once that check passed. A transport that
     * carries nothing while the phone itself is online is remembered as
     * stalled on this network. Later transports only start while [deadline]
     * leaves room; the first always gets its full try.
     */
    private suspend fun tryStart(
        context: Application,
        server: ColituServer,
        order: List<ColituVpnConfig>,
        begin: Long,
        fromCache: Boolean,
        deadline: Long,
        spare: SparePlan? = null,
        sameServer: List<ColituVpnConfig> = order,
        forcedSpare: ColituVpnConfig? = null,
        quiet: Boolean = false,
    ): StartOutcome {
        var cameUp = false
        for ((attempt, config) in order.withIndex()) {
            if (attempt > 0 && deadline - android.os.SystemClock.elapsedRealtime() < MIN_TRANSPORT_MS) break
            phase = if (attempt == 0) ConnectPhase.Starting else ConnectPhase.Switching
            if (attempt > 0) showToast(ColituLoc["home.switchingTransport"], error = false)
            // Parallel connect (happy eyeballs): the best two candidates start
            // together as primary and spare and are checked at the same time.
            val spareConfig = forcedSpare ?: spareFor(spare, config, sameServer)
            val transportBegin = android.os.SystemClock.elapsedRealtime()
            val guid = startCore(context, config, spareConfig) ?: continue
            cameUp = true
            phase = ConnectPhase.Verifying
            // Start plus check within TRANSPORT_BUDGET_MS (per pair), never
            // below the minimum a QUIC/TLS handshake through the tunnel needs.
            val spent = android.os.SystemClock.elapsedRealtime() - transportBegin
            val budget = (TRANSPORT_BUDGET_MS - spent).coerceIn(MIN_VERIFY_MS, VERIFY_TIMEOUT_MS)
            val checkBegin = android.os.SystemClock.elapsedRealtime()
            val (primaryMs, spareMs) = verifyPair(budget, withSpare = spareConfig != null)
            val winner = ColituWarmSpare.parallelWinner(primaryMs, spareMs)
            LogUtil.w(
                AppConfig.TAG,
                "Colitu: parallel round: ${config.protocolType} vs ${spareConfig?.let { "${it.protocolType} on ${it.serverId.take(8)}" } ?: "none"} " +
                    "→ winner ${winner.name.lowercase()} in ${android.os.SystemClock.elapsedRealtime() - checkBegin} ms",
            )
            // The route id stays the identity of a multihop choice (list marker, remembered transport).
            val actual = if (server.isMultihop) server else servers.firstOrNull { it.id == config.serverId } ?: server
            when (winner) {
                ColituWarmSpare.ParallelWinner.Primary -> {
                    if (spareConfig != null && spareMs != null && spareMs < 0) {
                        // Known dead at once: noted, the spare probe replaces it later.
                        LogUtil.w(AppConfig.TAG, "Colitu: spare ${spareConfig.protocolType} failed its first check")
                    }
                    connected(actual, config, spareConfig, guid, primaryMs, begin, fromCache, sameServer, quiet)
                    return StartOutcome.Connected
                }
                ColituWarmSpare.ParallelWinner.Spare -> {
                    val newPrimary = requireNotNull(spareConfig)
                    if (!deviceOnline()) return StartOutcome.Offline
                    primaryFailed(actual, config, fromCache)
                    // Roles swap: the passing spare becomes the primary (one quick
                    // reload, nothing is open yet) with a new spare beside it.
                    val newServer = servers.firstOrNull { it.id == newPrimary.serverId } ?: actual
                    val newSpare = spareAfterSwap(newPrimary, sameServer, config)
                    stopService(force = true, hold = true)
                    val swappedGuid = startCore(context, newPrimary, newSpare) ?: continue
                    val delayMs = verifyTunnel(budget, VerifyPath.Primary)
                    if (delayMs >= 0) {
                        LogUtil.w(AppConfig.TAG, "Colitu: roles swapped: ${newPrimary.protocolType} on ${newPrimary.serverId.take(8)} is the primary, spare ${newSpare?.protocolType ?: "none"}")
                        connected(newServer, newPrimary, newSpare, swappedGuid, delayMs, begin, fromCache, if (newServer.id == actual.id) sameServer else emptyList(), quiet)
                        return StartOutcome.Connected
                    }
                    LogUtil.w(AppConfig.TAG, "Colitu: ${newPrimary.protocolType} passed as spare but not as primary")
                    stopService(force = true, hold = true)
                }
                ColituWarmSpare.ParallelWinner.None -> {
                    LogUtil.w(AppConfig.TAG, "Colitu: ${config.protocolType} is up but carries no traffic")
                    // fail() ends the hold.
                    if (!deviceOnline()) return StartOutcome.Offline
                    primaryFailed(actual, config, fromCache)
                    spareConfig?.let { failedSpare ->
                        failedSpare.protocolType?.let { protocol ->
                            deadSpares += failedSpare.serverId to protocol
                            if (failedSpare.serverId == actual.id) stalledThisConnect += protocol
                        }
                    }
                    stopService(force = true, hold = true)
                }
            }
        }
        return StartOutcome.Failed(cameUp)
    }

    /** Imports [config] (with [spare]) and starts the core; the profile's guid, or null when it did not start. */
    private suspend fun startCore(context: Application, config: ColituVpnConfig, spare: ColituVpnConfig?): String? {
        val guid = ColituVpnRepository.importRuntimeConfig(context, config, spare).getOrElse {
            LogUtil.e(AppConfig.TAG, "Colitu: import failed for ${config.protocolType}")
            return null
        }
        MmkvManager.setSelectServer(guid)
        val started = CompletableDeferred<String?>().also { pendingStart = it }
        CoreServiceManager.startVService(context, guid)
        val startError = withTimeoutOrNull(START_TIMEOUT_MS) { started.await() } ?: "TUNNEL_TIMEOUT"
        pendingStart = null
        if (startError.isNotEmpty()) {
            LogUtil.w(AppConfig.TAG, "Colitu: ${config.protocolType} did not start: $startError")
            // The next transport follows; fail() ends the hold if none does.
            stopService(force = true, hold = true)
            return null
        }
        return guid
    }

    /**
     * Checks the primary and (with [withSpare]) the spare at the same time,
     * each through its own check inbound. The primary passing ends the
     * round; when the spare passes first, the primary still gets
     * [PARALLEL_GRACE_MS] (a slightly slower primary keeps its role).
     * Delays in ms, -1 failed, null not known (no spare, or not needed).
     */
    private suspend fun verifyPair(timeoutMs: Long, withSpare: Boolean): Pair<Long, Long?> = coroutineScope {
        val primary = async { verifyTunnel(timeoutMs, VerifyPath.Primary) }
        if (!withSpare) return@coroutineScope primary.await() to null
        val spare = async { verifyTunnel(timeoutMs, VerifyPath.Spare) }
        val primaryFirst = select<Boolean> {
            primary.onAwait { true }
            spare.onAwait { false }
        }
        if (primaryFirst) {
            val primaryMs = primary.await()
            if (primaryMs >= 0) {
                val spareMs = if (spare.isCompleted) spare.await() else null
                spare.cancel()
                primaryMs to spareMs
            } else {
                primaryMs to spare.await()
            }
        } else {
            val spareMs = spare.await()
            if (spareMs < 0) {
                primary.await() to spareMs
            } else {
                (withTimeoutOrNull(PARALLEL_GRACE_MS) { primary.await() } ?: -1L).also { primary.cancel() } to spareMs
            }
        }
    }

    /** The primary [config] on [actual] carried nothing in this round (the phone is online). */
    private fun primaryFailed(actual: ColituServer, config: ColituVpnConfig, fromCache: Boolean) {
        config.protocolType?.let { protocol ->
            stalledThisConnect += protocol
            // A cached profile may just be stale: only a fresh one's silence is remembered (and reported).
            if (!fromCache) {
                observe(actual, protocol, reachable = false, latencyMs = null)
                memory.markStalled(networkKey, actual.id, protocol, System.currentTimeMillis(), confirmed = false)
                saveMemory()
            }
        }
    }

    /**
     * The spare after a role swap: from the old primary's server (another
     * server than the new primary, or the same one, then the other family),
     * never the transport that just failed nor the new primary's own.
     */
    private fun spareAfterSwap(newPrimary: ColituVpnConfig, oldServerConfigs: List<ColituVpnConfig>, failed: ColituVpnConfig): ColituVpnConfig? {
        if (oldServerConfigs.isEmpty()) return null
        val sameServer = oldServerConfigs.first().serverId == newPrimary.serverId
        val now = System.currentTimeMillis()
        val stalled = stalledThisConnect + setOfNotNull(failed.protocolType) + memory.stalled(networkKey, failed.serverId, now) +
            deadSpares.filter { it.first == failed.serverId }.map { it.second } +
            if (sameServer) setOfNotNull(newPrimary.protocolType) else emptySet()
        val network = networkKey
        val offered = oldServerConfigs.mapNotNull { it.protocolType }
        val worked = offered.filterTo(mutableSetOf()) { memory.workedOnNetwork(network, it, now) }
        val (protocol, _) = ColituWarmSpare.spareChoice(newPrimary.protocolType, offered, stalled, sameServer, hintedBlocked(now), worked) ?: return null
        return oldServerConfigs.firstOrNull { it.protocolType == protocol }
    }

    /** Real traffic flowed through [config]: connected, remembered, watched. */
    private fun connected(
        actual: ColituServer,
        config: ColituVpnConfig,
        spareConfig: ColituVpnConfig?,
        guid: String,
        delayMs: Long,
        begin: Long,
        fromCache: Boolean,
        candidates: List<ColituVpnConfig>,
        quiet: Boolean,
    ) {
        onConnected(actual, config, guid, quiet)
        LogUtil.w(
            AppConfig.TAG,
            "Colitu: connected via ${config.protocolType} in ${android.os.SystemClock.elapsedRealtime() - begin} ms (cached profile: $fromCache)",
        )
        observe(actual, config.protocolType, reachable = true, latencyMs = delayMs)
        memory.recordSuccess(networkKey, actual.id, config.protocolType, System.currentTimeMillis(), goodServer = !actual.isMultihop)
        // The device was online and this transport worked: what failed before it in this round really stalled.
        memory.confirm(networkKey, actual.id, stalledThisConnect - config.protocolType.orEmpty(), System.currentTimeMillis())
        attachedSpare = spareConfig?.let { spare -> (servers.firstOrNull { it.id == spare.serverId } ?: actual) to spare.protocolType.orEmpty() }
        connectedConfig = config
        connectedCandidates = candidates.ifEmpty { listOf(config) }
        saveMemory()
        config.protocolType?.let { store.removeValueForKey(ColituTransportOrder.midSessionStallKey(actual.id, it)) }
        verifyJob?.cancel()
        verifyJob = viewModelScope.launch {
            // Keep the cached profile current for the next instant connect.
            if (fromCache) safeCall { fetchCandidates(actual) }
            config.protocolType?.let { watchTransport(actual, it) }
        }
    }

    /** elapsedRealtime of the last automatic mid-session transport switch. */
    private var lastAutoSwitchAt = 0L

    /**
     * The tunnel can stop carrying traffic after the connect check passed:
     * Russian mobile networks throttle a long-lived UDP flow (Hysteria2), and
     * DPI freezes TCP flows to a server once it has seen a few kilobytes
     * (Reality/XHTTP "connected but nothing loads"). Runs in [verifyJob] (so
     * every connect, switch and disconnect stops it): a real request through
     * the tunnel (spare included: only both paths dead counts) every
     * [STALL_PROBE_INTERVAL_MS], for TCP transports only during the first
     * [TCP_WATCH_FAST_MS] and then every [TCP_WATCH_SLOW_MS]; after
     * [STALL_PROBE_FAILURES] misses in a row while the phone itself is online
     * on an unchanged network, [protocol] goes last on this server for
     * [MID_SESSION_STALL_PENALTY_MS] (a TCP one also stalls on this network in
     * the memory) and the same server is reconnected on the next transport,
     * silently. At most one such switch per [AUTO_SWITCH_MIN_GAP_MS].
     */
    private suspend fun watchTransport(server: ColituServer, protocol: String) {
        var failures = 0
        // With a warm spare: misses of the primary alone while the normal
        // path (balancer, i.e. the spare) still works.
        var primaryMisses = 0
        var primaryRecorded = false
        val spare = attachedSpare
        // Spare health: checked alone every spareProbeIntervalMs while the primary is healthy.
        var spareMisses = 0
        var spareDead = false
        var nextSpareProbeAt = android.os.SystemClock.elapsedRealtime() + ColituWarmSpare.spareProbeIntervalMs(spare?.second)
        var replacementSpare: ColituVpnConfig? = null
        var lastDeferral: String? = null
        var swapDeferredSince = 0L
        var lastNetworks: Set<String>? = null
        val started = android.os.SystemClock.elapsedRealtime()
        while (connected && transport == protocol && connectedServerId == server.id) {
            val fast = protocol == "hysteria2" || android.os.SystemClock.elapsedRealtime() - started < TCP_WATCH_FAST_MS || failures > 0
            delay(if (fast) STALL_PROBE_INTERVAL_MS else TCP_WATCH_SLOW_MS)
            if (!connected || transport != protocol || connectedServerId != server.id) return
            val networks = underlyingNetworks()
            // Offline, airplane mode, Doze or a network change in progress:
            // the misses would say nothing about the transport.
            if (networks == null || networks != lastNetworks || deviceIdle()) {
                lastNetworks = networks
                failures = 0
                continue
            }
            val primaryOk = spare != null && verifyTunnel(STALL_PROBE_TIMEOUT_MS, VerifyPath.Primary) >= 0
            val normalOk = primaryOk || verifyTunnel(STALL_PROBE_TIMEOUT_MS) >= 0
            if (spare != null && primaryOk) {
                val tick = android.os.SystemClock.elapsedRealtime()
                if (!spareDead && tick >= nextSpareProbeAt) {
                    nextSpareProbeAt = tick + ColituWarmSpare.spareProbeIntervalMs(spare.second)
                    val spareOk = verifyTunnel(STALL_PROBE_TIMEOUT_MS, VerifyPath.Spare) >= 0
                    LogUtil.w(AppConfig.TAG, "Colitu: spare probe ${if (spareOk) "ok" else "miss"} (${spare.second} on ${spare.first.id.take(8)})")
                    spareMisses = if (spareOk) 0 else spareMisses + 1
                    if (spareMisses >= ColituWarmSpare.SPARE_PROBE_MISSES && deviceOnline()) {
                        spareDead = true
                        deadSpares += spare.first.id to spare.second
                        memory.markStalled(networkKey, spare.first.id, spare.second, System.currentTimeMillis(), confirmed = false)
                        saveMemory()
                        replacementSpare = replacementSpareFor(server, spare)
                        if (replacementSpare == null) LogUtil.w(AppConfig.TAG, "Colitu: spare ${spare.second} is dead and nothing can replace it; keeping it")
                    }
                }
                val next = replacementSpare
                if (next != null) {
                    if (swapDeferredSince == 0L) swapDeferredSince = tick
                    val deferral = ColituWarmSpare.swapDeferral(recentTunnelBytes(), tick - swapDeferredSince)
                    if (deferral == null) {
                        LogUtil.w(AppConfig.TAG, "Colitu: spare replaced: ${spare.second} on ${spare.first.id.take(8)} → ${next.protocolType} on ${next.serverId.take(8)} (spare probe missed ${ColituWarmSpare.SPARE_PROBE_MISSES}x, waited ${(tick - swapDeferredSince) / 1000} s)")
                        swapSpare(server, next)
                        return
                    }
                    // Logged once per reason, not once per probe with the byte count.
                    val reason = deferral.substringBefore(" (")
                    if (reason != lastDeferral) LogUtil.w(AppConfig.TAG, "Colitu: spare swap deferred: $deferral")
                    lastDeferral = reason
                }
            }
            // The spare is known dead: a dead primary means a reconnect right away, not after the usual misses.
            if (spareDead && !primaryOk && !normalOk) {
                if (++failures >= KNOWN_DEAD_SPARE_FAILURES && deviceOnline()) {
                    LogUtil.w(AppConfig.TAG, "Colitu: $protocol died while the spare is known dead, reconnecting")
                    stalledThisConnect += protocol
                    observe(server, protocol, reachable = false, latencyMs = null)
                    flushObservations()
                    startConnectFlow(fresh = false, continuing = true)
                    return
                }
                continue
            }
            when (ColituTransportOrder.watchAction(spare != null, primaryOk, normalOk)) {
                ColituTransportOrder.WatchAction.Fine -> {
                    failures = 0
                    primaryMisses = 0
                    continue
                }
                ColituTransportOrder.WatchAction.SpareCarries -> {
                    failures = 0
                    // The spare carries the traffic: no reconnect, only the next
                    // connect starts on the spare's transport instead.
                    if (spare != null && ++primaryMisses >= STALL_PROBE_FAILURES && !primaryRecorded && deviceOnline()) {
                        primaryRecorded = true
                        recordSpareCarrying(server, protocol, spare)
                    }
                    continue
                }
                ColituTransportOrder.WatchAction.BothDead -> Unit
            }
            if (++failures < STALL_PROBE_FAILURES) continue
            if (!connected || underlyingNetworks() != lastNetworks) {
                failures = 0
                continue
            }
            val now = android.os.SystemClock.elapsedRealtime()
            val nothingElse = offeredTransports.isNotEmpty() && offeredTransports.none { it != protocol }
            // Inside the gap the misses keep counting, so the switch happens as
            // soon as the gap ends instead of after three more misses.
            if (nothingElse || (lastAutoSwitchAt > 0L && now - lastAutoSwitchAt < AUTO_SWITCH_MIN_GAP_MS)) continue
            if (!deviceOnline()) continue
            failures = 0
            lastAutoSwitchAt = now
            val wallNow = System.currentTimeMillis()
            val proven = memory.workedOnNetwork(networkKey, protocol, wallNow)
            LogUtil.w(AppConfig.TAG, "Colitu: $protocol stalled mid-session ($STALL_PROBE_FAILURES probes failed, ${(now - started) / 1000} s after connect${if (proven) ", proven here: short penalty" else ""}), switching transport")
            store.encode(
                ColituTransportOrder.midSessionStallKey(server.id, protocol),
                wallNow + ColituTransportOrder.midSessionPenaltyMs(proven, MID_SESSION_STALL_PENALTY_MS),
            )
            if (protocol != "hysteria2") {
                // Tentative: confirmed only when the reconnect finds another transport that works.
                memory.markStalled(networkKey, server.id, protocol, System.currentTimeMillis(), confirmed = false)
                saveMemory()
            }
            stalledThisConnect += protocol
            observe(server, protocol, reachable = false, latencyMs = null)
            flushObservations()
            // Cancels this job; the cached profile starts the next transport right away.
            startConnectFlow(fresh = false, continuing = true)
            return
        }
    }

    /** The warm spare of the running tunnel (its server and transport), null without one. */
    private var attachedSpare: Pair<ColituServer, String>? = null

    /** The running primary profile and its server's other transports (for a new spare). */
    private var connectedConfig: ColituVpnConfig? = null
    private var connectedCandidates: List<ColituVpnConfig> = emptyList()

    /** Spares that died during this connect (server id to transport): never chosen again in it. */
    private val deadSpares = mutableSetOf<Pair<String, String>>()

    /**
     * A new spare after [dead] died, by the spare rules: the dead spare's
     * server (other transports), then the next ranked server, then (or in
     * manual mode only) the primary's own server; profiles from the cache or
     * fetched. Null: nothing qualifies.
     */
    private suspend fun replacementSpareFor(server: ColituServer, dead: Pair<ColituServer, String>): ColituVpnConfig? {
        val primary = connectedConfig ?: return null
        val now = System.currentTimeMillis()
        val network = networkKey
        val profiles = linkedMapOf<String, List<ColituVpnConfig>>()
        if (connectsAutomatically && !server.isMultihop) {
            val penalized = memory.penalized(network, now)
            val nextServer = rankedServers.firstOrNull { it.id != server.id && it.id != dead.first.id && it.id !in penalized }
            listOfNotNull(dead.first.takeIf { it.id != server.id }, nextServer).forEach { candidate ->
                val configs = ColituServerRepository.cachedNodeCandidates(candidate.id)
                    ?: safeCall { ColituServerRepository.fetchNodeCandidates(candidate.id) }.getOrNull()
                configs?.takeIf { it.isNotEmpty() }?.let { profiles[candidate.id] = it }
            }
        }
        profiles[server.id] = connectedCandidates
        val options = profiles.map { (id, configs) ->
            ColituWarmSpare.SpareOption(
                serverId = id,
                sameServer = id == server.id,
                offered = configs.mapNotNull { it.protocolType },
                stalled = stalledTransports(id) + memory.stalled(network, id, now) + if (id == server.id) setOfNotNull(primary.protocolType) else emptySet(),
            )
        }
        val worked = XrayMobileAdapter.supportedProtocols.filterTo(mutableSetOf()) { memory.workedOnNetwork(network, it, now) }
        val (serverId, protocol, reason) = ColituWarmSpare.replacement(primary.protocolType, options, deadSpares, worked, hintedBlocked(now))
            ?: return null
        LogUtil.w(AppConfig.TAG, "Colitu: replacement spare $protocol on ${serverId.take(8)} (spare reason: $reason)")
        return profiles[serverId]?.firstOrNull { it.protocolType == protocol }
    }

    /**
     * Reloads the core with the same primary and [newSpare] (about a second
     * without traffic; only called while the tunnel is idle). As a connect
     * job, so the short stop is not taken for a drop; the status stays
     * "Connected". When the primary does not come back, a normal reconnect.
     */
    private fun swapSpare(server: ColituServer, newSpare: ColituVpnConfig) {
        val primary = connectedConfig ?: return
        val candidates = connectedCandidates
        connectJob?.cancel()
        connectJob = viewModelScope.launch {
            val context = getApplication<Application>()
            if (serviceUp) {
                stopService(force = true, hold = true)
                delay(TEARDOWN_SETTLE_MS)
            }
            val begin = android.os.SystemClock.elapsedRealtime()
            val outcome = tryStart(
                context, server, listOf(primary), begin, fromCache = false, deadline = begin + TRANSPORT_BUDGET_MS,
                sameServer = candidates, forcedSpare = newSpare, quiet = true,
            )
            if (outcome != StartOutcome.Connected) {
                LogUtil.w(AppConfig.TAG, "Colitu: the primary did not come back after the spare swap, reconnecting")
                startConnectFlow(fresh = false, continuing = true)
            }
        }
    }

    /**
     * The primary [protocol] is dead but the spare carries the traffic: the
     * primary goes last on this server for the next connect and the spare's
     * transport (and server) becomes the remembered good one. Nothing
     * restarts now.
     */
    private fun recordSpareCarrying(server: ColituServer, protocol: String, spare: Pair<ColituServer, String>) {
        val now = System.currentTimeMillis()
        LogUtil.w(AppConfig.TAG, "Colitu: $protocol is dead, the warm spare ${spare.second} carries the traffic; it leads the next connect")
        val proven = memory.workedOnNetwork(networkKey, protocol, now)
        store.encode(ColituTransportOrder.midSessionStallKey(server.id, protocol), now + ColituTransportOrder.midSessionPenaltyMs(proven, MID_SESSION_STALL_PENALTY_MS))
        memory.markStalled(networkKey, server.id, protocol, now, confirmed = protocol != "hysteria2")
        memory.recordSuccess(networkKey, spare.first.id, spare.second, now, goodServer = !spare.first.isMultihop)
        saveMemory()
        observe(server, protocol, reachable = false, latencyMs = null)
        flushObservations()
    }

    /** Ids of the non-VPN networks with internet, or null when there is none. */
    private fun underlyingNetworks(): Set<String>? = runCatching {
        val cm = getApplication<Application>().getSystemService(ConnectivityManager::class.java)
            ?: return@runCatching null
        @Suppress("DEPRECATION")
        cm.allNetworks.filter { network ->
            val caps = cm.getNetworkCapabilities(network) ?: return@filter false
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }.map { it.toString() }.toSet().takeIf { it.isNotEmpty() }
    }.getOrNull()

    private fun deviceIdle(): Boolean = runCatching {
        getApplication<Application>().getSystemService(PowerManager::class.java)?.isDeviceIdleMode == true
    }.getOrDefault(false)

    private fun onConnected(server: ColituServer, config: ColituVpnConfig, guid: String, quiet: Boolean = false) {
        val protocol = config.protocolType
        transport = protocol
        store.encode(KEY_LAST_TRANSPORT, protocol)
        // The tile, widget and Always-on may restart exactly this profile
        // until the panel's offline grace for it ends.
        config.offlineGraceUntil?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
            ?.let { ColituQuickStart.allow(guid, it, ColituClock.skewMs) }
        connectedServerId = server.id
        store.encode(KEY_CONNECTED_SERVER, server.id)
        connectedAt = store.decodeLong(AppConfig.PREF_COLITU_CONNECTED_AT, 0L).takeIf { it > 0 } ?: System.currentTimeMillis()
        connectedSeconds = ((System.currentTimeMillis() - connectedAt) / 1000).toInt().coerceAtLeast(0)
        profileCountry = config.serverCountry.orEmpty()
        status = VpnStatus.Connected
        phase = ConnectPhase.Idle
        error = null
        reconnecting = false
        reconnectAttempt = 0
        resumeOnNetwork = false
        if (!quiet) showToast(ColituLoc.format("locations.switched", "server" to titleOf(server)), error = false)
        maybeShowRuNotice()
    }

    /**
     * Once per device, the first time a connection is up with the Russian
     * direct rule active: explain it and offer privacy mode. Not shown (and
     * not marked as seen) while privacy mode is on or the server is in Russia.
     */
    private fun maybeShowRuNotice() {
        if (ruNoticeVisible || !ruDirectActive || ColituRuBypass.noticeShown) return
        ruNoticeVisible = true
    }

    /** The notice was answered or dismissed; [enablePrivacy] turns privacy mode on. */
    fun answerRuNotice(enablePrivacy: Boolean) {
        ColituRuBypass.noticeShown = true
        ruNoticeVisible = false
        if (enablePrivacy) setPrivacyModeEnabled(true)
    }

    private fun fail(code: String?) {
        if (reconnecting) {
            if (code?.uppercase() in RECONNECT_RETRY_CODES) {
                if (scheduleReconnect()) return
                // Used up: the error below; a network that comes back tries once more.
                resumeOnNetwork = true
            }
            reconnecting = false
        }
        if (code == ColituDevicePause.CODE) {
            // The bootstrap carries the details (limit, active devices).
            status = VpnStatus.Disconnected
            phase = ConnectPhase.Idle
            viewModelScope.launch {
                stopService(force = true)
                refreshPolicy()
            }
            return
        }
        status = VpnStatus.Disconnected
        phase = ConnectPhase.Idle
        error = failureMessage(code)
        LogUtil.w(AppConfig.TAG, "Colitu: connect failed: $code (clock skew ${ColituClock.skewMs} ms)")
        showToast(error!!, error = true)
        viewModelScope.launch { stopService(force = true) }
    }

    /**
     * A wrong device clock breaks the config check, TLS and Reality alike and
     * is common on TV sticks, so it is named as the cause. Otherwise the
     * sentence for [code]; the code itself follows when the sentence is a
     * general one, so a screenshot tells support what happened.
     */
    private fun failureMessage(code: String?): String {
        if (ColituClock.deviceClockWrong && code !in setOf("NO_SERVERS", "NO_SERVER_SELECTED", "VPN_PERMISSION_DENIED")) return ColituLoc["err.clock"]
        val message = colituErrorMessage(code)
        val general = message == ColituLoc["err.engine"] || message == ColituLoc["err.generic"] || message == ColituLoc["err.unreachable"]
        return if (general && !code.isNullOrBlank()) "$message (${code.uppercase()})" else message
    }

    /**
     * Probes every transport at the same time outside the tunnel and orders
     * them: reachable before unreachable, then by preference (Hysteria2
     * first), then by delay. A transport that stalled last time goes last.
     */
    private suspend fun rank(configs: List<ColituVpnConfig>, excluded: Set<String>): List<ColituVpnConfig> {
        val byPreference = configs.sortedBy { rankOf(it.protocolType, excluded) }
        if (configs.size == 1) return configs
        val context = getApplication<Application>()
        // Copying the geo files and loading the core are disk work.
        withContext(Dispatchers.IO) {
            runCatching {
                SettingsManager.initAssets(context, context.assets)
                CoreNativeManager.initCoreEnv(context)
            }
        }
        val url = SettingsManager.getDelayTestUrl()
        return coroutineScope {
            val jobs = byPreference.map { config ->
                async(Dispatchers.IO) {
                    runCatching { CoreNativeManager.measureOutboundDelay(config.rawConfig.orEmpty(), url) }.getOrDefault(-1L)
                }
            }
            // Walk the transports in preference order and stop at the first
            // that answers: a dead lower-ranked transport no longer holds the
            // connection up for the whole probe window.
            val deadline = android.os.SystemClock.elapsedRealtime() + PROBE_TIMEOUT_MS
            var winner = -1
            for (i in byPreference.indices) {
                val left = deadline - android.os.SystemClock.elapsedRealtime()
                val delayMs = if (left <= 0) {
                    if (jobs[i].isCompleted) jobs[i].await() else -1L
                } else {
                    withTimeoutOrNull(left) { jobs[i].await() } ?: -1L
                }
                if (delayMs >= 0) {
                    winner = i
                    break
                }
            }
            LogUtil.w(AppConfig.TAG, "Colitu probe: first answer ${if (winner >= 0) byPreference[winner].protocolType else "none"}")
            if (winner <= 0) byPreference else listOf(byPreference[winner]) + byPreference.filterIndexed { i, _ -> i != winner }
        }
    }

    /** Which path a tunnel check takes. */
    private enum class VerifyPath { Normal, Primary, Spare }

    /**
     * Real request through the running tunnel; the delay in ms or -1.
     * [VerifyPath.Primary]: through the check inbound to the primary outbound
     * only (connect time; a warm spare must not pass a dead primary).
     * [VerifyPath.Spare]: the spare alone, one small request.
     * [VerifyPath.Normal]: the way apps go, spare included (only both dead matters).
     * Several checks may run at once (parallel connect).
     */
    private suspend fun verifyTunnel(timeoutMs: Long = VERIFY_TIMEOUT_MS, path: VerifyPath = VerifyPath.Normal): Long {
        val result = CompletableDeferred<Long>()
        val id = synchronized(pendingVerifies) { ++verifyId }
        pendingVerifies[id] = result
        val content = when (path) {
            VerifyPath.Primary -> "$id,primary"
            VerifyPath.Spare -> "$id,spare"
            VerifyPath.Normal -> id.toString()
        }
        return try {
            MessageUtil.sendMsg2Service(getApplication(), AppConfig.MSG_COLITU_VERIFY, content)
            withTimeoutOrNull(timeoutMs) { result.await() } ?: -1L
        } finally {
            pendingVerifies.remove(id)
        }
    }

    /** Bytes the tunnel carried in the last 10 s (from the VPN process), null when unknown. */
    private suspend fun recentTunnelBytes(): Long? {
        val result = CompletableDeferred<Long>()
        val id = synchronized(pendingVerifies) { ++verifyId }
        pendingIdle[id] = result
        return try {
            MessageUtil.sendMsg2Service(getApplication(), AppConfig.MSG_COLITU_IDLE, id.toString())
            withTimeoutOrNull(13_000L) { result.await() }?.takeIf { it >= 0 }
        } finally {
            pendingIdle.remove(id)
        }
    }

    fun disconnect() {
        // While connecting the service may already be starting although it
        // has not reported "running" yet; stop it regardless.
        val starting = status == VpnStatus.Connecting || pendingStart != null
        // Only a start still on its way answers later; a tunnel cancelled
        // during its traffic check is already up and stopped below.
        val midStart = pendingStart != null
        connectJob?.cancel()
        verifyJob?.cancel()
        pendingStart = null
        userStopped = true
        reconnecting = false
        resumeOnNetwork = false
        if (midStart) cancelledStart = true
        viewModelScope.launch {
            status = VpnStatus.Disconnecting
            stopService(force = starting)
            status = VpnStatus.Disconnected
            phase = ConnectPhase.Idle
            showToast(ColituLoc["info.disconnected"], error = false)
        }
    }

    /**
     * Stops the tunnel. [hold] (switching servers or transports) keeps the VPN
     * interface established while the core is down, so traffic is blocked
     * instead of leaving outside the tunnel; only a running or held service
     * can be held, anything else is stopped.
     */
    private suspend fun stopService(force: Boolean = false, hold: Boolean = false) {
        if (!serviceUp && !serviceHeld && !force) {
            clearSession()
            return
        }
        val holding = hold && (serviceUp || serviceHeld)
        val stopped = CompletableDeferred<Unit>().also { pendingStop = it }
        if (holding) {
            CoreServiceManager.holdVService(getApplication())
        } else {
            CoreServiceManager.stopVService(getApplication())
        }
        withTimeoutOrNull(STOP_TIMEOUT_MS) { stopped.await() }
        pendingStop = null
        serviceUp = false
        serviceHeld = holding
        clearSession()
    }

    private fun clearSession() {
        connectedServerId = null
        store.removeValueForKey(KEY_CONNECTED_SERVER)
        connectedAt = 0L
        connectedSeconds = 0
        uploadBps = 0.0
        downloadBps = 0.0
    }

    // ── Selection ──────────────────────────────────────────────────────────

    fun selectServer(server: ColituServer) {
        if (!server.isAvailable) return
        val changed = autoSelection || selectedServerId != server.id
        autoSelection = false
        selectedServerId = server.id
        store.encode(KEY_AUTO_SELECTION, false)
        ColituServerRepository.setSelectedServerId(server.id)
        if (changed) applySelection(server)
    }

    fun selectAuto() {
        val changed = !autoSelection
        autoSelection = true
        store.encode(KEY_AUTO_SELECTION, true)
        recommendedServer?.let { ColituServerRepository.setSelectedServerId(it.id) }
        if (changed) recommendedServer?.let { applySelection(it) }
    }

    private fun applySelection(server: ColituServer) {
        viewModelScope.launch {
            // A route is not a node: nothing to store as the preferred node.
            if (!server.isMultihop) {
                safeCall { ColituServerRepository.selectServer(server.id) }.onSuccess { store.encode(KEY_SYNCED_SERVER, server.id) }
            }
            if (connected || status == VpnStatus.Connecting) {
                showToast(ColituLoc.format("locations.switching", "server" to titleOf(server)), error = false)
                startConnectFlow()
            }
        }
    }

    // ── Settings ───────────────────────────────────────────────────────────

    fun setAutoConnectEnabled(value: Boolean) {
        autoConnect = value
        store.encode(KEY_AUTO_CONNECT, value)
    }

    /**
     * The warm spare changes the core's outbounds, so a live connection is
     * rebuilt; switched off while no tunnel runs, the stored profile (tile,
     * Always-on) loses its spare right away.
     */
    /**
     * Simple or Advanced mode; no reconnect (a live route or setting keeps
     * running, the next connect follows the mode). [fromHome]: the one-tap
     * button on Home, confirmed with a toast.
     */
    fun setAdvancedMode(value: Boolean, fromHome: Boolean = false) {
        if (advancedMode == value) return
        advancedMode = value
        ColituUiMode.advanced = value
        if (value && fromHome) showToast(ColituLoc["mode.advancedOn"], error = false)
    }

    fun setWarmSpareEnabled(value: Boolean) {
        if (warmSpare == value) return
        warmSpare = value
        ColituWarmSpare.enabled = value
        if (connected || status == VpnStatus.Connecting) {
            showToast("${ColituLoc["settings.warmSpare"]} · ${ColituLoc["settings.saved"]}", error = false)
            startConnectFlow()
        } else if (!value) {
            viewModelScope.launch { ColituVpnRepository.detachWarmSpareFromStoredProfile() }
        }
    }

    /** Ad blocking changes the tunnel's DNS, so a live connection is rebuilt. */
    fun setAdBlockEnabled(value: Boolean) {
        if (adBlock == value) return
        adBlock = value
        ColituAdBlock.enabled = value
        if (connected || status == VpnStatus.Connecting) {
            showToast(ColituLoc[if (value) "settings.adBlockOn" else "settings.adBlockOff"], error = false)
            startConnectFlow()
        }
    }

    /**
     * Privacy mode changes the tunnel's routing, so a live connection is
     * rebuilt like for ad blocking; otherwise the stored profile (quick
     * settings tile, Always-on) is updated right away.
     */
    fun setPrivacyModeEnabled(value: Boolean) {
        if (privacyMode == value) return
        privacyMode = value
        ColituRuBypass.privacyMode = value
        if (connected || status == VpnStatus.Connecting) {
            showToast("${ColituLoc["privacy.title"]} · ${ColituLoc["settings.saved"]}", error = false)
            startConnectFlow()
        } else {
            viewModelScope.launch { ColituVpnRepository.reapplyRuBypassToStoredProfile(value) }
        }
    }

    /**
     * Split tunneling changes the VPN interface (apps) and the routing
     * (sites), so a live connection is rebuilt; otherwise the stored profile
     * is updated for the tile and Always-on.
     */
    fun updateSplitTunnel(value: ColituSplitTunnel.Settings) {
        if (splitTunnel == value) return
        splitTunnel = value
        ColituSplitTunnel.settings = value
        if (connected || status == VpnStatus.Connecting) {
            showToast("${ColituLoc["split.title"]} · ${ColituLoc["settings.saved"]}", error = false)
            startConnectFlow()
        } else {
            viewModelScope.launch { ColituVpnRepository.reapplySplitTunnelToStoredProfile() }
        }
    }

    // ── Rotating exit IP ───────────────────────────────────────────────────

    /**
     * Saves the rotation (interval 0/300/600/1800 s, empty [countries] = the
     * panel's default set). The exit changes on the panel's schedule without
     * a reconnect; only a running non-VLESS transport cannot rotate, so that
     * one reconnects (the next profile is VLESS only).
     */
    suspend fun saveRotation(intervalSeconds: Int, countries: List<String>): Result<ColituRotationPreference> {
        val result = safeCall { ColituRotationRepository.save(intervalSeconds, countries) }
        result.onSuccess { saved ->
            rotation = saved
            rotationStatus = null
            showToast(ColituLoc["settings.saved"], error = false)
            if (saved.active && connected && !onMultihopRoute && !ColituMultihop.isVless(transport)) {
                showToast(ColituLoc["rotation.reconnect"], error = false)
                startConnectFlow()
            }
        }
        return result
    }

    /** The node whose rotation is shown: connected to a node (not a route) while rotation is on. */
    private fun rotationNode(): String? =
        if (connected && rotationActive && connectedServer?.isMultihop == false) connectedServerId else null

    /**
     * While the app is visible and the tunnel runs on a rotating node: asks for
     * the status at the panel's next_change_at, never more often than every
     * 60 seconds. Stops with [onBackground].
     */
    private fun startRotationPoll() {
        rotationPollJob?.cancel()
        rotationPollJob = viewModelScope.launch {
            var shown: String? = null
            while (true) {
                val node = rotationNode()
                if (node == null) {
                    rotationStatus = null
                    shown = null
                    delay(2_000)
                    continue
                }
                if (node != shown) {
                    rotationStatus = null
                    shown = node
                }
                val answer = ColituRotationRepository.status(node)
                if (rotationNode() == node) answer.onSuccess { rotationStatus = it }
                val wait = ColituRotation.nextPollDelay(answer.getOrNull()?.nextChangeAt, ColituClock.now()).toMillis()
                // Wakes early when the connection or the rotation setting changes.
                var waited = 0L
                while (waited < wait && rotationNode() == node) {
                    delay(1_000)
                    waited += 1_000
                }
            }
        }
    }

    fun showToast(text: String, error: Boolean) {
        if (text.isBlank()) return
        toast = ColituToastMessage(text, error)
        val id = toast!!.id
        viewModelScope.launch {
            delay(if (error) 5000 else 3000)
            if (toast?.id == id) toast = null
        }
    }

    fun clearError() {
        error = null
    }

    fun signOut() = finishSession(message = null, notifyServer = true)

    /** This phone was removed from the account on the devices list. */
    fun onCurrentDeviceRemoved() = finishSession(message = null, notifyServer = false)

    private fun endSession(message: String?) = finishSession(message, notifyServer = false)

    /**
     * Stops the tunnel, drops the tokens and everything tied to the account
     * (ColituTokenManager.clear) and resets this controller, so the next
     * sign-in starts from a clean state.
     */
    private fun finishSession(message: String?, notifyServer: Boolean) {
        viewModelScope.launch {
            connectJob?.cancel()
            verifyJob?.cancel()
            userStopped = true
            stopService(force = serviceUp || status != VpnStatus.Disconnected)
            status = VpnStatus.Disconnected
            phase = ConnectPhase.Idle
            if (notifyServer) runCatching { ColituAuthRepository.logout() }
            ColituTokenManager.clear()
            resetAccountState()
            _sessionEnded.tryEmit(message)
        }
    }

    private fun resetAccountState() {
        servers = emptyList()
        routes = emptyList()
        rotation = null
        rotationStatus = null
        user = null
        subscription = null
        denial = null
        error = null
        transport = null
        autoSelection = true
        autoConnect = false
        selectedServerId = null
        supportUnread = 0
        supportChecked = false
        supportAvailable = true
        loading = true
        updateHintShown = false
        devicePause = null
        outlook = null
        ColituNoticeCenter.reset()
    }

    // ── Service messages ───────────────────────────────────────────────────

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val content = intent?.getSerializableExtra("content")?.toString().orEmpty()
            when (intent?.getIntExtra("key", 0)) {
                AppConfig.MSG_STATE_RUNNING -> {
                    serviceUp = true
                    serviceHeld = false
                    if (status == VpnStatus.Disconnected && connectJob?.isActive != true) {
                        // The tunnel was already up (app reopened, quick-settings tile).
                        connectedAt = store.decodeLong(AppConfig.PREF_COLITU_CONNECTED_AT, 0L).takeIf { it > 0 }
                            ?: System.currentTimeMillis()
                        connectedServerId = connectedServerId ?: effectiveServer?.id
                        profileCountry = ColituRuBypass.profileCountry
                        status = VpnStatus.Connected
                        maybeShowRuNotice()
                    }
                }
                AppConfig.MSG_STATE_NOT_RUNNING -> {
                    serviceUp = false
                    if (status == VpnStatus.Connected && connectJob?.isActive != true) {
                        status = VpnStatus.Disconnected
                        clearSession()
                    }
                }
                AppConfig.MSG_STATE_START_SUCCESS -> {
                    serviceUp = true
                    serviceHeld = false
                    pendingStart?.complete("")
                    if (cancelledStart) {
                        cancelledStart = false
                        // Cancelled while it was starting: the stop is already on its way.
                        viewModelScope.launch { stopService(force = true) }
                    } else if (status == VpnStatus.Disconnected && connectJob?.isActive != true) {
                        connectedAt = System.currentTimeMillis()
                        profileCountry = ColituRuBypass.profileCountry
                        status = VpnStatus.Connected
                        maybeShowRuNotice()
                    }
                }
                AppConfig.MSG_STATE_START_FAILURE -> pendingStart?.complete(content.ifBlank { "ENGINE_FAILED" })
                AppConfig.MSG_STATE_STOP_SUCCESS -> {
                    serviceUp = false
                    pendingStop?.complete(Unit)
                    if (status == VpnStatus.Connected && connectJob?.isActive != true) {
                        status = VpnStatus.Disconnected
                        clearSession()
                        when {
                            userStopped -> Unit
                            // No reason: the tunnel went down by itself (core or interface failure).
                            content.isEmpty() -> onUnexpectedDrop()
                            // Stopped from outside the app (notification, tile, another VPN).
                            else -> showToast(ColituLoc["info.disconnected"], error = false)
                        }
                    }
                }
                AppConfig.MSG_COLITU_TRAFFIC -> {
                    val parts = content.split(',')
                    if (parts.size == 2 && connected) {
                        uploadBps = parts[0].toDoubleOrNull() ?: 0.0
                        downloadBps = parts[1].toDoubleOrNull() ?: 0.0
                    }
                }
                AppConfig.MSG_COLITU_VERIFY_RESULT -> {
                    // "<id>,<delay>": a late answer for a previous connection
                    // must not decide about the current one.
                    val (id, delayMs) = content.split(',').let { it.getOrNull(0)?.toLongOrNull() to it.getOrNull(1)?.toLongOrNull() }
                    id?.let { pendingVerifies[it]?.complete(delayMs ?: -1L) }
                }
                AppConfig.MSG_COLITU_IDLE_RESULT -> {
                    val (id, bytes) = content.split(',').let { it.getOrNull(0)?.toLongOrNull() to it.getOrNull(1)?.toLongOrNull() }
                    id?.let { pendingIdle[it]?.complete(bytes ?: -1L) }
                }
            }
        }
    }

    private fun registerReceiver() {
        if (receiverRegistered) return
        ContextCompat.registerReceiver(
            getApplication(),
            receiver,
            IntentFilter(AppConfig.BROADCAST_ACTION_ACTIVITY),
            Utils.receiverFlags(),
        )
        receiverRegistered = true
        MessageUtil.sendMsg2Service(getApplication(), AppConfig.MSG_REGISTER_CLIENT, "")
    }

    override fun onCleared() {
        if (networkCallbackRegistered) {
            runCatching { connectivity()?.unregisterNetworkCallback(networkCallback) }
            networkCallbackRegistered = false
        }
        if (receiverRegistered) {
            runCatching { getApplication<Application>().unregisterReceiver(receiver) }
            receiverRegistered = false
        }
        super.onCleared()
    }

    private fun cachedUser(): ColituUser? {
        if (!ColituTokenManager.isLoggedIn()) return null
        return ColituUser(
            id = ColituTokenManager.getUserId().orEmpty(),
            email = ColituTokenManager.getUserEmail().orEmpty(),
            name = ColituTokenManager.getUserName() ?: "Colitu",
            plan = ColituTokenManager.getPlan(),
            entitlementStatus = ColituTokenManager.getEntitlementStatus(),
        )
    }

    companion object {
        private const val KEY_AUTO_SELECTION = "auto_selection"
        private const val KEY_AUTO_CONNECT = "auto_connect"
        private const val KEY_LAST_TRANSPORT = "last_transport"
        /** v1 (global, no expiry); removed on the first load of [KEY_CONNECT_MEMORY]. */
        private const val KEY_STALLED_TRANSPORT = "stalled_transport"
        /** v1: + server id, the transport that last carried real traffic there (any network). */
        private const val KEY_GOOD_PREFIX = "good_transport_"
        /** ColituConnectMemory: last good server/transport, stalls and penalties per network. */
        private const val KEY_CONNECT_MEMORY = "connect_memory_v2"
        private const val KEY_CLIENT_COUNTRY = "client_country"
        private const val KEY_CLIENT_NETWORK = "client_network"
        /** ColituNetworkRecord: the panel's network token and hints of the last network seen with the VPN off. */
        private const val KEY_NETWORK_RECORD = "network_record"
        private const val OBSERVATION_DELAY_MS = 5_000L
        private const val KEY_CONNECTED_SERVER = "connected_server"
        private const val KEY_SYNCED_SERVER = "synced_server"
        private const val KEY_CAPS_VERSION = "device_caps_version"
        private const val KEY_PINGS = "server_pings"
        private const val KEY_TRIAL_BANNER_DAY = "trial_banner_dismissed_day"
        // Speed budget: one transport (start + traffic check) about 8 s, one
        // server 20 s, a whole automatic connect 45 s over at most 3 servers.
        /** A cold service start on a slow TV stick (interface, Xray, hev) needs a few seconds. */
        private const val START_TIMEOUT_MS = 7_000L
        private const val TRANSPORT_BUDGET_MS = 8_000L
        /** The traffic check never gets less: QUIC/TLS through a fresh tunnel plus the endpoint's TLS. */
        private const val MIN_VERIFY_MS = 4_000L
        private const val VERIFY_TIMEOUT_MS = 6_000L
        /** Another transport (or server) is only started with at least this much budget left. */
        private const val MIN_TRANSPORT_MS = 4_000L
        private const val SERVER_BUDGET_MS = 20_000L
        private const val CONNECT_BUDGET_MS = 45_000L
        private const val MAX_SERVERS_PER_CONNECT = 3
        /** The connect never waits longer than this for the warm spare's profile. */
        private const val SPARE_WAIT_MS = 2_000L
        /** Automatic reconnects after an unexpected drop, then the error. */
        private val RECONNECT_DELAYS_MS = longArrayOf(2_000L, 5_000L, 15_000L)
        /** Failures worth another automatic reconnect (not the account, plan or device pause). */
        private val RECONNECT_RETRY_CODES = setOf(
            "UNREACHABLE", "VERIFY_FAILED", "TUNNEL_TIMEOUT", "ENGINE_FAILED", "TUN_FAILED",
            "NETWORK_ERROR", "TIMEOUT", "IO_ERROR", "SERVER_UNAVAILABLE", "SERVICE_UNAVAILABLE", "CONFIG_NOT_READY",
        )
        /** Direct (outside the tunnel) reachability when the system did not validate the network. */
        private val DIRECT_CHECK_HOSTS = listOf("cp.cloudflare.com" to 443, "www.msftconnecttest.com" to 80)
        private const val DIRECT_CHECK_TIMEOUT_MS = 1_500
        private const val STOP_TIMEOUT_MS = 4_000L
        private const val PROBE_TIMEOUT_MS = 4_500L
        private const val TEARDOWN_SETTLE_MS = 600L
        private const val STALL_PROBE_INTERVAL_MS = 5_000L
        private const val STALL_PROBE_TIMEOUT_MS = 4_000L
        private const val STALL_PROBE_FAILURES = 3
        /** Misses of a primary whose spare is known dead before a reconnect. */
        private const val KNOWN_DEAD_SPARE_FAILURES = 2
        /** Parallel connect: after the spare passed first, the primary still gets this long. */
        private const val PARALLEL_GRACE_MS = 1_500L
        /** TCP transports are watched every STALL_PROBE_INTERVAL_MS this long after the connect, then less often. */
        private const val TCP_WATCH_FAST_MS = 90_000L
        private const val TCP_WATCH_SLOW_MS = 30_000L
        private const val AUTO_SWITCH_MIN_GAP_MS = 60_000L
        private const val MID_SESSION_STALL_PENALTY_MS = 10 * 60_000L
    }
}

/**
 * Runs a repository call and turns an unexpected exception (malformed JSON,
 * a type the parser did not expect) into a failed result instead of a crash.
 */
suspend fun <T> safeCall(block: suspend () -> Result<T>): Result<T> = try {
    block()
} catch (e: kotlinx.coroutines.CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}

/** active / trialing / expired / inactive, like planStatusOf on iOS. */
fun planStatusOf(user: ColituUser?, subscription: ColituSubscription?): String {
    val raw = (user?.entitlementStatus ?: subscription?.status ?: "inactive").lowercase()
    val status = when (raw) {
        "active" -> "active"
        "trialing", "trial_active" -> "trialing"
        "expired" -> "expired"
        // The free plan's 10 GB are used up until the next month.
        "quota_exceeded" -> "quota"
        else -> "inactive"
    }
    val expires = (user?.expiresAt ?: subscription?.expiresAt)?.let { runCatching { Instant.parse(it) }.getOrNull() }
    // Server time: TV sticks without a clock battery often run days off.
    if ((status == "active" || status == "trialing") && expires != null && expires.isBefore(ColituClock.now())) return "expired"
    return status
}

/** Plans further out than this are shown as "no expiry", like on iOS. */
private const val LIFETIME_DAYS = 3650L

/** Short form for one-line rows: "211 days left" or "No expiry". */
fun planLeftOf(expires: Instant): String {
    val left = java.time.Duration.between(ColituClock.now(), expires)
    if (left.toDays() > LIFETIME_DAYS) return ColituLoc["plan.lifetime"]
    return ColituLoc.format("plan.left", "left" to leftText(left))
}

private fun leftText(left: java.time.Duration): String =
    if (left.toDays() >= 1) ColituLoc.count("day", left.toDays().toInt())
    else ColituLoc.count("hour", left.toHours().toInt().coerceAtLeast(1))

fun planNameOf(user: ColituUser?, subscription: ColituSubscription?): String {
    return when (planStatusOf(user, subscription)) {
        "inactive" -> ColituLoc["plan.none"]
        "trialing" -> ColituLoc["plan.trialName"]
        else -> if (isFreePlan(user)) ColituLoc["plan.freeName"] else subscription?.planName?.takeIf { it.isNotBlank() }
            ?: user?.plan?.takeIf { it.isNotBlank() && it != "inactive" }
            ?: "Colitu VPN"
    }
}

/** The free plan (10 GB a month); its entitlement renews every month. */
fun isFreePlan(user: ColituUser?): Boolean = user?.plan.equals("Free", ignoreCase = true)

fun planDetailOf(expires: Instant): String {
    val left = java.time.Duration.between(ColituClock.now(), expires)
    if (left.toDays() > LIFETIME_DAYS) return ColituLoc["plan.lifetime"]
    return "${ColituLoc.format("plan.until", "date" to ColituLoc.date(expires))} · ${ColituLoc.format("plan.left", "left" to leftText(left))}"
}

/**
 * Start order of a server's transports, without Android state so it can be
 * unit tested. "Stalled" transports go last.
 */
internal object ColituTransportOrder {
    enum class WatchAction { Fine, SpareCarries, BothDead }

    /**
     * One mid-session probe round: only a dead normal path (the balancer,
     * i.e. primary and spare both) may lead to a reconnect; a dead primary
     * whose spare still carries the traffic never does.
     */
    fun watchAction(spareAttached: Boolean, primaryOk: Boolean, normalOk: Boolean): WatchAction = when {
        !normalOk -> WatchAction.BothDead
        spareAttached && !primaryOk -> WatchAction.SpareCarries
        else -> WatchAction.Fine
    }

    /**
     * The stalled set for one round: [remembered] (memory, mid-session,
     * hints) plus what failed in [thisConnect]. When that leaves at most one
     * of the [offered] transports (and more than one is offered), the marks
     * describe a bad network rather than bad transports: they are ignored
     * for this round and only [thisConnect] counts (default rank order).
     */
    /**
     * Start order of a round whose marks were ignored ([effectiveStalled]):
     * what failed in this round last, what carried traffic on this network
     * lately ([worked]) first, then unmarked transports, then the ignored
     * marks; preference order inside each group.
     */
    fun lockedOrder(configs: List<ColituVpnConfig>, remembered: Set<String>, thisConnect: Set<String>, worked: Set<String>): List<ColituVpnConfig> =
        configs.sortedWith(
            compareBy<ColituVpnConfig> { it.protocolType in thisConnect }
                .thenBy { it.protocolType !in worked }
                .thenBy { it.protocolType in remembered }
                .thenBy { rankOf(it.protocolType, emptySet()) },
        )

    fun effectiveStalled(offered: Collection<String>, remembered: Set<String>, thisConnect: Set<String>): Set<String> {
        val all = remembered + thisConnect
        val distinct = offered.toSet()
        return if (distinct.size >= 2 && (distinct - all).size <= 1) thisConnect else all
    }

    /** Store key: wall-clock ms until which [protocol] goes last on [serverId] after a mid-session stall. */
    fun midSessionStallKey(serverId: String, protocol: String) = "stall_until_$serverId|$protocol"

    /** Penalty for a transport that already worked on this network: blocks of one transport often last a minute or two. */
    const val PROVEN_STALL_PENALTY_MS = 90_000L

    /**
     * How long a mid-session stall puts a transport last on its server. A
     * transport proven on this network gets [PROVEN_STALL_PENALTY_MS]: when it
     * is the only one that works there (Hysteria2 where DPI freezes every TCP
     * transport), a long penalty kept the tunnel on freezing fallbacks for
     * minutes after a short UDP block (fault lab 2026-10-10). Others get [full].
     */
    fun midSessionPenaltyMs(proven: Boolean, full: Long): Long = if (proven) minOf(PROVEN_STALL_PENALTY_MS, full) else full

    fun stalled(connectTime: String?, midSession: String?): Set<String> = setOfNotNull(connectTime, midSession)

    fun rankOf(protocol: String?, stalled: Set<String>) =
        (XrayMobileAdapter.transportRank[protocol] ?: 3) + if (protocol in stalled) 10 else 0

    fun byRank(configs: List<ColituVpnConfig>, stalled: Set<String>) = configs.sortedBy { rankOf(it.protocolType, stalled) }

    /**
     * The transport that last worked first (no probe), unless it is not
     * Hysteria2 and Hysteria2 is on offer and not stalled; null means the
     * probe (or [byRank] without one) decides.
     */
    fun remembered(configs: List<ColituVpnConfig>, lastGood: String?, stalled: Set<String>): List<ColituVpnConfig>? {
        val preferred = configs.firstOrNull { it.protocolType == lastGood && it.protocolType !in stalled } ?: return null
        // Hysteria2 is the fastest transport: a remembered TCP transport must
        // not keep it from being tried again, so the probe decides whenever
        // Hysteria2 is on offer and did not stall last time.
        val hysteriaOffered = configs.any { it.protocolType == "hysteria2" && it.protocolType !in stalled }
        if (preferred.protocolType != "hysteria2" && hysteriaOffered) return null
        return listOf(preferred) + byRank(configs.filter { it !== preferred }, stalled)
    }
}
