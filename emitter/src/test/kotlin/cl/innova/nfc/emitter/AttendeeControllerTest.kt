package cl.innova.nfc.emitter

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
class AttendeeControllerTest {
    private val now = Instant.parse("2026-11-15T14:00:00Z")
    private val original = EmergencyCard(1, now.toString(), "O+", listOf("Penicilina"), listOf("Asma"), listOf("Inhalador"))
    private val relative = Contact("t0", "María", "Madre", "+56 9 0000 0001")
    private val active = demoSession(DemoAccount.Antonia, now.plusSeconds(3600))
    private val url = "http://192.168.1.42:3000"

    @Test fun `saves full card and preserves untouched fields despite duplicate presses`() = runTest {
        val backend = Fake()
        val app = ready(backend)
        assertEquals(active.person, app.state.value.person)
        assertEquals(original, app.state.value.snapshot?.card)
        app.editCard()
        app.updateCard(original.fields.copy(allergies = listOf(" Látex ", "Maní")))
        app.save()
        app.save()
        runCurrent()
        assertEquals(listOf(1L), backend.writes)
        assertEquals(original.fields.copy(allergies = listOf("Látex", "Maní")), backend.record.fields)
        assertEquals(2L, app.state.value.snapshot?.card?.version)
        assertNotNull(app.state.value.confirmation)
        assertNull(app.state.value.draft)

        app.editCard()
        app.updateCard(backend.record.fields.copy(bloodType = null, allergies = emptyList(),
            conditions = listOf("Nueva condición"), medications = listOf("Otro medicamento")))
        app.save()
        runCurrent()
        assertEquals(3L, app.state.value.snapshot?.card?.version)
        assertEquals(CardFields(null, emptyList(), listOf("Nueva condición"), listOf("Otro medicamento")), backend.record.fields)
    }

    @Test fun `mutations of a contact use consecutive versions and deletion requires confirmation`() = runTest {
        val backend = Fake()
        val app = ready(backend)
        app.editContact("missing")
        assertNull(app.state.value.draft)
        app.editContact(null)
        app.updateContact(ContactFields(" Ana ", "Amiga", "+56 (9) 1234-5678"))
        app.save()
        runCurrent()
        val added = app.state.value.snapshot!!.contacts.single { it.name == "Ana" }
        app.editContact(added.id)
        app.updateContact(added.fields.copy(relationship = "Tía"))
        app.save()
        runCurrent()
        assertEquals("Tía", app.state.value.snapshot!!.contacts.single { it.id == added.id }.relationship)
        app.requestRemoval(added.id)
        assertTrue(app.state.value.draft is EditDraft.Remove)
        assertEquals(listOf(1L, 2L), backend.writes)
        app.discard()
        assertTrue(app.state.value.snapshot!!.contacts.any { it.id == added.id })
        app.requestRemoval(added.id)
        app.save()
        runCurrent()
        assertEquals(listOf(1L, 2L, 3L), backend.writes)
        assertEquals(listOf(relative), app.state.value.snapshot!!.contacts)
    }

    @Test fun `conflict keeps draft and preserves remote edits until explicit review and save`() = runTest {
        val backend = Fake()
        val app = ready(backend)
        app.editCard()
        val userEdit = original.fields.copy(allergies = listOf("Látex"))
        app.updateCard(userEdit)
        backend.record = original.copy(version = 2, medications = listOf("Nuevo medicamento"))
        app.save()
        runCurrent()
        assertEquals(Review.Conflict, app.state.value.review)
        assertEquals(userEdit, (app.state.value.draft as EditDraft.Card).fields)
        assertEquals(2L, app.state.value.snapshot?.card?.version)
        app.save()
        runCurrent()
        assertEquals(listOf(1L), backend.writes)
        app.acceptLatestVersion()
        assertEquals(listOf("Nuevo medicamento"), (app.state.value.draft as EditDraft.Card).fields.medications)
        assertEquals(listOf(1L), backend.writes)
        app.save()
        runCurrent()
        assertEquals(listOf(1L, 2L), backend.writes)
        assertEquals(listOf("Látex"), backend.record.allergies)
        assertEquals(listOf("Nuevo medicamento"), backend.record.medications)
    }

