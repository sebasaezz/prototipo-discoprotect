package cl.innova.nfc.emitter

import cl.innova.nfc.protocol.CardCredential
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.HttpUrl

internal class ShareableIdentity(
    val person: Person,
    val origin: HttpUrl,
    val credential: CardCredential,
    val expiresAt: Instant,
    val elapsedDeadline: Long,
)

internal class IdentitySlot(val owner: Any?, val generation: Long, val identity: ShareableIdentity? = null)

/** One atomic slot for UI and HCE. The owner scopes mutations to the current controller. */
internal class HceIdentityStore(
    private val clock: Clock = Clock.systemUTC(),
    private val elapsedMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private val slot = MutableStateFlow(IdentitySlot(null, 0))
    val state = slot.asStateFlow()

    fun claim(owner: Any) {
        slot.update { IdentitySlot(owner, it.generation + 1) }
    }

    fun owns(owner: Any): Boolean = slot.value.owner === owner

    fun invalidate(owner: Any): IdentitySlot? {
        while (true) {
            val before = slot.value
            if (before.owner !== owner) return null
            val empty = IdentitySlot(owner, before.generation + 1)
            if (slot.compareAndSet(before, empty)) return empty
        }
    }

    fun publish(expected: IdentitySlot, session: AttendeeSession, origin: HttpUrl): ShareableIdentity? {
        if (expected.identity != null || slot.value !== expected) return null
        val now = clock.instant()
        val remaining = Duration.between(now, session.expiresAt).coerceAtMost(Duration.ofHours(8))
        if (remaining.isNegative || remaining.toMillis() == 0L) return null
        val generation = expected.generation + 1
        val identity = ShareableIdentity(
            session.person, origin, CardCredential(generation, session.nfcId),
            now.plus(remaining), elapsedMillis() + remaining.toMillis(),
        )
        return if (slot.compareAndSet(expected, IdentitySlot(expected.owner, generation, identity))) identity else null
    }

    /** The final atomic read confirms eligibility at the APDU decision point. */
    fun current(snapshot: IdentitySlot = slot.value): ShareableIdentity? {
        val identity = snapshot.identity ?: return null
        return identity.takeIf {
            elapsedMillis() < it.elapsedDeadline && clock.instant().isBefore(it.expiresAt) && slot.value === snapshot
        }
    }
}

internal object ProcessHceIdentity {
    val store = HceIdentityStore(elapsedMillis = android.os.SystemClock::elapsedRealtime)
}
