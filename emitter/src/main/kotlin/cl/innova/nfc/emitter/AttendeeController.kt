package cl.innova.nfc.emitter

import java.time.Clock
import java.time.Duration
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
    Identification("Identificación"), Card("Mi ficha"), Contacts("Contactos"), Connection("Conexión"),
}
internal enum class Task(val label: String) {
    Checking("Comprobando el servidor"), SigningIn("Verificando la sesión de Antonia"),
    Loading("Cargando desde el servidor"), Saving("Guardando en el servidor"),
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
    val identityMismatch: Boolean = false,
    val snapshot: EmergencySnapshot? = null,
    val fresh: Boolean = false,
    val task: Task? = null,
    val draft: EditDraft? = null,
    val review: Review? = null,
    val notice: String? = null,
    val confirmation: String? = null,
) {
    val editable get() = person != null && fresh && task == null &&
        snapshot?.card?.version?.let { it in 1L..MAX_WRITE_VERSION } == true
    val canSave get() = editable && review == null
}

internal class AttendeeController(
    private val scope: CoroutineScope,
    private val allowHttp: Boolean,
    storedAddress: String?,
    private val storeUrl: (String) -> Unit,
    private val apiFor: (HttpUrl) -> AttendeeApi,
    private val clock: Clock = Clock.systemUTC(),
) {
    private var target = storedAddress?.let { attendeeServerAddress(it, allowHttp) }
    private var api = target?.let(apiFor)
    private var session: AttendeeSession? = null
    private var draftOwner: String? = null
    private var operation: Job? = null
    private var expiry: Job? = null
    private var generation = 0L
    private val mutable = MutableStateFlow(AttendeeState(address = target?.toString().orEmpty()))
    val state = mutable.asStateFlow()
    private var ui: AttendeeState
        get() = mutable.value
        set(value) { mutable.value = value }

    fun navigate(section: Section) {
        if (ui.draft != null || ui.section == section) return
        ui = ui.copy(section = section)
    }

    fun connect(input: String) {
        if (ui.draft != null) return
        val address = attendeeServerAddress(input, allowHttp)
        if (address == null) {
            ui = ui.copy(notice = if (allowHttp) "Ingresa una URL HTTP o HTTPS con la dirección y el puerto del PC."
                else "Ingresa una URL HTTPS con la dirección del servidor.")
            return
        }
        if (address != target) {
            cancelWork()
            clearSession()
            target = address
            api = apiFor(address)
            storeUrl(address.toString())
            ui = ui.copy(address = address.toString(), identityMismatch = false, confirmation = null)
        }
        refresh()
    }

    fun login() {
        val remote = api ?: return
        if (ui.task != null || session != null) return
        work(Task.SigningIn) {
            val active = remote.login()
            current()
            if (!active.expiresAt.isAfter(clock.instant())) throw AttendeeFailure(FailureKind.Expired)
            if (draftOwner != null && draftOwner != active.person.id) throw AttendeeFailure(FailureKind.Identity)
            session = active
            expiry = scope.launch {
                val ttl = Duration.between(clock.instant(), active.expiresAt).coerceAtMost(Duration.ofHours(8))
                delay(ttl.toMillis().coerceAtLeast(0))
                if (session === active) {
                    val uncertain = ui.task == Task.Saving && ui.draft != null
                    cancelWork()
                    expire(uncertain)
                }
            }
            ui = ui.copy(person = active.person, identityMismatch = false, reachability = Reachability.Connected,
                task = Task.Loading)
            load(remote, active)
        }
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
        cancelWork()
        clearSession()
        draftOwner = null
        ui = AttendeeState(address = ui.address, section = Section.Connection, reachability = ui.reachability)
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
        validSession()
    }

    private fun validSession(): AttendeeSession? {
        val active = session ?: return null
        if (active.expiresAt.isAfter(clock.instant())) return active
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

    private fun clearSession() {
        expiry?.cancel()
        expiry = null
        session = null
        ui = ui.copy(person = null, snapshot = null, fresh = false)
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
            identityMismatch = ui.identityMismatch || kind == FailureKind.Identity,
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
        ui = ui.copy(task = task, notice = null, fresh = if (task == Task.Saving) ui.fresh else false)
        val request = Operation(++generation)
        operation = scope.launch {
            try {
                request.block()
            } catch (error: AttendeeFailure) {
                request.current()
                failed(error.kind)
            } finally {
                if (request.isCurrent) ui = ui.copy(task = null)
            }
        }
    }

    private inner class Operation(private val id: Long) {
        val isCurrent get() = id == generation
        suspend fun current() {
            coroutineContext.ensureActive()
            if (!isCurrent) throw CancellationException()
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
