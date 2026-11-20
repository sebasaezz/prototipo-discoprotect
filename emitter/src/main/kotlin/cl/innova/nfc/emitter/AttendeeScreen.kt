package cl.innova.nfc.emitter

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cl.discoprotect.ui.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable
internal fun AttendeeScreen(
    state: AttendeeState, identity: ShareableIdentity?, actions: AttendeeController, diagnostics: Diagnostics,
    onRefreshNfc: () -> Unit, onNfcSettings: () -> Unit,
) {
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    val draft = state.draft
    var showAccounts by remember { mutableStateOf(false) }
    var pendingAccount by remember { mutableStateOf<DemoAccount?>(null) }
    val chooseAccount = { if (state.canChangeAccount) showAccounts = true }
    fun leaveEditor() {
        if (state.task != null) return
        if (draft?.dirty == true || state.review != null) confirmDiscard = true else actions.discard()
    }

    val chat = state.sos.incidents?.firstOrNull { it.id == state.conversationId && it.hasConversation }
    val serviceState = remember(state.sharingGeneration, state.person?.id, state.address) { ServiceUiState() }
    fun back() {
        when {
            draft != null -> leaveEditor()
            chat != null || state.section == Section.Map -> actions.navigate(Section.Sos)
            state.section != state.section.main -> actions.navigate(Section.Profile)
            else -> actions.navigate(Section.Home)
        }
    }

    BackHandler(enabled = draft != null || state.section != Section.Home || state.sosWriting) { back() }

    DiscoprotectTheme {
        AttendeeLayout(state, actions, chat != null, ::back) {
            val task = state.task
            if (task != null && (chat == null || state.sosWriting)) {
                Status(task.label, Tone.Info)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            state.notice?.let { Status(it, Tone.Warning) }
            if (state.section == Section.Card || state.section == Section.Contacts) {
                state.confirmation?.let { Status(it, Tone.Success) }
            }

            if (draft != null) {
                EditScreen(state, draft, actions, ::leaveEditor, chooseAccount)
            } else if (chat != null) {
                AttendeeConversation(state, chat, actions, Modifier.weight(1f))
            } else {
                when (state.section) {
                    Section.Home -> AttendeeHome(state, actions) {
                        serviceState.tab = ServiceTab.Ticket
                        actions.navigate(Section.Services)
                    }
                    Section.Sos -> AttendeeHelp(state, actions)
                    Section.Map -> state.sos.event?.let { EventMapScreen(it, state.sos, actions) }
                    Section.Services -> AttendeeServicesScreen(state, serviceState, actions)
                    Section.Profile -> AttendeeProfile(state, actions)
                    Section.Identification -> EmitterScreen(
                        diagnostics, identity, state.reachability, onNfcSettings,
                        onChoose = { actions.navigate(Section.Account) },
                    )
                    Section.Diagnostics -> NfcDiagnostics(diagnostics, identity, onRefreshNfc)
                    Section.Card -> CardScreen(state, actions)
                    Section.Contacts -> ContactsScreen(state, actions)
                    Section.Connection -> ConnectionScreen(state, actions)
                    Section.Account -> AccountScreen(state, identity, actions, chooseAccount)
                }
            }
        }
        if (showAccounts) {
            AlertDialog(
                onDismissRequest = { showAccounts = false },
                title = { Text("Elegir cuenta de demostración") },
                text = {
                    Column {
                        DemoAccount.entries.forEach { account ->
                            TextButton(
                                onClick = {
                                    showAccounts = false
                                    if (draft != null || state.selectedAccount != null && state.selectedAccount != account) {
                                        pendingAccount = account
                                    } else actions.selectAccount(account)
                                },
                                enabled = state.canChangeAccount && identity?.person?.account != account.key,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            ) { Text(account.label) }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showAccounts = false },
                    modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancelar") } },
            )
        }
        pendingAccount?.let { account ->
            AlertDialog(
                onDismissRequest = { pendingAccount = null },
                title = { Text("¿Cambiar de cuenta?") },
                text = { Text(if (draft != null)
                    "Se descartará el borrador actual y se deshabilitará la emisión hasta verificar la cuenta elegida."
                    else "La emisión se deshabilitará hasta verificar la cuenta elegida.") },
                confirmButton = {
                    Button(onClick = { pendingAccount = null; actions.selectAccount(account) },
                        enabled = state.canChangeAccount,
                        modifier = Modifier.heightIn(min = 48.dp)) { Text("Confirmar cambio") }
                },
                dismissButton = { TextButton(onClick = { pendingAccount = null },
                    modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancelar") } },
            )
        }
        if (confirmDiscard && draft != null) {
            AlertDialog(onDismissRequest = { confirmDiscard = false },
                title = { Text("¿Descartar el borrador?") },
                text = { Text("Se cerrará la edición. Los cambios confirmados en el servidor seguirán en la ficha.") },
                confirmButton = {
                    TextButton(onClick = { confirmDiscard = false; actions.discard() },
                        modifier = Modifier.heightIn(min = 48.dp)) { Text("Descartar borrador") }
                },
                dismissButton = {
                    TextButton(onClick = { confirmDiscard = false }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Seguir editando")
                    }
                })
        }
    }
}

@Composable
private fun ConnectionScreen(state: AttendeeState, actions: AttendeeController) {
    var input by rememberSaveable(state.address) { mutableStateOf(state.address) }
    val sameAddress = attendeeServerAddress(input, BuildConfig.DEBUG)?.toString() == state.address
    val idle = state.task == null
    val focus = LocalFocusManager.current
    Text("Usa la dirección del servidor en la red del evento.", style = MaterialTheme.typography.bodyLarge)
    OutlinedTextField(
        input, { input = it.filterNot { char -> char == '\r' || char == '\n' } },
        label = { Text("Dirección del servidor") },
        placeholder = { Text(if (BuildConfig.DEBUG) "http://IP-del-PC:3000" else "https://servidor") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
        enabled = idle, maxLines = 3, modifier = Modifier.fillMaxWidth(),
    )
    if (sameAddress) {
        when (state.reachability) {
            Reachability.Connected -> Status("Servidor conectado", Tone.Success)
            Reachability.Offline -> Status("Sin conexión", Tone.Warning)
            Reachability.Unchecked -> Status("Conexión por comprobar", Tone.Info)
        }
    }
    OutlinedButton(onClick = { focus.clearFocus(); actions.connect(input) },
        enabled = idle && input.isNotBlank(), modifier = Modifier.heightIn(min = 48.dp)) {
        Text("Comprobar conexión")
    }
    DestinationLink("Cuenta", if (state.person == null) "Verificar cuenta de demostración" else "Administrar sesión") {
        actions.navigate(Section.Account)
    }
}

@Composable
private fun AccountScreen(
    state: AttendeeState, identity: ShareableIdentity?, actions: AttendeeController, onChoose: () -> Unit,
) {
    if (identity != null) {
        Text(identity.person.name, style = MaterialTheme.typography.headlineSmall)
        Status("Credencial NFC verificada", Tone.Success)
    } else {
        val selected = state.selectedAccount
        if (selected == null) Text("Elige una cuenta de demostración para activar tu credencial.")
        else Field("Cuenta elegida · Sin verificar", selected.label)
    }
    if (state.address.isNotBlank()) {
        Button(onClick = onChoose, enabled = state.canChangeAccount,
            modifier = Modifier.heightIn(min = 48.dp)) {
            Text(if (state.selectedAccount == null) "Elegir cuenta" else "Cambiar cuenta")
        }
        if (state.selectedAccount != null && identity == null && state.task == null) {
            OutlinedButton(onClick = actions::retryLogin, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Reintentar verificación")
            }
        }
    }
    if (state.address.isBlank()) {
        Button(onClick = { actions.navigate(Section.Connection) }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("Configurar servidor")
        }
    }
    if (state.selectedAccount != null) {
        TextButton(onClick = actions::logout, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(if (identity != null) "Cerrar sesión" else "Cancelar selección")
        }
    }
}

@Composable
private fun CardScreen(state: AttendeeState, actions: AttendeeController) {
    val person = state.person
    val snapshot = state.snapshot
    if (person == null || snapshot == null) {
        Heading("Mi ficha", "Consulta tus datos de emergencia y el estado de la ficha.")
        DataAvailability(state, actions)
        return
    }
    Heading(person.name, person.age?.let { "$it años · Mi ficha de emergencia" } ?: "Edad sin registrar · Mi ficha de emergencia")
    CardStatus(state, snapshot)
    Button(onClick = actions::editCard, enabled = state.editable, modifier = Modifier.heightIn(min = 48.dp)) {
        Text("Editar ficha")
    }
    Field("Grupo sanguíneo declarado", snapshot.card.bloodType ?: "Sin registrar")
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
        color = if (snapshot.card.allergies.isEmpty()) MaterialTheme.colorScheme.surface
            else MaterialTheme.colorScheme.errorContainer,
        contentColor = if (snapshot.card.allergies.isEmpty()) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onErrorContainer) {
        Column(Modifier.padding(Space.md)) { Values("Alergias", snapshot.card.allergies) }
    }
    Values("Condiciones relevantes", snapshot.card.conditions)
    Values("Medicamentos", snapshot.card.medications)
    RefreshCard(state, actions)
    TextButton(onClick = { actions.navigate(Section.Contacts) }, modifier = Modifier.heightIn(min = 48.dp)) {
        Text("Ver contactos de emergencia")
    }
}

@Composable
private fun ContactsScreen(state: AttendeeState, actions: AttendeeController) {
    Heading("Contactos", "Personas a quienes el personal de seguridad puede contactar en una emergencia.")
    val snapshot = state.snapshot
    if (snapshot == null) {
        DataAvailability(state, actions)
        return
    }
    CardStatus(state, snapshot)
    RefreshCard(state, actions)
    Button(onClick = { actions.editContact(null) }, enabled = state.editable, modifier = Modifier.heightIn(min = 48.dp)) {
        Text("Agregar contacto")
    }
    if (snapshot.contacts.isEmpty()) Text("Sin contactos registrados.")
    snapshot.contacts.forEach { entry ->
        Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(Space.md), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                Field(entry.name, "${entry.relationship}\n${entry.phone}")
                Row {
                    TextButton(onClick = { actions.editContact(entry.id) }, enabled = state.editable,
                        modifier = Modifier.heightIn(min = 48.dp)) { Text("Editar") }
                    TextButton(onClick = { actions.requestRemoval(entry.id) }, enabled = state.editable,
                        modifier = Modifier.heightIn(min = 48.dp)) { Text("Eliminar") }
                }
            }
        }
    }
}

@Composable
private fun DataAvailability(state: AttendeeState, actions: AttendeeController) {
    if (state.task != null) return
    if (state.person == null) {
        Status("Elige una cuenta y verifícala para consultar la ficha", Tone.Info)
        Button(onClick = { actions.navigate(Section.Account) }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("Verificar cuenta")
        }
    } else {
        OutlinedButton(onClick = actions::refresh, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("Volver a cargar desde el servidor")
        }
    }
}

@Composable
private fun CardStatus(state: AttendeeState, snapshot: EmergencySnapshot) {
    val updated = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
        .withLocale(Locale.getDefault()).withZone(ZoneId.systemDefault()).format(Instant.parse(snapshot.card.updatedAt))
    Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        Status(if (state.fresh) "Fuente: servidor" else "Última lectura del servidor · Vigencia sin comprobar",
            if (state.fresh) Tone.Success else Tone.Warning)
        Text("Actualizada el $updated · Versión ${snapshot.card.version}", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun RefreshCard(state: AttendeeState, actions: AttendeeController) {
    TextButton(onClick = actions::refresh, enabled = state.task == null, modifier = Modifier.heightIn(min = 48.dp)) {
        Text("Actualizar desde el servidor")
    }
}

@Composable
internal fun Values(label: String, items: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (items.isEmpty()) Text("Sin registro", style = MaterialTheme.typography.bodyLarge)
        else items.forEach { Text(it, style = MaterialTheme.typography.bodyLarge) }
    }
}
