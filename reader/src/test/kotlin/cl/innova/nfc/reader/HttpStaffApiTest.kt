package cl.innova.nfc.reader

import cl.innova.nfc.protocol.NfcProtocol
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class HttpStaffApiTest {
    private val server = MockWebServer()
    private val client = HttpStaffApi.client().newBuilder()
        .readTimeout(350, TimeUnit.MILLISECONDS)
        .callTimeout(2, TimeUnit.SECONDS)
        .build()
    private lateinit var api: HttpStaffApi
    private val token = "a".repeat(43)
    private val expiry = java.time.Instant.parse("2030-01-01T00:00:00Z")
    private val event = StaffEvent("demo-festival", "Festival Demo", "Recinto ficticio")
    private val session = StaffSession(token, expiry)

    @Before fun setUp() {
        server.start(InetAddress.getLoopbackAddress(), 0)
        api = HttpStaffApi(server.url("/"), client)
    }

    @After fun close() {
        server.close()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    @Test fun `health validates both statuses`() = runBlocking {
        reply("""{"status":"ok","database":"ok"}""")
        api.health()
        assertEquals("GET /api/v1/health HTTP/1.1", request().requestLine)
        reply("""{"status":"ok","database":"down"}""")
        fails(ApiProblem.Unavailable) { api.health() }
    }

    @Test fun `closed server and response timeout are distinct`() = runBlocking {
        val closed = MockWebServer()
        closed.start(InetAddress.getLoopbackAddress(), 0)
        val url = closed.url("/")
        closed.close()
        fails(ApiProblem.Network) { HttpStaffApi(url, client).health() }
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        fails(ApiProblem.Timeout) { api.health() }
    }

    @Test fun `login submits the guard account and handles rejected credentials`() = runBlocking {
        reply("""{"token":"$token","role":"STAFF","expiresAt":"$expiry"}""", 201)
        val active = api.login("pin-de-prueba")
        assertEquals(expiry, active.expiresAt)
        assertEquals(token, active.token)
        val request = request()
        assertEquals("POST /api/v1/demo/sessions HTTP/1.1", request.requestLine)
        assertEquals("""{"account":"guardia","pin":"pin-de-prueba"}""", request.body.readUtf8())
        assertNull(request.getHeader("Authorization"))

        reply("{}", 401)
        fails(ApiProblem.Unauthorized) { api.login("incorrecto") }
        reply("""{"token":"$token","role":"ATTENDEE","expiresAt":"$expiry"}""")
        fails(ApiProblem.Forbidden) { api.login("pin-de-prueba") }
    }

    @Test fun `malformed and incomplete JSON cannot look like successful responses`() = runBlocking {
        reply("{incompleto")
        fails(ApiProblem.InvalidResponse) { api.health() }
        reply("""{"status":"ok"}""")
        fails(ApiProblem.InvalidResponse) { api.health() }
        reply(profile.replace("[]", "[15]"))
        fails(ApiProblem.InvalidResponse) { api.resolve(session, event, NfcProtocol.DEMO_PERSON_ID) }
    }

    @Test fun `events filters staff memberships and uses Bearer`() = runBlocking {
        reply("""{"events":[
            {"id":"demo-festival","name":"Festival Demo","venue":"Recinto ficticio","role":"STAFF"},
            {"id":"other","name":"Otro","venue":"Recinto B","role":"ATTENDEE"}
        ]}""".trimIndent())
        assertEquals(listOf(event), api.events(session))
        val request = request()
        assertEquals("GET /api/v1/events HTTP/1.1", request.requestLine)
        assertEquals("Bearer $token", request.getHeader("Authorization"))
    }

    @Test fun `resolution decodes UTF-8 nulls empty lists and multiple contacts`() = runBlocking {
        reply(profile)
        val result = api.resolve(session, event, NfcProtocol.DEMO_PERSON_ID)
        assertEquals("Antonia Reyes", result.name)
        assertNull(result.age)
        assertNull(result.bloodType)
        assertEquals(listOf("Látex"), result.allergies)
        assertTrue(result.conditions.isEmpty())
        assertTrue(result.medications.isEmpty())
        assertEquals(listOf("María Reyes", "Tomás Ficticio"), result.contacts.map { it.name })
        assertEquals(4, result.version)
        assertEquals(expiry, result.updatedAt)
        val request = request()
        assertEquals("POST /api/v1/nfc/resolve HTTP/1.1", request.requestLine)
        assertEquals("Bearer $token", request.getHeader("Authorization"))
        assertEquals("""{"token":"${NfcProtocol.DEMO_PERSON_ID}","eventId":"demo-festival"}""", request.body.readUtf8())
    }

    @Test fun `resolution preserves HTTP errors without issuing a request for a malformed ID`() = runBlocking {
        val statuses = mapOf(401 to ApiProblem.Unauthorized, 403 to ApiProblem.Forbidden,
            404 to ApiProblem.NotFound, 500 to ApiProblem.Server)
        for ((status, problem) in statuses) {
            reply("""{"error":{"message":"detalle interno"}}""", status)
            fails(problem) { api.resolve(session, event, NfcProtocol.DEMO_PERSON_ID) }
        }
        fails(ApiProblem.InvalidId) { api.resolve(session, event, "id-invalido") }
        assertEquals(statuses.size, server.requestCount)
    }

    @Test fun `address accepts an origin and enforces HTTPS when debug is off`() {
        assertEquals("http://192.168.1.50:3000/", serverAddress(" http://192.168.1.50:3000 ", true).toString())
        assertNotNull(serverAddress("https://servidor.ejemplo", false))
        assertNull(serverAddress("http://192.168.1.50:3000", false))
        for (bad in listOf("localhost", "http://127.0.0.1", "http://[::1]", "http://PC/api/v1",
            "http://user:pass@PC", "http://PC#fragmento", "http://PC/?q=1")) {
            assertNull(bad, serverAddress(bad, true))
        }
    }

    @Test fun `redirects do not forward the PIN or token`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(307).addHeader("Location", server.url("/another")))
        fails(ApiProblem.Server) { api.login("pin-de-prueba") }
        assertEquals(1, server.requestCount)
    }

    private fun reply(body: String, status: Int = 200) {
        server.enqueue(MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json; charset=utf-8").setBody(body))
    }

    private fun request() = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))

    private suspend fun fails(problem: ApiProblem, block: suspend () -> Unit) {
        try {
            block()
            fail("Se esperaba $problem")
        } catch (error: ApiFailure) {
            assertEquals(problem, error.problem)
        }
    }

    private val profile = """{
      "person":{"name":"Antonia Reyes","age":null},
      "card":{"bloodType":null,"allergies":["Látex"],"conditions":[],"medications":[],"updatedAt":"$expiry","version":4},
      "contacts":[
        {"name":"María Reyes","relationship":"Madre","phone":"+56 9 0000 0001"},
        {"name":"Tomás Ficticio","relationship":"Amigo","phone":"+56 9 0000 0002"}
      ]
    }"""
}
