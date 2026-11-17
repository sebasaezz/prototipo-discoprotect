package cl.innova.nfc.reader

import cl.discoprotect.ui.*
import java.io.IOException
import java.io.InterruptedIOException
import java.net.URI
import java.time.Instant
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

internal fun serverAddress(input: String, allowHttp: Boolean): HttpUrl? {
    val text = input.trim()
    val uri = runCatching { URI(text) }.getOrNull() ?: return null
    val url = text.toHttpUrlOrNull() ?: return null
    if (uri.rawAuthority.isNullOrBlank() || uri.rawUserInfo != null ||
        uri.rawQuery != null || uri.rawFragment != null || uri.rawPath !in listOf("", "/")) return null
    if (!url.isHttps && !allowHttp) return null
    if (url.host in setOf("localhost", "localhost.", "0.0.0.0", "::", "::1") || url.host.startsWith("127.")) return null
    return url
}

internal enum class StaffAccount(val key: String, val label: String) {
    First("guardia", "Guardia Demo"), Second("guardia2", "Guardia Demo 2"),
}
internal class StaffSession(val token: String, val expiresAt: Instant, val personId: String, val name: String)
internal data class StaffEvent(val id: String, val name: String, val venue: String)
internal enum class ApiProblem { Network, Timeout, Unavailable, Unauthorized, Forbidden, NotFound, InvalidId, InvalidResponse, Server, Conflict, InvalidInput }
internal class ApiFailure(val problem: ApiProblem) : Exception(problem.name)

internal enum class IncidentAction(val path: String, val label: String, val from: IncidentStatus, val to: IncidentStatus) {
    Claim("claim", "Asumir", IncidentStatus.OPEN, IncidentStatus.ASSIGNED),
    Start("start", "Iniciar atención", IncidentStatus.ASSIGNED, IncidentStatus.IN_PROGRESS),
    Resolve("resolve", "Resolver", IncidentStatus.IN_PROGRESS, IncidentStatus.RESOLVED),
    Close("close", "Cerrar solicitud", IncidentStatus.ASSIGNED, IncidentStatus.CLOSED);

    fun accepts(status: IncidentStatus) = status == from || this == Close && status == IncidentStatus.IN_PROGRESS
}

@Serializable internal data class StaffIncidentQueue(
    val incidents: List<Incident>, val guardBusy: Boolean = false, val activeIncident: Incident? = null,
)
internal data class IncidentCommand(val incidentId: String, val action: IncidentAction, val body: OperationBody)
@Serializable internal data class OperationBody(val version: Long, val operationId: String, val resolutionNote: String? = null)
@Serializable internal data class AppliedOperation(
    val operationId: String, val fromStatus: IncidentStatus, val toStatus: IncidentStatus,
    val appliedVersion: Long, val appliedAt: String, val replayed: Boolean,
)
@Serializable internal data class OperationReply(val operation: AppliedOperation, val incident: Incident)
@Serializable internal data class TriageDetail(val triage: IncidentTriage, val description: String?)
@Serializable internal data class ReviewBody(
    val triageVersion: Long, val operationId: String, val decision: ReviewDecision,
    val category: TriageCategory? = null, val urgency: TriageUrgency? = null,
)
@Serializable internal data class ReviewReply(val review: TriageReview, val triage: IncidentTriage, val replayed: Boolean)
@Serializable internal data class DispatchZone(val id: String, val name: String)
@Serializable internal data class DispatchLocationInput(val zoneId: String?, val text: String?)
@Serializable internal data class DispatchReviewBody(
    val expectedRevision: Long, val operationId: String, val humanPriority: DispatchPriority?,
    val confirmedLocation: DispatchLocationInput?, val dangerAssessment: DangerAssessment, val reason: String,
)
@Serializable internal data class DispatchDetail(val dispatch: IncidentDispatch, val zones: List<DispatchZone>)
@Serializable internal data class DispatchReview(val revision: Long, val operationId: String)
@Serializable internal data class DispatchReply(val review: DispatchReview, val dispatch: IncidentDispatch, val replayed: Boolean)

