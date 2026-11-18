package cl.innova.nfc.emitter

import cl.innova.nfc.protocol.NfcId
import cl.discoprotect.ui.*
import java.io.IOException
import java.io.InterruptedIOException
import java.net.URI
import java.time.Clock
import java.time.Instant
import cl.discoprotect.ui.EventCompanions
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

internal fun attendeeServerAddress(input: String, allowHttp: Boolean): HttpUrl? {
    val text = input.trim()
    val uri = runCatching { URI(text) }.getOrNull() ?: return null
    val url = text.toHttpUrlOrNull() ?: return null
    if (uri.rawAuthority.isNullOrBlank() || uri.rawUserInfo != null || uri.rawQuery != null ||
        uri.rawFragment != null || uri.rawPath !in listOf("", "/")) return null
    if (!url.isHttps && !allowHttp) return null
    if (url.host in setOf("localhost", "localhost.", "0.0.0.0", "::", "::1") || url.host.startsWith("127.")) return null
    return url
}

internal enum class FailureKind(val message: String) {
    Offline("No se pudo conectar. Revisa la dirección, el Wi-Fi y el firewall del PC."),
    Timeout("El servidor tardó demasiado en responder."),
    Invalid("El servidor rechazó los datos. Revisa los campos."),
    Expired("La sesión dejó de ser válida. Vuelve a verificar la cuenta."),
    Forbidden("La sesión no tiene permiso para esta operación."),
    Missing("La ficha o el contacto solicitado ya no está disponible."),
    Conflict("La ficha cambió en el servidor. Revisa la versión vigente antes de guardar."),
    Identity("La identidad o el identificador recibido no es válido para la cuenta elegida."),
    NoCredential("La cuenta no tiene una credencial NFC activa."),
    Response("La respuesta no corresponde al contrato de Discoprotect."),
    Server("El servidor no pudo completar la consulta."),
    Changed("La ficha se actualizó durante la consulta. Vuelve a cargarla."),
}
internal class AttendeeFailure(val kind: FailureKind) : Exception(kind.name)

internal enum class DemoAccount(val key: String, val label: String) {
    Antonia("antonia", "Antonia Reyes"), Martin("martin", "Martín Ejemplo"), Sofia("sofia", "Sofía Ficticia"),
}

internal class AttendeeSession(val token: String, val expiresAt: Instant, val person: Person, val nfcId: NfcId)

@Serializable private data class HelpBody(
    val clientRequestId: String, val zoneId: String?, val locationText: String?,
    val reasonCode: SosReason?, val mode: String, val firstMessage: ChatDraft? = null,
)
@Serializable private data class HelpReceipt(val incident: Incident, val conversation: ChatState, val message: ChatMessage?)

internal interface AttendeeApi {
    suspend fun health()
    suspend fun ticket(session: AttendeeSession, eventId: String): TicketReply =
        throw AttendeeFailure(FailureKind.Response)
    suspend fun promotions(session: AttendeeSession, eventId: String): PromotionReply =
        throw AttendeeFailure(FailureKind.Response)
    suspend fun companions(session: AttendeeSession, eventId: String): EventCompanions =
        throw AttendeeFailure(FailureKind.Response)
    suspend fun invite(session: AttendeeSession, eventId: String): Invitation =
        throw AttendeeFailure(FailureKind.Response)
    suspend fun acceptInvite(session: AttendeeSession, eventId: String, code: String): AcceptedInvitation =
        throw AttendeeFailure(FailureKind.Response)
    suspend fun unlinkCompanion(session: AttendeeSession, eventId: String, id: String): ClosedCompanion =
        throw AttendeeFailure(FailureKind.Response)
    suspend fun login(account: DemoAccount, onCredential: () -> Unit = {}): AttendeeSession
    suspend fun card(session: AttendeeSession): EmergencyCard
    suspend fun contacts(session: AttendeeSession): List<Contact>
    suspend fun replaceCard(session: AttendeeSession, version: Long, fields: CardFields): EmergencyCard
    suspend fun saveContact(session: AttendeeSession, version: Long, id: String?, fields: ContactFields): Long
    suspend fun deleteContact(session: AttendeeSession, version: Long, id: String): Long
    suspend fun events(session: AttendeeSession): List<SosEvent>
    suspend fun zones(session: AttendeeSession, eventId: String): List<SosZone>
    suspend fun incidents(session: AttendeeSession, eventId: String): List<Incident>
    suspend fun createSos(session: AttendeeSession, attempt: SosAttempt.Create): Incident
    suspend fun cancelSos(session: AttendeeSession, attempt: SosAttempt.Cancel): Incident
    suspend fun conversation(session: AttendeeSession, id: String, after: Long): ChatPage =
        throw AttendeeFailure(FailureKind.Response)
    suspend fun message(session: AttendeeSession, id: String, draft: ChatDraft): ChatWrite =
        throw AttendeeFailure(FailureKind.Response)
}

