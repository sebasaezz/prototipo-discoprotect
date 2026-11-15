package cl.innova.nfc.reader

import cl.discoprotect.ui.Incident
import java.time.Clock
import java.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import okhttp3.HttpUrl

internal enum class ServerState { Unconfigured, Unchecked, Connected, Unreachable }
internal enum class SetupTask { Checking, SigningIn }

internal sealed interface ScanState {
    data object Ready : ScanState
    data object Reading : ScanState
    data object Resolving : ScanState
    data class Found(val profile: HealthProfile, val source: Source) : ScanState
    data object UnknownLocal : ScanState
    data class NfcError(val message: String) : ScanState
    data class RemoteError(val problem: ApiProblem) : ScanState
}

internal enum class Source { Server, Local }

internal data class ReaderUiState(
    val address: String = "",
    val configuring: Boolean = true,
    val server: ServerState = ServerState.Unconfigured,
    val task: SetupTask? = null,
    val signedIn: Boolean = false,
    val events: List<StaffEvent> = emptyList(),
    val event: StaffEvent? = null,
    val local: Boolean = false,
    val notice: String? = null,
    val scan: ScanState = ScanState.Ready,
    val debug: NfcDebug = NfcDebug(),
    val incidentsVisible: Boolean = false,
    val incidentsLoading: Boolean = false,
    val incidents: List<Incident>? = null,
    val incidentProblem: ApiProblem? = null,
) {
    val canContinue get() = signedIn && event != null && server == ServerState.Connected
    val canScan get() = !configuring && !incidentsVisible && (local || signedIn && event != null) && scan == ScanState.Ready
}

