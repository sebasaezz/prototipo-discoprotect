package cl.innova.nfc.emitter

import cl.innova.nfc.protocol.CardSession
import cl.innova.nfc.protocol.NfcId
import cl.innova.nfc.protocol.NfcProtocol
import java.net.InetAddress
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class HttpAttendeeApiTest {
    private val server = MockWebServer()
    private val http = HttpAttendeeApi.client().newBuilder().readTimeout(500, TimeUnit.MILLISECONDS).build()
    private lateinit var api: HttpAttendeeApi
    private val token = "a".repeat(43)
    private val expiry = "2090-01-01T00:00:00Z"
    private val person = Person("a", "antonia", "Antonia Reyes", 22)
    private val session = AttendeeSession(token, Instant.parse(expiry), person, requireNotNull(NfcId.parse(NfcProtocol.DEMO_PERSON_ID)))
    private val identity = """{"person":{"id":"a","account":"antonia","name":"Antonia Reyes","age":22},"role":"ATTENDEE"}"""
    private val credential = """{"token":"${NfcProtocol.DEMO_PERSON_ID}","active":true}"""
    private val card = """{"version":3,"updatedAt":"2026-11-15T14:00:00Z","bloodType":"A−","allergies":["Látex"],"conditions":["Asma"],"medications":["Inhalador"]}"""
    private val contact = """{"id":"new","name":"María","relationship":"Tía","phone":"+56 (9) 1234-5678"}"""

    @Test fun `SOS uses event and owner routes with a stable idempotency body after timeout`() = runBlocking {
        val clientId = "34522603-54fe-4ea1-af0b-39699c9515d2"
        val id = "34522603-54fe-4ea1-af0b-39699c9515d3"
        val attempt = SosAttempt.Create("festival", SosRequest(clientId, null, "Cerca del acceso"))
        val incident = """{"id":"$id","eventId":"festival","requesterPersonId":"a","requesterName":"Antonia Reyes",
            "zoneId":null,"zoneName":null,"locationText":"Cerca del acceso","status":"OPEN","clientRequestId":"$clientId",
            "createdAt":"2026-11-15T14:00:00Z","updatedAt":"2026-11-15T14:00:00Z"}"""
        reply("""{"events":[{"id":"festival","name":"Festival","venue":"Recinto","role":"ATTENDEE"}]}""")
        assertEquals("festival", api.events(session).single().id)
        verify("GET", "events")
        reply("""{"zones":[{"id":"escenario","name":"Escenario"}]}""")
        assertEquals("escenario", api.zones(session, "festival").single().id)
        verify("GET", "events/festival/zones")

        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        expect(FailureKind.Timeout) { api.createSos(session, attempt) }
        val sent = verify("POST", "events/festival/incidents").body.readUtf8()
        assertEquals("""{"clientRequestId":"$clientId","zoneId":null,"locationText":"Cerca del acceso"}""", sent)
        reply(incident)
        assertEquals(id, api.createSos(session, attempt).id)
        assertEquals(sent, verify("POST", "events/festival/incidents").body.readUtf8())
        reply("""{"incidents":[$incident]}""")
        assertEquals(id, api.incidents(session, "festival").single().id)
        verify("GET", "me/incidents?eventId=festival")
        reply(incident.replace("OPEN", "CANCELLED"))
        assertEquals("CANCELLED", api.cancelSos(session, SosAttempt.Cancel("festival", id)).status.name)
        verify("POST", "me/incidents/$id/cancel")
        reply("""{"incidents":[${incident.replace("\"a\"", "\"otro\"")}]}""")
        expect(FailureKind.Response) { api.incidents(session, "festival") }
        reply("{}", 401)
        expect(FailureKind.Expired) { api.incidents(session, "festival") }
    }

    @Before fun start() {
        server.start(InetAddress.getLoopbackAddress(), 0)
        api = HttpAttendeeApi(server.url("/"), http)
    }

    @After fun stop() {
        server.shutdown()
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }

    @Test fun `health distinguishes valid unhealthy timeout and refused connection`() = runBlocking {
        reply("""{"status":"ok","database":"ok"}""")
        api.health()
        verify("GET", "health", authenticated = false)
        reply("""{"status":"ok","database":"down"}""")
        expect(FailureKind.Server) { api.health() }
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        expect(FailureKind.Timeout) { api.health() }
        val closed = MockWebServer()
        closed.start(InetAddress.getLoopbackAddress(), 0)
        val url = closed.url("/")
        closed.close()
        expect(FailureKind.Offline) { HttpAttendeeApi(url, http).health() }
    }

    @Test fun `URL validation and HTTPS in release`() {
        assertNotNull(attendeeServerAddress("http://192.168.1.42:3000", true))
        assertNotNull(attendeeServerAddress("https://pc.example:443/", false))
        assertNull(attendeeServerAddress("http://192.168.1.42", false))
        for (bad in listOf("pc:3000", "http://localhost", "http://127.0.0.1", "https://user:pass@pc",
            "http://pc/api/v1", "http://pc/?x=1", "http://pc#fragment", "http://[::1]")) {
            assertNull(bad, attendeeServerAddress(bad, true))
        }
    }

    @Test fun `Antonia logs in and proves account and active credential`() = runBlocking {
        reply(login())
        reply(identity)
        reply(credential)
        val active = api.login(DemoAccount.Antonia)
        assertEquals(person, active.person)
        assertEquals(token, active.token)
        assertEquals(session.expiresAt, active.expiresAt)
        assertEquals("""{"account":"antonia"}""", verify("POST","demo/sessions",false).body.readUtf8())
        verify("GET", "me")
        verify("GET", "me/nfc-credential")
        Unit
    }

    @Test fun `three accounts use the credential returned by REST`() = runBlocking {
        DemoAccount.entries.forEach { account ->
            val mock = demoSession(account, Instant.parse(expiry))
            reply(login())
            reply("""{"person":{"id":"${mock.person.id}","account":"${account.key}","name":"Nombre confirmado","age":null},"role":"ATTENDEE"}""")
            reply("""{"token":"${mock.nfcId.hex.lowercase()}","active":true}""")
            var fetching = false
            val result = api.login(account) { fetching = true }
            assertTrue(fetching)
            assertEquals("Nombre confirmado", result.person.name)
            assertEquals(mock.nfcId.hex, result.nfcId.hex)
            assertEquals("""{"account":"${account.key}"}""", verify("POST", "demo/sessions", false).body.readUtf8())
            verify("GET", "me")
            verify("GET", "me/nfc-credential")
        }
    }

    @Test fun `valid credential contents are taken from the server, not the seed`() = runBlocking {
        val alternate = "ABCDEF0123456789".repeat(2)
        reply(login())
        reply(identity)
        reply(credential.replace(NfcProtocol.DEMO_PERSON_ID, alternate))
        assertEquals(alternate, api.login(DemoAccount.Antonia).nfcId.hex)
    }

    @Test fun `wrong role account and malformed credentials block verified session`() = runBlocking {
        for (replies in listOf(listOf(login("STAFF")), listOf(login(), identity.replace("antonia", "martin")),
            listOf(login(), identity.replace("ATTENDEE", "STAFF")),
            listOf(login(), identity, credential.replace(NfcProtocol.DEMO_PERSON_ID, "G".repeat(32))),
            listOf(login(), identity, credential.replace(NfcProtocol.DEMO_PERSON_ID, "A".repeat(31))))) {
            replies.forEach { reply(it) }
            expect(FailureKind.Identity) { api.login(DemoAccount.Antonia) }
        }
        assertTrue((0 until server.requestCount).none { request().path == "/api/v1/me/emergency-card" })
    }

    @Test fun `inactive and missing credentials are distinguished from expired session or malformed JSON`() = runBlocking {
        reply(login())
        reply(identity)
        reply(credential.replace("true", "false"))
        expect(FailureKind.NoCredential) { api.login(DemoAccount.Antonia) }
        reply(login())
        reply(identity)
        reply("{}", 404)
        expect(FailureKind.NoCredential) { api.login(DemoAccount.Antonia) }
        reply(login().replace(expiry, "2000-01-01T00:00:00Z"))
        expect(FailureKind.Expired) { api.login(DemoAccount.Antonia) }
        reply(login())
        reply("{")
        expect(FailureKind.Response) { api.login(DemoAccount.Antonia) }
    }

    @Test fun `a failed login cannot enable any HCE response`() = runBlocking {
        reply(login("STAFF"))
        val registry = HceIdentityStore()
        val hce = CardSession { registry.current()?.credential }
        expect(FailureKind.Identity) { api.login(DemoAccount.Antonia) }
        assertEquals("6A82", NfcProtocol.toHex(hce.process(NfcProtocol.selectAid())))
        assertEquals("6A82", NfcProtocol.toHex(hce.process(NfcProtocol.getPersonId())))
    }

    @Test fun `card preserves fields and sends UTF-8 null type and separate lists`() = runBlocking {
        reply(card)
        val original = api.card(session)
        assertEquals("A−", original.bloodType)
        assertEquals(listOf("Látex"), original.allergies)
        verify("GET", "me/emergency-card")
        reply(card.replace(":3,", ":4,").replace("Látex", "Maní"))
        assertEquals(4L, api.replaceCard(session, 3, original.fields.copy(allergies = listOf("Maní"))).version)
        assertEquals("""{"version":3,"bloodType":"A−","allergies":["Maní"],"conditions":["Asma"],"medications":["Inhalador"]}""",
            verify("PUT", "me/emergency-card").body.readUtf8())
        reply("""{"version":5,"updatedAt":"2026-11-15T14:00:00Z","bloodType":null,"allergies":[],"conditions":[],"medications":[]}""")
        val result = api.replaceCard(session, 4, CardFields(null, emptyList(), emptyList(), emptyList()))
        assertNull(result.bloodType)
        assertTrue(result.allergies.isEmpty())
        assertEquals("""{"version":4,"bloodType":null,"allergies":[],"conditions":[],"medications":[]}""",
            verify("PUT", "me/emergency-card").body.readUtf8())
    }

    @Test fun `contacts use exact methods and the version returned by last write`() = runBlocking {
        reply("""{"contacts":[$contact]}""")
        val entry = api.contacts(session).single()
        assertEquals("María", entry.name)
        verify("GET", "me/contacts")
        reply("""{"contact":$contact,"cardVersion":4}""", 201)
        val added = api.saveContact(session, 3, null, entry.fields)
        assertEquals("""{"version":3,"name":"María","relationship":"Tía","phone":"+56 (9) 1234-5678"}""",
            verify("POST", "me/contacts").body.readUtf8())
        reply("""{"contact":${contact.replace("Tía","Amiga")},"cardVersion":5}""")
        val edited = api.saveContact(session, added, entry.id, entry.fields.copy(relationship = "Amiga"))
        assertTrue(verify("PUT", "me/contacts/new").body.readUtf8().contains(""""version":4"""))
        reply("""{"cardVersion":6}""")
        assertEquals(6L, api.deleteContact(session, edited, entry.id))
        assertEquals("""{"version":5}""", verify("DELETE", "me/contacts/new").body.readUtf8())
        reply("{}",404)
        expect(FailureKind.Missing) { api.deleteContact(session, 6, "bad/id") }
        assertEquals("/api/v1/me/contacts/bad%2Fid", request().path)
    }

    @Test fun `malformed JSON wrong version and HTTP errors do not confirm writes`() = runBlocking {
        reply("bad")
        expect(FailureKind.Response) { api.card(session) }
        for ((status, kind) in listOf(400 to FailureKind.Invalid, 401 to FailureKind.Expired,
            403 to FailureKind.Forbidden, 409 to FailureKind.Conflict, 500 to FailureKind.Server)) {
            reply("{}", status)
            expect(kind) { api.replaceCard(session, 3, CardFields(null, emptyList(), emptyList(), emptyList())) }
        }
        reply(card)
        expect(FailureKind.Response) { api.replaceCard(session, 3, CardFields(null, emptyList(), emptyList(), emptyList())) }
        server.enqueue(MockResponse().setResponseCode(307).addHeader("Location", server.url("/redirect")))
        expect(FailureKind.Server) { api.card(session) }
        assertEquals(8, server.requestCount)
    }

    private fun login(role: String = "ATTENDEE") = """{"token":"$token","role":"$role","expiresAt":"$expiry"}"""
    private fun reply(body: String, status: Int = 200) {
        server.enqueue(MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json; charset=utf-8").setBody(body))
    }
    private fun request() = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
    private fun verify(method: String, path: String, authenticated: Boolean = true) = request().also {
        assertEquals(method, it.method)
        assertEquals("/api/v1/$path", it.path)
        assertEquals(if (authenticated) "Bearer $token" else null, it.getHeader("Authorization"))
    }
    private suspend fun expect(kind: FailureKind, block: suspend () -> Unit) {
        try {
            block()
            fail("Se esperaba $kind")
        } catch (error: AttendeeFailure) {
            assertEquals(kind, error.kind)
        }
    }
}
