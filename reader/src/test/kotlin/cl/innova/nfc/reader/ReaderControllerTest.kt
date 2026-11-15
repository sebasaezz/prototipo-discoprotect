package cl.innova.nfc.reader

import cl.innova.nfc.protocol.NfcProtocol
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderControllerTest {
    private val address = "http://192.168.1.50:3000"
    private val eventA = StaffEvent("A", "Festival A", "Recinto A")
    private val eventB = StaffEvent("B", "Festival B", "Recinto B")
    private val now = Instant.parse("2026-11-15T14:00:00Z")
    private val session = StaffSession("guard-bearer", now.plusSeconds(3600))
    private val nfcId = NfcProtocol.DEMO_PERSON_ID
    private val debug = NfcDebug(true, true, "Success", nfcId, 20)
    private val original = HealthProfile("Antonia", 22, "O+", listOf("Látex"), emptyList(), emptyList(),
        emptyList(), now, 2)

    @Test fun `event selection and updates after closing NFC`() = runTest {
        val backend = FakeApi().apply { availableEvents = listOf(eventA, eventB) }
        val screen = controller(backend)
        authenticate(screen)
        assertNull(screen.state.value.event)
        screen.continueReading()
        assertTrue(screen.state.value.configuring)
        screen.selectEvent(eventB.id)
        screen.continueReading()

        var rfClosed = false
        backend.onResolve = { active, event, id ->
            assertTrue(rfClosed)
            assertSame(session, active)
            assertEquals(eventB, event)
            assertEquals(nfcId, id)
            original
        }
        screen.scan {
            assertEquals(ScanState.Reading, screen.state.value.scan)
            rfClosed = true
            readIdentity()
        }
        screen.scan { error("detección duplicada") }
        runCurrent()
        assertEquals(ScanState.Found(original, Source.Server), screen.state.value.scan)
        assertEquals(debug, screen.state.value.debug)
        screen.newScan()
        backend.onResolve = { _, _, _ -> original.copy(allergies = listOf("Maní"), version = 3) }
        screen.scan { readIdentity() }
        runCurrent()
        assertEquals(3, (screen.state.value.scan as ScanState.Found).profile.version)
    }

    @Test fun `offline profile requires deliberate local mode and a new physical read`() = runTest {
        val backend = FakeApi()
        val screen = ready(backend)
        backend.onResolve = { _, _, _ -> throw ApiFailure(ApiProblem.Network) }
        screen.scan { readIdentity() }
        runCurrent()
        assertEquals(ScanState.RemoteError(ApiProblem.Network), screen.state.value.scan)
        assertEquals(ServerState.Unreachable, screen.state.value.server)
        assertEquals(debug, screen.state.value.debug)
        assertFalse(screen.state.value.local)
        screen.useLocal()
        assertEquals(ScanState.Ready, screen.state.value.scan)
        screen.scan { readIdentity() }
        runCurrent()
        val local = screen.state.value.scan as ScanState.Found
        assertEquals(Source.Local, local.source)
        assertEquals(listOf("Penicilina"), local.profile.allergies)
        assertNull(local.profile.version)
        assertEquals(1, backend.resolveCount)
        screen.configure()
        screen.check(address)
        runCurrent()
        assertTrue(screen.state.value.canContinue)
        screen.continueReading()
        backend.onResolve = { _, _, _ -> original }
        screen.scan { readIdentity() }
        runCurrent()
        assertEquals(ScanState.Found(original, Source.Server), screen.state.value.scan)
    }

    @Test fun `401 clears session and 403 or 404 preserve authoritative error`() = runTest {
        val backend = FakeApi()
        val screen = ready(backend)
        for (problem in listOf(ApiProblem.Forbidden, ApiProblem.NotFound)) {
            backend.onResolve = { _, _, _ -> throw ApiFailure(problem) }
            screen.scan { readIdentity() }
            runCurrent()
            assertEquals(ScanState.RemoteError(problem), screen.state.value.scan)
            assertFalse(screen.state.value.local)
            screen.newScan()
        }
        backend.onResolve = { _, _, _ -> throw ApiFailure(ApiProblem.Unauthorized) }
        screen.scan { readIdentity() }
        runCurrent()
        assertFalse(screen.state.value.signedIn)
        assertFalse(screen.state.value.local)
        assertNull(screen.state.value.event)
        assertTrue(screen.state.value.configuring)
        assertTrue(screen.state.value.notice!!.contains("sesión"))
        screen.login("pin-de-prueba")
        runCurrent()
        screen.continueReading()
        backend.onResolve = { _, _, _ -> original }
        screen.scan { readIdentity() }
        runCurrent()
        assertEquals(ScanState.Found(original, Source.Server), screen.state.value.scan)
    }

    @Test fun `NFC failure never calls HTTP and new scan recovers`() = runTest {
        val backend = FakeApi()
        val screen = ready(backend)
        screen.scan { ReadResult(NfcOutcome.Failure("interrumpido"), NfcDebug(tagDetected = true)) }
        runCurrent()
        assertEquals(ScanState.NfcError("interrumpido"), screen.state.value.scan)
        assertEquals(0, backend.resolveCount)
        screen.newScan()
        screen.scan { readIdentity() }
        runCurrent()
        assertEquals(ScanState.Found(original, Source.Server), screen.state.value.scan)
    }

    @Test fun `late replies cannot replace a newer scan after pause or event change`() = runTest {
        val backend = FakeApi().apply { availableEvents = listOf(eventA, eventB) }
        val screen = controller(backend)
        authenticate(screen)
        screen.selectEvent(eventA.id)
        screen.continueReading()
        lateinit var late: Continuation<HealthProfile>
        backend.onResolve = { _, _, _ -> suspendCoroutine { late = it } }
        screen.scan { readIdentity() }
        runCurrent()
        assertEquals(ScanState.Resolving, screen.state.value.scan)
        screen.pause()
        screen.resume()
        screen.configure()
        screen.selectEvent(eventB.id)
        screen.continueReading()
        backend.onResolve = { _, selected, _ ->
            assertEquals(eventB, selected)
            original.copy(version = 5)
        }
        screen.scan { readIdentity() }
        runCurrent()
        late.resume(original)
        runCurrent()
        assertEquals(5, (screen.state.value.scan as ScanState.Found).profile.version)
    }

    @Test fun `address change clears login and stale event results`() = runTest {
        val backend = FakeApi()
        val stored = mutableListOf<String>()
        val screen = controller(backend, saveAddress = { stored += it })
        authenticate(screen)
        screen.check("http://192.168.1.51:3000")
        runCurrent()
        assertEquals(listOf("http://192.168.1.50:3000/", "http://192.168.1.51:3000/"), stored)
        assertFalse(screen.state.value.signedIn)
        assertNull(screen.state.value.event)
        assertEquals(ServerState.Connected, screen.state.value.server)
    }

    @Test fun `expiry removes a previously displayed server profile`() = runTest {
        val screen = ready(FakeApi())
        screen.scan { readIdentity() }
        runCurrent()
        assertTrue(screen.state.value.scan is ScanState.Found)
        advanceTimeBy(3_600_000)
        runCurrent()
        assertFalse(screen.state.value.signedIn)
        assertTrue(screen.state.value.configuring)
        assertEquals(ScanState.Ready, screen.state.value.scan)
    }

    private fun TestScope.ready(api: FakeApi): ReaderController = controller(api).also {
        authenticate(it)
        it.continueReading()
    }

    private fun TestScope.authenticate(screen: ReaderController) {
        screen.check(address)
        runCurrent()
        screen.login("pin-de-prueba")
        runCurrent()
    }

    private fun TestScope.controller(api: FakeApi, saveAddress: (String) -> Unit = {}): ReaderController =
        ReaderController(backgroundScope, true, null, saveAddress, { api }, clock = Clock.fixed(now, ZoneOffset.UTC))

    private fun readIdentity() = ReadResult(NfcOutcome.Identity(nfcId), debug)

    private inner class FakeApi : StaffApi {
        var availableEvents = listOf(eventA)
        var resolveCount = 0
        var onResolve: suspend (StaffSession, StaffEvent, String) -> HealthProfile = { _, _, _ -> original }
        override suspend fun health() = Unit
        override suspend fun login(pin: String) = session
        override suspend fun events(session: StaffSession) = availableEvents
        override suspend fun resolve(session: StaffSession, event: StaffEvent, token: String): HealthProfile {
            resolveCount++
            return onResolve(session, event, token)
        }
    }
}
