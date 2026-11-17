package cl.innova.nfc.emitter

import cl.discoprotect.ui.*
import org.junit.Assert.*
import org.junit.Test

class IncidentChatTest {
    private val id = "9cafee54-17b1-41b9-9f95-01988ce10452"
    private val state = ChatState(id, ChatControl.AUTO, 1, null, "PENDING", 2)

    @Test
    fun pageDoesNotAdvanceAcrossAMissingMessage() {
        val second = ChatMessage(id, 2, ChatAuthor(ChatAuthorType.AI, "Asistente IA"),
            "¿Dónde estás?", "2026-11-16T18:00:00.000Z")
        val page = ChatPage(state, listOf(second), 2, false)
        assertTrue(page.valid(id, 1))
        assertFalse(page.valid(id, 0))
        assertFalse(page.valid("other", 1))
    }

    @Test
    fun receiptMustMatchTheExactDraftAndAuthor() {
        val draft = ChatDraft.create("   Estoy en la salida   ")
        val message = ChatMessage(id, 1, ChatAuthor(ChatAuthorType.ATTENDEE, "Cuenta demo"),
            draft.text, "2026-11-16T18:00:00.000Z", draft.clientMessageId)
        assertTrue(ChatWrite(message, state).valid(id, draft, ChatAuthorType.ATTENDEE))
        assertFalse(ChatWrite(message, state).valid(id, draft, ChatAuthorType.STAFF))
        assertFalse(ChatWrite(message, state).valid(id, ChatDraft.create(draft.text), ChatAuthorType.ATTENDEE))
    }
}