/** Coordinates foreground work. All public methods and state updates run on the UI dispatcher. */
internal class ReaderController(
    private val scope: CoroutineScope,
    private val allowHttp: Boolean,
    storedAddress: String?,
    private val saveAddress: (String) -> Unit,
    private val apiFor: (HttpUrl) -> StaffApi,
    private val profiles: ProfileRepository = ProfileRepository(),
    private val clock: Clock = Clock.systemUTC(),
) {
    private var address = storedAddress?.let { serverAddress(it, allowHttp) }
    private var api = address?.let(apiFor)
    private var session: StaffSession? = null
    private var operation: Job? = null
    private var expiry: Job? = null
    private var generation = 0L

    private val mutable = MutableStateFlow(ReaderUiState(
        address = address?.toString().orEmpty(),
        server = if (address == null) ServerState.Unconfigured else ServerState.Unchecked,
    ))
    val state = mutable.asStateFlow()
    private var ui: ReaderUiState
        get() = mutable.value
        set(value) { mutable.value = value }

    fun configure() {
        cancelWork()
        ui = ui.copy(configuring = true, local = false, scan = ScanState.Ready, debug = NfcDebug(), notice = null,
            incidentsVisible = false, incidents = null, incidentProblem = null)
        checkExpiry()
    }

    fun check(input: String) {
        val target = serverAddress(input, allowHttp)
        if (target == null) {
            ui = ui.copy(notice = if (allowHttp) "Ingresa una URL HTTP o HTTPS con la IP del computador y el puerto."
                else "Ingresa una URL HTTPS con la dirección del servidor.")
            return
        }
        cancelWork()
        if (target != address) {
            clearSession()
            address = target
            api = apiFor(target)
            saveAddress(target.toString())
        }
        checkExpiry()
        val remote = requireNotNull(api)
        ui = ui.copy(address = target.toString(), server = ServerState.Unchecked,
            task = SetupTask.Checking, notice = null, scan = ScanState.Ready, debug = NfcDebug())
        work {
            try {
                remote.health()
                current()
                ui = ui.copy(server = ServerState.Connected)
                val active = session
                if (active != null) loadEvents(remote, active, this)
            } catch (failure: ApiFailure) {
                current()
                setupFailure(failure.problem)
            } finally {
                if (isCurrent) ui = ui.copy(task = null)
            }
        }
    }

    fun login(pin: String) {
        val remote = api ?: return
        if (ui.task != null || ui.server != ServerState.Connected) return
        if (pin.length !in 4..128) {
            ui = ui.copy(notice = "Ingresa el PIN de guardia configurado en el servidor.")
            return
        }
        cancelWork()
        clearSession()
        ui = ui.copy(task = SetupTask.SigningIn, notice = null)
        work {
            try {
                val active = remote.login(pin)
                current()
                if (!active.expiresAt.isAfter(clock.instant())) {
                    expire()
                    return@work
                }
                startSession(active)
                ui = ui.copy(signedIn = true)
                loadEvents(remote, active, this)
            } catch (failure: ApiFailure) {
                current()
                if (failure.problem == ApiProblem.Unauthorized && session == null) {
                    ui = ui.copy(notice = "El servidor rechazó el PIN de guardia.")
                } else {
                    setupFailure(failure.problem)
                }
            } finally {
                if (isCurrent) ui = ui.copy(task = null)
            }
        }
    }

    private suspend fun loadEvents(remote: StaffApi, active: StaffSession, request: Work) {
        val events = remote.events(active)
        request.current()
        val selection = events.firstOrNull { it.id == ui.event?.id } ?: events.singleOrNull()
        ui = ui.copy(events = events, event = selection,
            notice = if (events.isEmpty()) "La cuenta no tiene eventos autorizados." else null)
    }

    fun selectEvent(id: String) {
        val event = ui.events.firstOrNull { it.id == id } ?: return
        if (ui.task != null || !checkExpiry()) return
        cancelWork()
        ui = ui.copy(event = event, scan = ScanState.Ready, debug = NfcDebug(), notice = null,
            incidents = null, incidentProblem = null)
        if (ui.incidentsVisible) refreshIncidents()
    }

    fun continueReading() {
        if (!checkExpiry() || ui.task != null || ui.event == null || !ui.incidentsVisible && !ui.canContinue) return
        if (ui.incidentsVisible) cancelWork()
        ui = ui.copy(configuring = false, notice = null, incidentsVisible = false, incidents = null, incidentProblem = null)
    }

    fun openIncidents() {
        if (ui.local || ui.task != null || ui.event == null || !checkExpiry() || ui.incidentsVisible) return
        cancelWork()
        ui = ui.copy(configuring = false, incidentsVisible = true, scan = ScanState.Ready, debug = NfcDebug(),
            incidents = null, incidentProblem = null)
        refreshIncidents()
    }

    fun refreshIncidents() {
        if (!ui.incidentsVisible || ui.incidentsLoading || !checkExpiry()) return
        val remote = api ?: return
        val active = session ?: return
        val event = ui.event ?: return
        ui = ui.copy(incidentsLoading = true)
        work {
            try {
                val incidents = remote.incidents(active, event)
                current()
                if (checkExpiry()) ui = ui.copy(incidents = incidents, incidentProblem = null, server = ServerState.Connected)
            } catch (failure: ApiFailure) {
                current()
                if (failure.problem == ApiProblem.Unauthorized) expire()
                else ui = ui.copy(
                    incidentProblem = failure.problem,
                    incidents = ui.incidents.takeUnless { failure.problem == ApiProblem.Forbidden },
                    server = if (failure.problem.isConnectivityError()) ServerState.Unreachable else ServerState.Connected,
                )
            } finally {
                if (isCurrent) ui = ui.copy(incidentsLoading = false)
            }
        }
    }

    fun useLocal() {
        cancelWork()
        ui = ui.copy(local = true, configuring = false, notice = null, scan = ScanState.Ready, debug = NfcDebug(),
            incidentsVisible = false, incidents = null, incidentProblem = null)
    }

    fun logout() {
        cancelWork()
        clearSession()
        ui = ui.copy(configuring = true, local = false, notice = null, scan = ScanState.Ready, debug = NfcDebug())
    }

    fun newScan() {
        cancelWork()
        ui = ui.copy(scan = ScanState.Ready, debug = NfcDebug())
        if (!ui.local) checkExpiry()
    }

    fun pause() {
        cancelWork()
        ui = ui.copy(scan = ScanState.Ready, debug = NfcDebug())
    }

    fun resume() {
        if (!ui.local && checkExpiry() && ui.incidentsVisible) refreshIncidents()
    }

    fun scan(read: suspend () -> ReadResult) {
        if (!ui.canScan || (!ui.local && !checkExpiry())) return
        val local = ui.local
        val remote = api
        val active = session
        val event = ui.event
        ui = ui.copy(scan = ScanState.Reading, debug = NfcDebug(tagDetected = true))
        work {
            val result = read()
            current()
            ui = ui.copy(debug = result.debug)
            when (val outcome = result.outcome) {
                is NfcOutcome.Failure -> ui = ui.copy(scan = ScanState.NfcError(outcome.message))
                is NfcOutcome.Identity -> {
                    if (local) {
                        ui = ui.copy(scan = profiles.local(outcome.id)?.let { ScanState.Found(it, Source.Local) }
                            ?: ScanState.UnknownLocal)
                    } else if (checkExpiry()) {
                        ui = ui.copy(scan = ScanState.Resolving)
                        try {
                            val profile = profiles.remote(requireNotNull(remote), requireNotNull(active),
                                requireNotNull(event), outcome.id)
                            current()
                            if (checkExpiry()) ui = ui.copy(scan = ScanState.Found(profile, Source.Server),
                                server = ServerState.Connected)
                        } catch (failure: ApiFailure) {
                            current()
                            val problem = failure.problem
                            if (problem == ApiProblem.Unauthorized) {
                                ui = ui.copy(server = ServerState.Connected)
                                expire()
                            }
                            else ui = ui.copy(scan = ScanState.RemoteError(problem),
                                server = if (problem.isConnectivityError()) ServerState.Unreachable else ServerState.Connected)
                        }
                    }
                }
            }
        }
    }

    private fun cancelWork() {
        generation++
        operation?.cancel()
        operation = null
        ui = ui.copy(task = null, incidentsLoading = false)
    }

    private fun work(block: suspend Work.() -> Unit) {
        val request = Work(++generation)
        operation = scope.launch { request.block() }
    }

    private inner class Work(private val number: Long) {
        val isCurrent get() = generation == number
        suspend fun current() {
            coroutineContext.ensureActive()
            if (!isCurrent) throw CancellationException()
        }
    }

    private fun startSession(active: StaffSession) {
        session = active
        expiry = scope.launch {
            delay(Duration.between(clock.instant(), active.expiresAt).toMillis().coerceAtLeast(0))
            if (session === active) {
                if (ui.local) {
                    clearSession()
                } else {
                    cancelWork()
                    expire()
                }
            }
        }
    }

    private fun clearSession() {
        expiry?.cancel()
        expiry = null
        session = null
        ui = ui.copy(signedIn = false, events = emptyList(), event = null,
            incidentsVisible = false, incidents = null, incidentsLoading = false, incidentProblem = null)
    }

    private fun checkExpiry(): Boolean {
        val active = session ?: return false
        if (active.expiresAt.isAfter(clock.instant())) return true
        expire()
        return false
    }

    private fun expire() {
        clearSession()
        ui = ui.copy(configuring = true, scan = ScanState.Ready,
            notice = "La sesión venció o dejó de ser válida. Vuelve a iniciar sesión.")
    }

    private fun setupFailure(problem: ApiProblem) {
        when (problem) {
            ApiProblem.Unauthorized -> expire()
            ApiProblem.Forbidden -> ui = ui.copy(events = emptyList(), event = null, notice = problem.description())
            else -> ui = ui.copy(
                notice = problem.description(),
                server = if (problem.isConnectivityError() || ui.task == SetupTask.Checking && !ui.signedIn)
                    ServerState.Unreachable else ui.server,
            )
        }
    }
}