internal interface StaffApi {
    suspend fun health()
    suspend fun login(pin: String, account: StaffAccount = StaffAccount.First): StaffSession
    suspend fun events(session: StaffSession): List<StaffEvent>
    suspend fun incidents(session: StaffSession, event: StaffEvent): List<Incident>
    suspend fun queue(session: StaffSession, event: StaffEvent): StaffIncidentQueue {
        val incidents = incidents(session, event)
        val own = incidents.firstOrNull { it.status.active && it.assignedStaff?.id == session.personId }
        return StaffIncidentQueue(incidents, own != null, own)
    }
    suspend fun operate(session: StaffSession, event: StaffEvent, command: IncidentCommand): OperationReply
    suspend fun triage(session: StaffSession, event: StaffEvent, id: String): TriageDetail
    suspend fun review(session: StaffSession, event: StaffEvent, id: String, body: ReviewBody): ReviewReply
    suspend fun resolve(session: StaffSession, event: StaffEvent, token: String): HealthProfile
    suspend fun conversation(session: StaffSession, event: StaffEvent, id: String, after: Long): ChatPage =
        throw ApiFailure(ApiProblem.InvalidResponse)
    suspend fun message(session: StaffSession, event: StaffEvent, id: String, draft: ChatDraft): ChatWrite =
        throw ApiFailure(ApiProblem.InvalidResponse)
    suspend fun dispatch(session: StaffSession, event: StaffEvent, id: String): DispatchDetail =
        throw ApiFailure(ApiProblem.InvalidResponse)
    suspend fun reviewDispatch(session: StaffSession, event: StaffEvent, id: String, body: DispatchReviewBody): DispatchReply =
        throw ApiFailure(ApiProblem.InvalidResponse)
}

internal class HttpStaffApi(private val address: HttpUrl, private val client: OkHttpClient) : StaffApi {
    override suspend fun health() {
        val reply = exchange<HealthReply>("health")
        requireReply(reply.status == "ok" && reply.database == "ok", ApiProblem.Unavailable)
    }

    override suspend fun login(pin: String, account: StaffAccount): StaffSession {
        val reply = exchange<SessionReply>("demo/sessions", payload = json.encodeToString(LoginBody(account.key, pin)))
        requireReply(reply.role == "STAFF", ApiProblem.Forbidden)
        requireReply(reply.token.matches(Regex("[A-Za-z0-9_-]{43}")))
        val active = StaffSession(reply.token, instant(reply.expiresAt), "", "")
        val identity = exchange<StaffIdentity>("me", active)
        requireReply(identity.role == "STAFF" && identity.person.account == account.key &&
            identity.person.id.isNotBlank() && identity.person.name.isNotBlank())
        return StaffSession(active.token, active.expiresAt, identity.person.id, identity.person.name)
    }

    override suspend fun events(session: StaffSession): List<StaffEvent> =
        exchange<EventsReply>("events", session).events.filter { it.role == "STAFF" }.map {
            requireReply(it.id.isNotBlank() && it.name.isNotBlank() && it.venue.isNotBlank())
            StaffEvent(it.id, it.name, it.venue)
        }

    override suspend fun incidents(session: StaffSession, event: StaffEvent): List<Incident> = queue(session, event).incidents

    override suspend fun queue(session: StaffSession, event: StaffEvent): StaffIncidentQueue {
        return exchange<StaffIncidentQueue>(incidentUrl(event).addQueryParameter("order", "dispatch").build(), session).also { queue ->
            requireReply(queue.incidents.all { it.validOperational(event.id) } &&
                queue.incidents.distinctBy { it.id }.size == queue.incidents.size)
            queue.activeIncident?.let {
                requireReply(queue.guardBusy && it.validOperational(it.eventId) &&
                    it.status.active && it.assignedStaff?.id == session.personId)
            }
        }
    }

    private fun incidentUrl(event: StaffEvent): HttpUrl.Builder {
        requireReply(event.id.isNotBlank() && event.id !in setOf(".", ".."))
        return address.newBuilder().addPathSegments("api/v1/events")
            .addPathSegment(event.id).addPathSegment("incidents")
    }

    override suspend fun operate(session: StaffSession, event: StaffEvent, command: IncidentCommand): OperationReply {
        requireReply(isIncidentUuid(command.incidentId) && isIncidentUuid(command.body.operationId) && command.body.version > 0,
            ApiProblem.InvalidInput)
        val url = incidentUrl(event).addPathSegment(command.incidentId).addPathSegment(command.action.path).build()
        return exchange<OperationReply>(url, session, json.encodeToString(command.body)).also { (operation, incident) ->
            val actionTime = when (command.action) {
                IncidentAction.Claim -> incident.assignedAt
                IncidentAction.Start -> incident.startedAt
                IncidentAction.Resolve -> incident.resolvedAt
                IncidentAction.Close -> incident.updatedAt
            }
            requireReply(incident.validOperational(event.id) && incident.id == command.incidentId &&
                incident.assignedStaff?.id == session.personId && operation.operationId == command.body.operationId &&
                command.action.accepts(operation.fromStatus) && operation.toStatus == command.action.to &&
                operation.appliedVersion == command.body.version + 1 && operation.appliedAt == actionTime &&
                incident.version >= operation.appliedVersion &&
                (operation.replayed || incident.version == operation.appliedVersion) &&
                (incident.version > operation.appliedVersion || incident.status == operation.toStatus))
        }
    }

