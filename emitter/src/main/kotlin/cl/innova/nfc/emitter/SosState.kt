package cl.innova.nfc.emitter

import cl.discoprotect.ui.Incident
import cl.discoprotect.ui.IncidentStatus
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable

@Serializable
internal data class SosEvent(val id: String, val name: String, val venue: String, val role: String)

@Serializable
internal data class SosZone(val id: String, val name: String)

@Serializable
internal data class SosRequest(val clientRequestId: String, val zoneId: String?, val locationText: String?)

internal sealed interface SosAttempt {
    val eventId: String
    fun matches(incident: Incident): Boolean

    data class Create(override val eventId: String, val body: SosRequest) : SosAttempt {
        override fun matches(incident: Incident) = incident.eventId == eventId &&
            incident.clientRequestId == body.clientRequestId && incident.zoneId == body.zoneId &&
            incident.locationText == body.locationText
    }

    data class Cancel(override val eventId: String, val id: String) : SosAttempt {
        override fun matches(incident: Incident) = incident.eventId == eventId &&
            incident.id == id && incident.status == IncidentStatus.CANCELLED
    }
}

internal enum class SosOutcome { Ready, Confirmed, Cancelled, Uncertain, Offline, Unsent, Rejected }

internal data class SosState(
    val events: List<SosEvent> = emptyList(),
    val event: SosEvent? = null,
    val zones: List<SosZone>? = null,
    val locationChosen: Boolean = false,
    val zoneId: String? = null,
    val reference: String = "",
    val incidents: List<Incident>? = null,
    val pending: SosAttempt? = null,
    val outcome: SosOutcome = SosOutcome.Ready,
    val receipt: String? = null,
    val error: String? = null,
) {
    val canSend get() = event != null && zones != null && locationChosen && pending == null
}

private const val holdSteps = 30
private const val holdIntervalMillis = 50L

internal suspend fun holdSos(onProgress: (Float) -> Unit, complete: () -> Unit) {
    repeat(holdSteps) { step ->
        delay(holdIntervalMillis)
        onProgress((step + 1).toFloat() / holdSteps)
    }
    complete()
}
