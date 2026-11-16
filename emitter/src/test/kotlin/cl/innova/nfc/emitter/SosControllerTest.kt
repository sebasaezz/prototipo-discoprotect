package cl.innova.nfc.emitter

import cl.discoprotect.ui.Incident
import cl.discoprotect.ui.IncidentStatus
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SosControllerTest {
    private val now = Instant.parse("2026-11-15T21:30:00Z")
    private val event = SosEvent("demo-festival", "Festival Demo", "Recinto ficticio", "ATTENDEE")
    private val zone = SosZone("escenario", "Escenario")

    @Test fun `hold completes once after 1500 ms and cancellation stops it`() = runTest {
        var progress = 0f
        var completions = 0
        val short = launch { holdSos({ progress = it }, { completions++ }) }
        advanceTimeBy(1000)
        assertTrue(progress in 0.01f..0.99f)
        short.cancel()
        advanceTimeBy(1000)
        assertEquals(0, completions)
        launch { holdSos({ progress = it }, { completions++ }) }
        advanceTimeBy(1500)
        runCurrent()
        assertEquals(1f, progress, 0f)
        assertEquals(1, completions)
    }

    @Test fun `event and location must be selected and confirmation comes from server`() = runTest {
        val backend = Server()
        val app = open(backend)
        assertEquals(listOf(event), app.state.value.sos.events)
        app.sendSos()
        assertEquals(0, backend.attempts.size)
        app.selectSosEvent(event.id)
        runCurrent()
        app.selectSosZone("otra")
        assertFalse(app.state.value.sos.canSend)
        app.selectSosZone(zone.id)
        app.updateSosReference(" Junto a la salida ")
        app.sendSos()
        app.sendSos()
        assertEquals(Task.SosSending, app.state.value.task)
        assertNull(app.state.value.sos.receipt)
        runCurrent()
        assertEquals(SosOutcome.Confirmed, app.state.value.sos.outcome)
        val item = app.state.value.sos.incidents!!.single()
        assertEquals(item.id, app.state.value.sos.receipt)
        assertEquals(zone.id, item.zoneId)
        assertEquals("Junto a la salida", item.locationText)
        assertEquals(1, backend.attempts.size)
        app.cancelSos(item.id)
        runCurrent()
        assertEquals(SosOutcome.Cancelled, app.state.value.sos.outcome)
        assertEquals(IncidentStatus.CANCELLED, app.state.value.sos.incidents!!.single().status)
    }

    @Test fun `timeout retries with the same body and a fresh app can discover existing requests`() = runTest {
        val backend = Server()
        val app = ready(backend)
        backend.loseResponse = true
        app.sendSos()
        runCurrent()
        assertEquals(SosOutcome.Uncertain, app.state.value.sos.outcome)
        assertNull(app.state.value.sos.receipt)
        backend.loseResponse = false
        app.retrySos()
        runCurrent()
        assertEquals(2, backend.attempts.size)
        assertEquals(backend.attempts.first(), backend.attempts.last())
        val incident = backend.saved.values.single()
        assertEquals(incident.id, app.state.value.sos.receipt)
        assertNull(incident.zoneId)
        assertEquals(listOf(incident), ready(backend).state.value.sos.incidents)
    }

    @Test fun `failed preflight never sends and late reads cannot publish after switching account`() = runTest {
        val backend = Server()
        val app = ready(backend)
        backend.offline = true
        app.sendSos()
        runCurrent()
        assertEquals(SosOutcome.Offline, app.state.value.sos.outcome)
        assertTrue(backend.attempts.isEmpty())
        assertNull(app.state.value.sos.pending)
        backend.offline = false
        lateinit var resume: Continuation<Unit>
        backend.pauseList = { suspendCoroutine { resume = it } }
        app.refreshSos()
        runCurrent()
        app.selectAccount(DemoAccount.Martin)
        runCurrent()
        resume.resume(Unit)
        runCurrent()
        assertEquals(DemoAccount.Martin.key, app.state.value.person?.account)
        assertEquals(SosState(), app.state.value.sos)
    }

    @Test fun `lost cancellation response is resolved by the authoritative history`() = runTest {
        val backend = Server()
        val app = ready(backend)
        app.sendSos()
        runCurrent()
        val id = requireNotNull(app.state.value.sos.receipt)
        backend.loseResponse = true
        app.cancelSos(id)
        runCurrent()
        assertEquals(SosOutcome.Uncertain, app.state.value.sos.outcome)
        assertEquals(SosAttempt.Cancel(event.id, id), app.state.value.sos.pending)
        assertNull(app.state.value.sos.receipt)
        app.refreshSos()
        runCurrent()
        assertNull(app.state.value.sos.pending)
        assertEquals(SosOutcome.Cancelled, app.state.value.sos.outcome)
        assertEquals(id, app.state.value.sos.receipt)
    }

    @Test fun `account switching is blocked during send and pending recovery uses the original account`() = runTest {
        val backend = Server()
        val app = ready(backend)
        backend.loseResponse = true
        app.sendSos()
        app.selectAccount(DemoAccount.Martin)
        runCurrent()
        assertEquals(DemoAccount.Antonia.key, app.state.value.person?.account)
        app.selectAccount(DemoAccount.Martin)
        runCurrent()
        app.navigate(Section.Sos)
        runCurrent()
        app.selectSosEvent(event.id)
        runCurrent()
        assertNull(app.state.value.sos.pending)
        assertTrue(app.state.value.sos.incidents!!.isEmpty())
        app.selectAccount(DemoAccount.Antonia)
        runCurrent()
        app.navigate(Section.Sos)
        runCurrent()
        assertEquals(SosOutcome.Confirmed, app.state.value.sos.outcome)
        assertEquals(backend.saved.values.single().id, app.state.value.sos.receipt)
        assertEquals(1, backend.attempts.size)
    }

    @Test fun `expiration clears visible incident data and HCE and re-login can reconcile`() = runTest {
        val backend = Server()
        val app = ready(backend)
        backend.loseResponse = true
        app.sendSos()
        runCurrent()
        backend.expireReads = true
        app.refreshSos()
        runCurrent()
        assertNull(app.state.value.person)
        assertNull(app.sharing.current())
        assertEquals(SosState(), app.state.value.sos)
        backend.expireReads = false
        app.retryLogin()
        runCurrent()
        app.refreshSos()
        runCurrent()
        assertEquals(SosOutcome.Confirmed, app.state.value.sos.outcome)
    }

    @Test fun `visible history polls without disturbing the form and ignores lower versions`() = runTest {
        val backend = Server()
        val app = ready(backend)
        app.sendSos()
        runCurrent()
        val original = backend.saved.values.single()
        val key = requireNotNull(original.clientRequestId)
        backend.saved[key] = original.copy(status = IncidentStatus.ASSIGNED, version = 2, assignedAt = now.toString())
        app.updateSosReference("Referencia en edición")
        advanceTimeBy(5000)
        runCurrent()
        assertEquals(IncidentStatus.ASSIGNED, app.state.value.sos.incidents?.single()?.status)
        assertEquals("Referencia en edición", app.state.value.sos.reference)
        backend.saved[key] = original
        app.pause()
        val reads = backend.reads
        advanceTimeBy(30000)
        runCurrent()
        assertEquals(reads, backend.reads)
        app.resume()
        runCurrent()
        assertEquals(reads + 1, backend.reads)
        assertEquals(2L, app.state.value.sos.incidents?.single()?.version)
    }

    private fun TestScope.open(backend: Server) = AttendeeController(
        backgroundScope, true, "http://192.168.1.10:3000", {}, { backend }, Clock.fixed(now, ZoneOffset.UTC),
    ).also {
        it.selectAccount(DemoAccount.Antonia)
        runCurrent()
        it.navigate(Section.Sos)
        runCurrent()
    }

    private fun TestScope.ready(backend: Server) = open(backend).also {
        it.selectSosEvent(event.id)
        runCurrent()
        it.selectSosZone(null)
    }

    private inner class Server : H4AttendeeApi() {
        val attempts = mutableListOf<SosAttempt.Create>()
        val saved = mutableMapOf<String, Incident>()
        var loseResponse = false
        var offline = false
        var expireReads = false
        var reads = 0
        var pauseList: (suspend () -> Unit)? = null

        override suspend fun health() {
            if (offline) throw AttendeeFailure(FailureKind.Offline)
        }
        override suspend fun login(account: DemoAccount, onCredential: () -> Unit) =
            demoSession(account, now.plusSeconds(3600)).also { onCredential() }

        override suspend fun card(session: AttendeeSession) =
            EmergencyCard(1, now.toString(), null, emptyList(), emptyList(), emptyList())

        override suspend fun contacts(session: AttendeeSession): List<Contact> = emptyList()
        override suspend fun replaceCard(session: AttendeeSession, version: Long, fields: CardFields): EmergencyCard = error("unexpected")
        override suspend fun saveContact(session: AttendeeSession, version: Long, id: String?, fields: ContactFields): Long = error("unexpected")
        override suspend fun deleteContact(session: AttendeeSession, version: Long, id: String): Long = error("unexpected")

        override suspend fun events(session: AttendeeSession) = listOf(event)
        override suspend fun zones(session: AttendeeSession, eventId: String) = listOf(zone)
        override suspend fun incidents(session: AttendeeSession, eventId: String): List<Incident> {
            reads++
            if (expireReads) throw AttendeeFailure(FailureKind.Expired)
            pauseList?.invoke()
            return saved.values.filter { it.requesterPersonId == session.person.id && it.eventId == eventId }
        }

        override suspend fun createSos(session: AttendeeSession, attempt: SosAttempt.Create): Incident {
            attempts += attempt
            val body = attempt.body
            val incident = saved.getOrPut(body.clientRequestId) {
                Incident(UUID.randomUUID().toString(), attempt.eventId, session.person.name,
                    body.zoneId, zone.name.takeIf { body.zoneId == zone.id }, body.locationText,
                    IncidentStatus.OPEN, now.toString(), now.toString(), session.person.id, body.clientRequestId)
            }
            if (loseResponse) throw AttendeeFailure(FailureKind.Timeout)
            return incident
        }

        override suspend fun cancelSos(session: AttendeeSession, attempt: SosAttempt.Cancel): Incident {
            val original = saved.values.single { it.id == attempt.id }
            val cancelled = original.copy(status = IncidentStatus.CANCELLED)
            saved[requireNotNull(original.clientRequestId)] = cancelled
            if (loseResponse) throw AttendeeFailure(FailureKind.Timeout)
            return cancelled
        }
    }
}

internal abstract class H4AttendeeApi : AttendeeApi {
    override suspend fun events(session: AttendeeSession): List<SosEvent> = error("unexpected SOS call")
    override suspend fun zones(session: AttendeeSession, eventId: String): List<SosZone> = error("unexpected SOS call")
    override suspend fun incidents(session: AttendeeSession, eventId: String): List<Incident> = error("unexpected SOS call")
    override suspend fun createSos(session: AttendeeSession, attempt: SosAttempt.Create): Incident = error("unexpected SOS call")
    override suspend fun cancelSos(session: AttendeeSession, attempt: SosAttempt.Cancel): Incident = error("unexpected SOS call")
}