    private fun triageUrl(event: StaffEvent, id: String): HttpUrl.Builder {
        requireReply(isIncidentUuid(id), ApiProblem.InvalidInput)
        return incidentUrl(event).addPathSegment(id).addPathSegment("triage")
    }

    private fun conversationUrl(event: StaffEvent, id: String): HttpUrl.Builder {
        requireReply(isIncidentUuid(id), ApiProblem.InvalidInput)
        return incidentUrl(event).addPathSegment(id).addPathSegment("conversation")
    }

    private fun dispatchUrl(event: StaffEvent, id: String): HttpUrl.Builder {
        requireReply(isIncidentUuid(id), ApiProblem.InvalidInput)
        return incidentUrl(event).addPathSegment(id).addPathSegment("dispatch")
    }

    override suspend fun dispatch(session: StaffSession, event: StaffEvent, id: String): DispatchDetail =
        exchange(dispatchUrl(event, id).build(), session)

    override suspend fun reviewDispatch(session: StaffSession, event: StaffEvent, id: String, body: DispatchReviewBody): DispatchReply =
        exchange<DispatchReply>(dispatchUrl(event, id).addPathSegment("reviews").build(), session, json.encodeToString(body)).also {
            requireReply(it.review.operationId == body.operationId && it.review.revision == body.expectedRevision + 1 &&
                it.dispatch.revision >= it.review.revision)
        }

    override suspend fun conversation(session: StaffSession, event: StaffEvent, id: String, after: Long): ChatPage =
        exchange<ChatPage>(conversationUrl(event, id).addQueryParameter("afterSeq", after.toString()).build(), session)
            .also { requireReply(it.valid(id, after)) }

    override suspend fun message(session: StaffSession, event: StaffEvent, id: String, draft: ChatDraft): ChatWrite =
        try {
            exchange<ChatWrite>(conversationUrl(event, id).addPathSegment("messages").build(), session, json.encodeToString(draft))
                .also { requireReply(it.valid(id, draft, ChatAuthorType.STAFF)) }
        } catch (failure: ApiFailure) {
            if (failure.problem in setOf(ApiProblem.Conflict, ApiProblem.Forbidden, ApiProblem.NotFound, ApiProblem.InvalidInput)) {
                throw ChatRejected()
            }
            throw failure
        }

    override suspend fun triage(session: StaffSession, event: StaffEvent, id: String): TriageDetail =
        exchange<TriageDetail>(triageUrl(event, id).build(), session).also {
            requireReply(it.triage.valid() && (it.description == null ||
                it.description.isNotBlank() && it.description.length <= 280))
        }

    override suspend fun review(session: StaffSession, event: StaffEvent, id: String, body: ReviewBody): ReviewReply {
        val url = triageUrl(event, id).addPathSegment("reviews").build()
        return exchange<ReviewReply>(url, session, json.encodeToString(body)).also { (review, triage, replayed) ->
            requireReply(review.valid() && triage.valid() && review.actor.id == session.personId &&
                review.operationId == body.operationId && review.reviewedTriageVersion == body.triageVersion &&
                review.decision == body.decision &&
                (body.decision == ReviewDecision.CONFIRM || review.category == body.category && review.urgency == body.urgency) &&
                triage.triageVersion >= review.reviewVersion && (replayed || triage.latestReview == review))
        }
    }

    override suspend fun resolve(session: StaffSession, event: StaffEvent, token: String): HealthProfile {
        if (!token.matches(Regex("[a-fA-F0-9]{32}"))) throw ApiFailure(ApiProblem.InvalidId)
        val reply = exchange<ProfileReply>("nfc/resolve", session, json.encodeToString(ResolveBody(token, event.id)))
        requireReply(reply.person.name.isNotBlank() && (reply.person.age == null || reply.person.age >= 0))
        val card = reply.card
        requireReply(card.version > 0)
        val contacts = reply.contacts.map {
            requireReply(it.name.isNotBlank() && it.relationship.isNotBlank() && it.phone.isNotBlank())
            EmergencyContact(it.name, it.relationship, it.phone)
        }
        return HealthProfile(reply.person.name, reply.person.age, card.bloodType, card.allergies,
            card.conditions, card.medications, contacts, instant(card.updatedAt), card.version)
    }

