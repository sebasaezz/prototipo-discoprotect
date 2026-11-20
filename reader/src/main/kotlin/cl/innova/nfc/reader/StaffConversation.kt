package cl.innova.nfc.reader

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
internal fun StaffConversation(
    incident: Incident, state: ReaderUiState, actions: ReaderController,
    modifier: Modifier, onDetails: () -> Unit,
) {
    IncidentChat(
        incident.id, attendee = false, outbox = actions.chatOutbox, staffId = state.staffId,
        load = { actions.conversation(incident.id, it) }, send = { actions.message(incident.id, it) },
        conversationLayout = { ChatLayout(incident, it, modifier, onDetails) },
    )
}

@Composable
private fun ChatLayout(incident: Incident, chat: IncidentChatContent, modifier: Modifier, onDetails: () -> Unit) {
    val keyboard = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val list = rememberLazyListState()
    val closed = !incident.status.active || chat.state?.control == ChatControl.CLOSED
    val empty = chat.messages.isEmpty() && !closed
    val parts = chat.messages.size + (if (closed || empty) 1 else 0)
    val followLatest = !list.canScrollForward
    LaunchedEffect(parts, keyboard) {
        if (followLatest && parts > 0) list.scrollToItem(parts - 1)
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        if (!keyboard) {
            Text(incident.requesterName, style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                when {
                    chat.state?.control == ChatControl.STAFF && incident.status == IncidentStatus.OPEN ->
                        Status("Personal a cargo", Tone.Info)
                    closed && incident.status.active -> Status("Atención terminada", Tone.Info)
                    else -> Status(incident.status.label, incident.status.tone)
                }
                TextButton(onClick = onDetails, modifier = Modifier.heightIn(min = 48.dp)) { Text("Detalle operativo") }
            }
            chat.state?.guard?.let { Text("Guardia responsable · ${it.displayName}", style = MaterialTheme.typography.bodyMedium) }
            if (chat.state?.control == ChatControl.AUTO) {
                Text(if (chat.state.aiStatus == "UNAVAILABLE") "IA temporalmente no disponible" else "Atención automática",
                    style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (chat.disconnected) Status("Sin conexión con el chat", Tone.Warning)
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(Space.sm),
            contentPadding = PaddingValues(vertical = Space.sm),
            state = list,
        ) {
            if (empty) item(key = "initial") {
                ChatSystemNote(if (chat.state == null) "Consultando conversación…" else "Todavía no hay mensajes.")
            }
            items(chat.messages, key = { it.seq }) { SharedChatBubble(it, attendee = false) }
            if (closed) item(key = "closed") { ChatSystemNote("Conversación cerrada · Solo lectura") }
        }
        if (chat.allowed && !closed || chat.pending) SharedChatComposer(chat)
        else if (!closed && chat.state != null) {
            ChatSystemNote("Solo el guardia asignado puede responder.")
        }
    }
}
