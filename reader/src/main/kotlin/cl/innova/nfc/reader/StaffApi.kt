package cl.innova.nfc.reader

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

internal class StaffSession(val token: String, val expiresAt: Instant)
internal data class StaffEvent(val id: String, val name: String, val venue: String)
internal enum class ApiProblem { Network, Timeout, Unavailable, Unauthorized, Forbidden, NotFound, InvalidId, InvalidResponse, Server }
internal class ApiFailure(val problem: ApiProblem) : Exception(problem.name)

internal interface StaffApi {
    suspend fun health()
    suspend fun login(pin: String): StaffSession
    suspend fun events(session: StaffSession): List<StaffEvent>
    suspend fun resolve(session: StaffSession, event: StaffEvent, token: String): HealthProfile
}

internal class HttpStaffApi(private val address: HttpUrl, private val client: OkHttpClient) : StaffApi {
    override suspend fun health() {
        val reply = exchange<HealthReply>("health")
        requireReply(reply.status == "ok" && reply.database == "ok", ApiProblem.Unavailable)
    }

    override suspend fun login(pin: String): StaffSession {
        val reply = exchange<SessionReply>("demo/sessions", payload = json.encodeToString(LoginBody("guardia", pin)))
        requireReply(reply.role == "STAFF", ApiProblem.Forbidden)
        requireReply(reply.token.matches(Regex("[A-Za-z0-9_-]{43}")))
        return StaffSession(reply.token, instant(reply.expiresAt))
    }

    override suspend fun events(session: StaffSession): List<StaffEvent> =
        exchange<EventsReply>("events", session).events.filter { it.role == "STAFF" }.map {
            requireReply(it.id.isNotBlank() && it.name.isNotBlank() && it.venue.isNotBlank())
            StaffEvent(it.id, it.name, it.venue)
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

    private suspend inline fun <reified T> exchange(path: String, session: StaffSession? = null, payload: String? = null): T {
        val request = Request.Builder()
            .url(address.newBuilder().addPathSegments("api/v1/$path").build())
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
