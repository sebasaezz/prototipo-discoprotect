package cl.innova.nfc.emitter

import cl.discoprotect.ui.SosReason

import cl.discoprotect.ui.Incident
import cl.discoprotect.ui.ChatDraft
import cl.discoprotect.ui.IncidentStatus
import cl.discoprotect.ui.currentIncidentSnapshot
import cl.discoprotect.ui.incidentPollDelay
import java.time.Clock
import java.time.Duration
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.HttpUrl

internal enum class Section(val label: String) {
    Identification("Identificación"), Sos("SOS"), Card("Mi ficha"), Contacts("Contactos"), Connection("Conexión"),
}
internal enum class Task(val label: String) {
    Checking("Comprobando el servidor"), SigningIn("Verificando sesión"),
    Switching("Cambiando de cuenta y verificando sesión"), Credential("Recuperando credencial NFC"),
    Loading("Cargando desde el servidor"), Saving("Guardando en el servidor"),
    SosReading("Consultando solicitudes SOS"), SosSending("Enviando solicitud SOS"),
    SosCancelling("Enviando cancelación"),
}
internal enum class Reachability { Unchecked, Connected, Offline }
internal enum class Review { Conflict, Uncertain, Present, Removed }

internal sealed interface EditDraft {
    val version: Long
    val dirty: Boolean

    data class Card(override val version: Long, val original: CardFields, val fields: CardFields) : EditDraft {
        override val dirty get() = fields != original
    }

    data class ContactEdit(
        override val version: Long,
        val id: String?,
        val original: ContactFields,
        val fields: ContactFields,
        val knownIds: Set<String> = emptySet(),
    ) : EditDraft {
        override val dirty get() = fields != original
    }

    data class Remove(override val version: Long, val contact: Contact) : EditDraft {
        override val dirty = true
    }
}

internal data class AttendeeState(
    val address: String = "",
    val section: Section = Section.Identification,
    val reachability: Reachability = Reachability.Unchecked,
    val person: Person? = null,
    val selectedAccount: DemoAccount? = null,
    val sharingGeneration: Long? = null,
    val snapshot: EmergencySnapshot? = null,
    val fresh: Boolean = false,
    val task: Task? = null,
    val draft: EditDraft? = null,
    val review: Review? = null,
    val notice: String? = null,
    val confirmation: String? = null,
    val sos: SosState = SosState(),
) {
    val editable get() = person != null && fresh && task == null &&
        snapshot?.card?.version?.let { it in 1L..MAX_WRITE_VERSION } == true
    val canSave get() = editable && review == null
    val sosWriting get() = task == Task.SosSending || task == Task.SosCancelling
    val canChangeAccount get() = task != Task.Saving && !sosWriting
}

