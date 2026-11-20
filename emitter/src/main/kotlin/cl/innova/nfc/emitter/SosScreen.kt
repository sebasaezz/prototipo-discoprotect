package cl.innova.nfc.emitter

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cl.discoprotect.ui.*
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

@Composable
internal fun AttendeeHome(state: AttendeeState, actions: AttendeeController, openTicket: () -> Unit) {
    val sos = state.sos
    val event = sos.event
    val active = sos.incidents.orEmpty().firstOrNull { it.status.active }
    val canSend = state.person != null && event != null && state.task == null && sos.pending == null
    var additional by remember(state.sharingGeneration, event?.id, active?.id) { mutableStateOf(false) }
    var confirmSend by remember(state.sharingGeneration, event?.id) { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Text(event?.name ?: "Sin evento seleccionado", style = MaterialTheme.typography.headlineSmall)
        Text(sessionSummary(state), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        if (active != null && !additional) {
            Field("Solicitud activa", active.status.label)
            DestinationLink(
                if (active.hasConversation) "Continuar conversación" else "Ver solicitud",
                prominent = true,
            ) {
                if (active.hasConversation) actions.openConversation(active.id) else actions.navigate(Section.Sos)
            }
        } else {
            key(state.sharingGeneration, event?.id) {
                HoldSosButton(canSend, send = { actions.sendHelp() }, openConfirmation = { confirmSend = true })
            }
            when {
                state.person == null -> {
                    Text("Verifica tu cuenta para pedir ayuda al personal del evento.")
                    Button(onClick = { actions.navigate(Section.Account) }) { Text("Verificar cuenta") }
                }
                event == null -> Text("Elige un evento autorizado para activar el SOS.")
                sos.pending == null -> Text("El personal del evento recibirá tu solicitud.")
            }
        }
    }

    if (state.person != null) {
        EventPicker(sos.events, event, enabled = state.task == null && sos.pending == null) {
            actions.selectSosEvent(it.id)
        }
        if (sos.events.isEmpty() && state.task == null) {
            Text("Consulta el servidor para cargar tus eventos autorizados.")
        }
        SosFeedback(state, actions)
        if (active != null) {
            TextButton(onClick = { additional = !additional }, enabled = additional || canSend,
                modifier = Modifier.heightIn(min = 48.dp)) {
                Text(if (additional) "Volver a la solicitud activa" else "Pedir ayuda adicional")
            }
        }
        if (sos.events.isEmpty() || sos.historyError != null) {
            RefreshSos(state, actions)
        }
    }

    LocationLink(state, actions)
    HorizontalDivider()
    DestinationLink("Entrada QR") { openTicket() }
    DestinationLink("Mi ficha de emergencia") { actions.navigate(Section.Card) }
    event?.let { Text(it.venue, style = MaterialTheme.typography.bodyMedium) }

    if (confirmSend && canSend) {
        SosConfirmation("¿Enviar SOS?", "Se enviará tu solicitud al personal de ${event.name}.${sos.selectedLocation?.let { "\nUbicación adjunta · ${it.source.label}" }.orEmpty()}",
            "Enviar SOS", dismiss = { confirmSend = false }, accept = {
                confirmSend = false
                actions.sendHelp()
            })
    }
}

@Composable
internal fun LocationLink(state: AttendeeState, actions: AttendeeController) {
    if (state.sos.event != null && state.person != null) {
        val location = (state.sos.pending as? SosAttempt.Create)?.body?.geoLocation ?: state.sos.selectedLocation
        DestinationLink("Tu ubicación en el evento", location?.let { "Ubicación para el SOS · ${it.source.label}" }
            ?: "Mapa del recinto") { actions.navigate(Section.Map) }
    }
}

private fun sessionSummary(state: AttendeeState): String {
    val connection = when (state.reachability) {
        Reachability.Connected -> "Servidor conectado"
        Reachability.Offline -> "Sin conexión"
        Reachability.Unchecked -> "Conexión por comprobar"
    }
    return "${state.person?.name ?: "Sin sesión"} · $connection"
}

@Composable
internal fun AttendeeHelp(state: AttendeeState, actions: AttendeeController) {
    Text("Ayuda", style = MaterialTheme.typography.headlineMedium)
    if (state.person == null) {
        Text("Verifica tu cuenta para consultar tus solicitudes de ayuda.")
        DestinationLink("Verificar cuenta") { actions.navigate(Section.Account) }
        return
    }

    val sos = state.sos
    sos.event?.let { Text(it.name, style = MaterialTheme.typography.bodyMedium) }
    val (active, history) = sos.incidents.orEmpty().partition { it.status.active }
    if (active.isEmpty()) {
        Text(when {
            sos.event == null -> "Selecciona un evento en Inicio para ver tus solicitudes."
            sos.incidents == null -> "Consulta tus solicitudes del evento."
            else -> "No tienes solicitudes activas."
        })
        DestinationLink("Ir al SOS", prominent = true) { actions.navigate(Section.Home) }
    } else {
        Text("Solicitudes activas", style = MaterialTheme.typography.titleMedium)
        active.forEach { HelpIncident(state, it, actions) }
    }


    LocationLink(state, actions)
    SosFeedback(state, actions)
    RefreshSos(state, actions)
    if (history.isNotEmpty()) {
        HorizontalDivider()
        Text("Solicitudes anteriores", style = MaterialTheme.typography.titleMedium)
        history.forEach { HelpIncident(state, it, actions) }
    }
    sos.lastConsultedAt?.let {
        Text("Última consulta · ${incidentTime(it.toString())}", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun HelpIncident(state: AttendeeState, incident: Incident, actions: AttendeeController) {
    var details by remember(incident.id) { mutableStateOf(false) }
    Surface(
        Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.padding(Space.md), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Status(incident.status.label, incident.status.tone)
            Text(incidentTime(incident.createdAt), style = MaterialTheme.typography.bodyMedium)
            if (incident.hasConversation) {
                Button(onClick = { actions.openConversation(incident.id) },
                    enabled = !state.sosWriting, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(if (incident.status.active) "Continuar conversación" else "Ver conversación")
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                TextButton(onClick = { details = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Ver detalles")
                }
                CancelRequest(state, incident, actions)
            }
        }
    }
    if (details) IncidentDetails(incident, onDismiss = { details = false })
}

@Composable
internal fun IncidentDetails(
    incident: Incident, onDismiss: () -> Unit, extra: @Composable () -> Unit = {},
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Detalles de la solicitud") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                IncidentCard(incident)
                extra()
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cerrar detalles") }
        },
    )
}


@Composable
internal fun CancelRequest(state: AttendeeState, incident: Incident, actions: AttendeeController) {
    if (!incident.status.active) return
    var confirm by remember(incident.id) { mutableStateOf(false) }
    val canCancel = state.task == null && state.sos.pending == null && state.sos.historyFresh
    TextButton(onClick = { confirm = true }, enabled = canCancel,
        modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancelar solicitud") }
    if (confirm && canCancel) {
        SosConfirmation("¿Cancelar esta solicitud?",
            "Se cerrará la conversación y la solicitud quedará en el historial.",
            "Cancelar solicitud", dismiss = { confirm = false }, accept = {
                confirm = false
                actions.cancelSos(incident.id)
            })
    }
}

@Composable
internal fun SosFeedback(state: AttendeeState, actions: AttendeeController) {
    val sos = state.sos
    when (sos.outcome) {
        SosOutcome.Ready -> Unit
        SosOutcome.Confirmed -> Unit
        SosOutcome.Cancelled -> Status("Solicitud cancelada en el servidor", Tone.Success)
        SosOutcome.Uncertain -> if (!state.sosWriting) Status(
            if (sos.pending is SosAttempt.Cancel) "No se pudo confirmar la cancelación"
            else "No se pudo confirmar la recepción", Tone.Warning,
        )
        SosOutcome.Offline -> Status(
            if (sos.pending == null) "Sin conexión · No se inició el envío" else "Sin conexión", Tone.Warning,
        )
        SosOutcome.Unsent -> Status("No se inició el envío", Tone.Warning)
        SosOutcome.Rejected -> Status("El servidor rechazó la operación", Tone.Error)
    }
    sos.error?.let { Status(it, Tone.Warning) }
    sos.historyError?.let {
        Status(it, Tone.Warning)
        if (sos.incidents != null) Text("La última consulta puede estar desactualizada.")
    }
    if (!state.sosWriting && sos.pending != null && sos.event != null) {
        OutlinedButton(onClick = actions::retrySos,
            enabled = state.task == null && (sos.pending !is SosAttempt.Cancel || sos.historyFresh),
            modifier = Modifier.heightIn(min = 48.dp)) {
            Text(if (sos.pending is SosAttempt.Cancel) "Reintentar cancelación" else "Reintentar el mismo envío")
        }
    }
}

@Composable
private fun RefreshSos(state: AttendeeState, actions: AttendeeController) {
    TextButton(onClick = actions::refreshSos, enabled = state.task == null,
        modifier = Modifier.heightIn(min = 48.dp)) { Text("Consultar servidor") }
}

@Composable
internal fun EventPicker(
    events: List<SosEvent>, selected: SosEvent?, enabled: Boolean,
    compact: Boolean = false, select: (SosEvent) -> Unit,
) {
    if (events.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    Box {
        val label = if (selected == null) "Elegir evento" else "Cambiar evento"
        if (compact) {
            TextButton(onClick = { expanded = true }, enabled = enabled,
                modifier = Modifier.heightIn(min = 48.dp)) { Text(label) }
        } else {
            OutlinedButton(onClick = { expanded = true }, enabled = enabled,
                modifier = Modifier.heightIn(min = 48.dp)) { Text(label) }
        }
        DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            events.forEach { item ->
                DropdownMenuItem(text = { Text("${item.name} · ${item.venue}") }, onClick = {
                    expanded = false
                    select(item)
                }, modifier = Modifier.heightIn(min = 48.dp))
            }
        }
    }
}

@Composable
private fun HoldSosButton(enabled: Boolean, send: () -> Unit, openConfirmation: () -> Unit) {
    var progress by remember { mutableFloatStateOf(0f) }
    var pressing by remember { mutableStateOf(false) }
    val sendCurrent by rememberUpdatedState(send)
    val confirmCurrent by rememberUpdatedState(openConfirmation)
    val shape = MaterialTheme.shapes.large
    val bounds = Modifier.fillMaxWidth().heightIn(min = 124.dp)
    val content: @Composable () -> Unit = {
        Column(Modifier.padding(Space.md), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            Text("Pedir ayuda · SOS", style = MaterialTheme.typography.headlineSmall)
            Text(when {
                !enabled -> "SOS no disponible"
                pressing -> "Mantén pulsado…"
                else -> "Mantén pulsado 1,5 s"
            }, style = MaterialTheme.typography.bodyMedium)
            if (pressing) LinearProgressIndicator(progress = { progress },
                modifier = Modifier.fillMaxWidth().padding(top = Space.sm),
                color = MaterialTheme.colorScheme.onError,
                trackColor = MaterialTheme.colorScheme.onError.copy(alpha = 0.35f))
        }
    }
    if (!enabled) {
        Surface(bounds, shape, color = MaterialTheme.colorScheme.surfaceVariant, content = content)
        return
    }
    Surface(
        modifier = bounds.semantics(mergeDescendants = true) {
            role = Role.Button
            onClick("Activar SOS con confirmación") { openConfirmation(); true }
        }.pointerInput(Unit) {
            var holdCompleted = false
            detectTapGestures(onTap = { if (!holdCompleted) confirmCurrent() }, onPress = {
                coroutineScope {
                    holdCompleted = false
                    pressing = true
                    val timer = launch {
                        holdSos({ progress = it }) {
                            holdCompleted = true
                            sendCurrent()
                        }
                    }
                    try {
                        tryAwaitRelease()
                    } finally {
                        timer.cancel()
                        pressing = false
                        progress = 0f
                    }
                }
            })
        },
        color = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError,
        shape = shape, content = content,
    )
}

@Composable
private fun SosConfirmation(title: String, message: String, action: String, dismiss: () -> Unit, accept: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = { Text(message) },
        confirmButton = { Button(onClick = accept, modifier = Modifier.heightIn(min = 48.dp)) { Text(action) } },
        dismissButton = { TextButton(onClick = dismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Volver") } })
}
