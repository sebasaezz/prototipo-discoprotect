package cl.discoprotect.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.UUID
import kotlinx.serialization.Serializable

@Serializable
internal enum class IncidentStatus(val label: String, val tone: Tone) {
    OPEN("Abierta", Tone.Warning),
    ASSIGNED("Asignada", Tone.Info),
    IN_PROGRESS("En atención", Tone.Info),
    RESOLVED("Resuelta", Tone.Success),
    CANCELLED("Cancelada", Tone.Warning),
}

@Serializable
internal data class AssignedStaff(val id: String, val name: String)

@Serializable
internal data class Incident(
    val id: String,
    val eventId: String,
    val requesterName: String,
    val zoneId: String?,
    val zoneName: String?,
    val locationText: String?,
    val status: IncidentStatus,
    val createdAt: String,
    val updatedAt: String,
    val requesterPersonId: String? = null,
    val clientRequestId: String? = null,
    val version: Long = 1,
    val assignedAt: String? = null,
    val startedAt: String? = null,
    val resolvedAt: String? = null,
    val assignedStaff: AssignedStaff? = null,
    val resolutionNote: String? = null,
    val reasonCode: SosReason? = null,
    val dangerAlert: SosReason? = null,
    val triage: IncidentTriage? = null,
    val hasConversation: Boolean = false,
    val dispatch: IncidentDispatch? = null,
) {
    fun validFor(event: String): Boolean = eventId == event && isIncidentUuid(id) &&
        requesterName.isNotBlank() && (zoneId == null) == (zoneName == null) &&
        (zoneId == null || zoneId.isNotBlank() && !zoneName.isNullOrBlank()) &&
        (locationText == null || locationText.isNotBlank() && locationText.length <= 160) &&
        version > 0 &&
        (assignedAt != null) == (status in setOf(IncidentStatus.ASSIGNED, IncidentStatus.IN_PROGRESS, IncidentStatus.RESOLVED)) &&
        (startedAt != null) == (status in setOf(IncidentStatus.IN_PROGRESS, IncidentStatus.RESOLVED)) &&
        (resolvedAt != null) == (status == IncidentStatus.RESOLVED) &&
        runCatching {
            listOfNotNull(createdAt, assignedAt, startedAt, resolvedAt, updatedAt)
                .map(Instant::parse).zipWithNext().all { (earlier, later) -> earlier <= later }
        }.getOrDefault(false)
}

internal fun isIncidentUuid(value: String): Boolean =
    runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)

@Serializable
internal data class IncidentList(val incidents: List<Incident>)

internal fun currentIncidentSnapshot(previous: List<Incident>?, incoming: List<Incident>): List<Incident> {
    val known = previous.orEmpty().associateBy { it.id }
    return incoming.map { item ->
        val current = known[item.id] ?: return@map item
        val operational = if (current.version > item.version) current else item
        val triage = if ((current.triage?.triageVersion ?: 0) > (item.triage?.triageVersion ?: 0)) current.triage else item.triage
        if (operational.triage == triage) operational else operational.copy(triage = triage)
    }
}

internal fun incidentPollDelay(failures: Int): Long = when (failures) {
    0 -> 5_000
    1 -> 10_000
    2 -> 20_000
    else -> 30_000
}

internal fun incidentTime(value: String): String = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
    .withZone(ZoneId.systemDefault()).format(Instant.parse(value))

@Composable
internal fun IncidentCard(incident: Incident, actions: @Composable () -> Unit = {}) {
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(Space.md), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Text(incident.requesterName, style = MaterialTheme.typography.titleMedium)
            Status(incident.status.label, incident.status.tone)
            if (incident.dispatch != null) {
                Field("Ubicación", incident.dispatch.displayedLocation())
            } else {
                Field("Zona", incident.zoneName ?: "Ubicación desconocida")
                incident.locationText?.let { Field("Referencia manual", it) }
            }
            incident.assignedStaff?.let { Field("Guardia asignado", it.name) }
            Field("Creación", incidentTime(incident.createdAt))
            incident.assignedAt?.let { Field("Asignación", incidentTime(it)) }
            incident.startedAt?.let { Field("Inicio de atención", incidentTime(it)) }
            incident.resolvedAt?.let { Field("Resolución", incidentTime(it)) }
            if (incident.updatedAt != incident.createdAt) Field("Último cambio", incidentTime(incident.updatedAt))
            incident.resolutionNote?.let { Field("Nota de cierre", it) }
            Field("ID de solicitud", incident.id)
            actions()
        }
    }
}
