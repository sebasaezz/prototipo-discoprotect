package cl.discoprotect.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable internal enum class ChatControl { AUTO, STAFF, CLOSED }
@Serializable internal enum class ChatAuthorType { ATTENDEE, AI, STAFF }
@Serializable internal data class ChatAuthor(val type: ChatAuthorType, val displayName: String)
@Serializable internal data class ChatGuard(val id: String, val displayName: String)
@Serializable internal data class ChatState(
    val incidentId: String, val control: ChatControl, val controlVersion: Long,
    val guard: ChatGuard?, val aiStatus: String, val lastSeq: Long,
)
@Serializable internal data class ChatMessage(
    val id: String, val seq: Long, val author: ChatAuthor, val text: String,
    val createdAt: String, val clientMessageId: String? = null,
)
@Serializable internal data class ChatDraft(val clientMessageId: String, val text: String) {
    companion object {
        fun create(text: String) = ChatDraft(UUID.randomUUID().toString(), text.trim())
    }
}
@Serializable internal data class ChatPage(
    val conversation: ChatState, val messages: List<ChatMessage>, val nextAfterSeq: Long, val hasMore: Boolean,
)
@Serializable internal data class ChatWrite(val message: ChatMessage, val conversation: ChatState)

internal fun ChatPage.valid(id: String, after: Long): Boolean =
    conversation.incidentId == id && conversation.controlVersion > 0 &&
        messages.map { it.seq } == (after + 1..after + messages.size).toList() &&
        nextAfterSeq == after + messages.size && (!hasMore || messages.isNotEmpty()) &&
        conversation.lastSeq >= nextAfterSeq && messages.all { it.valid() }

internal fun ChatMessage.valid(): Boolean =
    isIncidentUuid(id) && seq > 0 && text.isNotBlank() && text.length <= 500 &&
        author.displayName.isNotBlank() && runCatching { java.time.Instant.parse(createdAt) }.isSuccess

internal fun ChatWrite.valid(id: String, draft: ChatDraft, author: ChatAuthorType) =
    conversation.incidentId == id && message.valid() && message.clientMessageId == draft.clientMessageId &&
        message.text == draft.text && message.author.type == author && conversation.lastSeq >= message.seq

internal class ChatRejected : Exception()

internal class ChatOutbox {
    private val drafts = MutableStateFlow<Map<String, ChatDraft>>(emptyMap())
    val pending = drafts.asStateFlow()

    operator fun get(incidentId: String) = drafts.value[incidentId]

    fun reserve(incidentId: String, text: String): ChatDraft {
        return this[incidentId] ?: ChatDraft.create(text).also { drafts.value = drafts.value + (incidentId to it) }
    }

    fun clear(incidentId: String, attempt: ChatDraft) {
        if (this[incidentId] == attempt) drafts.value = drafts.value - incidentId
    }

    fun clearSession() { drafts.value = emptyMap() }
}

