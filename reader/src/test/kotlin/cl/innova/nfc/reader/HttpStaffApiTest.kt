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
    private val session = StaffSession(token, expiry, "demo-guardia", "Guardia Demo")

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

    @Test fun `venue uses the selected event bearer and rejects another event response`() = runBlocking {
        val body = """{"eventId":"demo-festival","venue":{"name":"Festival Demo","venue":"Recinto ficticio",
            "description":null,"accessNotes":null,"responseNotes":"Acceso norte"},
            "zones":[{"id":"acceso","name":"Acceso","description":null,"reference":null}],
            "points":[{"name":"Salida A","kind":"EXIT","zoneId":"acceso","zoneName":"Acceso","reference":"Portón norte"}]}"""
        reply(body)
        val context = api.venue(session, event)
        assertEquals("Acceso norte", context.venue.responseNotes)
        assertEquals(PointKind.EXIT, context.points.single().kind)
        val sent = request()
        assertEquals("GET /api/v1/events/demo-festival/venue HTTP/1.1", sent.requestLine)
        assertEquals("Bearer $token", sent.getHeader("Authorization"))
        reply(body.replace("demo-festival", "otro-evento"))
        fails(ApiProblem.InvalidResponse) { api.venue(session, event) }
    }

    @Test fun `dispatch decodes visual evidence and displays the verified location first`() = runBlocking {
        val id = "5c44bc28-7b70-4d42-a297-1bcd242ac1df"
        val body = """{"zones":[],"dispatch":{"effectivePriority":"P2","prioritySource":"HUMAN","priorityReasons":[],
            "humanPriority":"P2","elapsedSeconds":15,"revision":1,"evaluatedAt":"2026-11-17T22:00:00Z",
            "declaredLocation":{"zoneId":null,"zoneName":null,"text":"Junto al estand de mote"},
            "inferredLocation":{"zoneId":"entrada","zoneName":"Entrada principal","text":null},
            "attendeeLocation":{"zoneId":"entrada","zoneName":"Entrada principal","source":"CONFIRMED_AI","messageSeq":3,
              "evidence":{"messageSeq":1,"quote":"Junto al estand de mote","feature":"stand de mote con huesillo"}},
            "confirmedLocation":{"zoneId":"escenario","zoneName":"Escenario","text":"Junto a la valla"},
            "analysis":{"category":"OTHER","summary":"Junto al estand de mote","uncertainty":[],
              "observedAt":"2026-11-17T22:00:00Z","inputSeq":1,"locationEvidence":
              {"messageSeq":1,"quote":"Junto al estand de mote","feature":"stand de mote con huesillo"}}}}"""
        reply(body)
        val dispatch = api.dispatch(session, event, id).dispatch
        assertEquals("Entrada principal", dispatch.inferredLocation?.zoneName)
        assertEquals("stand de mote con huesillo", dispatch.analysis?.locationEvidence?.feature)
        assertEquals("Confirmada · Escenario · Junto a la valla", dispatch.displayedLocation())
        assertEquals("Confirmada por el asistente · Entrada principal",
            dispatch.copy(confirmedLocation = null).displayedLocation())
        assertEquals(3L, dispatch.attendeeLocation?.messageSeq)
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
        reply("""{"person":{"id":"demo-guardia","account":"guardia","name":"Guardia Demo"},"role":"STAFF"}""")
        val active = api.login("pin-de-prueba")
        assertEquals(expiry, active.expiresAt)
        assertEquals(token, active.token)
        val request = request()
        assertEquals("POST /api/v1/demo/sessions HTTP/1.1", request.requestLine)
        assertEquals("""{"account":"guardia","pin":"pin-de-prueba"}""", request.body.readUtf8())
        assertNull(request.getHeader("Authorization"))
        assertEquals("Bearer $token", request().getHeader("Authorization"))

        reply("""{"token":"$token","role":"STAFF","expiresAt":"$expiry"}""", 201)
        reply("""{"person":{"id":"demo-guardia2","account":"guardia2","name":"Guardia Demo 2"},"role":"STAFF"}""")
        assertEquals("demo-guardia2", api.login("segundo-pin", StaffAccount.Second).personId)
        assertEquals("""{"account":"guardia2","pin":"segundo-pin"}""", request().body.readUtf8())
        assertEquals("GET /api/v1/me HTTP/1.1", request().requestLine)

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

    @Test fun `event incidents send a Bearer without an NFC token and reject another event`() = runBlocking {
        val list = """{"incidents":[{"id":"34522603-54fe-4ea1-af0b-39699c9515d2","eventId":"demo-festival",
            "requesterName":"Antonia Reyes","zoneId":null,"zoneName":null,"locationText":"Cerca de la fila",
            "status":"OPEN","createdAt":"2026-11-15T14:00:00Z","updatedAt":"2026-11-15T14:00:00Z"}]}"""
        reply(list)
        val incident = api.incidents(session, event).single()
        assertEquals("Antonia Reyes", incident.requesterName)
        assertNull(incident.zoneName)
        assertEquals("Cerca de la fila", incident.locationText)
        val request = request()
        assertEquals("GET /api/v1/events/demo-festival/incidents?order=dispatch HTTP/1.1", request.requestLine)
        assertEquals("Bearer $token", request.getHeader("Authorization"))
        assertEquals(0, request.bodySize)
        reply(list.replace("demo-festival", "other"))
        fails(ApiProblem.InvalidResponse) { api.incidents(session, event) }
        reply("{}", 401)
        fails(ApiProblem.Unauthorized) { api.incidents(session, event) }
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
