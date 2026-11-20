package cl.innova.nfc.emitter

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import cl.discoprotect.ui.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
internal fun AttendeeConversation(
    state: AttendeeState, incident: Incident, actions: AttendeeController, modifier: Modifier,
) {
    IncidentChat(
        incident.id, attendee = true, outbox = actions.chatOutbox,
        load = { actions.conversation(incident.id, it) },
        send = { actions.message(incident.id, it) },
        attendeeLayout = { chat ->
            ConversationLayout(state, incident, chat, actions, modifier)
        },
    )
}

@Composable
private fun ConversationLayout(
    state: AttendeeState, incident: Incident, chat: AttendeeChatContent,
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
                    SystemNote(systemMessage)
                    SystemNote("Prototipo académico · Ante una emergencia médica en Chile, llama al 131.")
                }
            }
            items(results, key = { it.seq }) { ChatBubble(it) }
            if (closed) item(key = "closed") {
                SystemNote("Conversación cerrada")
            }
        }
        if (chat.allowed || chat.pending) ChatComposer(chat)
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

@Composable
private fun SystemNote(message: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = Space.xs), contentAlignment = Alignment.Center) {
        Text(message, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ChatBubble(message: ChatMessage) {
    val mine = message.author.type == ChatAuthorType.ATTENDEE
    val colors = MaterialTheme.colorScheme
    val (fill, ink, author) = when (message.author.type) {
        ChatAuthorType.ATTENDEE -> Triple(colors.primaryContainer, colors.onPrimaryContainer, "Tú")
        ChatAuthorType.AI -> Triple(colors.surface, colors.onSurface, "Asistente IA")
        ChatAuthorType.STAFF -> Triple(colors.tertiaryContainer, colors.onTertiaryContainer,
            "Personal de seguridad · ${message.author.displayName}")
    }
    BoxWithConstraints(
        Modifier.fillMaxWidth(),
        contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Surface(
            modifier = Modifier.widthIn(max = maxWidth * 0.88f),
            shape = MaterialTheme.shapes.large, color = fill, contentColor = ink,
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = Space.sm)) {
                Text(author, style = MaterialTheme.typography.labelMedium)
                Text(message.text, style = MaterialTheme.typography.bodyLarge)
                val time = remember(message.createdAt) {
                    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
                        .withZone(ZoneId.systemDefault()).format(Instant.parse(message.createdAt))
                }
                Text(time, style = MaterialTheme.typography.labelMedium, modifier = Modifier.align(Alignment.End))
            }
        }
    }
}

@Composable
private fun ChatComposer(chat: AttendeeChatContent) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        HorizontalDivider()
        if (chat.rejected) Status("El servidor rechazó el mensaje.", Tone.Warning)
        if (chat.pending && !chat.sending) Status("Envío sin confirmar", Tone.Warning)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                chat.draft, { if (!chat.sending && !chat.pending) chat.edit(it) },
                enabled = chat.allowed,
                label = { Text("Mensaje") }, maxLines = 3,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = chat.send, enabled = chat.canSend,
                modifier = Modifier.heightIn(min = 48.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = Space.sm),
            ) {
                Text(when {
                    chat.sending -> "Enviando…"
                    chat.pending -> "Reintentar"
                    else -> "Enviar"
                })
            }
        }
    }
}
