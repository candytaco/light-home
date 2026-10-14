package com.candytaco.lighthome.ha

import com.candytaco.lighthome.DevDefaults
import com.candytaco.lighthome.net.CfAccessException
import com.candytaco.lighthome.net.ServerConfig
import com.candytaco.lighthome.storage.CredentialStore
import com.thelightphone.sdk.LightConnectivity
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

sealed interface SessionStatus {
    /** Not running (tool is in the background). */
    data object Idle : SessionStatus

    data object Connecting : SessionStatus

    /** The phone has no network; the session reconnects as soon as it's back. */
    data object Offline : SessionStatus

    data class Connected(val haVersion: String) : SessionStatus

    /** A transient failure; the session retries on its own. */
    data class Retrying(val message: String) : SessionStatus

    /** No server configured yet. */
    data class NeedsSetup(val message: String) : SessionStatus

    /** HA wants a username/password (no refresh token, or it was revoked). */
    data class NeedsSignIn(val message: String) : SessionStatus

    /** Cloudflare Access refused the request; needs a new setup code or policy change. */
    data class Blocked(val message: String) : SessionStatus
}

/**
 * The tool's single connection to Home Assistant, shared by every screen.
 *
 * Screens call [start] when they show and [stop] when the tool goes to the background, so the
 * socket only exists while the tool is on screen. While running, it keeps [entities] live via
 * `subscribe_entities` and reconnects with backoff after transient failures.
 */
