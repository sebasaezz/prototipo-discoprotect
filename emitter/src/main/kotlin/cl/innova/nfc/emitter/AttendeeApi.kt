package cl.innova.nfc.emitter

import cl.innova.nfc.protocol.NfcProtocol
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
    Expired("La sesión dejó de ser válida. Vuelve a iniciar sesión como Antonia."),
    Forbidden("La sesión no tiene permiso para esta operación."),
    Missing("La ficha o el contacto solicitado ya no está disponible."),
    Conflict("La ficha cambió en el servidor. Revisa la versión vigente antes de guardar."),
    Identity("La sesión o la credencial no coincide con el perfil NFC de Antonia."),
    Response("La respuesta no corresponde al contrato de Discoprotect."),
    Server("El servidor no pudo completar la consulta."),
    Changed("La ficha se actualizó durante la consulta. Vuelve a cargarla."),
}
internal class AttendeeFailure(val kind: FailureKind) : Exception(kind.name)

internal class AttendeeSession(val token: String, val expiresAt: Instant, val person: Person)

internal interface AttendeeApi {
    suspend fun health()
    suspend fun login(): AttendeeSession
    suspend fun card(session: AttendeeSession): EmergencyCard
    suspend fun contacts(session: AttendeeSession): List<Contact>
    suspend fun replaceCard(session: AttendeeSession, version: Long, fields: CardFields): EmergencyCard
    suspend fun saveContact(session: AttendeeSession, version: Long, id: String?, fields: ContactFields): Long
    suspend fun deleteContact(session: AttendeeSession, version: Long, id: String): Long
}

internal class HttpAttendeeApi(private val address: HttpUrl, private val client: OkHttpClient) : AttendeeApi {
    override suspend fun health() {
        val result = get<Health>("health")
        checkReply(result.status == "ok" && result.database == "ok", FailureKind.Server)
    }

    override suspend fun login(): AttendeeSession {
        val result = send<SessionReply>("POST", "demo/sessions", null, json.encodeToString(Login("antonia")))
        checkReply(result.role == "ATTENDEE", FailureKind.Identity)
        checkReply(result.token.matches(Regex("[A-Za-z0-9_-]{43}")))
        val expires = parseInstant(result.expiresAt)
        val identity = get<Identity>("me", result.token)
        checkReply(identity.role == "ATTENDEE" && identity.person.account == "antonia", FailureKind.Identity)
        checkReply(identity.person.id.isNotBlank() && identity.person.name.isNotBlank() &&
            (identity.person.age == null || identity.person.age >= 0))
        val credential = try {
            get<Credential>("me/nfc-credential", result.token)
        } catch (error: AttendeeFailure) {
            if (error.kind == FailureKind.Missing) throw AttendeeFailure(FailureKind.Identity)
            throw error
        }
        checkReply(credential.active && credential.token.equals(NfcProtocol.DEMO_PERSON_ID, ignoreCase = true),
            FailureKind.Identity)
        return AttendeeSession(result.token, expires, identity.person)
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

@Serializable private class Health(val status: String, val database: String)
@Serializable private class Login(val account: String)
@Serializable private class SessionReply(val token: String, val role: String, val expiresAt: String)
@Serializable private class Identity(val person: Person, val role: String)
@Serializable private class Credential(val token: String, val active: Boolean)
@Serializable private class Contacts(val contacts: List<Contact>)
@Serializable private class ContactSaved(val contact: Contact, val cardVersion: Long)
@Serializable private class VersionReply(val cardVersion: Long)
@Serializable private class VersionWrite(val version: Long)
@Serializable private class ContactWrite(val version: Long, val name: String, val relationship: String, val phone: String)
@Serializable private class CardWrite(
    val version: Long, val bloodType: String?, val allergies: List<String>,
    val conditions: List<String>, val medications: List<String>,
)
