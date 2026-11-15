package cl.innova.nfc.emitter

import cl.innova.nfc.protocol.CardSession
import cl.innova.nfc.protocol.NfcId
import cl.innova.nfc.protocol.NfcProtocol
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

internal fun demoSession(account: DemoAccount, expiresAt: Instant): AttendeeSession {
    val hex = when (account) {
        DemoAccount.Antonia -> NfcProtocol.DEMO_PERSON_ID
        DemoAccount.Martin -> "00000000000000000000000000000002"
        DemoAccount.Sofia -> "00000000000000000000000000000003"
    }
    return AttendeeSession("http-${account.key}", expiresAt,
        Person("demo-${account.key}", account.key, account.label, 22), requireNotNull(NfcId.parse(hex)))
}

@OptIn(ExperimentalCoroutinesApi::class)
class IdentitySwitchTest {
    private val initialTime = Instant.parse("2026-11-15T12:00:00Z")
    private val origin = "http://192.168.1.42:3000/"
    private val secondOrigin = "http://192.168.1.43:3000/"

    @Test fun verifiedIdentityIsPublishedForEveryAccountAndTheUiUsesTheSameGeneration() = runTest {
        val test = environment()
        val screen = test.screen
        assertUnavailable(test)
        for (account in DemoAccount.entries) {
            screen.selectAccount(account)
            assertUnavailable(test)
            runCurrent()
            assertActive(test, account)
        }
        screen.logout()
        assertUnavailable(test)
        assertNull(screen.state.value.selectedAccount)
    }

    @Test fun delayedSwitchInvalidatesExistingRfSessionAndFailureNeverRestoresIt() = runTest {
        val test = environment()
        test.screen.selectAccount(DemoAccount.Antonia)
        runCurrent()
        assertEquals("9000", test.rf.select())
        lateinit var finish: Continuation<Unit>
        test.backend.waitFor = { account -> if (account == DemoAccount.Martin) suspendCoroutine { finish = it } }
        test.screen.selectAccount(DemoAccount.Martin)
        assertEquals(Task.Switching, test.screen.state.value.task)
        assertUnavailable(test)
        runCurrent()
        assertEquals(Task.Credential, test.screen.state.value.task)
        assertEquals("6A82", test.rf.get())
        test.backend.loginError = FailureKind.Timeout
        finish.resume(Unit)
        runCurrent()
        assertUnavailable(test)
        assertEquals(DemoAccount.Martin, test.screen.state.value.selectedAccount)
        assertEquals(Reachability.Offline, test.screen.state.value.reachability)
        test.backend.waitFor = {}
        test.backend.loginError = null
        test.screen.retryLogin()
        runCurrent()
        assertActive(test, DemoAccount.Martin)
    }

    @Test fun lateBAndALoginsCannotCompleteAfterRapidReturnToAOrLogout() = runTest {
        val test = environment()
        val delayed = mutableListOf<Continuation<Unit>>()
        test.backend.waitFor = { suspendCoroutine { delayed.add(it) } }
        test.screen.selectAccount(DemoAccount.Antonia)
        runCurrent()
        test.screen.selectAccount(DemoAccount.Martin)
        runCurrent()
        test.screen.selectAccount(DemoAccount.Antonia)
        runCurrent()
        assertEquals(3, delayed.size)
        delayed[2].resume(Unit)
        runCurrent()
        assertActive(test, DemoAccount.Antonia)
        val current = test.store.current()
        delayed[1].resume(Unit)
        runCurrent()
        assertSame(current, test.store.current())
        test.screen.logout()
        delayed[0].resume(Unit)
        runCurrent()
        assertUnavailable(test)
    }

    @Test fun changingOriginInterruptsPendingLoginAndBindsFutureIdentitiesToNewOrigin() = runTest {
        val test = environment()
        test.screen.selectAccount(DemoAccount.Antonia)
        runCurrent()
        assertActive(test, DemoAccount.Antonia)
        lateinit var late: Continuation<Unit>
        test.backend.waitFor = { suspendCoroutine { late = it } }
        test.screen.selectAccount(DemoAccount.Martin)
        runCurrent()
        test.screen.connect(secondOrigin)
        assertUnavailable(test)
        runCurrent()
        late.resume(Unit)
        runCurrent()
        assertUnavailable(test)
        test.backend.waitFor = {}
        test.screen.selectAccount(DemoAccount.Sofia)
        runCurrent()
        assertActive(test, DemoAccount.Sofia)
        assertEquals(secondOrigin, test.store.current()?.origin.toString())
        assertEquals(listOf(secondOrigin), test.saved)
    }

