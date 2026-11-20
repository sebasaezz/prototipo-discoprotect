package cl.discoprotect.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
internal fun ChatSystemNote(message: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = Space.xs), contentAlignment = Alignment.Center) {
        Text(message, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun SharedChatBubble(message: ChatMessage, attendee: Boolean) {
    val mine = if (attendee) message.author.type == ChatAuthorType.ATTENDEE
        else message.author.type == ChatAuthorType.STAFF && message.clientMessageId != null
    val colors = MaterialTheme.colorScheme
    val (fill, ink, author) = when (message.author.type) {
        ChatAuthorType.ATTENDEE -> if (mine) Triple(colors.primaryContainer, colors.onPrimaryContainer, "Tú")
            else Triple(colors.surfaceVariant, colors.onSurfaceVariant, "Asistente · ${message.author.displayName}")
        ChatAuthorType.AI -> Triple(colors.surface, colors.onSurface, "Asistente IA")
        ChatAuthorType.STAFF -> if (mine) Triple(colors.primaryContainer, colors.onPrimaryContainer, "Tú")
            else Triple(colors.tertiaryContainer, colors.onTertiaryContainer,
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
internal fun SharedChatComposer(chat: IncidentChatContent) {
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