internal class ProfileRepository(private val localProfiles: HealthRepository = HealthRepository()) {
    fun local(id: String): HealthProfile? = localProfiles.find(id)
    suspend fun remote(api: StaffApi, session: StaffSession, event: StaffEvent, id: String): HealthProfile =
        api.resolve(session, event, id)
}

internal fun ApiProblem.isConnectivityError(): Boolean =
    this in setOf(ApiProblem.Network, ApiProblem.Timeout, ApiProblem.Unavailable, ApiProblem.Server)

internal fun ApiProblem.description(): String = when (this) {
    ApiProblem.Network -> "No se pudo conectar al servidor. Revisa la red Wi-Fi, la dirección y el firewall del computador."
    ApiProblem.Timeout -> "El servidor tardó demasiado en responder. Comprueba la conexión y vuelve a intentar."
    ApiProblem.Unavailable -> "El servidor respondió, pero su base de datos no está disponible."
    ApiProblem.Unauthorized -> "La sesión dejó de ser válida. Vuelve a iniciar sesión."
    ApiProblem.Forbidden -> "La cuenta no tiene permiso para consultar este evento o recurso."
    ApiProblem.NotFound -> "La credencial no tiene una ficha disponible para el evento seleccionado."
    ApiProblem.InvalidId -> "El identificador recibido no tiene el formato esperado."
    ApiProblem.InvalidResponse -> "La respuesta no coincide con la API de Discoprotect. Revisa la dirección y el servidor."
    ApiProblem.Server -> "El servidor no pudo responder a la consulta. Vuelve a intentar."
}
