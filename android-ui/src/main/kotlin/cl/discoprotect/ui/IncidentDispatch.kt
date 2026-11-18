package cl.discoprotect.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.serialization.Serializable

@Serializable
internal enum class DispatchPriority(val label: String, val icon: String, val background: Long, val foreground: Long) {
    P0("P0 · Crítica", "!", 0xFFFCEBED, 0xFF832234),
    P1("P1 · Alta", "▲", 0xFFFFE9D3, 0xFF773B00),
    P2("P2 · Media", "◆", 0xFFFFF3CD, 0xFF654C00),
    P3("P3 · Baja", "●", 0xFFE9F0FA, 0xFF254C81),
    PENDING("Pendiente de evaluación", "?", 0xFFEDE3F8, 0xFF402078),
}

@Serializable internal data class DispatchLocation(val zoneId: String?, val zoneName: String?, val text: String?) {
    fun label() = listOfNotNull(zoneName, text).joinToString(" · ")
}

@Serializable internal data class DispatchAnalysis(
    val category: TriageCategory,
    val summary: String,
    val uncertainty: List<String>,
    val observedAt: String,
    val inputSeq: Long,
    val locationEvidence: DispatchLocationEvidence? = null,
)

@Serializable internal data class DispatchLocationEvidence(val messageSeq: Long, val quote: String, val feature: String?)

@Serializable internal data class DispatchRisk(val kind: String, val messageSeq: Long, val quote: String, val uncertain: Boolean)

@Serializable internal data class DispatchOperational(
    val situation: String, val pending: List<String>, val guidance: String?, val risks: List<DispatchRisk>,
)

@Serializable internal enum class AttendeeLocationSource(val label: String) {
    CONFIRMED_AI("Confirmada por el asistente"), INDICATED("Indicada por el asistente"),
}

@Serializable internal data class AttendeeDispatchLocation(
    val zoneId: String, val zoneName: String, val source: AttendeeLocationSource, val messageSeq: Long,
    val evidence: DispatchLocationEvidence,
)

@Serializable internal enum class DangerAssessment(val label: String) {
    UNCHANGED("Mantener evaluación de peligro"), CONFIRMED("Confirmar peligro actual"), CLEARED("Peligro descartado o superado"),
}

@Serializable internal data class IncidentDispatch(
    val effectivePriority: DispatchPriority, val prioritySource: String, val priorityReasons: List<String>,
    val humanPriority: DispatchPriority?, val elapsedSeconds: Long, val revision: Long, val evaluatedAt: String,
    val confirmedLocation: DispatchLocation?, val declaredLocation: DispatchLocation?, val inferredLocation: DispatchLocation?,
    val analysis: DispatchAnalysis?,
    val attendeeLocation: AttendeeDispatchLocation? = null,
    val operational: DispatchOperational? = null,
) {
    fun priorityLabel() = effectivePriority.label + when (prioritySource) {
        "AI_PROVISIONAL" -> " · Provisional IA"
        "HUMAN" -> " · Guardia"
        "DECLARED_DANGER" -> " · Peligro declarado"
        "LOCAL_RISK" -> " · Señal crítica del chat"
        "LOCAL_URGENT" -> " · Señal urgente del chat"
        else -> ""
    }

    fun displayedLocation(): String = when {
        confirmedLocation != null -> "Confirmada · ${confirmedLocation.label()}"
        attendeeLocation != null -> "${attendeeLocation.source.label} · ${attendeeLocation.zoneName}"
        declaredLocation?.zoneName != null -> "Indicada por el asistente · ${declaredLocation.label()}"
        inferredLocation != null -> "Inferida por IA · ${inferredLocation.label()}"
        declaredLocation != null -> "Declarada · ${declaredLocation.label()}"
        else -> "Ubicación desconocida"
    }
}

@Composable
internal fun DispatchBadge(dispatch: IncidentDispatch) {
    val priority = dispatch.effectivePriority
    Surface(color = Color(priority.background), contentColor = Color(priority.foreground),
        shape = MaterialTheme.shapes.small) {
        Text("${priority.icon}  ${dispatch.priorityLabel()}", modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelLarge)
    }
}
