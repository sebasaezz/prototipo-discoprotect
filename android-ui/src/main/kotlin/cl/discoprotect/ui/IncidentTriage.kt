package cl.discoprotect.ui

import java.time.Instant
import kotlinx.serialization.Serializable

@Serializable
internal enum class SosReason(val label: String, val alert: String? = null) {
    UNRESPONSIVE("Persona que no responde", "Se reportó una persona que no responde"),
    IMMEDIATE_DANGER("Peligro inmediato", "Se reportó peligro inmediato"),
    ILLNESS_OR_INJURY("Malestar o lesión"),
    AGGRESSION_OR_HARASSMENT("Agresión o acoso"),
    CROWDING("Aglomeración"),
    OTHER("Otro motivo"),
}

@Serializable
internal enum class TriageCategory(val label: String) {
    MEDICAL("Salud o lesión"), SECURITY("Seguridad"), CROWD("Aglomeración"), OTHER("Otro"), UNKNOWN("Por determinar"),
}

@Serializable
internal enum class TriageUrgency(val label: String) {
    IMMEDIATE("Inmediata"), PRIORITY("Prioritaria"), UNDETERMINED("Por determinar"),
}

@Serializable
internal enum class TriageUncertainty(val label: String) { MEDIUM("Media"), HIGH("Alta") }

@Serializable
internal enum class TriageMissing(val label: String) {
    WHAT_HAPPENED("Qué ocurrió"), CURRENT_CONDITION("Situación actual"),
    ONGOING_DANGER("Si el peligro continúa"), PEOPLE_AFFECTED("Cuántas personas están afectadas"),
}

@Serializable
internal enum class TriageStatus(val label: String) {
    PENDING("Triaje pendiente"), SUGGESTED("Sugerencia de IA"), MANUAL("Triaje manual"),
}

@Serializable
internal data class TriageSuggestion(
    val category: TriageCategory,
    val urgency: TriageUrgency,
    val summary: String,
    val missingInformation: List<TriageMissing>,
    val uncertainty: TriageUncertainty,
) {
    fun valid() = summary.isNotBlank() && summary.length <= 220 && missingInformation.size <= 3 &&
        missingInformation.distinct().size == missingInformation.size
}

@Serializable
internal enum class ReviewDecision { CONFIRM, CORRECT }

@Serializable
internal data class TriageReview(
    val reviewVersion: Long,
    val reviewedTriageVersion: Long,
    val operationId: String,
    val decision: ReviewDecision,
    val category: TriageCategory,
    val urgency: TriageUrgency,
    val reviewedAt: String,
    val actor: AssignedStaff,
) {
    fun valid() = reviewVersion > 1 && reviewedTriageVersion == reviewVersion - 1 && isIncidentUuid(operationId) &&
        actor.id.isNotBlank() && actor.name.isNotBlank() && runCatching { Instant.parse(reviewedAt) }.isSuccess
}

@Serializable
internal data class IncidentTriage(
    val triageVersion: Long,
    val status: TriageStatus,
    val suggestion: TriageSuggestion?,
    val fallbackReason: String?,
    val fallback: TriageSuggestion?,
    val latestReview: TriageReview?,
) {
    fun valid() = triageVersion > 0 && (when (status) {
        TriageStatus.PENDING -> suggestion == null && fallback == null && fallbackReason == null && latestReview == null
        TriageStatus.SUGGESTED -> suggestion?.valid() == true && fallback == null && fallbackReason == null
        TriageStatus.MANUAL -> suggestion == null && fallback?.valid() == true && !fallbackReason.isNullOrBlank()
    }) && (latestReview == null || latestReview.valid() && latestReview.reviewVersion == triageVersion)
}