internal class HttpAttendeeApi(
    private val address: HttpUrl,
    private val client: OkHttpClient,
    private val clock: Clock = Clock.systemUTC(),
) : AttendeeApi {
    override suspend fun ticket(session: AttendeeSession, eventId: String): TicketReply =
        service(session, eventId, listOf("tickets", "mine"))

    override suspend fun promotions(session: AttendeeSession, eventId: String): PromotionReply =
        service(session, eventId, listOf("promotions"))

    override suspend fun companions(session: AttendeeSession, eventId: String): EventCompanions =
        service(session, eventId, listOf("companions"))

    override suspend fun invite(session: AttendeeSession, eventId: String): Invitation =
        service(session, eventId, listOf("companions", "invite"), "{}")

    override suspend fun acceptInvite(session: AttendeeSession, eventId: String, code: String): AcceptedInvitation =
        service(session, eventId, listOf("companions", "accept"), json.encodeToString(InvitationCode(code)))

    override suspend fun unlinkCompanion(session: AttendeeSession, eventId: String, id: String): ClosedCompanion =
        service(session, eventId, listOf("companions", id, "unlink"), "{}")

    private suspend inline fun <reified T> service(
        session: AttendeeSession, eventId: String, segments: List<String>, payload: String? = null,
    ): T {
        val url = sosEventEndpoint(eventId, segments.first()).newBuilder()
        segments.drop(1).forEach { url.addPathSegment(it) }
        val request = Request.Builder().url(url.build())
        if (payload != null) request.post(payload.toRequestBody(jsonMedia))
        return exchange(request, session.token)
    }

    override suspend fun health() {
        val result = get<Health>("health")
        checkReply(result.status == "ok" && result.database == "ok", FailureKind.Server)
    }

    override suspend fun login(account: DemoAccount, onCredential: () -> Unit): AttendeeSession {
        val result = send<SessionReply>("POST", "demo/sessions", null, json.encodeToString(Login(account.key)))
        checkReply(result.role == "ATTENDEE", FailureKind.Identity)
        checkReply(result.token.matches(Regex("[A-Za-z0-9_-]{43}")))
        val expires = parseInstant(result.expiresAt)
        checkReply(expires.isAfter(clock.instant()), FailureKind.Expired)
        val identity = get<Identity>("me", result.token)
        checkReply(identity.role == "ATTENDEE" && identity.person.account == account.key, FailureKind.Identity)
        checkReply(identity.person.id.isNotBlank() && identity.person.name.isNotBlank() &&
            (identity.person.age == null || identity.person.age >= 0))
        onCredential()
        val credential = try {
            get<Credential>("me/nfc-credential", result.token)
        } catch (error: AttendeeFailure) {
            if (error.kind == FailureKind.Missing) throw AttendeeFailure(FailureKind.NoCredential)
            throw error
        }
        checkReply(credential.active, FailureKind.NoCredential)
        val id = NfcId.parse(credential.token) ?: throw AttendeeFailure(FailureKind.Identity)
        checkReply(expires.isAfter(clock.instant()), FailureKind.Expired)
        return AttendeeSession(result.token, expires, identity.person, id)
    }

    override suspend fun card(session: AttendeeSession): EmergencyCard =
        get<EmergencyCard>("me/emergency-card", session.token).validated()

    override suspend fun contacts(session: AttendeeSession): List<Contact> =
        get<Contacts>("me/contacts", session.token).contacts.also { contacts ->
            checkReply(contacts.all { it.id.isNotBlank() && it.fields.valid() } &&
                contacts.map { it.id }.distinct().size == contacts.size)
        }

    override suspend fun replaceCard(session: AttendeeSession, version: Long, fields: CardFields): EmergencyCard =
        send<EmergencyCard>("PUT", "me/emergency-card", session.token, json.encodeToString(CardWrite(
            version, fields.bloodType, fields.allergies, fields.conditions, fields.medications,
        ))).validated().also { checkReply(it.version == version + 1) }

    override suspend fun saveContact(session: AttendeeSession, version: Long, id: String?, fields: ContactFields): Long {
        val result = send<ContactSaved>(if (id == null) "POST" else "PUT", "me/contacts", session.token,
            json.encodeToString(ContactWrite(version, fields.name, fields.relationship, fields.phone)), id)
        checkReply(result.cardVersion == version + 1 && result.contact.id.isNotBlank() &&
            (id == null || result.contact.id == id) && result.contact.fields == fields)
        return result.cardVersion
    }

    override suspend fun deleteContact(session: AttendeeSession, version: Long, id: String): Long =
        send<VersionReply>("DELETE", "me/contacts", session.token, json.encodeToString(VersionWrite(version)), id)
            .cardVersion.also { checkReply(it == version + 1) }

    override suspend fun events(session: AttendeeSession): List<SosEvent> =
        get<SosEvents>("events", session.token).events.filter { it.role == "ATTENDEE" }.also { events ->
            checkReply(events.all { it.id.validSegment() && it.name.isNotBlank() && it.venue.isNotBlank() } &&
                events.distinctBy { it.id }.size == events.size)
        }

    override suspend fun zones(session: AttendeeSession, eventId: String): List<SosZone> =
        exchange<SosZones>(Request.Builder().url(sosEventEndpoint(eventId, "zones")), session.token).zones.also { zones ->
            checkReply(zones.all { it.id.isNotBlank() && it.name.isNotBlank() } &&
                zones.distinctBy { it.id }.size == zones.size)
        }

    override suspend fun incidents(session: AttendeeSession, eventId: String): List<Incident> {
        val url = endpoint("me/incidents").newBuilder().addQueryParameter("eventId", eventId).build()
        return exchange<IncidentList>(Request.Builder().url(url), session.token).incidents.also { incidents ->
            checkReply(incidents.distinctBy { it.id }.size == incidents.size)
            incidents.forEach { it.validated(session, eventId) }
        }
    }

    override suspend fun createSos(session: AttendeeSession, attempt: SosAttempt.Create): Incident {
        val body = attempt.body
        val mode = attempt.mode
        val payload = if (mode == null) json.encodeToString(body) else json.encodeToString(HelpBody(
            body.clientRequestId, body.zoneId, body.locationText, body.reasonCode, mode, attempt.firstMessage,
        ))
        val request = Request.Builder().url(sosEventEndpoint(attempt.eventId, "incidents")).post(payload.toRequestBody(jsonMedia))
        val incident = if (mode == null) exchange<Incident>(request, session.token) else {
            val receipt = exchange<HelpReceipt>(request, session.token)
            val first = attempt.firstMessage
            val matched = if (first == null) receipt.message == null else receipt.message?.let {
                ChatWrite(it, receipt.conversation).valid(receipt.incident.id, first, ChatAuthorType.ATTENDEE) && it.seq == 1L
            } == true
            checkReply(receipt.incident.hasConversation && receipt.conversation.incidentId == receipt.incident.id && matched)
            receipt.incident
        }
        return incident.validated(session, attempt.eventId).also { checkReply(attempt.matches(it)) }
    }

    override suspend fun conversation(session: AttendeeSession, id: String, after: Long): ChatPage {
        checkReply(isIncidentUuid(id), FailureKind.Invalid)
        val url = endpoint("me/incidents/$id/conversation").newBuilder().addQueryParameter("afterSeq", after.toString()).build()
        return exchange<ChatPage>(Request.Builder().url(url), session.token).also { checkReply(it.valid(id, after)) }
    }

    override suspend fun message(session: AttendeeSession, id: String, draft: ChatDraft): ChatWrite {
        checkReply(isIncidentUuid(id), FailureKind.Invalid)
        return try {
            send<ChatWrite>("POST", "me/incidents/$id/conversation/messages", session.token, json.encodeToString(draft))
                .also { checkReply(it.valid(id, draft, ChatAuthorType.ATTENDEE)) }
        } catch (failure: AttendeeFailure) {
            if (failure.kind in setOf(FailureKind.Conflict, FailureKind.Forbidden, FailureKind.Invalid, FailureKind.Missing)) {
                throw ChatRejected()
            }
            throw failure
        }
    }

    override suspend fun cancelSos(session: AttendeeSession, attempt: SosAttempt.Cancel): Incident {
        checkReply(isIncidentUuid(attempt.id), FailureKind.Invalid)
        return exchange<Incident>(Request.Builder().url(endpoint("me/incidents/${attempt.id}/cancel"))
            .post(ByteArray(0).toRequestBody()), session.token).validated(session, attempt.eventId)
            .also { checkReply(attempt.matches(it)) }
    }

    private fun Incident.validated(session: AttendeeSession, eventId: String) = also {
        checkReply(validFor(eventId) && requesterPersonId == session.person.id && clientRequestId?.let(::isIncidentUuid) == true &&
            assignedStaff == null && resolutionNote == null)
    }

    private fun sosEventEndpoint(eventId: String, resource: String): HttpUrl {
        checkReply(eventId.validSegment(), FailureKind.Invalid)
        return endpoint("events").newBuilder().addPathSegment(eventId).addPathSegment(resource).build()
    }

    private fun EmergencyCard.validated() = also {
        checkReply(version in 1..MAX_WRITE_VERSION + 1 && fields.valid())
        parseInstant(updatedAt)
    }

    private suspend inline fun <reified T> get(path: String, token: String? = null): T =
        exchange(Request.Builder().url(endpoint(path)), token)

    private suspend inline fun <reified T> send(
        method: String, path: String, token: String?, payload: String, contactId: String? = null,
    ): T = exchange(Request.Builder().url(endpoint(path, contactId))
        .method(method, payload.toRequestBody(jsonMedia)), token)

    private fun endpoint(path: String, contactId: String? = null) =
        address.newBuilder().addPathSegments("api/v1/$path").apply {
            if (contactId != null) {
                checkReply(contactId.isNotBlank() && contactId !in setOf(".", ".."), FailureKind.Invalid)
                addPathSegment(contactId)
            }
        }.build()

    private suspend inline fun <reified T> exchange(builder: Request.Builder, token: String?): T {
        builder.header("Accept", "application/json").header("Cache-Control", "no-store")
        if (token != null) builder.header("Authorization", "Bearer $token")
        val body = read(client.newCall(builder.build()))
        return try {
            withContext(Dispatchers.Default) { json.decodeFromString<T>(body) }
        } catch (_: SerializationException) {
            throw AttendeeFailure(FailureKind.Response)
        }
    }

    private suspend fun read(call: Call): String = suspendCancellableCoroutine { result ->
        result.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                result.resumeWithException(e.failure())
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val content = response.use {
                        if (!it.isSuccessful) throw AttendeeFailure(when (it.code) {
                            400 -> FailureKind.Invalid
                            401 -> FailureKind.Expired
                            403 -> FailureKind.Forbidden
                            404 -> FailureKind.Missing
                            409 -> FailureKind.Conflict
                            else -> FailureKind.Server
                        })
                        val body = it.body ?: throw AttendeeFailure(FailureKind.Response)
                        val media = body.contentType()
                        checkReply(media?.type == "application" && media.subtype == "json")
                        val bytes = body.source()
                        checkReply(!bytes.request(256 * 1024 + 1L))
                        bytes.readUtf8()
                    }
                    result.resume(content)
                } catch (error: IOException) {
                    result.resumeWithException(error.failure())
                } catch (error: AttendeeFailure) {
                    result.resumeWithException(error)
                }
            }
        })
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val jsonMedia = "application/json; charset=utf-8".toMediaType()
        fun client(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS).callTimeout(8, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).build()
    }
}