class HaSession(
    private val store: CredentialStore,
    private val connectivity: LightConnectivity,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val client = HttpClient(OkHttp) {
        followRedirects = false // keep Cloudflare Access 302s visible (see classifyCfAccess)
        install(HttpTimeout)
        install(WebSockets)
        engine {
            config {
                connectTimeout(15, TimeUnit.SECONDS)
                pingInterval(20, TimeUnit.SECONDS)
            }
        }
    }

    private val _status = MutableStateFlow<SessionStatus>(SessionStatus.Idle)
    val status: StateFlow<SessionStatus> = _status.asStateFlow()

    private val _entities = MutableStateFlow<Map<String, EntityState>>(emptyMap())
    val entities: StateFlow<Map<String, EntityState>> = _entities.asStateFlow()

    private val _registry = MutableStateFlow<Registry?>(null)
    val registry: StateFlow<Registry?> = _registry.asStateFlow()

    private val _homeConfig = MutableStateFlow(HomeConfig())

    /** The user's HA Home panel settings (favorites, summary order). */
    val homeConfig: StateFlow<HomeConfig> = _homeConfig.asStateFlow()

    private val _dashboardData = MutableStateFlow(DashboardData())

    /** The dashboard shown on Home (its card config) and the dashboards to choose from. */
    val dashboardData: StateFlow<DashboardData> = _dashboardData.asStateFlow()

    private val _server = MutableStateFlow<ServerConfig?>(null)

    /** The server in use: saved in settings, else the debug defaults from tool/dev.properties. */
    val server: StateFlow<ServerConfig?> = _server.asStateFlow()

    private val _userName = MutableStateFlow<String?>(null)

    /** The signed-in HA user's display name (`auth/current_user`), once connected. */
    val userName: StateFlow<String?> = _userName.asStateFlow()

    private val _signedIn = MutableStateFlow(false)

    /** True while a refresh token is stored. */
    val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

    @Volatile
    private var tokens: HaTokens? = null

    @Volatile
    private var connection: HaConnection? = null

    private var runJob: Job? = null

    /** Network state, watched only while the session runs. */
    private val online = MutableStateFlow(true)

    @Synchronized
    fun start() {
        if (runJob?.isActive == true) return
        runJob = scope.launch {
            val networkWatch = launch {
                connectivity.observeNetworkStatus().collect { online.value = it.isConnected }
            }
            try {
                runLoop()
            } finally {
                networkWatch.cancel()
            }
        }
    }

    @Synchronized
    fun stop() {
        runJob?.cancel()
        runJob = null
        _status.value = SessionStatus.Idle
    }

    /** Reconnects from scratch, e.g. after a settings change. */
    @Synchronized
    fun restart() {
        stop()
        start()
    }

    private suspend fun currentServer(): ServerConfig? = store.loadServer() ?: DevDefaults.server

    /**
     * Saves a new server address, keeping the Cloudflare key. A different server can't use this
     * server's sign-in, so the HA sign-in is cleared. Returns false when [rawUrl] isn't http(s).
     */
    suspend fun setServerUrl(rawUrl: String): Boolean {
        val current = currentServer()
        val next = ServerConfig.create(
            rawUrl,
            current?.cfAccess?.clientId.orEmpty(),
            current?.cfAccess?.clientSecret.orEmpty(),
        ) ?: return false
        if (next.baseUrl != current?.baseUrl) forgetSignIn()
        saveAndReconnect(next)
        return true
    }

    /** Saves (or with blank values, removes) the Cloudflare Access service token. */
    suspend fun setCfAccess(clientId: String, clientSecret: String) {
        val current = currentServer() ?: return
        saveAndReconnect(ServerConfig.create(current.baseUrl, clientId, clientSecret) ?: return)
    }

    /**
     * Applies a scanned setup code: it defines the server completely (address and Cloudflare key,
     * or no key). A different address clears the HA sign-in. Returns false for an unknown code.
     */
    suspend fun applySetupCode(text: String): Boolean {
        val next = ServerConfig.fromSetupCode(text) ?: return false
        if (next.baseUrl != currentServer()?.baseUrl) forgetSignIn()
        saveAndReconnect(next)
        return true
    }

    /** Revokes the refresh token on the server (best effort), forgets it, and reconnects. */
    suspend fun signOut() {
        withContext(Dispatchers.IO) {
            val refreshToken = tokens?.refreshToken ?: store.loadRefreshToken()
            val server = currentServer()
            if (refreshToken != null && server != null) {
                runCatching { HaAuthApi(client, server).revoke(refreshToken) }
            }
            forgetSignIn()
        }
        restart()
    }

    private suspend fun forgetSignIn() {
        store.clearRefreshToken()
        tokens = null
        _signedIn.value = false
        _userName.value = null
    }

    private suspend fun saveAndReconnect(server: ServerConfig) {
        withContext(Dispatchers.IO) { store.saveServer(server) }
        _server.value = server
        restart()
    }

    /** Sends a service call on the live connection. Throws if not connected. */
    suspend fun callService(domain: String, service: String, entityId: String, data: JsonObject? = null) {
        val live = connection ?: throw HaConnectionClosedException()
        live.callService(domain, service, entityId, data)
    }

    /** Consecutive failed attempts; reset once entities stream in. Drives [backoffMillis]. */
    @Volatile
    private var failures = 0

    private suspend fun runLoop() {
        failures = 0
        while (currentCoroutineContext().isActive) {
            val server = currentServer()
            _server.value = server
            _signedIn.value = tokens != null || store.loadRefreshToken() != null
            if (server == null) {
                _status.value = SessionStatus.NeedsSetup("No server configured")
                return
            }
            if (!online.value) {
                _status.value = SessionStatus.Offline
                online.first { it }
            }
            val api = HaAuthApi(client, server)
            try {
                _status.value = SessionStatus.Connecting
                api.checkReachable()
                val accessToken = obtainAccessToken(api) ?: return
                connectAndStream(server, accessToken)
                // The subscription only ends by throwing; reaching here means the socket closed.
                throw HaConnectionClosedException()
            } catch (e: CancellationException) {
                throw e
            } catch (e: CfAccessException) {
                _status.value = SessionStatus.Blocked(e.failure.message)
                return
            } catch (e: HaAuthRejectedException) {
                // The WebSocket refused the access token: force a refresh on the next attempt.
                tokens = tokens?.copy(expiresAtMillis = 0)
                failures++
                _status.value = SessionStatus.Retrying("Access token refresh in progress…")
                waitBeforeRetry(backoffMillis(failures))
            } catch (e: Exception) {
                failures++
                _status.value = if (online.value) SessionStatus.Retrying(describeError(e)) else SessionStatus.Offline
                waitBeforeRetry(backoffMillis(failures))
            }
        }
    }

    /**
     * Backoff, cut short when the network comes back: a phone leaving a dead zone reconnects at
     * once instead of sitting out the rest of a 30-second wait.
     */
    private suspend fun waitBeforeRetry(millis: Long) {
        withTimeoutOrNull(millis) {
            online.first { !it }
            online.first { it }
        }
    }

    private suspend fun connectAndStream(server: ServerConfig, accessToken: String) {
        val live = HaConnection.open(client, server, accessToken, scope)
        connection = live
        // Losing the network: drop the socket now rather than when the next ping times out (~20 s).
        val dropOnNetworkLoss = scope.launch {
            online.first { !it }
            live.close()
        }
        try {
            _registry.value = loadRegistry(live)
            _homeConfig.value = loadHomeConfig(live)
            _dashboardData.value = loadDashboards(live)
            _userName.value = try {
                (live.command("auth/current_user") as? JsonObject)?.string("name")
            } catch (e: HaCommandException) {
                null
            }
            var first = true
            live.subscribe("subscribe_entities").collect { event ->
                // The first event lists every entity; start from scratch so entities deleted
                // while we were disconnected don't linger.
                _entities.update { current -> applyEntityEvent(if (first) emptyMap() else current, event) }
                if (first) {
                    first = false
                    failures = 0
                    _status.value = SessionStatus.Connected(live.haVersion)
                }
            }
        } finally {
            dropOnNetworkLoss.cancel()
            connection = null
            live.close()
        }
    }

    private suspend fun loadRegistry(live: HaConnection): Registry = coroutineScope {
        val floors = async { live.command("config/floor_registry/list") }
        val areas = async { live.command("config/area_registry/list") }
        val devices = async { live.command("config/device_registry/list") }
        val entities = async { live.command("config/entity_registry/list") }
        Registry.parse(floors.await(), areas.await(), devices.await(), entities.await())
    }

    /** The dashboard list, the saved choice, and that dashboard's config. */
    private suspend fun loadDashboards(live: HaConnection): DashboardData {
        val dashboards = try {
            DashboardData.parseList(live.command("lovelace/dashboards/list"))
        } catch (e: HaCommandException) {
            listOf(DashboardData.DEFAULT_DASHBOARD)
        }
        val selected = store.loadDashboardPath()?.takeIf { path -> dashboards.any { it.urlPath == path } }
        return DashboardData(loadDashboardConfig(live, selected), dashboards, selected)
    }

    /** HA answers `config_not_found` for a dashboard that is auto-generated (has no cards of its own). */
    private suspend fun loadDashboardConfig(live: HaConnection, urlPath: String?): DashboardState = try {
        val config = live.command("lovelace/config") { urlPath?.let { put("url_path", it) } } as? JsonObject
        if (config == null) DashboardState.AutoGenerated else DashboardState.Loaded(config)
    } catch (e: HaCommandException) {
        if (e.code == "config_not_found") DashboardState.AutoGenerated else DashboardState.Failed(e.message ?: e.code)
    }

    /** Switches Home to another dashboard ([urlPath] null = default) and remembers the choice. */
    fun selectDashboard(urlPath: String?) {
        scope.launch {
            store.saveDashboardPath(urlPath)
            _dashboardData.update { it.copy(selectedPath = urlPath, state = DashboardState.Loading) }
            val live = connection ?: return@launch // loaded on the next connect
            val state = try {
                loadDashboardConfig(live, urlPath)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DashboardState.Failed(describeError(e))
            }
            _dashboardData.update { if (it.selectedPath == urlPath) it.copy(state = state) else it }
        }
    }

    /** Home panel settings; defaults when the user never customized Home (or HA predates it). */
    private suspend fun loadHomeConfig(live: HaConnection): HomeConfig = try {
        HomeConfig.parse(live.command("frontend/get_system_data") { put("key", "home") })
    } catch (e: HaCommandException) {
        HomeConfig()
    }

    /**
     * A valid access token: the cached one, else refreshed from the stored refresh token.
     * Returns null (after setting [status] to [SessionStatus.NeedsSignIn]) when there is no
     * usable sign-in; the sign-in screen then drives [startSignIn] / [submitSignIn] / [finishSignIn].
     */
    private suspend fun obtainAccessToken(api: HaAuthApi): String? {
        tokens?.takeUnless { it.needsRefresh(System.currentTimeMillis()) }?.let { return it.accessToken }

        val refreshToken = tokens?.refreshToken ?: store.loadRefreshToken()
        if (refreshToken == null) {
            _status.value = SessionStatus.NeedsSignIn("No Home Assistant sign-in")
            return null
        }
        return try {
            api.refresh(refreshToken).also { tokens = it }.accessToken
        } catch (e: HaAuthRejectedException) {
            forgetSignIn()
            _status.value = SessionStatus.NeedsSignIn("Home Assistant sign-in expired")
            null
        }
    }

    // --- Interactive sign-in (HA login flow), driven by the sign-in screen ---

    private suspend fun signInApi(): HaAuthApi {
        val server = currentServer() ?: throw IllegalStateException("No server configured")
        return HaAuthApi(client, server)
    }

    /** Starts a login flow; the first step is normally a username/password form. */
    suspend fun startSignIn(): LoginStep = withContext(Dispatchers.IO) { signInApi().startLoginFlow() }

    /** Submits one form step. A form with errors means "same step again"; a new form is the next step. */
    suspend fun submitSignIn(flowId: String, values: Map<String, String>): LoginStep =
        withContext(Dispatchers.IO) { signInApi().submitStep(flowId, values) }

    /** Exchanges the flow's auth code for tokens, stores the refresh token and reconnects. */
    suspend fun finishSignIn(authCode: String) {
        withContext(Dispatchers.IO) {
            val fresh = signInApi().exchangeCode(authCode)
            store.saveRefreshToken(fresh.refreshToken)
            tokens = fresh
            _signedIn.value = true
        }
        start()
    }

    private fun backoffMillis(failures: Int): Long = when (failures) {
        1 -> 2_000L
        2 -> 5_000L
        3 -> 10_000L
        else -> 30_000L
    }
}

/** Short, verb-free status text for a failure. */
internal fun describeError(e: Throwable): String = when (e) {
    is CfAccessException -> e.failure.message
    is UnknownHostException -> "Server address not found"
    is ConnectException, is SocketTimeoutException -> "Server unreachable"
    is HaConnectionClosedException -> "Connection lost; reconnection pending"
    is HaHttpException, is HaProtocolException, is HaCommandException -> e.message ?: "Server error"
    is IOException -> "Network error: ${e.message}"
    else -> e.message ?: "Unexpected error"
}