    @Test fun `lost response after insert cannot duplicate contact`() = runTest {
        val backend = Fake().apply { lostCreate = true }
        val app = ready(backend)
        app.editContact(null)
        val contact = ContactFields("María", "Tía", "123")
        app.updateContact(contact)
        app.save()
        runCurrent()
        assertEquals(Review.Uncertain, app.state.value.review)
        assertNull(app.state.value.confirmation)
        assertFalse(app.state.value.fresh)
        app.updateContact(contact.copy(phone = "456"))
        assertEquals(contact, (app.state.value.draft as EditDraft.ContactEdit).fields)
        app.acceptLatestVersion()
        app.save()
        runCurrent()
        assertEquals(listOf(1L), backend.writes)
        app.refresh()
        runCurrent()
        assertEquals(Review.Present, app.state.value.review)
        app.acceptLatestVersion()
        app.save()
        runCurrent()
        assertEquals(1, backend.people.count { it.fields == contact })
        assertEquals(listOf(1L), backend.writes)
        app.discard()
        assertNull(app.state.value.draft)
    }

    @Test fun `offline draft can be reviewed when the server recovers`() = runTest {
        val backend = Fake().apply { rejectWrite = FailureKind.Offline }
        val app = ready(backend)
        app.editCard()
        app.updateCard(original.fields.copy(allergies = listOf("Látex")))
        app.save()
        runCurrent()
        assertFalse(app.state.value.canSave)
        assertEquals(Reachability.Offline, app.state.value.reachability)
        backend.rejectWrite = null
        app.refresh()
        runCurrent()
        assertEquals(Review.Uncertain, app.state.value.review)
        app.acceptLatestVersion()
        app.save()
        runCurrent()
        assertEquals(2L, app.state.value.snapshot?.card?.version)
    }

    @Test fun `validation and cancellation leave confirmed data intact`() = runTest {
        val backend = Fake()
        val app = ready(backend)
        app.editCard()
        app.updateCard(original.fields.copy(allergies = listOf(" ")))
        app.save()
        runCurrent()
        assertTrue(backend.writes.isEmpty())
        assertNotNull(app.state.value.notice)
        app.navigate(Section.Connection)
        assertEquals(Section.Card, app.state.value.section)
        app.discard()
        assertNull(app.state.value.draft)
        assertEquals(original, app.state.value.snapshot?.card)
    }

    @Test fun `401 clears session and signing in again requires review`() = runTest {
        val backend = Fake().apply { rejectWrite = FailureKind.Expired }
        val app = ready(backend)
        app.editCard()
        app.updateCard(original.fields.copy(bloodType = "A−"))
        app.save()
        runCurrent()
        assertNull(app.state.value.person)
        assertNull(app.state.value.snapshot)
        assertNotNull(app.state.value.draft)
        app.save()
        assertEquals(listOf(1L), backend.writes)
        backend.rejectWrite = null
        app.retryLogin()
        runCurrent()
        assertEquals(Review.Conflict, app.state.value.review)
        assertFalse(app.state.value.canSave)
    }

    @Test fun `expiry clears personal data and retains unsaved draft`() = runTest {
        val app = ready(Fake())
        app.editCard()
        app.updateCard(original.fields.copy(bloodType = "A−"))
        advanceTimeBy(3_600_000)
        runCurrent()
        assertNull(app.state.value.person)
        assertNull(app.state.value.snapshot)
        assertNotNull(app.state.value.draft)
        assertFalse(app.state.value.canSave)
    }

