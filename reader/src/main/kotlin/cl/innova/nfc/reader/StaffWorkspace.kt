package cl.innova.nfc.reader

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cl.discoprotect.ui.*

@Composable
internal fun ReaderScreen(state: ReaderUiState, nfc: NfcAvailability, actions: ReaderController, onSettings: () -> Unit) {
    val authorized = !state.local && state.signedIn && state.event != null
    val pendingChat by actions.chatOutbox.pending.collectAsState()
    val editorByIncident = remember(state.staffId, state.event?.id, state.local) { mutableStateMapOf<String, DispatchEditor>() }
    val ticket = remember(state.staffId, state.event?.id, state.local) { StaffTicketState() }
    var page by rememberSaveable { mutableStateOf(StaffPage.Home) }
    var returnPage by rememberSaveable { mutableStateOf(StaffPage.Home) }
    var detailOrigin by rememberSaveable { mutableStateOf(StaffPage.Incidents) }
    var caseId by rememberSaveable(state.staffId, state.event?.id, state.local) { mutableStateOf<String?>(null) }
    val contextLocked = actions.contextLocked || pendingChat.isNotEmpty() || ticket.pending != null ||
        editorByIncident.values.any { it.pending != null }

    fun open(next: StaffPage) {
        if (next == page) return
        if (next == StaffPage.Nfc) actions.continueReading() else actions.leaveReader()
        if (page.main) returnPage = page
        page = next
    }

    fun openCase(incident: Incident, chat: Boolean) {
        if (incident.eventId != state.event?.id) return
        detailOrigin = page
        caseId = incident.id
        open(if (chat && incident.hasConversation) StaffPage.Chat else StaffPage.Detail)
    }

    fun back() {
        open(when (page) {
            StaffPage.Chat -> StaffPage.Detail
            StaffPage.Detail -> detailOrigin
            StaffPage.Home -> StaffPage.Home
            else -> if (page.main) StaffPage.Home else returnPage
        })
    }

    LaunchedEffect(page) {
        if (page == StaffPage.Nfc) actions.continueReading() else actions.leaveReader()
    }
    LaunchedEffect(state.staffId, state.event?.id, state.local, state.task) {
        if (authorized && state.task == null && !state.incidentsVisible) {
            actions.openIncidents()
            if (page == StaffPage.Connection || page == StaffPage.Event) open(StaffPage.Home)
        } else if (!authorized && !state.local && page in operationalPages) {
            open(StaffPage.Connection)
        }
    }
    BackHandler(enabled = page != StaffPage.Home) { back() }
    val incident = state.incidents?.firstOrNull { it.id == caseId && it.eventId == state.event?.id }
        ?: state.activeIncident?.takeIf { it.id == caseId && it.eventId == state.event?.id }

    DiscoprotectTheme {
        StaffLayout(page, caseId, ::open, ::back) {
            if (state.local && page != StaffPage.Nfc) Status("Modo demo local · Datos ficticios", Tone.Warning)
            when (page) {
                StaffPage.Home -> StaffHome(state, ::open) { item, chat ->
                    if (item.eventId == state.event?.id) openCase(item, chat)
                    else if (!contextLocked) actions.selectEvent(item.eventId)
                    else open(StaffPage.Event)
                }
                StaffPage.Incidents -> StaffQueue(state, actions) { openCase(it, false) }
                StaffPage.Tools -> {
                    Text("Herramientas", style = MaterialTheme.typography.headlineMedium)
                    if (authorized || state.local) StaffLink("Leer identificación NFC", prominent = true) { open(StaffPage.Nfc) }
                    if (authorized) {
                        StaffLink("Control de entradas", "Cámara y código manual") { open(StaffPage.Tickets) }
                        StaffLink("Recinto y zonas", "Referencias y puntos operativos") { open(StaffPage.Venue) }
                        StaffLink("Búsqueda autorizada", "Incidentes, zonas y guardias") { open(StaffPage.Search) }
                    } else StaffLink("Configurar acceso") { open(StaffPage.Connection) }
                }
                StaffPage.Profile -> {
                    Text("Perfil", style = MaterialTheme.typography.headlineMedium)
                    Text(if (state.local) "Modo demo local" else state.staffName ?: "Sin sesión de guardia")
                    StaffLink("Conexión y cuenta", staffConnection(state)) { open(StaffPage.Connection) }
                    if (authorized) StaffLink("Evento", state.event?.name) { open(StaffPage.Event) }
                    StaffLink("Diagnósticos NFC", "Adaptador y última lectura") { open(StaffPage.Diagnostics) }
                }
                StaffPage.Nfc -> if (state.readingVisible) {
                    ReadingPage(state, nfc, actions, onSettings)
                } else StaffLink("Configurar acceso") { open(StaffPage.Connection) }
                StaffPage.Tickets -> if (authorized) StaffTicketsPanel(actions, ticket)
                StaffPage.Venue -> if (authorized) StaffVenuePanel(actions)
                StaffPage.Search -> if (authorized) StaffSearchPanel(actions) { id ->
                    val item = state.incidents?.firstOrNull { it.id == id }
                    if (item != null) openCase(item, false)
                    else {
                        caseId = id
                        detailOrigin = StaffPage.Search
                        open(StaffPage.Detail)
                        actions.refreshIncidents()
                    }
                }
                StaffPage.Connection -> if (state.local) {
                    Button(onClick = { actions.configure(); open(StaffPage.Connection) },
                        modifier = Modifier.heightIn(min = 48.dp)) { Text("Volver al modo conectado") }
                } else {
                    ConnectionSetup(state, actions, contextLocked) {
                        actions.useLocal()
                        open(StaffPage.Nfc)
                    }
                }
                StaffPage.Event -> {
                    state.event?.let { Field(it.name, it.venue) }
                    if (contextLocked) Status("Hay una operación pendiente. Confírmala antes de cambiar de evento.", Tone.Warning)
                    EventSelector(state, actions::selectEvent, enabled = !contextLocked)
                }
                StaffPage.Diagnostics -> {
                    Field("Adaptador NFC", when (nfc) {
                        NfcAvailability.Ready -> "Disponible"
                        NfcAvailability.Disabled -> "Desactivado"
                        NfcAvailability.Missing -> "Sin adaptador"
                        NfcAvailability.Error -> "Error al iniciar"
                    })
                    if (nfc == NfcAvailability.Disabled) {
                        OutlinedButton(onClick = onSettings, modifier = Modifier.heightIn(min = 48.dp)) { Text("Abrir ajustes de NFC") }
                    }
                    StaffNfcDiagnostics(state)
                }
                StaffPage.Detail -> if (authorized && incident != null) {
                    val editor = remember(incident.id, editorByIncident) { editorByIncident.getOrPut(incident.id, ::DispatchEditor) }
                    StaffIncidentDetail(state, incident, actions, editor, onChat = { open(StaffPage.Chat) })
                } else Status("La solicitud no está disponible en la cola del evento.", Tone.Info)
                StaffPage.Chat -> if (authorized && incident != null && incident.hasConversation) {
                    StaffConversation(incident, state, actions, Modifier.weight(1f), onDetails = { open(StaffPage.Detail) })
                } else Status("La conversación no está disponible.", Tone.Info)
            }
        }
    }
}

private val operationalPages = setOf(StaffPage.Detail, StaffPage.Chat, StaffPage.Nfc, StaffPage.Tickets,
    StaffPage.Venue, StaffPage.Search, StaffPage.Event)
