package com.v2ray.ang.colitu.app

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
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
import com.v2ray.ang.colitu.data.ColituDeviceOutlook
import com.v2ray.ang.colitu.data.ColituDevicePause
import com.v2ray.ang.colitu.data.ColituMultihop
import com.v2ray.ang.colitu.data.ColituRotation
import com.v2ray.ang.colitu.data.ColituRotationPreference
import com.v2ray.ang.colitu.data.ColituRotationStatus
import com.v2ray.ang.colitu.data.ColituSplitTunnel
import com.v2ray.ang.colitu.data.ColituRuBypass
import com.v2ray.ang.colitu.data.ColituServer
import com.v2ray.ang.colitu.data.ColituSubscription
import com.v2ray.ang.colitu.data.ColituUser
import com.v2ray.ang.colitu.data.ColituVpnConfig
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant

enum class VpnStatus { Disconnected, Connecting, Connected, Disconnecting }

enum class ConnectPhase { Idle, Preparing, Probing, Starting, Verifying, Switching }

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
     * Last measured ping per server id. Measured only while the VPN is off
     * (through the tunnel it would be node-to-node) and kept between runs.
     */
    var pings by mutableStateOf(cachedPings())
        private set
    private var pingJob: Job? = null

    fun pingOf(server: ColituServer): Int? = pings[server.id]

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
    private var pendingVerify: CompletableDeferred<Long>? = null
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
    val recommendedServer: ColituServer? get() = servers.firstOrNull { it.isRecommended && it.isAvailable } ?: servers.firstOrNull { it.isAvailable }
    /** Nodes first, then the multihop routes. */
    val allServers: List<ColituServer> get() = servers + routes
    val selectedServer: ColituServer? get() = allServers.firstOrNull { it.id == selectedServerId }
    val effectiveServer: ColituServer? get() = if (autoSelection) recommendedServer else selectedServer ?: recommendedServer
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
        registerReceiver()
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
        coroutineScope {
            val policy = async { refreshPolicy() }
            val list = async { safeCall { ColituServerRepository.fetchServers() } }
            val me = async { runCatching { ColituAuthRepository.fetchMe() } }
            val sub = async { safeCall { ColituBillingRepository.fetchSubscription() } }
            val rotationPref = async { safeCall { ColituRotationRepository.fetch() } }
            list.await().fold(
                onSuccess = { response ->
                    servers = response.servers
                    routes = response.multihop
                    offline = false
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
    }

    /** Re-measures the pings unless a tunnel is up or starting. */
    fun measurePings() {
        if (status != VpnStatus.Disconnected || pingJob?.isActive == true) return
        // A route's ping is measured to its entry node only ("Estimated · +1 hop").
        val list = servers + routes
        pingJob = viewModelScope.launch {
            val measured = com.v2ray.ang.colitu.data.ColituLatency.measureAll(list)
            if (measured.isEmpty() || status != VpnStatus.Disconnected) return@launch
            pings = pings + measured
            val json = com.google.gson.JsonObject().apply { pings.forEach { (id, ms) -> addProperty(id, ms) } }
            store.encode(KEY_PINGS, json.toString())
        }
    }

    private fun cachedPings(): Map<String, Int> = runCatching {
        val raw = store.decodeString(KEY_PINGS) ?: return emptyMap()
        com.google.gson.JsonParser.parseString(raw).asJsonObject.entrySet()
            .mapNotNull { (id, value) -> value.takeIf { it.isJsonPrimitive }?.asInt?.let { id to it } }
            .toMap()
    }.getOrDefault(emptyMap())

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
     * connect. Each one is left out of the next attempt until every transport
     * the server offers has been tried; a new connect by the user starts over.
     */
    private val stalledThisConnect = mutableSetOf<String>()

    /** Transports the server offered on the last fresh profile. */
    private var offeredTransports: Set<String> = emptySet()

    private fun startConnectFlow(fresh: Boolean = false, continuing: Boolean = false) {
        if (!continuing) stalledThisConnect.clear()
        connectJob?.cancel()
        verifyJob?.cancel()
        userStopped = false
        cancelledStart = false
        connectJob = viewModelScope.launch { runConnect(fresh) }
    }

    /**
     * Connect as fast as the last good state allows: the cached profile and
     * the transport that worked last time start right away; probing and a
     * fresh profile are only needed when that fails. [fresh] forces both.
     */
    private suspend fun runConnect(fresh: Boolean = false) {
        val context = getApplication<Application>()
        val begin = android.os.SystemClock.elapsedRealtime()
        status = VpnStatus.Connecting
        phase = ConnectPhase.Preparing
        error = null
        val server = effectiveServer
        if (server == null) {
            fail(if (servers.isEmpty()) "NO_SERVERS" else "NO_SERVER_SELECTED")
            return
        }
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
                fail(it.message)
                return
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

        val cached = if (fresh || !synced) null else {
            val stored = if (isRoute) ColituServerRepository.cachedRouteCandidates(server.id) else ColituServerRepository.cachedConfigCandidates(server.id)
            stored?.let { restrictFor(server, it) }?.takeIf { it.isNotEmpty() }
        }
        if (cached != null) {
            if (tryStart(context, server, orderForStart(server, cached, probe = false), begin, fromCache = true)) return
            // The cached profile no longer works (credentials rotated, node
            // changed): fall through to a fresh profile and a full probe.
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
            fail(it.message)
            return
        }
        // A multihop route and a rotating exit run on VLESS only.
        val offered = restrictFor(server, fetched)
        if (offered.isEmpty()) {
            fail(if (isRoute) "MULTIHOP_NEEDS_VLESS" else "ROTATION_NEEDS_VLESS")
            return
        }
        offeredTransports = offered.mapNotNull { it.protocolType }.toSet()
        // Leave out what already stalled in this connect; when nothing else is
        // left the whole list is tried once more.
        val configs = offered.filter { it.protocolType !in stalledThisConnect }.ifEmpty { offered }

        if (tryStart(context, server, orderForStart(server, configs, probe = true), begin, fromCache = false)) return
        fail("UNREACHABLE")
    }

    private suspend fun fetchCandidates(server: ColituServer): Result<List<ColituVpnConfig>> =
        if (server.isMultihop) ColituServerRepository.fetchRouteCandidates(server.id) else ColituServerRepository.fetchConfigCandidates()

    /** VLESS only while connecting over a multihop route or while the exit IP rotates; the other transports cannot be carried through the mesh. */
    private fun restrictFor(server: ColituServer, configs: List<ColituVpnConfig>): List<ColituVpnConfig> =
        if (server.isMultihop || rotationActive) ColituMultihop.restrictToVless(configs) else configs

    /**
     * The transport that last worked on this server first (no probe), unless
     * it is not Hysteria2 and Hysteria2 is on offer; otherwise the probe
     * decides. The rest follow in preference order.
     */
    private suspend fun orderForStart(server: ColituServer, configs: List<ColituVpnConfig>, probe: Boolean): List<ColituVpnConfig> {
        val stalled = setOfNotNull(store.decodeString(KEY_STALLED_TRANSPORT))
        val lastGood = store.decodeString(KEY_GOOD_PREFIX + server.id)
        val preferred = configs.firstOrNull { it.protocolType == lastGood && it.protocolType !in stalled }
        // Hysteria2 is the fastest transport: a remembered TCP transport must
        // not keep it from being tried again, so the probe decides whenever
        // Hysteria2 is on offer and did not stall last time.
        val hysteriaOffered = configs.any { it.protocolType == "hysteria2" && it.protocolType !in stalled }
        if (preferred != null && (preferred.protocolType == "hysteria2" || !hysteriaOffered)) {
            return listOf(preferred) + configs.filter { it !== preferred }.sortedBy { rankOf(it.protocolType, stalled) }
        }
        if (!probe) return configs.sortedBy { rankOf(it.protocolType, stalled) }
        phase = ConnectPhase.Probing
        return rank(configs, stalled)
    }

    private fun rankOf(protocol: String?, stalled: Set<String>) =
        (XrayMobileAdapter.transportRank[protocol] ?: 3) + if (protocol in stalled) 10 else 0

    /**
     * Starts the transports in [order] until one comes up; every one is tried.
     * The UI shows "connected" as soon as the tunnel is up; the real-traffic
     * check runs afterwards and moves to another transport if needed.
     */
    private suspend fun tryStart(
        context: Application,
        server: ColituServer,
        order: List<ColituVpnConfig>,
        begin: Long,
        fromCache: Boolean,
    ): Boolean {
        for ((attempt, config) in order.withIndex()) {
            phase = if (attempt == 0) ConnectPhase.Starting else ConnectPhase.Switching
            if (attempt > 0) showToast(ColituLoc["home.switchingTransport"], error = false)
            val guid = ColituVpnRepository.importRuntimeConfig(context, config).getOrElse {
                LogUtil.e(AppConfig.TAG, "Colitu: import failed for ${config.protocolType}")
                continue
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
                continue
            }
            // The route id stays the identity of a multihop choice (list marker, remembered transport).
            val actual = if (server.isMultihop) server else servers.firstOrNull { it.id == config.serverId } ?: server
            onConnected(actual, config, guid)
            LogUtil.w(
                AppConfig.TAG,
                "Colitu: connected via ${config.protocolType} in ${android.os.SystemClock.elapsedRealtime() - begin} ms (cached profile: $fromCache)",
            )
            verifyJob?.cancel()
            verifyJob = viewModelScope.launch { verifyAfterConnect(actual, config, fromCache) }
            return true
        }
        return false
    }

    /**
     * Real traffic through the tunnel, after "connected" is already shown.
     * A transport that carries nothing is remembered as stalled and the
     * connection is rebuilt from a fresh profile with a full probe, without
     * it, until every transport the server offers has been tried.
     */
    private suspend fun verifyAfterConnect(server: ColituServer, config: ColituVpnConfig, fromCache: Boolean) {
        val delayMs = verifyTunnel()
        if (!connected) return
        if (delayMs >= 0) {
            store.encode(KEY_GOOD_PREFIX + server.id, config.protocolType)
            if (store.decodeString(KEY_STALLED_TRANSPORT) == config.protocolType) store.removeValueForKey(KEY_STALLED_TRANSPORT)
            // Keep the cached profile current for the next instant connect.
            if (fromCache) safeCall { fetchCandidates(server) }
            return
        }
        LogUtil.w(AppConfig.TAG, "Colitu: ${config.protocolType} is up but carries no traffic")
        store.removeValueForKey(KEY_GOOD_PREFIX + server.id)
        store.encode(KEY_STALLED_TRANSPORT, config.protocolType)
        config.protocolType?.let { stalledThisConnect += it }
        // A cached profile may be stale, so a fresh one is always worth one try.
        val untried = fromCache || offeredTransports.isEmpty() || (offeredTransports - stalledThisConnect).isNotEmpty()
        if (untried) {
            showToast(ColituLoc["home.switchingTransport"], error = false)
            startConnectFlow(fresh = true, continuing = true)
        } else {
            showToast(ColituLoc["err.verify"], error = true)
        }
    }

    private fun onConnected(server: ColituServer, config: ColituVpnConfig, guid: String) {
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
        showToast(ColituLoc.format("locations.switched", "server" to titleOf(server)), error = false)
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

    /** Real request through the running tunnel; the delay in ms or -1. */
    private suspend fun verifyTunnel(): Long {
        val result = CompletableDeferred<Long>().also { pendingVerify = it }
        val id = ++verifyId
        MessageUtil.sendMsg2Service(getApplication(), AppConfig.MSG_COLITU_VERIFY, id.toString())
        val delayMs = withTimeoutOrNull(VERIFY_TIMEOUT_MS) { result.await() } ?: -1L
        pendingVerify = null
        return delayMs
    }

    fun disconnect() {
        // While connecting the service may already be starting although it
        // has not reported "running" yet; stop it regardless.
        val starting = status == VpnStatus.Connecting || pendingStart != null
        connectJob?.cancel()
        verifyJob?.cancel()
        pendingStart = null
        userStopped = true
        if (starting) cancelledStart = true
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
                        // Stopped from outside the app (notification, another VPN).
                        status = VpnStatus.Disconnected
                        clearSession()
                        if (!userStopped) showToast(ColituLoc["info.disconnected"], error = false)
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
                    if (id == verifyId) pendingVerify?.complete(delayMs ?: -1L)
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
        private const val KEY_STALLED_TRANSPORT = "stalled_transport"
        /** + server id: the transport that last carried real traffic there. */
        private const val KEY_GOOD_PREFIX = "good_transport_"
        private const val KEY_CONNECTED_SERVER = "connected_server"
        private const val KEY_SYNCED_SERVER = "synced_server"
        private const val KEY_CAPS_VERSION = "device_caps_version"
        private const val KEY_PINGS = "server_pings"
        private const val KEY_TRIAL_BANNER_DAY = "trial_banner_dismissed_day"
        private const val START_TIMEOUT_MS = 20_000L
        private const val STOP_TIMEOUT_MS = 4_000L
        private const val VERIFY_TIMEOUT_MS = 12_000L
        private const val PROBE_TIMEOUT_MS = 4_500L
        private const val TEARDOWN_SETTLE_MS = 600L
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