private fun checkReply(ok: Boolean, failure: FailureKind = FailureKind.Response) {
    if (!ok) throw AttendeeFailure(failure)
}
private fun parseInstant(value: String): Instant =
    runCatching { Instant.parse(value) }.getOrElse { throw AttendeeFailure(FailureKind.Response) }
private fun IOException.failure() =
    AttendeeFailure(if (this is InterruptedIOException) FailureKind.Timeout else FailureKind.Offline)
private fun String.validSegment() = isNotBlank() && this !in setOf(".", "..")

@Serializable private class Health(val status: String, val database: String)
@Serializable internal data class DemoTicket(val code: String, val expiresAt: String, val usedAt: String?, val revokedAt: String?)
@Serializable internal data class TicketReply(val ticket: DemoTicket?)
@Serializable internal data class EventPromotion(
    val id: String, val title: String, val description: String, val imageUrl: String?, val linkUrl: String?,
    val endsAt: String,
)
@Serializable internal data class PromotionReply(val promotions: List<EventPromotion>)
@Serializable internal data class Invitation(val code: String, val expiresAt: String)
@Serializable private data class InvitationCode(val code: String)
@Serializable internal data class AcceptedInvitation(val id: String)
@Serializable internal data class ClosedCompanion(val closed: Boolean)
@Serializable private class Login(val account: String)
@Serializable private class SessionReply(val token: String, val role: String, val expiresAt: String)
@Serializable private class Identity(val person: Person, val role: String)
@Serializable private class Credential(val token: String, val active: Boolean)
@Serializable private class Contacts(val contacts: List<Contact>)
@Serializable private class SosEvents(val events: List<SosEvent>)
@Serializable private class SosZones(val zones: List<SosZone>)
@Serializable private class ContactSaved(val contact: Contact, val cardVersion: Long)
@Serializable private class VersionReply(val cardVersion: Long)
@Serializable private class VersionWrite(val version: Long)
@Serializable private class ContactWrite(val version: Long, val name: String, val relationship: String, val phone: String)
@Serializable private class CardWrite(
    val version: Long, val bloodType: String?, val allergies: List<String>,
    val conditions: List<String>, val medications: List<String>,
)