    private suspend inline fun <reified T> exchange(path: String, session: StaffSession? = null, payload: String? = null): T =
        exchange(address.newBuilder().addPathSegments("api/v1/$path").build(), session, payload)

    private suspend inline fun <reified T> exchange(url: HttpUrl, session: StaffSession? = null, payload: String? = null): T {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("Cache-Control", "no-store")
        if (session != null) request.header("Authorization", "Bearer ${session.token}")
        if (payload != null) request.post(payload.toRequestBody(jsonMedia))
        val body = client.newCall(request.build()).read()
        return try {
            withContext(Dispatchers.Default) { json.decodeFromString<T>(body) }
        } catch (_: SerializationException) {
            throw ApiFailure(ApiProblem.InvalidResponse)
        }
    }

    private suspend fun Call.read(): String = suspendCancellableCoroutine { result ->
        result.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                result.resumeWithException(e.toApiFailure())
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val text = response.use {
                        if (!it.isSuccessful) throw ApiFailure(when (it.code) {
                            401 -> ApiProblem.Unauthorized
                            403 -> ApiProblem.Forbidden
                            404 -> ApiProblem.NotFound
                            409 -> ApiProblem.Conflict
                            400 -> ApiProblem.InvalidInput
                            else -> ApiProblem.Server
                        })
                        val body = it.body ?: throw ApiFailure(ApiProblem.InvalidResponse)
                        val media = body.contentType()
                        requireReply(media?.type == "application" && media.subtype == "json")
                        val source = body.source()
                        requireReply(!source.request(256 * 1024 + 1L))
                        source.readUtf8()
                    }
                    result.resume(text)
                } catch (error: ApiFailure) {
                    result.resumeWithException(error)
                } catch (error: IOException) {
                    result.resumeWithException(error.toApiFailure())
                }
            }
        })
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val jsonMedia = "application/json; charset=utf-8".toMediaType()

        fun client(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()
    }
}

private fun Incident.validOperational(eventId: String): Boolean = validFor(eventId) &&
    (assignedStaff != null) == (assignedAt != null) &&
    (assignedStaff == null || assignedStaff.id.isNotBlank() && assignedStaff.name.isNotBlank()) &&
    (resolutionNote == null || status in setOf(IncidentStatus.RESOLVED, IncidentStatus.CLOSED, IncidentStatus.WITHDRAWN) &&
        resolutionNote.isNotBlank() && resolutionNote.length <= 240) &&
    dangerAlert == reasonCode?.takeIf { it.alert != null } && (triage == null || triage.valid())

private fun requireReply(condition: Boolean, problem: ApiProblem = ApiProblem.InvalidResponse) {
    if (!condition) throw ApiFailure(problem)
}

private fun instant(value: String): Instant =
    runCatching { Instant.parse(value) }.getOrElse { throw ApiFailure(ApiProblem.InvalidResponse) }

private fun IOException.toApiFailure() =
    ApiFailure(if (this is InterruptedIOException) ApiProblem.Timeout else ApiProblem.Network)

@Serializable private class HealthReply(val status: String, val database: String)
@Serializable private class LoginBody(val account: String, val pin: String)
@Serializable private class SessionReply(val token: String, val role: String, val expiresAt: String)
@Serializable private class StaffIdentity(val person: StaffPerson, val role: String)
@Serializable private class StaffPerson(val id: String, val account: String, val name: String)
@Serializable private class EventsReply(val events: List<EventReply>)
@Serializable private class EventReply(val id: String, val name: String, val venue: String, val role: String)
@Serializable private class ResolveBody(val token: String, val eventId: String)
@Serializable private class ProfileReply(val person: PersonReply, val card: CardReply, val contacts: List<ContactReply>)
@Serializable private class PersonReply(val name: String, val age: Int?)
@Serializable private class CardReply(
    val bloodType: String?, val allergies: List<String>, val conditions: List<String>,
    val medications: List<String>, val updatedAt: String, val version: Int,
)
@Serializable private class ContactReply(val name: String, val relationship: String, val phone: String)