@Composable
internal fun IncidentChat(
    incidentId: String,
    attendee: Boolean,
    outbox: ChatOutbox,
    staffId: String? = null,
    load: suspend (Long) -> ChatPage,
    send: suspend (ChatDraft) -> ChatWrite,
    sendLabel: String = "Enviar mensaje",
    emptyConversationMessage: String? = null,
) {
    val owner = LocalLifecycleOwner.current
    val loader by rememberUpdatedState(load)
    val sender by rememberUpdatedState(send)
    val scope = rememberCoroutineScope()
    var cursor by remember(incidentId) { mutableLongStateOf(0L) }
    var chat by remember(incidentId) { mutableStateOf<ChatState?>(null) }
    var messages by remember(incidentId) { mutableStateOf(emptyList<ChatMessage>()) }
    var draft by remember(incidentId) { mutableStateOf(outbox[incidentId]?.text.orEmpty()) }
    val outbound by outbox.pending.collectAsState()
    val pending = outbound[incidentId]
    var sending by remember(incidentId) { mutableStateOf(false) }
    var disconnected by remember(incidentId) { mutableStateOf(false) }
    var rejected by remember(incidentId) { mutableStateOf(false) }

    LaunchedEffect(owner, incidentId) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var pause = 2_000L
            while (true) {
                try {
                    var more: Boolean
                    do {
                        val page = loader(cursor)
                        if ((chat?.controlVersion ?: 0) <= page.conversation.controlVersion &&
                            (chat?.lastSeq ?: 0) <= page.conversation.lastSeq) chat = page.conversation
                        messages = (messages + page.messages).distinctBy { it.seq }.sortedBy { it.seq }
                        val attempted = outbox[incidentId]
                        if (attempted != null && page.messages.any { it.clientMessageId == attempted.clientMessageId }) {
                            outbox.clear(incidentId, attempted)
                            draft = ""
                        }
                        cursor = page.nextAfterSeq
                        more = page.hasMore
                    } while (more)
                    disconnected = false
                    pause = 2_000L
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    disconnected = true
                    pause = (pause * 2).coerceAtMost(30_000L)
                }
                delay(pause)
            }
        }
    }

    Text("Conversación", style = MaterialTheme.typography.titleMedium)
    if (attendee) Text("Prototipo académico. Ante una emergencia médica en Chile, llama al 131.")
    val state = chat
    if (disconnected) Status("Sin conexión con el chat · se muestra la última consulta.", Tone.Warning)
    if (state == null && !disconnected) Text("Consultando conversación…")
    val emptyMessage = emptyConversationMessage?.takeIf {
        state != null && state.lastSeq == 0L && state.control != ChatControl.CLOSED
    }
    emptyMessage?.let { Text(it) }
    when (state?.control) {
        ChatControl.AUTO -> if (emptyMessage == null) {
            if (attendee && state.aiStatus == "UNAVAILABLE") {
                Status("IA temporalmente no disponible. Tu solicitud sigue visible para el personal", Tone.Warning)
            } else {
                Text(if (state.aiStatus == "PENDING") "La IA está revisando el relato. A la espera del personal."
                    else "A la espera del personal.")
            }
        }
        ChatControl.STAFF -> Text("Guardia a cargo · ${state.guard?.displayName.orEmpty()}")
        ChatControl.CLOSED -> Text("Conversación cerrada")
        null -> Unit
    }
    messages.forEach { message ->
        val author = when (message.author.type) {
            ChatAuthorType.AI -> "Asistente IA"
            ChatAuthorType.ATTENDEE -> if (attendee) "Tú" else message.author.displayName
            ChatAuthorType.STAFF -> "Guardia · ${message.author.displayName}"
        }
        Text("$author · ${incidentTime(message.createdAt)}", style = MaterialTheme.typography.labelLarge)
        Text(message.text)
    }
    val allowed = state != null && state.control != ChatControl.CLOSED &&
        (attendee || state.control == ChatControl.STAFF && state.guard?.id == staffId)
    if (allowed || pending != null) {
        OutlinedTextField(draft, { if (it.length <= 500) draft = it },
            enabled = !sending && pending == null, label = { Text("Mensaje") }, maxLines = 4,
            modifier = Modifier.fillMaxWidth())
        if (rejected) Status("El servidor rechazó el mensaje.", Tone.Warning)
        if (pending != null && !sending) Status("Envío sin confirmar", Tone.Warning)
        Button(enabled = !sending && (pending != null || allowed && !disconnected && draft.isNotBlank()),
            modifier = Modifier.heightIn(min = 48.dp), onClick = {
                val attempt = outbox.reserve(incidentId, draft)
                sending = true
                rejected = false
                scope.launch {
                    try {
                        val reply = sender(attempt)
                        messages = (messages + reply.message).distinctBy { it.seq }.sortedBy { it.seq }
                        if ((chat?.controlVersion ?: 0) <= reply.conversation.controlVersion &&
                            (chat?.lastSeq ?: 0) <= reply.conversation.lastSeq) chat = reply.conversation
                        outbox.clear(incidentId, attempt)
                        draft = ""
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: ChatRejected) {
                        outbox.clear(incidentId, attempt)
                        rejected = true
                    } catch (_: Exception) {
                        disconnected = true
                    } finally {
                        sending = false
                    }
                }
            }) { Text(if (sending) "Enviando…" else if (pending != null) "Reintentar envío" else sendLabel) }
    }
}
