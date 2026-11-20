package cl.innova.nfc.reader

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cl.discoprotect.ui.*

internal fun staffConnection(state: ReaderUiState) = when (state.server) {
    ServerState.Connected -> "Servidor conectado"
    ServerState.Unreachable -> "Servidor sin conexión"
    ServerState.Unchecked -> "Conexión por comprobar"
    ServerState.Unconfigured -> "Servidor sin configurar"
}

@Composable
internal fun StaffHome(state: ReaderUiState, open: (StaffPage) -> Unit, openCase: (Incident, Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Text(if (state.local) "Demo local" else state.event?.name ?: "Inicio", style = MaterialTheme.typography.headlineMedium)
        if (!state.local) {
            Text("${state.staffName ?: "Sin sesión de guardia"} · ${staffConnection(state)}",
                style = MaterialTheme.typography.bodyMedium)
        }
    }
    if (!state.signedIn || state.event == null || state.local) {
        state.notice?.takeIf { !state.local }?.let { Status(it, Tone.Warning) }
        if (state.local) {
            StaffLink("Leer identificación NFC", prominent = true) { open(StaffPage.Nfc) }
        } else {
            Text("Verifica tu cuenta y selecciona un evento autorizado para consultar la operación.")
            val setup = if (state.signedIn && state.events.isNotEmpty()) StaffPage.Event else StaffPage.Connection
            StaffLink(if (setup == StaffPage.Event) "Elegir evento" else "Configurar acceso", prominent = true) { open(setup) }
        }
        return
    }

    ActiveAttention(state, openCase)
    StaffLink("Leer identificación NFC", prominent = state.activeIncident == null) { open(StaffPage.Nfc) }
    val queue = state.incidents
    SectionTitle("Emergencias del evento")
    if (state.incidentProblem != null) {
        Status(if (state.incidentProblem.isConnectivityError()) "Cola sin conexión · última consulta disponible"
            else state.incidentProblem.description(), Tone.Warning)
    }
    if (queue != null) {
        val active = queue.filter { it.status.active }
        val critical = active.count { it.dispatch?.effectivePriority == DispatchPriority.P0 }
        val high = active.count { it.dispatch?.effectivePriority == DispatchPriority.P1 }
        Text("${active.size} activas · $critical críticas · $high de prioridad alta", style = MaterialTheme.typography.bodyMedium)
        active.firstOrNull { it.id != state.activeIncident?.id }?.let { top ->
            StaffCaseCard(top, "Ver detalles") { openCase(top, false) }
        }
    } else if (state.incidentProblem == null) Text("Consultando la cola…")
    StaffLink("Ver cola completa") { open(StaffPage.Incidents) }
}

@Composable
private fun SectionTitle(title: String) = Text(title, style = MaterialTheme.typography.titleMedium)

@Composable
private fun ActiveAttention(state: ReaderUiState, openCase: (Incident, Boolean) -> Unit) {
    SectionTitle("Mi atención activa")
    val active = state.activeIncident
    if (active == null) {
        Text(when {
            state.incidents == null || state.incidentProblem != null -> "Sin consulta vigente de asignaciones."
            state.guardBusy -> "La asignación está en otro evento autorizado."
            else -> "Sin solicitudes asignadas."
        }, style = MaterialTheme.typography.bodyMedium)
        return
    }
    val here = active.eventId == state.event?.id
    StaffCaseCard(active, if (here) "Continuar atención" else "Ir al evento de mi atención",
        showGuard = !here, chat = if (here && active.hasConversation) ({ openCase(active, true) }) else null) {
        openCase(active, false)
    }
}

