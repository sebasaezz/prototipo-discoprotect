package cl.innova.nfc.reader

import cl.innova.nfc.protocol.NfcProtocol
import cl.discoprotect.ui.Incident
import cl.discoprotect.ui.IncidentStatus
import cl.discoprotect.ui.AssignedStaff
import cl.discoprotect.ui.IncidentTriage
import cl.discoprotect.ui.ReviewDecision
import cl.discoprotect.ui.TriageCategory
import cl.discoprotect.ui.TriageReview
import cl.discoprotect.ui.TriageStatus
import cl.discoprotect.ui.TriageSuggestion
import cl.discoprotect.ui.TriageUncertainty
import cl.discoprotect.ui.TriageUrgency
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
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
    private val session = StaffSession("guard-bearer", now.plusSeconds(3600), "demo-guardia", "Guardia Demo")
    private val nfcId = NfcProtocol.DEMO_PERSON_ID
    private val debug = NfcDebug(true, true, "Success", nfcId, 20)
    private val original = HealthProfile("Antonia", 22, "O+", listOf("Látex"), emptyList(), emptyList(),
        emptyList(), now, 2)

    @Test fun `incident list requires a staff event, never scans and refreshes serially`() = runTest {
        val backend = FakeApi().apply { availableEvents = listOf(eventA, eventB) }
        val screen = controller(backend)
        screen.openIncidents()
        authenticate(screen)
        screen.openIncidents()
        assertFalse(screen.state.value.incidentsVisible)
        screen.selectEvent(eventA.id)
        val incident = incident(eventA)
        backend.onIncidents = { active, event ->
            assertSame(session, active)
            assertEquals(eventA, event)
            listOf(incident)
        }
        screen.openIncidents()
        screen.refreshIncidents()
        screen.scan { error("Incidentes no requiere una lectura") }
        runCurrent()
        assertEquals(listOf(incident), screen.state.value.incidents)
        assertEquals(1, backend.incidentCalls)
        assertEquals(0, backend.resolveCount)
        backend.onIncidents = { _, _ -> throw ApiFailure(ApiProblem.Unauthorized) }
        screen.refreshIncidents()
        runCurrent()
        assertFalse(screen.state.value.signedIn)
        assertFalse(screen.state.value.incidentsVisible)
        assertNull(screen.state.value.incidents)
    }

    @Test fun `a response from a previous event cannot replace the current list`() = runTest {
        val backend = FakeApi().apply { availableEvents = listOf(eventA, eventB) }
        val screen = controller(backend)
        authenticate(screen)
        val first = incident(eventA)
        val second = incident(eventB)
        lateinit var late: Continuation<List<Incident>>
        backend.onIncidents = { _, selected ->
            if (selected == eventA) suspendCoroutine { late = it } else listOf(second)
        }
        screen.selectEvent(eventA.id)
        screen.openIncidents()
        runCurrent()
        screen.selectEvent(eventB.id)
        runCurrent()
        late.resume(listOf(first))
        runCurrent()
        assertEquals(listOf(second), screen.state.value.incidents)
        backend.onIncidents = { _, _ -> throw ApiFailure(ApiProblem.Forbidden) }
        screen.refreshIncidents()
        runCurrent()
        assertNull(screen.state.value.incidents)
        assertEquals(ApiProblem.Forbidden, screen.state.value.incidentProblem)
    }

    @Test fun `polling pauses and backs off and never replaces a newer incident version`() = runTest {
        val open = incident(eventA)
        val assigned = open.copy(version = 2, status = IncidentStatus.ASSIGNED,
            assignedAt = now.toString(), assignedStaff = AssignedStaff(session.personId, session.name))
        val remote = FakeApi().apply { onIncidents = { _, _ -> listOf(assigned) } }
        val screen = controller(remote)
        authenticate(screen)
        screen.openIncidents()
        runCurrent()
        remote.onIncidents = { _, _ -> throw ApiFailure(ApiProblem.Network) }
        advanceTimeBy(5000)
        runCurrent()
        assertEquals(2, remote.incidentCalls)
        assertFalse(screen.state.value.incidentsCurrent)
        advanceTimeBy(9999)
        runCurrent()
        assertEquals(2, remote.incidentCalls)
        screen.pause()
        advanceTimeBy(30000)
        assertEquals(2, remote.incidentCalls)
        remote.onIncidents = { _, _ -> listOf(open) }
        screen.resume()
        runCurrent()
        assertEquals(3, remote.incidentCalls)
        assertEquals(listOf(assigned), screen.state.value.incidents)
        assertTrue(screen.state.value.incidentsCurrent)
    }

    @Test fun `lost write keeps exact command and displays the live status on historical confirmation`() = runTest {
        val open = incident(eventA)
        val remote = FakeApi()
        var current = open
        val commands = mutableListOf<IncidentCommand>()
        remote.onIncidents = { _, _ -> listOf(current) }
        remote.onOperate = { command ->
            commands += command
            if (commands.size == 1) throw ApiFailure(ApiProblem.Timeout)
            OperationReply(AppliedOperation(command.body.operationId, IncidentStatus.OPEN, IncidentStatus.ASSIGNED,
                2, now.toString(), true), current)
        }
        val screen = controller(remote)
        authenticate(screen)
        screen.openIncidents()
        runCurrent()
        screen.operateIncident(open.id, open.version, IncidentAction.Claim)
        runCurrent()
        assertTrue(screen.state.value.pendingIncident)
        assertEquals(IncidentStatus.OPEN, screen.state.value.incidents?.single()?.status)
        current = open.copy(version = 3, status = IncidentStatus.IN_PROGRESS, assignedAt = now.toString(),
            startedAt = now.toString(), assignedStaff = AssignedStaff(session.personId, session.name))
        screen.refreshIncidents()
        runCurrent()
        screen.retryIncident()
        runCurrent()
        assertEquals(2, commands.size)
        assertEquals(commands[0], commands[1])
        assertFalse(screen.state.value.pendingIncident)
        assertEquals(IncidentStatus.IN_PROGRESS, screen.state.value.incidents?.single()?.status)
        assertTrue(screen.state.value.incidentNotice!!.contains("original confirmada: Asignada. Estado vigente: En atención"))
    }

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

    @Test fun `triage review retries exact command and late detail is discarded after context changes`() = runTest {
        val result = TriageSuggestion(TriageCategory.MEDICAL, TriageUrgency.PRIORITY, "Caída ficticia.",
            emptyList(), TriageUncertainty.HIGH)
        val pending = IncidentTriage(1, TriageStatus.PENDING, null, null, null, null)
        val suggested = IncidentTriage(2, TriageStatus.SUGGESTED, result, null, null, null)
        val staff = AssignedStaff(session.personId, session.name)
        val base = incident(eventA)
        val assigned = base.copy(version = 2, status = IncidentStatus.ASSIGNED,
            assignedAt = now.toString(), assignedStaff = staff, triage = pending)
        val backend = FakeApi().apply {
            availableEvents = listOf(eventA, eventB)
            onIncidents = { _, selected -> if (selected == eventA) listOf(assigned) else emptyList() }
            onTriage = { TriageDetail(suggested, "Se cayó.") }
        }
        val screen = controller(backend)
        authenticate(screen)
        screen.selectEvent(eventA.id)
        screen.openIncidents()
        runCurrent()
        screen.openTriage(base.id)
        runCurrent()
        assertEquals("Se cayó.", screen.state.value.triageDetail?.description)
        screen.refreshIncidents()
        runCurrent()
        assertEquals(suggested, screen.state.value.incidents?.single()?.triage)
        val attempts = mutableListOf<ReviewBody>()
        backend.onReview = { command ->
            attempts += command
            if (attempts.size == 1) throw ApiFailure(ApiProblem.Timeout)
            val review = TriageReview(3, 2, command.operationId, ReviewDecision.CORRECT,
                TriageCategory.OTHER, TriageUrgency.PRIORITY, now.toString(), staff)
            ReviewReply(review, suggested.copy(triageVersion = 3, latestReview = review), true)
        }
        screen.reviewTriage(2, ReviewDecision.CORRECT, TriageCategory.OTHER, TriageUrgency.PRIORITY)
        runCurrent()
        assertTrue(screen.state.value.triagePending)
        assertEquals(IncidentAction.Start, screen.state.value.actionFor(assigned))
        screen.retryTriage()
        runCurrent()
        assertEquals(2, attempts.size)
        assertEquals(attempts[0], attempts[1])
        assertFalse(screen.state.value.triagePending)
        assertEquals(3L, screen.state.value.incidents?.single()?.triage?.triageVersion)

        lateinit var late: Continuation<TriageDetail>
        backend.onTriage = { suspendCoroutine { late = it } }
        screen.openTriage(base.id)
        runCurrent()
        screen.selectEvent(eventB.id)
        runCurrent()
        late.resume(TriageDetail(suggested, "Relato del otro evento."))
        runCurrent()
        assertNull(screen.state.value.triageDetail)
        assertEquals(eventB, screen.state.value.event)
    }

    private fun incident(event: StaffEvent) = Incident(UUID.randomUUID().toString(), event.id, "Antonia Reyes",
        null, null, "Cerca de la fila", IncidentStatus.OPEN, now.toString(), now.toString())

    private inner class FakeApi : StaffApi {
        override suspend fun triage(session: StaffSession, event: StaffEvent, id: String): TriageDetail =
            onTriage(id)
        override suspend fun review(session: StaffSession, event: StaffEvent, id: String, body: ReviewBody): ReviewReply =
            onReview(body)
        var onTriage: suspend (String) -> TriageDetail = { error("Unexpected triage read") }
        var onReview: suspend (ReviewBody) -> ReviewReply = { error("Unexpected triage review") }
        var availableEvents = listOf(eventA)
        var resolveCount = 0
        var incidentCalls = 0
        var onIncidents: suspend (StaffSession, StaffEvent) -> List<Incident> = { _, _ -> emptyList() }
        var onOperate: suspend (IncidentCommand) -> OperationReply = { error("Operación inesperada") }
        var onResolve: suspend (StaffSession, StaffEvent, String) -> HealthProfile = { _, _, _ -> original }
        override suspend fun health() = Unit
        override suspend fun login(pin: String, account: StaffAccount) = session
        override suspend fun operate(session: StaffSession, event: StaffEvent, command: IncidentCommand): OperationReply =
            onOperate(command)
        override suspend fun events(session: StaffSession) = availableEvents
        override suspend fun incidents(session: StaffSession, event: StaffEvent): List<Incident> {
            incidentCalls++
            return onIncidents(session, event)
        }
        override suspend fun resolve(session: StaffSession, event: StaffEvent, token: String): HealthProfile {
            resolveCount++
            return onResolve(session, event, token)
        }
    }
}
