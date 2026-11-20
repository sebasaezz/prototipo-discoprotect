package cl.innova.nfc.emitter

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import cl.discoprotect.ui.*

@Composable
internal fun AttendeeConversation(
    state: AttendeeState, incident: Incident, actions: AttendeeController, modifier: Modifier,
) {
    IncidentChat(
        incident.id, attendee = true, outbox = actions.chatOutbox,
        load = { actions.conversation(incident.id, it) },
        send = { actions.message(incident.id, it) },
        conversationLayout = { chat ->
            ConversationLayout(state, incident, chat, actions, modifier)
        },
    )
}

@Composable
private fun ConversationLayout(
    state: AttendeeState, incident: Incident, chat: IncidentChatContent,
    actions: AttendeeController, modifier: Modifier,
) {
    val imeOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    var details by remember(incident.id) { mutableStateOf(false) }
    val list = rememberLazyListState()
    val results = chat.messages
    val systemMessage = if (results.isEmpty() && chat.state?.control != ChatControl.CLOSED) {
        "Solicitud recibida. Puedes contarle al personal qué ocurre."
    } else null
    val closed = chat.state?.control == ChatControl.CLOSED
    val total = results.size + (if (systemMessage == null) 0 else 1) + (if (closed) 1 else 0)

    // While a list is displaying the previous snapshot, canScrollForward reflects that snapshot.
    val followLatest = !list.canScrollForward
    LaunchedEffect(total, imeOpen) {
        if (followLatest && total > 0) list.scrollToItem(total - 1)
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        if (!imeOpen) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Space.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when {
                    chat.state?.control == ChatControl.STAFF && incident.status == IncidentStatus.OPEN ->
                        Status("Personal a cargo", Tone.Info)
                    closed && incident.status.active -> Status("Atención terminada", Tone.Info)
                    else -> Status(incident.status.label, incident.status.tone)
                }
                TextButton(onClick = { actions.refreshSos(); details = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Detalles")
                }
            }
            incident.geoLocation?.let { Text("Ubicación enviada · ${it.source.label}", style = MaterialTheme.typography.bodyMedium) }
            chatStatus(chat.state)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            if (chat.disconnected) Status("Sin conexión con el chat", Tone.Warning)
            if (chat.state?.control == ChatControl.AUTO && chat.state.aiStatus == "UNAVAILABLE") {
                Status("IA temporalmente no disponible. El personal puede ver tu solicitud.", Tone.Warning)
            }
        }
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(vertical = Space.sm),
            verticalArrangement = Arrangement.spacedBy(Space.sm),
            state = list,
        ) {
            if (systemMessage != null) item(key = "received") {
                Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    ChatSystemNote(systemMessage)
                    ChatSystemNote("Prototipo académico · Ante una emergencia médica en Chile, llama al 131.")
                }
            }
            items(results, key = { it.seq }) { SharedChatBubble(it, attendee = true) }
            if (closed) item(key = "closed") {
                ChatSystemNote("Conversación cerrada")
            }
        }
        if (chat.allowed || chat.pending) SharedChatComposer(chat)
    }

    if (details) {
        IncidentDetails(incident, onDismiss = { details = false }) {
            Text("Prototipo académico. Usa solo casos ficticios. Ante una emergencia médica en Chile, llama al 131.")
            if (state.sos.receipt == incident.id || (state.sos.pending as? SosAttempt.Cancel)?.id == incident.id) {
                SosFeedback(state, actions)
            }
            if (!closed) CancelRequest(state, incident, actions)
        }
    }
}


private fun chatStatus(chat: ChatState?): String? = when (chat?.control) {
    ChatControl.AUTO -> when (chat.aiStatus) {
        "UNAVAILABLE" -> null
        "PENDING" -> "El asistente IA está revisando el relato."
        else -> "Asistente IA · A la espera del personal"
    }
    ChatControl.STAFF -> chat.guard?.let { "Guardia a cargo · ${it.displayName}" } ?: "Personal de seguridad a cargo"
    ChatControl.CLOSED -> null
    null -> "Consultando conversación…"
}
