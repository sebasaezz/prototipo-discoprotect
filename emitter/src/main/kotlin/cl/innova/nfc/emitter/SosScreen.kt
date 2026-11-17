package cl.innova.nfc.emitter

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
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
internal fun SosScreen(state: AttendeeState, actions: AttendeeController) {
    Heading("Chat de ayuda", "Pide ayuda al personal del evento.")
    Text("Demostración académica. Usa solo casos ficticios.")
    val person = state.person
    if (person == null) {
        Status("Verifica tu cuenta para enviar y consultar solicitudes.", Tone.Info)
        OutlinedButton(onClick = { actions.navigate(Section.Connection) }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("Ir a Conexión")
        }
        return
    }
    val sos = state.sos
    val idle = state.task == null
    val editable = idle && sos.pending == null
    val event = sos.event
    var confirmSend by remember(person.id, event?.id) { mutableStateOf(false) }
    var confirmCancel by remember(person.id, event?.id) { mutableStateOf<String?>(null) }
    var chatId by remember(person.id, event?.id) { mutableStateOf<String?>(null) }
    var report by remember(person.id, event?.id) { mutableStateOf("") }
    LaunchedEffect(sos.receipt) {
        if (sos.incidents.orEmpty().any { it.id == sos.receipt && it.hasConversation }) {
            chatId = sos.receipt
            report = ""
        }
    }
    Field("Cuenta activa", person.name)

    if (sos.events.isEmpty()) {
        if (idle && sos.error == null) Text("Selecciona «Consultar servidor» para cargar tus eventos autorizados.")
    } else {
        SosMenu("Evento", event?.name ?: "Seleccionar evento", editable) { close ->
            sos.events.forEach { item ->
                DropdownMenuItem(text = { Text("${item.name} · ${item.venue}") }, onClick = {
                    close()
                    actions.selectSosEvent(item.id)
                }, modifier = Modifier.heightIn(min = 48.dp))
            }
        }
    }

    if (event != null) {
        val currentChat = sos.incidents?.firstOrNull { it.id == chatId && it.hasConversation }
        if (currentChat != null) {
            Field("Solicitud recibida", "${currentChat.id} · ${incidentTime(currentChat.createdAt)}")
            Status(currentChat.status.label, currentChat.status.tone)
            key(person.id, event.id, currentChat.id) {
                IncidentChat(currentChat.id, attendee = true, outbox = actions.chatOutbox,
                    load = { actions.conversation(currentChat.id, it) }, send = { actions.message(currentChat.id, it) })
            }
            TextButton(onClick = { chatId = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Otra solicitud") }
        } else {
            OutlinedTextField(report, { if (it.length <= 500) report = it }, enabled = editable,
                label = { Text("¿Qué está ocurriendo y dónde?") }, supportingText = { Text("${report.length}/500") },
                maxLines = 5, modifier = Modifier.fillMaxWidth())
            Button(onClick = { actions.sendHelp(report) }, enabled = editable && report.isNotBlank(),
                modifier = Modifier.heightIn(min = 48.dp)) { Text("Enviar primer mensaje") }
        }
        if (editable) {
            key(person.id, event.id) {
                HoldSosButton({ actions.sendHelp() }) { confirmSend = true }
            }
            TextButton(onClick = { confirmSend = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Enviar SOS sin texto con confirmación")
            }
        }
    }

    when (sos.outcome) {
        SosOutcome.Ready -> Unit
        SosOutcome.Confirmed -> Status("Solicitud recibida por el servidor", Tone.Success)
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
    sos.receipt?.let { Field("ID confirmado", it) }
    sos.error?.let { Status(it, Tone.Warning) }
    sos.historyError?.let {
        Status(it, Tone.Warning)
        if (sos.incidents != null) Text("La última consulta puede estar desactualizada.")
    }
    sos.lastConsultedAt?.let { Field("Última consulta", incidentTime(it.toString())) }
    OutlinedButton(onClick = actions::refreshSos, enabled = idle, modifier = Modifier.heightIn(min = 48.dp)) {
        Text("Consultar servidor")
    }
    if (sos.pending != null && event != null && !state.sosWriting) {
        TextButton(onClick = actions::retrySos,
            enabled = idle && (sos.pending !is SosAttempt.Cancel || sos.historyFresh),
            modifier = Modifier.heightIn(min = 48.dp)) {
            Text(if (sos.pending is SosAttempt.Cancel) "Reintentar cancelación" else "Reintentar el mismo envío")
        }
    }

    sos.incidents?.let { incidents ->
        Heading("Mis solicitudes", "Historial de este evento.")
        if (incidents.isEmpty()) Text("No tienes solicitudes registradas en este evento.")
        incidents.forEach { incident ->
            IncidentCard(incident) {
                if (incident.hasConversation && incident.id != chatId) {
                    TextButton(onClick = { chatId = incident.id },
                        modifier = Modifier.heightIn(min = 48.dp)) { Text("Abrir conversación") }
                }
                if (incident.status == IncidentStatus.OPEN) {
                    TextButton(onClick = { confirmCancel = incident.id }, enabled = editable && sos.historyFresh,
                        modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancelar solicitud") }
                }
            }
        }
    }

    if (confirmSend && editable && event != null) {
        SosConfirmation("¿Enviar SOS?", "Se enviará tu solicitud al personal de ${event.name}.",
            "Enviar SOS", dismiss = { confirmSend = false }, accept = {
                confirmSend = false
                actions.sendHelp()
            })
    }
    confirmCancel?.takeIf { id ->
        editable && sos.historyFresh && sos.incidents?.any { it.id == id && it.status == IncidentStatus.OPEN } == true
    }?.let { id ->
        SosConfirmation("¿Cancelar esta solicitud?", "Se conservará el registro con estado Cancelada.",
            "Cancelar solicitud", dismiss = { confirmCancel = null }, accept = {
                confirmCancel = null
                actions.cancelSos(id)
            })
    }
}

@Composable
private fun SosMenu(label: String, selected: String, enabled: Boolean, options: @Composable (() -> Unit) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(selected)
            }
            DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
                options { expanded = false }
            }
        }
    }
}

@Composable
private fun HoldSosButton(send: () -> Unit, openConfirmation: () -> Unit) {
    var progress by remember { mutableFloatStateOf(0f) }
    var pressing by remember { mutableStateOf(false) }
    val sendCurrent by rememberUpdatedState(send)
    Surface(
        color = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).semantics(mergeDescendants = true) {
            role = Role.Button
            onClick("Solicitar con confirmación") { openConfirmation(); true }
        }.pointerInput(Unit) {
            detectTapGestures(onTap = {}, onPress = {
                coroutineScope {
                    pressing = true
                    val timer = launch { holdSos({ progress = it }, sendCurrent) }
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
    ) {
        Box(Modifier.padding(Space.md), contentAlignment = Alignment.Center) {
            Text(if (pressing) "Mantén pulsado…" else "Mantén pulsado SOS · 1,5 s",
                style = MaterialTheme.typography.titleMedium)
        }
    }
    if (pressing) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun SosConfirmation(title: String, message: String, action: String, dismiss: () -> Unit, accept: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = { Text(message) },
        confirmButton = { Button(onClick = accept, modifier = Modifier.heightIn(min = 48.dp)) { Text(action) } },
        dismissButton = { TextButton(onClick = dismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Volver") } })
}