    @Test fun expiryRejectsAnExistingRfSelectionWithoutRunningUiExpiry() = runTest {
        val test = environment()
        test.screen.selectAccount(DemoAccount.Antonia)
        runCurrent()
        assertEquals("9000", test.rf.select())
        test.elapsed = 3_600_000
        assertEquals("6A82", test.rf.get())
        assertEquals("6A82", test.rf.select())
        assertNull(test.screen.visibleIdentity(test.screen.state.value, test.store.state.value))
        test.screen.resume()
        assertNull(test.screen.state.value.person)
        assertEquals(FailureKind.Expired.message, test.screen.state.value.notice)
    }

    @Test fun retainVerifiedIdentityWhenOfflineButNeverDuringUnsuccessfulSwitch() = runTest {
        val test = environment()
        test.screen.selectAccount(DemoAccount.Sofia)
        runCurrent()
        test.backend.offline = true
        test.screen.refresh()
        runCurrent()
        assertEquals(Reachability.Offline, test.screen.state.value.reachability)
        assertFalse(test.screen.state.value.fresh)
        assertActive(test, DemoAccount.Sofia)
        test.screen.selectAccount(DemoAccount.Martin)
        runCurrent()
        assertUnavailable(test)
        test.backend.offline = false
        test.screen.retryLogin()
        runCurrent()
        assertActive(test, DemoAccount.Martin)
    }

    @Test fun recreationKeepsOneControllerButReplacementAndNewProcessStartEmpty() = runTest {
        val test = environment()
        test.screen.selectAccount(DemoAccount.Martin)
        runCurrent()
        val before = test.store.current()
        test.screen.resume() // Activity recreation attaches to the same ViewModel and controller.
        assertSame(before, test.store.current())
        assertTrue(test.saved.isEmpty())
        val replacement = test.controller(test.store)
        assertNull(replacement.state.value.person)
        assertNull(test.store.current())
        assertEquals("6A82", test.rf.get())
        assertEquals("6A82", test.rf.select())
        val displacedState = test.screen.state.value
        val slot = test.store.state.value
        val visible = test.screen.visibleIdentity(displacedState, slot)
        assertNull(visible)
        assertNull(test.screen.presentation(displacedState, slot, visible).person)
        replacement.selectAccount(DemoAccount.Sofia)
        runCurrent()
        assertEquals(DemoAccount.Sofia.key, test.store.current()?.person?.account)
        test.screen.close() // Clearing the displaced ViewModel cannot clear the newer one.
        assertEquals(DemoAccount.Sofia.key, test.store.current()?.person?.account)
        val newProcess = HceIdentityStore(test.clock) { test.elapsed }
        val recreated = test.controller(newProcess)
        assertNull(newProcess.current())
        assertNull(recreated.state.value.selectedAccount)
        assertEquals(origin, recreated.state.value.address)
        assertTrue(test.saved.isEmpty())
    }

    @Test fun accountChangeIsBlockedWhileSavingCard() = runTest {
        assertAccountChangeBlockedDuringWrite {
            editCard()
            val fields = (state.value.draft as EditDraft.Card).fields
            updateCard(fields.copy(allergies = listOf("Polen")))
        }
    }

    @Test fun accountChangeIsBlockedWhileCreatingContact() = runTest {
        assertAccountChangeBlockedDuringWrite {
            editContact(null)
            updateContact(ContactFields("Nuevo contacto", "Amigo", "123"))
        }
    }

    @Test fun accountChangeIsBlockedWhileEditingContact() = runTest {
        assertAccountChangeBlockedDuringWrite {
            editContact(DemoAccount.Antonia.key)
            updateContact(ContactFields("Contacto editado", "Familiar", "456"))
        }
    }

    @Test fun accountChangeIsBlockedWhileDeletingContact() = runTest {
        assertAccountChangeBlockedDuringWrite {
            requestRemoval(DemoAccount.Antonia.key)
        }
    }

