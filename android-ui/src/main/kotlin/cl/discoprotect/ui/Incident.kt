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
internal enum class IncidentStatus { OPEN, CANCELLED }

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
) {
    fun validFor(event: String): Boolean = eventId == event && isIncidentUuid(id) &&
        requesterName.isNotBlank() && (zoneId == null) == (zoneName == null) &&
        (zoneId == null || zoneId.isNotBlank() && !zoneName.isNullOrBlank()) &&
        (locationText == null || locationText.isNotBlank() && locationText.length <= 160) &&
        runCatching { Instant.parse(updatedAt) >= Instant.parse(createdAt) }.getOrDefault(false)
}

internal fun isIncidentUuid(value: String): Boolean =
    runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)

@Serializable
internal data class IncidentList(val incidents: List<Incident>)

@Composable
internal fun IncidentCard(incident: Incident, actions: @Composable () -> Unit = {}) {
    val date = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
        .withZone(ZoneId.systemDefault()).format(Instant.parse(incident.createdAt))
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(Space.md), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Text(incident.requesterName, style = MaterialTheme.typography.titleMedium)
            Status(
                if (incident.status == IncidentStatus.OPEN) "Abierta" else "Cancelada",
                if (incident.status == IncidentStatus.OPEN) Tone.Info else Tone.Warning,
            )
            Field("Zona", incident.zoneName ?: "Ubicación desconocida")
            incident.locationText?.let { Field("Referencia manual", it) }
            Text(date, style = MaterialTheme.typography.bodyMedium)
            Field("ID de solicitud", incident.id)
            actions()
        }
    }
}