internal class AttendeeController(
    private val scope: CoroutineScope,
    private val allowHttp: Boolean,
    storedAddress: String?,
    private val storeUrl: (String) -> Unit,
    private val apiFor: (HttpUrl) -> AttendeeApi,
    private val clock: Clock = Clock.systemUTC(),
    val sharing: HceIdentityStore = HceIdentityStore(clock),
) {
    private var target = storedAddress?.let { attendeeServerAddress(it, allowHttp) }
    private var api = target?.let(apiFor)
    private var session: AttendeeSession? = null
    private var draftOwner: String? = null
    private var operation: Job? = null
    private var expiry: Job? = null
    private var generation = 0L
    // Scoped to the current server. Only the original person can recover an attempt.
    private val pendingSos = mutableMapOf<String, SosAttempt>()
    private var foreground = true
    private var sosRead: Job? = null
    private var sosTimer: Job? = null
    private var sosReadGeneration = 0L
    private var sosFailures = 0
    private val identityOwner = Any().also(sharing::claim)
    private val mutable = MutableStateFlow(AttendeeState(address = target?.toString().orEmpty()))
    val state = mutable.asStateFlow()
    private var ui: AttendeeState
        get() = mutable.value
        set(value) { mutable.value = value }

    fun navigate(section: Section) {
        if (ui.draft != null || ui.section == section) return
        stopSosPolling()
        updateSos(ui.sos.copy(historyFresh = false))
        ui = ui.copy(section = section)
        if (section == Section.Sos) refreshSos()
    }

    fun connect(input: String) {
        if (ui.draft != null || ui.sosWriting) return
        val address = attendeeServerAddress(input, allowHttp)
        if (address == null) {
            ui = ui.copy(notice = if (allowHttp) "Ingresa una URL HTTP o HTTPS con la dirección y el puerto del PC."
                else "Ingresa una URL HTTPS con la dirección del servidor.")
            return
        }
        if (address != target) {
            clearSession()
            cancelWork()
            pendingSos.clear()
            target = address
            api = apiFor(address)
            storeUrl(address.toString())
            ui = ui.copy(address = address.toString(), selectedAccount = null,
                reachability = Reachability.Unchecked, confirmation = null)
        }
        refresh()
    }

    /** Account changes are confirmed by the UI before entering this method. */
    fun selectAccount(account: DemoAccount) {
        if (!ui.canChangeAccount) return
        beginLogin(account, keepDraft = false)
    }

    fun retryLogin() {
        val account = ui.selectedAccount ?: return
        if (ui.task == null && validSession() == null) beginLogin(account, keepDraft = true)
    }

    private fun beginLogin(account: DemoAccount, keepDraft: Boolean) {
        val remote = api ?: return
        val origin = target ?: return
        if (session?.person?.account == account.key && validSession() != null) return
        val switching = ui.selectedAccount != null && ui.selectedAccount != account
        val pending = clearSession() ?: return
        cancelWork()
        if (!keepDraft) draftOwner = null
        ui = ui.copy(selectedAccount = account, confirmation = null,
            section = if (switching) Section.Connection else ui.section,
            draft = ui.draft.takeIf { keepDraft }, review = ui.review.takeIf { keepDraft })
        work(if (switching) Task.Switching else Task.SigningIn) {
            val active = remote.login(account) {
                requireCurrent()
                ui = ui.copy(task = Task.Credential)
            }
            current()
            if (active.person.account != account.key || active.person.id.isBlank() ||
                draftOwner != null && draftOwner != active.person.id) throw AttendeeFailure(FailureKind.Identity)
            val identity = sharing.publish(pending, active, origin) ?: throw AttendeeFailure(FailureKind.Expired)
            session = active
            ui = ui.copy(person = identity.person, sharingGeneration = identity.credential.generation,
                reachability = Reachability.Connected, task = Task.Loading,
                sos = pendingSos[active.person.id]?.let { SosState(pending = it, outcome = SosOutcome.Uncertain) }
                    ?: SosState())
            expiry = scope.launch {
                delay(Duration.between(clock.instant(), identity.expiresAt).toMillis().coerceAtLeast(0))
                if (session === active) {
                    val uncertain = ui.task == Task.Saving && ui.draft != null
                    cancelWork()
                    expire(uncertain)
                }
            }
            load(remote, active)
            if (ui.section == Section.Sos && foreground) {
                ui = ui.copy(task = null)
                refreshSos()
            }
        }
    }

    fun visibleIdentity(state: AttendeeState, slot: IdentitySlot): ShareableIdentity? =
        sharing.current(slot)?.takeIf {
            slot.owner === identityOwner && state.sharingGeneration == it.credential.generation &&
                state.person == it.person
        }

    fun presentation(state: AttendeeState, slot: IdentitySlot, identity: ShareableIdentity?): AttendeeState {
        if (slot.owner !== identityOwner) return AttendeeState(address = state.address, section = state.section)
        return if (state.person != null && identity == null)
            state.copy(person = null, snapshot = null, fresh = false, confirmation = null, sos = SosState())
        else state
    }

    fun refresh() {
        val remote = api ?: return
        if (ui.task != null) return
        val active = validSession()
        work(if (active == null) Task.Checking else Task.Loading) {
            remote.health()
            current()
            ui = ui.copy(reachability = Reachability.Connected)
            if (active != null) load(remote, active)
        }
    }

    fun logout() {
        if (ui.sosWriting) return
        resetLogin()
    }

    private fun resetLogin() {
        clearSession()
        cancelWork()
        draftOwner = null
        ui = AttendeeState(address = ui.address, section = Section.Connection, reachability = ui.reachability)
    }

    fun refreshSos() {
        if (!foreground || ui.section != Section.Sos || ui.task != null || sosRead?.isActive == true) return
        if (ui.sos.event != null && ui.sos.zones != null) {
            refreshSosHistory()
        } else {
            loadSosContext()
        }
    }

    private fun loadSosContext() = sosWork(Task.SosReading) { remote, active ->
        val events = remote.events(active)
        current()
        val pending = ui.sos.pending
        val selectedId = pending?.eventId ?: ui.sos.event?.id
        val event = events.firstOrNull { it.id == selectedId }
        updateSos(if (event?.id == ui.sos.event?.id) ui.sos.copy(events = events, event = event)
            else SosState(events = events, event = event, pending = pending,
                outcome = if (pending == null) SosOutcome.Ready else SosOutcome.Uncertain))
        if (event != null) loadSosEvent(remote, active, event)
        else if (pending != null) updateSos(ui.sos.copy(error = "El evento de la solicitud pendiente ya no está disponible."))
    }

    fun selectSosEvent(id: String) {
        if (ui.sos.pending != null || ui.task != null || ui.section != Section.Sos) return
        val event = ui.sos.events.firstOrNull { it.id == id } ?: return
        sosFailures = 0
        updateSos(SosState(events = ui.sos.events, event = event))
        sosWork(Task.SosReading) { remote, active -> loadSosEvent(remote, active, event) }
    }

    fun selectSosZone(id: String?) {
        val zones = ui.sos.zones ?: return
        if (!canEditSos() || id != null && zones.none { it.id == id }) return
        updateSos(ui.sos.copy(locationChosen = true, zoneId = id, outcome = SosOutcome.Ready, error = null))
    }

    fun updateSosReference(value: String) {
        if (!canEditSos() || value.length > 160) return
        updateSos(ui.sos.copy(reference = value, outcome = SosOutcome.Ready, error = null))
    }

    fun selectSosReason(value: SosReason?) {
        if (!canEditSos()) return
        updateSos(ui.sos.copy(reasonCode = value, outcome = SosOutcome.Ready, error = null))
    }

    fun updateSosDescription(value: String) {
        if (!canEditSos() || value.length > 280) return
        updateSos(ui.sos.copy(description = value, outcome = SosOutcome.Ready, error = null))
    }

    fun sendSos() {
        val form = ui.sos
        if (!canEditSos() || !form.canSend) return
        val body = SosRequest(UUID.randomUUID().toString(), form.zoneId, form.reference.trim().ifEmpty { null },
            form.reasonCode, form.description.takeUnless { it.isBlank() })
        submitSos(SosAttempt.Create(requireNotNull(form.event).id, body))
    }

    fun sendHelp(text: String? = null) {
        val form = ui.sos
        val event = form.event ?: return
        if (!canEditSos() || text != null && (text.isBlank() || text.length > 500)) return
        val body = SosRequest(UUID.randomUUID().toString(), form.zoneId, form.reference.trim().ifEmpty { null }, form.reasonCode)
        val first = text?.let { ChatDraft.create(it) }
        submitSos(SosAttempt.Create(event.id, body, if (first == null) "SOS" else "CHAT", first))
    }

    suspend fun conversation(id: String, after: Long) = chatRequest(id) { remote, active ->
        remote.conversation(active, id, after)
    }

    suspend fun message(id: String, draft: ChatDraft) = chatRequest(id) { remote, active ->
        remote.message(active, id, draft)
    }

    private suspend fun <T> chatRequest(id: String, request: suspend (AttendeeApi, AttendeeSession) -> T): T {
        val remote = api ?: throw AttendeeFailure(FailureKind.Offline)
        val active = validSession() ?: throw AttendeeFailure(FailureKind.Expired)
        val eventId = ui.sos.event?.id
        if (ui.section != Section.Sos || ui.sos.incidents?.none { it.id == id && it.eventId == eventId } != false) {
            throw CancellationException()
        }
        return request(remote, active).also {
            if (session !== active || api !== remote || ui.section != Section.Sos || ui.sos.event?.id != eventId) {
                throw CancellationException()
            }
        }
    }

    fun cancelSos(id: String) {
        if (!canEditSos() || !ui.sos.historyFresh) return
        val incident = ui.sos.incidents?.firstOrNull { it.id == id && it.status == IncidentStatus.OPEN } ?: return
        submitSos(SosAttempt.Cancel(incident.eventId, id))
    }

    fun retrySos() {
        val pending = ui.sos.pending ?: return
        if (pending is SosAttempt.Cancel && !ui.sos.historyFresh) return
        if (ui.sos.event?.id == pending.eventId) submitSos(pending)
    }

    private fun canEditSos() = ui.section == Section.Sos && ui.task == null && ui.sos.pending == null

    private fun updateSos(state: SosState) {
        ui = ui.copy(sos = state)
    }

    private suspend fun Operation.loadSosEvent(remote: AttendeeApi, active: AttendeeSession, event: SosEvent) {
        // Recover writes before loading the form, even if zones are temporarily unavailable.
        val incidents = remote.incidents(active, event.id)
        current()
        applySosHistory(active, incidents)
        val zones = remote.zones(active, event.id)
        current()
        val state = ui.sos
        val chosen = state.locationChosen && (state.zoneId == null || zones.any { it.id == state.zoneId })
        updateSos(state.copy(zones = zones, locationChosen = chosen, zoneId = state.zoneId.takeIf { chosen }))
    }

    private fun applySosHistory(active: AttendeeSession, incoming: List<Incident>) {
        sosFailures = 0
        val incidents = currentIncidentSnapshot(ui.sos.incidents, incoming)
        val pending = ui.sos.pending
        updateSos(ui.sos.copy(incidents = incidents, historyError = null,
            lastConsultedAt = clock.instant(), historyFresh = true))
        val recovered = pending?.let { incidents.firstOrNull(it::matches) }
        if (recovered != null) {
            confirmSos(active, recovered)
        } else if (pending is SosAttempt.Cancel &&
            incidents.any { it.id == pending.id && it.status != IncidentStatus.OPEN }) {
            pendingSos.remove(active.person.id)
            updateSos(ui.sos.copy(pending = null, outcome = SosOutcome.Rejected,
                error = "El personal ya asumió la solicitud. La cancelación no se aplicó."))
        }
    }

    private fun refreshSosHistory() {
        val event = ui.sos.event ?: return
        val active = validSession() ?: return
        val remote = api ?: return
        sosTimer?.cancel()
        val request = ++sosReadGeneration
        sosRead = scope.launch {
            try {
                val items = remote.incidents(active, event.id)
                coroutineContext.ensureActive()
                if (request == sosReadGeneration && session === active && validSession() === active) {
                    applySosHistory(active, items)
                }
            } catch (failure: AttendeeFailure) {
                coroutineContext.ensureActive()
                if (request == sosReadGeneration && session === active) reportSosReadFailure(failure.kind)
            } finally {
                if (request == sosReadGeneration) {
                    sosRead = null
                    scheduleSosPoll()
                }
            }
        }
    }

    private fun reportSosReadFailure(kind: FailureKind) {
        if (kind == FailureKind.Expired) {
            expire()
            return
        }
        sosFailures = (sosFailures + 1).coerceAtMost(3)
        val state = ui.sos
        updateSos(if (kind == FailureKind.Forbidden) {
            SosState(pending = state.pending, outcome = state.outcome, historyError = kind.message)
        } else state.copy(historyError = kind.message, historyFresh = false))
    }

    private fun scheduleSosPoll() {
        if (!foreground || ui.section != Section.Sos || session == null || ui.task != null) return
        if (ui.sos.event == null && sosFailures == 0) return
        sosTimer?.cancel()
        sosTimer = scope.launch {
            delay(incidentPollDelay(sosFailures))
            sosTimer = null
            refreshSos()
        }
    }

    private fun stopSosPolling() {
        sosReadGeneration++
        sosRead?.cancel()
        sosRead = null
        sosTimer?.cancel()
        sosTimer = null
    }

    private fun submitSos(attempt: SosAttempt) {
        val task = if (attempt is SosAttempt.Create) Task.SosSending else Task.SosCancelling
        sosWork(task) { remote, active ->
            val owner = active.person.id
            val retrying = pendingSos[owner] != null
            var sending = false
            try {
                if (!retrying && (attempt !is SosAttempt.Create || attempt.mode == null)) remote.health()
                current()
                pendingSos[owner] = attempt
                updateSos(ui.sos.copy(pending = attempt, receipt = null))
                sending = true
                val incident = when (attempt) {
                    is SosAttempt.Create -> remote.createSos(active, attempt)
                    is SosAttempt.Cancel -> remote.cancelSos(active, attempt)
                }
                current()
                confirmSos(active, incident)
            } catch (failure: AttendeeFailure) {
                current()
                val network = failure.kind in setOf(FailureKind.Offline, FailureKind.Timeout)
                val uncertain = if (retrying) failure.kind != FailureKind.Conflict || attempt is SosAttempt.Cancel
                    else sending && (network || failure.kind in setOf(FailureKind.Response, FailureKind.Server, FailureKind.Expired))
                if (!uncertain) pendingSos.remove(owner)
                if (failure.kind == FailureKind.Expired) {
                    expire()
                    return@sosWork
                }
                val outcome = when {
                    uncertain -> SosOutcome.Uncertain
                    network -> SosOutcome.Offline
                    !sending -> SosOutcome.Unsent
                    else -> SosOutcome.Rejected
                }
                val message = when (failure.kind) {
                    FailureKind.Conflict -> "El servidor informó un conflicto. Consulta tus solicitudes."
                    FailureKind.Missing -> "El servidor no encontró la solicitud."
                    else -> failure.kind.message
                }
                updateSos(ui.sos.copy(pending = pendingSos[owner], outcome = outcome, receipt = null,
                    error = message, historyFresh = false))
                ui = ui.copy(reachability = reachabilityAfter(failure.kind))
            }
        }
    }

    private fun confirmSos(active: AttendeeSession, incident: Incident) {
        pendingSos.remove(active.person.id)
        val items = ui.sos.incidents.orEmpty().filterNot { it.id == incident.id } + incident
        val sending = ui.sos.pending is SosAttempt.Create
        updateSos(ui.sos.copy(pending = null, receipt = incident.id, error = null,
            reasonCode = ui.sos.reasonCode.takeUnless { sending },
            description = if (sending) "" else ui.sos.description,
            outcome = if (incident.status == IncidentStatus.CANCELLED) SosOutcome.Cancelled else SosOutcome.Confirmed,
            incidents = currentIncidentSnapshot(ui.sos.incidents, items)
                .sortedWith(compareByDescending<Incident> { it.createdAt }.thenByDescending { it.id })))
        ui = ui.copy(reachability = Reachability.Connected)
    }

    private fun sosWork(task: Task, block: suspend Operation.(AttendeeApi, AttendeeSession) -> Unit) {
        val remote = api ?: return
        if (ui.task != null || ui.section != Section.Sos) return
        val active = validSession() ?: return
        updateSos(ui.sos.copy(error = null))
        work(task) {
            try {
                block(remote, active)
            } catch (failure: AttendeeFailure) {
                current()
                reportSosReadFailure(failure.kind)
            }
        }
    }

    fun editCard() {
        if (!ui.editable || ui.draft != null || validSession() == null) return
        val card = requireNotNull(ui.snapshot).card
        begin(EditDraft.Card(card.version, card.fields, card.fields))
    }

    fun editContact(id: String?) {
        if (!ui.editable || ui.draft != null || validSession() == null) return
        val data = requireNotNull(ui.snapshot)
        val existing = data.contacts.firstOrNull { it.id == id }
        if (id != null && existing == null) return
        val fields = existing?.fields ?: ContactFields()
        begin(EditDraft.ContactEdit(data.card.version, id, fields, fields, data.contacts.map { it.id }.toSet()))
    }

    fun requestRemoval(id: String) {
        if (!ui.editable || ui.draft != null || validSession() == null) return
        val data = requireNotNull(ui.snapshot)
        val match = data.contacts.firstOrNull { it.id == id } ?: return
        begin(EditDraft.Remove(data.card.version, match))
    }

    private fun begin(draft: EditDraft) {
        draftOwner = session?.person?.id
        ui = ui.copy(draft = draft, notice = null, confirmation = null, review = null)
    }

    fun updateCard(fields: CardFields) {
        val edit = ui.draft as? EditDraft.Card ?: return
        if (ui.task == null && ui.review == null) ui = ui.copy(draft = edit.copy(fields = fields))
    }

    fun updateContact(fields: ContactFields) {
        val edit = ui.draft as? EditDraft.ContactEdit ?: return
        if (ui.task == null && ui.review == null) ui = ui.copy(draft = edit.copy(fields = fields))
    }

    fun discard() {
        if (ui.task != null) return
        draftOwner = null
        ui = ui.copy(draft = null, review = null, notice = null)
    }

    fun acceptLatestVersion() {
        val latest = ui.snapshot ?: return
        if (!ui.editable || ui.review !in setOf(Review.Conflict, Review.Uncertain)) return
        val version = latest.card.version
        val updated = when (val edit = ui.draft) {
            is EditDraft.Card -> edit.copy(version = version, original = latest.card.fields,
                fields = edit.fields.applyEdits(edit.original, latest.card.fields))
            is EditDraft.ContactEdit -> {
                val fields = if (edit.id == null) edit.original
                    else latest.contacts.firstOrNull { it.id == edit.id }?.fields ?: return
                edit.copy(version = version, original = fields, fields = edit.fields.applyEdits(edit.original, fields),
                    knownIds = latest.contacts.map { it.id }.toSet())
            }
            is EditDraft.Remove -> {
                val contact = latest.contacts.firstOrNull { it.id == edit.contact.id } ?: return
                edit.copy(version = version, contact = contact)
            }
            null -> return
        }
        ui = ui.copy(draft = updated, review = null,
            notice = "Revisa el formulario con la versión $version. Puedes guardarlo cuando esté listo.")
    }

    fun save() {
        val edit = ui.draft ?: return
        val remote = api ?: return
        if (!ui.canSave || edit.version != ui.snapshot?.card?.version) return
        val active = validSession() ?: return
        if (!validEdit(edit)) {
            ui = ui.copy(notice = "Revisa los campos antes de guardar.")
            return
        }
        work(Task.Saving) {
            val repository = EmergencyRepository(remote)
            val version = try {
                repository.save(active, edit)
            } catch (error: AttendeeFailure) {
                current()
                when (error.kind) {
                    FailureKind.Conflict, FailureKind.Missing -> {
                        ui = ui.copy(review = Review.Conflict, fresh = false, notice = error.kind.message)
                        load(remote, active)
                    }
                    FailureKind.Offline, FailureKind.Timeout, FailureKind.Response, FailureKind.Server -> {
                        ui = ui.copy(review = Review.Uncertain, fresh = false, notice = uncertainMessage,
                            reachability = reachabilityAfter(error.kind))
                    }
                    else -> failed(error.kind)
                }
                return@work
            }
            current()
            draftOwner = null
            ui = ui.copy(draft = null, review = null, fresh = false, task = Task.Loading,
                confirmation = "Cambios guardados en el servidor · Versión $version", notice = null)
            load(remote, active)
        }
    }

    fun resume() {
        foreground = true
        if (!sharing.owns(identityOwner)) {
            resetLogin()
            sharing.claim(identityOwner)
        }
        validSession()
        if (ui.section == Section.Sos) refreshSos()
    }

    fun pause() {
        foreground = false
        stopSosPolling()
        if (ui.task == Task.SosReading) cancelWork()
        updateSos(ui.sos.copy(historyFresh = false))
    }

    fun close() {
        clearSession()
        cancelWork()
        draftOwner = null
    }

    private fun validSession(): AttendeeSession? {
        val active = session ?: return null
        if (active.expiresAt.isAfter(clock.instant()) && visibleIdentity(ui, sharing.state.value) != null) return active
        val uncertain = ui.task == Task.Saving && ui.draft != null
        cancelWork()
        expire(uncertain)
        return null
    }

    private fun expire(uncertain: Boolean = false) {
        clearSession()
        ui = ui.copy(notice = FailureKind.Expired.message, confirmation = null,
            review = if (uncertain) Review.Uncertain else ui.draft?.let { ui.review ?: Review.Conflict })
    }

    private fun clearSession(): IdentitySlot? {
        stopSosPolling()
        sosFailures = 0
        val pending = sharing.invalidate(identityOwner)
        expiry?.cancel()
        expiry = null
        session = null
        ui = ui.copy(person = null, sharingGeneration = null, snapshot = null, fresh = false, sos = SosState())
        return pending
    }

    private suspend fun Operation.load(remote: AttendeeApi, active: AttendeeSession) {
        val data = EmergencyRepository(remote).load(active)
        current()
        val review = reviewFor(data)
        val notice = when (review) {
            Review.Conflict -> FailureKind.Conflict.message
            Review.Uncertain -> uncertainMessage
            Review.Present -> "El servidor ya muestra el cambio intentado. Cierra el borrador para evitar repetirlo."
            Review.Removed -> "El contacto ya no está en el servidor. Cierra el borrador para continuar."
            null -> null
        }
        ui = ui.copy(snapshot = data, fresh = true, reachability = Reachability.Connected, review = review, notice = notice)
    }

    private fun reviewFor(data: EmergencySnapshot): Review? {
        val edit = ui.draft ?: return null
        if (edit is EditDraft.ContactEdit && edit.id != null && data.contacts.none { it.id == edit.id }) return Review.Removed
        if (edit is EditDraft.Remove && data.contacts.none { it.id == edit.contact.id }) return Review.Removed
        if (ui.review in setOf(Review.Uncertain, Review.Present)) {
            val present = when (edit) {
                is EditDraft.Card -> data.card.fields == edit.fields.normalized()
                is EditDraft.ContactEdit -> data.contacts.any {
                    (if (edit.id == null) it.id !in edit.knownIds else it.id == edit.id) &&
                        it.fields == edit.fields.normalized()
                }
                is EditDraft.Remove -> false
            }
            return if (present) Review.Present else Review.Uncertain
        }
        return if (edit.version != data.card.version || ui.review != null) Review.Conflict else null
    }

    private fun failed(kind: FailureKind) {
        if (kind == FailureKind.Expired) {
            expire()
            return
        }
        ui = ui.copy(notice = kind.message,
            reachability = reachabilityAfter(kind),
            fresh = if (kind == FailureKind.Invalid) ui.fresh else false)
    }

    private fun reachabilityAfter(kind: FailureKind) = when (kind) {
        FailureKind.Offline, FailureKind.Timeout -> Reachability.Offline
        else -> Reachability.Connected
    }

    private fun cancelWork() {
        generation++
        operation?.cancel()
        operation = null
        ui = ui.copy(task = null)
    }

    private fun work(task: Task, block: suspend Operation.() -> Unit) {
        stopSosPolling()
        val preserveFresh = task in setOf(Task.Saving, Task.SosReading, Task.SosSending, Task.SosCancelling)
        ui = ui.copy(task = task, notice = null, fresh = preserveFresh && ui.fresh)
        val request = Operation(++generation)
        operation = scope.launch {
            try {
                request.block()
            } catch (error: AttendeeFailure) {
                request.current()
                failed(error.kind)
            } finally {
                if (request.isCurrent) {
                    ui = ui.copy(task = null)
                    scheduleSosPoll()
                }
            }
        }
    }

    private inner class Operation(private val id: Long) {
        val isCurrent get() = id == generation
        suspend fun current() {
            coroutineContext.ensureActive()
            requireCurrent()
        }

        fun requireCurrent() {
            if (!isCurrent || !sharing.owns(identityOwner)) throw CancellationException()
        }
    }

    private companion object {
        const val uncertainMessage =
            "El resultado del guardado es incierto. Carga la versión vigente y comprueba el cambio antes de volver a enviar."

        fun validEdit(edit: EditDraft) = when (edit) {
            is EditDraft.Card -> edit.dirty && edit.fields.valid()
            is EditDraft.ContactEdit -> edit.dirty && edit.fields.valid()
            is EditDraft.Remove -> true
        }
    }
}