    private fun TestScope.assertAccountChangeBlockedDuringWrite(prepare: AttendeeController.() -> Unit) {
        val test = environment()
        val screen = test.screen
        screen.selectAccount(DemoAccount.Antonia)
        runCurrent()
        val identity = requireNotNull(test.store.current())

        val completeWrite = CompletableDeferred<Unit>()
        val writeStarted = CompletableDeferred<Unit>()
        test.backend.beforeWrite = {
            writeStarted.complete(Unit)
            completeWrite.await()
        }
        screen.prepare()
        val draft = requireNotNull(screen.state.value.draft)
        assertTrue(screen.state.value.canChangeAccount)
        screen.save()
        runCurrent()
        assertTrue(writeStarted.isCompleted)
        assertEquals(Task.Saving, screen.state.value.task)
        assertFalse(screen.state.value.canChangeAccount)

        screen.selectAccount(DemoAccount.Martin)
        runCurrent()
        assertEquals(Task.Saving, screen.state.value.task)
        assertEquals(DemoAccount.Antonia, screen.state.value.selectedAccount)
        assertSame(draft, screen.state.value.draft)
        assertSame(identity, test.store.current())

        completeWrite.complete(Unit)
        runCurrent()
        assertTrue(screen.state.value.canChangeAccount)
        assertNull(screen.state.value.draft)
        assertEquals(2L, screen.state.value.snapshot?.card?.version)

        screen.selectAccount(DemoAccount.Martin)
        runCurrent()
        assertActive(test, DemoAccount.Martin)
        assertEquals(10L, screen.state.value.snapshot?.card?.version)
    }

    @Test fun accountChangeDiscardsDraftAndMaintainsIndependentVersionsAndContacts() = runTest {
        val test = environment()
        val screen = test.screen
        screen.selectAccount(DemoAccount.Antonia)
        runCurrent()
        screen.editCard()
        val a = (screen.state.value.draft as EditDraft.Card).fields
        screen.updateCard(a.copy(allergies = listOf("Borrador de Antonia")))
        assertTrue(screen.state.value.canChangeAccount)
        screen.selectAccount(DemoAccount.Martin)
        assertNull(screen.state.value.draft)
        assertNull(screen.state.value.snapshot)
        runCurrent()
        assertActive(test, DemoAccount.Martin)
        assertEquals(10L, screen.state.value.snapshot?.card?.version)
        assertEquals("Contacto martin", screen.state.value.snapshot?.contacts?.single()?.name)
        screen.editCard()
        val b = (screen.state.value.draft as EditDraft.Card).fields
        screen.updateCard(b.copy(allergies = listOf("Polen")))
        screen.save()
        runCurrent()
        screen.editContact(null)
        screen.updateContact(ContactFields("Nuevo contacto", "Amigo", "123"))
        screen.save()
        runCurrent()
        screen.editContact("nuevo")
        screen.updateContact(ContactFields("Nuevo contacto", "Familiar", "456"))
        screen.save()
        runCurrent()
        assertEquals("456", screen.state.value.snapshot?.contacts?.last()?.phone)
        screen.requestRemoval("nuevo")
        screen.save()
        runCurrent()
        assertEquals(14L, screen.state.value.snapshot?.card?.version)
        assertEquals(listOf("Polen"), screen.state.value.snapshot?.card?.allergies)
        screen.selectAccount(DemoAccount.Antonia)
        runCurrent()
        assertEquals(1L, screen.state.value.snapshot?.card?.version)
        assertEquals(listOf("Alergia antonia"), screen.state.value.snapshot?.card?.allergies)
        assertEquals("Contacto antonia", screen.state.value.snapshot?.contacts?.single()?.name)
    }

    @Test fun staleSlotCannotBePublishedEvenWithoutCoroutineCancellation() = runTest {
        val test = environment()
        val owner = Any()
        test.store.claim(owner)
        val old = requireNotNull(test.store.invalidate(owner))
        val new = requireNotNull(test.store.invalidate(owner))
        val server = requireNotNull(attendeeServerAddress(origin, true))
        val active = demoSession(DemoAccount.Martin, initialTime.plusSeconds(3600))
        assertNull(test.store.publish(old, active, server))
        assertNotNull(test.store.publish(new, active, server))
        assertNull(test.store.publish(new, active, server))
    }

