package cl.innova.nfc.reader

import cl.discoprotect.ui.Incident
import cl.discoprotect.ui.IncidentStatus
import cl.discoprotect.ui.IncidentTriage
import cl.discoprotect.ui.ReviewDecision
import cl.discoprotect.ui.TriageCategory
import cl.discoprotect.ui.TriageUrgency
import cl.discoprotect.ui.currentIncidentSnapshot
import cl.discoprotect.ui.incidentPollDelay
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
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
    val lastIncidentRead: Instant? = null,
    val staffId: String? = null,
    val staffName: String? = null,
    val incidentWriting: Boolean = false,
    val pendingIncident: Boolean = false,
    val incidentNotice: String? = null,
    val triageId: String? = null,
    val triageDetail: TriageDetail? = null,
    val triageLoading: Boolean = false,
    val triageWriting: Boolean = false,
    val triagePending: Boolean = false,
    val triageNotice: String? = null,
) {
    val canContinue get() = signedIn && event != null && server == ServerState.Connected
    val canScan get() = !configuring && !incidentsVisible && (local || signedIn && event != null) && scan == ScanState.Ready
    val incidentsCurrent get() = lastIncidentRead != null && incidentProblem == null && !incidentWriting
    fun actionFor(incident: Incident): IncidentAction? = IncidentAction.entries.firstOrNull {
        it.from == incident.status && (it == IncidentAction.Claim || staffId != null && incident.assignedStaff?.id == staffId)
    }
    fun canReview(incident: Incident) = incidentsCurrent && staffId != null &&
        incident.assignedStaff?.id == staffId && incident.status in setOf(IncidentStatus.ASSIGNED, IncidentStatus.IN_PROGRESS)
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
    private var foreground = true
    private var incidentTimer: Job? = null
    private var incidentFailures = 0
    private val pendingCommands = mutableMapOf<Pair<String, String>, IncidentCommand>()
    private val pendingReviews = mutableMapOf<Triple<String, String, String>, ReviewBody>()
    private var detailRequest: Job? = null
    private var reviewRequest: Job? = null

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
            pendingCommands.clear()
            pendingReviews.clear()
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

    fun login(pin: String, account: StaffAccount = StaffAccount.First) {
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
                val active = remote.login(pin, account)
                current()
                if (!active.expiresAt.isAfter(clock.instant())) {
                    expire()
                    return@work
                }
                startSession(active)
                ui = ui.copy(signedIn = true, staffId = active.personId, staffName = active.name)
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
            incidents = null, incidentProblem = null, lastIncidentRead = null, pendingIncident = false, incidentNotice = null)
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
            incidents = null, incidentProblem = null, lastIncidentRead = null, pendingIncident = false, incidentNotice = null)
        refreshIncidents()
    }

    fun refreshIncidents() {
        if (!foreground || !ui.incidentsVisible || ui.incidentsLoading || ui.incidentWriting || !checkExpiry()) return
        val remote = api ?: return
        val active = session ?: return
        val event = ui.event ?: return
        incidentTimer?.cancel()
        ui = ui.copy(incidentsLoading = true, pendingIncident = pendingCommands.containsKey(active.personId to event.id))
        work {
            try {
                val incidents = remote.incidents(active, event)
                current()
                if (checkExpiry()) {
                    incidentFailures = 0
                    ui = ui.copy(incidents = currentIncidentSnapshot(ui.incidents, incidents), incidentProblem = null,
                        lastIncidentRead = clock.instant(), server = ServerState.Connected)
                }
            } catch (failure: ApiFailure) {
                current()
                if (failure.problem == ApiProblem.Unauthorized) expire()
                else {
                    if (failure.problem == ApiProblem.Forbidden) closeTriage()
                    incidentFailures = (incidentFailures + 1).coerceAtMost(3)
                    ui = ui.copy(
                        incidentProblem = failure.problem,
                        incidents = ui.incidents.takeUnless { failure.problem == ApiProblem.Forbidden },
                        lastIncidentRead = ui.lastIncidentRead.takeUnless { failure.problem == ApiProblem.Forbidden },
                        server = if (failure.problem.isConnectivityError()) ServerState.Unreachable else ServerState.Connected,
                    )
                }
            } finally {
                if (isCurrent) {
                    ui = ui.copy(incidentsLoading = false)
                    scheduleIncidents()
                }
            }
        }
    }

    private fun scheduleIncidents() {
        if (!foreground || !ui.incidentsVisible || session == null) return
        incidentTimer = scope.launch {
            delay(incidentPollDelay(incidentFailures))
            incidentTimer = null
            refreshIncidents()
        }
    }

    fun operateIncident(incidentId: String, version: Long, action: IncidentAction, note: String = "") {
        if (!ui.incidentsCurrent || ui.pendingIncident || note.length > 240) return
        val incident = ui.incidents?.firstOrNull { it.id == incidentId && it.version == version } ?: return
        if (ui.actionFor(incident) != action) return
        val command = IncidentCommand(incident.id, action,
            OperationBody(incident.version, UUID.randomUUID().toString(), note.trim().ifEmpty { null }))
        submitIncident(command)
    }

    fun retryIncident() {
        if (!ui.incidentsCurrent) return
        val active = session ?: return
        val event = ui.event ?: return
        pendingCommands[active.personId to event.id]?.let(::submitIncident)
    }

    private fun submitIncident(command: IncidentCommand) {
        if (!foreground || !ui.incidentsVisible || !checkExpiry()) return
        val remote = api ?: return
        val active = session ?: return
        val event = ui.event ?: return
        val key = active.personId to event.id
        val retrying = pendingCommands.containsKey(key)
        cancelWork(keepTriage = true)
        pendingCommands[key] = command
        ui = ui.copy(incidentWriting = true, pendingIncident = true, incidentNotice = null)
        work {
            try {
                val (operation, incident) = remote.operate(active, event, command)
                current()
                pendingCommands.remove(key)
                val items = ui.incidents.orEmpty().filterNot { it.id == incident.id } + incident
                ui = ui.copy(incidents = currentIncidentSnapshot(ui.incidents, items)
                    .sortedWith(compareByDescending<Incident> { it.createdAt }.thenByDescending { it.id }),
                    pendingIncident = false,
                    incidentNotice = if (operation.replayed && operation.appliedVersion != incident.version)
                        "Operación original confirmada: ${operation.toStatus.label}. Estado vigente: ${incident.status.label}."
                    else "Operación confirmada: ${operation.toStatus.label}.")
            } catch (failure: ApiFailure) {
                current()
                when (failure.problem) {
                    ApiProblem.Unauthorized -> expire()
                    else -> {
                        val inaccessible = failure.problem in setOf(ApiProblem.Forbidden, ApiProblem.NotFound)
                        val rejected = failure.problem in setOf(ApiProblem.Conflict, ApiProblem.InvalidInput) ||
                            inaccessible && !retrying
                        if (rejected) pendingCommands.remove(key)
                        ui = ui.copy(pendingIncident = !rejected, incidentNotice = when {
                            rejected || inaccessible -> failure.problem.description()
                            else -> "Consulta el estado y reintenta la misma operación."
                        })
                    }
                }
            } finally {
                if (isCurrent) {
                    ui = ui.copy(incidentWriting = false, lastIncidentRead = null)
                    refreshIncidents()
                }
            }
        }
    }

    fun openTriage(id: String) {
        if (!foreground || !ui.incidentsVisible || ui.triageWriting || !checkExpiry() ||
            ui.incidents?.none { it.id == id && it.triage != null } != false) return
        val remote = api ?: return
        val active = session ?: return
        val event = ui.event ?: return
        detailRequest?.cancel()
        ui = ui.copy(triageId = id, triageDetail = null, triageLoading = true, triageNotice = null,
            triagePending = pendingReviews.containsKey(Triple(active.personId, event.id, id)))
        detailRequest = scope.launch {
            try {
                val detail = remote.triage(active, event, id)
                coroutineContext.ensureActive()
                if (checkExpiry()) {
                    updateTriage(id, detail.triage)
                    ui = ui.copy(triageDetail = detail)
                }
            } catch (failure: ApiFailure) {
                coroutineContext.ensureActive()
                if (failure.problem == ApiProblem.Unauthorized) expire()
                else ui = ui.copy(triageNotice = failure.problem.description())
            } finally {
                if (coroutineContext[Job]?.isActive == true) ui = ui.copy(triageLoading = false)
            }
        }
    }

    fun closeTriage() {
        detailRequest?.cancel()
        reviewRequest?.cancel()
        ui = ui.copy(triageId = null, triageDetail = null, triageNotice = null,
            triageLoading = false, triageWriting = false, triagePending = false)
    }

    fun reviewTriage(version: Long, decision: ReviewDecision, category: TriageCategory? = null, urgency: TriageUrgency? = null) {
        val id = ui.triageId ?: return
        val incident = ui.incidents?.firstOrNull { it.id == id && it.triage?.triageVersion == version } ?: return
        if (!ui.canReview(incident) || ui.triageDetail == null || ui.triagePending ||
            decision == ReviewDecision.CONFIRM && incident.triage?.suggestion == null ||
            decision == ReviewDecision.CORRECT && (category == null || urgency == null)) return
        submitReview(id, ReviewBody(version, UUID.randomUUID().toString(), decision, category, urgency))
    }

    fun retryTriage() {
        val id = ui.triageId ?: return
        val active = session ?: return
        val event = ui.event ?: return
        pendingReviews[Triple(active.personId, event.id, id)]?.let { submitReview(id, it) }
    }

    private fun submitReview(id: String, body: ReviewBody) {
        if (!foreground || !ui.incidentsVisible || ui.triageWriting || !checkExpiry()) return
        val remote = api ?: return
        val active = session ?: return
        val event = ui.event ?: return
        val key = Triple(active.personId, event.id, id)
        pendingReviews[key] = body
        ui = ui.copy(triageWriting = true, triagePending = true, triageNotice = null)
        reviewRequest = scope.launch {
            var reload = false
            try {
                val reply = remote.review(active, event, id, body)
                coroutineContext.ensureActive()
                pendingReviews.remove(key)
                updateTriage(id, reply.triage)
                ui = ui.copy(triagePending = false, triageNotice = "Revisión guardada.")
            } catch (failure: ApiFailure) {
                coroutineContext.ensureActive()
                when (failure.problem) {
                    ApiProblem.Unauthorized -> expire()
                    ApiProblem.Conflict, ApiProblem.InvalidInput, ApiProblem.Forbidden, ApiProblem.NotFound -> {
                        pendingReviews.remove(key)
                        reload = failure.problem == ApiProblem.Conflict
                        ui = ui.copy(triagePending = false, triageDetail = null, triageNotice = failure.problem.description())
                    }
                    else -> ui = ui.copy(triageNotice = "Resultado sin confirmar. Reintenta la misma revisión.")
                }
            } finally {
                if (coroutineContext[Job]?.isActive == true) {
                    ui = ui.copy(triageWriting = false)
                    if (reload) {
                        openTriage(id)
                        ui = ui.copy(triageNotice = "El triaje cambió. Revisa la versión vigente antes de enviar.")
                        refreshIncidents()
                    }
                }
            }
        }
    }

    private fun updateTriage(id: String, triage: IncidentTriage) {
        ui = ui.copy(incidents = ui.incidents?.map { incident ->
            if (incident.id == id && triage.triageVersion >= (incident.triage?.triageVersion ?: 0)) {
                incident.copy(triage = triage)
            } else incident
        })
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
        foreground = false
        cancelWork()
        ui = ui.copy(scan = ScanState.Ready, debug = NfcDebug(), lastIncidentRead = null)
    }

    fun resume() {
        foreground = true
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

    private fun cancelWork(keepTriage: Boolean = false) {
        if (!keepTriage) closeTriage()
        generation++
        operation?.cancel()
        operation = null
        incidentTimer?.cancel()
        incidentTimer = null
        incidentFailures = 0
        ui = ui.copy(task = null, incidentsLoading = false, incidentWriting = false)
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
        closeTriage()
        expiry?.cancel()
        expiry = null
        session = null
        ui = ui.copy(signedIn = false, events = emptyList(), event = null,
            incidentsVisible = false, incidents = null, incidentsLoading = false, incidentProblem = null,
            staffId = null, staffName = null, lastIncidentRead = null, pendingIncident = false, incidentNotice = null)
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
    ApiProblem.Conflict -> "El incidente cambió. Revisa su estado actual antes de volver a actuar."
    ApiProblem.InvalidInput -> "El servidor rechazó los datos de la operación."
}