@Composable
internal fun StaffQueue(state: ReaderUiState, actions: ReaderController, open: (Incident) -> Unit) {
    Text("Incidentes", style = MaterialTheme.typography.headlineMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
        state.event?.let { Text(it.name, Modifier.align(androidx.compose.ui.Alignment.CenterVertically),
            style = MaterialTheme.typography.bodyMedium) }
        if (state.incidentsVisible) TextButton(onClick = actions::refreshIncidents,
            enabled = !state.incidentsLoading && !state.incidentWriting, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("Actualizar incidentes")
        }
    }
    StaffQueueFeedback(state, actions)
    if (state.local || !state.signedIn || state.event == null) {
        Text("La cola requiere una sesión conectada y un evento autorizado.")
        return
    }
    val list = state.incidents ?: return
    val own = state.activeIncident?.takeIf { it.eventId == state.event?.id }
    if (own != null) {
        SectionTitle("Mi atención activa")
        StaffCaseCard(own, "Continuar atención", compact = true, showGuard = false) { open(own) }
    }
    val active = list.filter { it.status.active && it.id != own?.id }
    val history = list.filterNot { it.status.active }
    SectionTitle("Pendientes y en atención")
    if (active.isEmpty()) Text("Sin otras solicitudes activas.")
    active.forEach { item -> StaffCaseCard(item, "Ver detalles") { open(item) } }
    if (history.isNotEmpty()) {
        HorizontalDivider()
        SectionTitle("Historial")
        history.forEach { item -> StaffCaseCard(item, "Ver detalles") { open(item) } }
    }
}

@Composable
internal fun StaffQueueFeedback(state: ReaderUiState, actions: ReaderController) {
    if (state.incidentsLoading && state.incidents == null) {
        Status("Consultando incidentes", Tone.Info)
        LinearProgressIndicator(Modifier.fillMaxWidth())
    }
    if (state.incidentWriting) Status("Enviando operación", Tone.Info)
    state.incidentNotice?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    if (state.pendingIncident && !state.incidentWriting) {
        Status("Resultado sin confirmar", Tone.Warning)
        OutlinedButton(onClick = actions::retryIncident, enabled = state.incidentsCurrent,
            modifier = Modifier.heightIn(min = 48.dp)) { Text("Reintentar la misma operación") }
    }
    state.incidentProblem?.let {
        Status(it.description(), Tone.Warning)
        if (state.incidents != null) Text("Se muestra la última consulta disponible.")
    }

}

internal fun staffSummary(incident: Incident): String? =
    incident.dispatch?.operational?.situation?.takeIf { it.isNotBlank() }
        ?: incident.dispatch?.analysis?.summary?.takeIf { it.isNotBlank() }

@Composable
internal fun StaffCaseSignals(incident: Incident, compact: Boolean = false) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        incident.dispatch?.let { DispatchBadge(it, compact = compact) }
        Status(incident.status.label, incident.status.tone)
    }
    val location = incident.dispatch?.displayedLocation() ?: incident.zoneName ?: "Ubicación desconocida"
    Text(location, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun StaffCaseCard(
    incident: Incident, label: String, compact: Boolean = false, showGuard: Boolean = true,
    chat: (() -> Unit)? = null, open: () -> Unit,
) {
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(Space.md), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            Text(incident.requesterName, style = MaterialTheme.typography.titleMedium)
            StaffCaseSignals(incident, compact = true)
            staffSummary(incident)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = if (compact) 1 else 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
            if (showGuard) incident.assignedStaff?.let { Text("Guardia · ${it.name}", style = MaterialTheme.typography.bodyMedium) }
            incident.dispatch?.elapsedSeconds?.takeIf { incident.status.active && it >= 0 }?.let {
                val minutes = it / 60
                val elapsed = when {
                    minutes >= 60 -> "${minutes / 60} h ${minutes % 60} min"
                    minutes >= 1 -> "$minutes min"
                    else -> "$it s"
                }
                Text("$elapsed desde el aviso", style = MaterialTheme.typography.labelMedium)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                if (label == "Ver detalles") {
                    TextButton(onClick = open, modifier = Modifier.heightIn(min = 48.dp)) { Text(label) }
                } else {
                    Button(onClick = open, modifier = Modifier.heightIn(min = 48.dp)) { Text(label) }
                }
                if (chat != null) TextButton(onClick = chat, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Abrir conversación")
                }
            }
        }
    }
}