    private fun assertUnavailable(test: Environment) {
        assertNull(test.store.current())
        assertNull(test.screen.state.value.person)
        assertNull(test.screen.visibleIdentity(test.screen.state.value, test.store.state.value))
        assertEquals("6A82", test.rf.select())
        assertEquals("6A82", test.rf.get())
    }

    private fun assertActive(test: Environment, account: DemoAccount) {
        val identity = requireNotNull(test.store.current())
        assertSame(identity, test.screen.visibleIdentity(test.screen.state.value, test.store.state.value))
        assertEquals(account.key, identity.person.account)
        assertEquals("9000", test.rf.select())
        assertEquals(demoSession(account, initialTime).nfcId.hex,
            NfcProtocol.parsePersonIdResponse(test.rf.process(NfcProtocol.getPersonId())))
    }

    private fun CardSession.select() = NfcProtocol.toHex(process(NfcProtocol.selectAid()))
    private fun CardSession.get() = NfcProtocol.toHex(process(NfcProtocol.getPersonId()))
    private fun TestScope.environment() = Environment(this)

    private inner class Environment(private val scope: TestScope) {
        val clock: Clock = Clock.fixed(initialTime, ZoneOffset.UTC)
        var elapsed = 0L
        val store = HceIdentityStore(clock) { elapsed }
        val saved = mutableListOf<String>()
        val backend = FakeApi()
        val screen = controller(store)
        val rf = CardSession { store.current()?.credential }
        fun controller(registry: HceIdentityStore) =
            AttendeeController(scope.backgroundScope, true, origin, { saved.add(it) }, { backend }, clock, registry)
    }

    private inner class FakeApi : H4AttendeeApi() {
        var waitFor: suspend (DemoAccount) -> Unit = {}
        var beforeWrite: suspend () -> Unit = {}
        var loginError: FailureKind? = null
        var offline = false
        private val cards = DemoAccount.entries.associate { account ->
            account.key to EmergencyCard(if (account == DemoAccount.Martin) 10 else 1, initialTime.toString(),
                null, listOf("Alergia ${account.key}"), emptyList(), emptyList())
        }.toMutableMap()
        private val entries = DemoAccount.entries.associate { account ->
            account.key to listOf(Contact(account.key, "Contacto ${account.key}", "Familiar", "123"))
        }.toMutableMap()

        override suspend fun health() {
            if (offline) throw AttendeeFailure(FailureKind.Offline)
        }
        override suspend fun login(account: DemoAccount, onCredential: () -> Unit): AttendeeSession {
            health()
            onCredential()
            waitFor(account)
            loginError?.let { throw AttendeeFailure(it) }
            return demoSession(account, initialTime.plusSeconds(3600))
        }
        override suspend fun card(session: AttendeeSession) = cards.getValue(session.person.account)
        override suspend fun contacts(session: AttendeeSession) = entries.getValue(session.person.account)
        override suspend fun replaceCard(session: AttendeeSession, version: Long, fields: CardFields): EmergencyCard {
            val next = advance(session, version).copy(bloodType = fields.bloodType, allergies = fields.allergies,
                conditions = fields.conditions, medications = fields.medications)
            cards[session.person.account] = next
            return next
        }
        override suspend fun saveContact(session: AttendeeSession, version: Long, id: String?, fields: ContactFields): Long {
            val next = advance(session, version)
            val entry = Contact(id ?: "nuevo", fields.name, fields.relationship, fields.phone)
            entries[session.person.account] = contacts(session).filterNot { it.id == id } + entry
            return next.version
        }
        override suspend fun deleteContact(session: AttendeeSession, version: Long, id: String): Long {
            val next = advance(session, version)
            entries[session.person.account] = contacts(session).filterNot { it.id == id }
            return next.version
        }
        private suspend fun advance(session: AttendeeSession, version: Long): EmergencyCard {
            beforeWrite()
            assertEquals("http-${session.person.account}", session.token)
            val before = card(session)
            if (version != before.version) throw AttendeeFailure(FailureKind.Conflict)
            return before.copy(version = version + 1).also { cards[session.person.account] = it }
        }
    }
}