    @Test fun `old write cannot update UI after logout and server change`() = runTest {
        val backend = Fake()
        val app = ready(backend)
        lateinit var late: Continuation<Unit>
        backend.pauseWrite = { suspendCoroutine { late = it } }
        app.editCard()
        app.updateCard(original.fields.copy(bloodType = "A−"))
        app.save()
        runCurrent()
        assertEquals(Task.Saving, app.state.value.task)
        app.logout()
        app.connect("http://192.168.1.43:3000")
        runCurrent()
        app.selectAccount(DemoAccount.Antonia)
        runCurrent()
        late.resume(Unit)
        runCurrent()
        assertEquals("http://192.168.1.43:3000/", app.state.value.address)
        assertEquals(1L, app.state.value.snapshot?.card?.version)
        assertNull(app.state.value.confirmation)
        assertNull(app.state.value.draft)
    }

    @Test fun `identity error blocks edition even with a reachable server`() = runTest {
        val backend = Fake().apply { rejectLogin = true }
        val app = ready(backend)
        app.editCard()
        assertEquals(FailureKind.Identity.message, app.state.value.notice)
        assertNull(app.state.value.person)
        assertNull(app.state.value.draft)
    }

    @Test fun `local validators match the limits and phone formats of the API`() {
        assertTrue(original.fields.copy(bloodType = "AB−", allergies = List(30) { "ñ".repeat(160) }).valid())
        assertFalse(original.fields.copy(bloodType = "AB-").valid())
        assertFalse(original.fields.copy(allergies = List(31) { "a" }).valid())
        assertFalse(original.fields.copy(conditions = listOf("a".repeat(161))).valid())
        assertFalse(original.fields.copy(medications = listOf(" ")).valid())
        assertTrue(ContactFields(" Ana ", "Niñera", "+56 (9) 0000-0001").valid())
        for (bad in listOf("+--", "12", "0".repeat(33), "123 ext 9")) {
            assertFalse(ContactFields("Ana", "Niñera", bad).valid())
        }
        assertFalse(ContactFields("x".repeat(161), "Tía", "123").valid())
    }

    private fun TestScope.ready(api: Fake): AttendeeController =
        AttendeeController(backgroundScope, true, null, {}, { api }, Clock.fixed(now, ZoneOffset.UTC)).also {
            it.connect(url)
            runCurrent()
            it.selectAccount(DemoAccount.Antonia)
            runCurrent()
            it.navigate(Section.Card)
        }

    private inner class Fake : H4AttendeeApi() {
        var record = original
        var people = listOf(relative)
        var rejectWrite: FailureKind? = null
        var rejectLogin = false
        var lostCreate = false
        var pauseWrite: (suspend () -> Unit)? = null
        val writes = mutableListOf<Long>()
        override suspend fun health() = Unit
        override suspend fun login(account: DemoAccount, onCredential: () -> Unit): AttendeeSession {
            if (rejectLogin) throw AttendeeFailure(FailureKind.Identity)
            onCredential()
            return active
        }
        override suspend fun card(session: AttendeeSession) = record
        override suspend fun contacts(session: AttendeeSession) = people
        override suspend fun replaceCard(session: AttendeeSession, version: Long, fields: CardFields): EmergencyCard {
            val next = write(version)
            pauseWrite?.invoke()
            record = EmergencyCard(next, now.toString(), fields.bloodType, fields.allergies, fields.conditions, fields.medications)
            return record
        }
        override suspend fun saveContact(session: AttendeeSession, version: Long, id: String?, fields: ContactFields): Long {
            val next = write(version)
            val entry = Contact(id ?: "t${people.size}", fields.name, fields.relationship, fields.phone)
            people = people.filterNot { it.id == id } + entry
            record = record.copy(version = next)
            if (lostCreate && id == null) throw AttendeeFailure(FailureKind.Timeout)
            return next
        }
        override suspend fun deleteContact(session: AttendeeSession, version: Long, id: String): Long {
            val next = write(version)
            people = people.filterNot { it.id == id }
            record = record.copy(version = next)
            return next
        }
        private fun write(version: Long): Long {
            writes += version
            rejectWrite?.let { throw AttendeeFailure(it) }
            if (version != record.version) throw AttendeeFailure(FailureKind.Conflict)
            return version + 1
        }
    }
}
