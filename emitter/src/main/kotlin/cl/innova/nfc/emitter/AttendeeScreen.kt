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

@OptIn(ExperimentalLayoutApi::class)
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

    BackHandler(enabled = draft != null) { leaveEditor() }
    BackHandler(enabled = draft == null && state.section != Section.Identification) {
        actions.navigate(Section.Identification)
    }

    DiscoprotectTheme {
        key(state.section, draft?.javaClass) {
            NfcPage("Asistente") {
                if (draft == null) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                        Section.entries.forEach { section ->
                            FilterChip(
                                selected = section == state.section,
                                onClick = { actions.navigate(section) },
                                label = { Text(section.label) },
                                modifier = Modifier.heightIn(min = 48.dp),
                            )
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                    state.task?.let {
                        Status(it.label, Tone.Info)
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    state.notice?.let { Status(it, Tone.Warning) }
                    if (state.section in setOf(Section.Card, Section.Contacts)) {
                        state.confirmation?.let { Status(it, Tone.Success) }
                    }
                }

                if (draft != null) {
                    EditScreen(state, draft, actions, ::leaveEditor, chooseAccount)
                } else {
                    when (state.section) {
                        Section.Identification -> EmitterScreen(
                            diagnostics, identity, state.reachability, onRefreshNfc, onNfcSettings,
                            onChoose = { actions.navigate(Section.Connection) },
                        )
                        Section.Card -> CardScreen(state, actions)
                        Section.Contacts -> ContactsScreen(state, actions)
                        Section.Connection -> ConnectionScreen(state, identity, actions, chooseAccount)
                    }
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
private fun ConnectionScreen(
    state: AttendeeState, identity: ShareableIdentity?, actions: AttendeeController, onChoose: () -> Unit,
) {
    var input by rememberSaveable(state.address) { mutableStateOf(state.address) }
    val sameAddress = attendeeServerAddress(input, BuildConfig.DEBUG)?.toString() == state.address
    val idle = state.task == null
    val focus = LocalFocusManager.current
    Heading("Conexión", "Usa la dirección del computador en la red del evento.")
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
    if (identity != null) {
        Field("Cuenta activa", identity.person.name)
        Status("Credencial NFC verificada", Tone.Success)
        Button(onClick = { actions.navigate(Section.Card) }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("Ir a Mi ficha")
        }
    } else {
        val selected = state.selectedAccount
        if (selected == null) Status("Sin cuenta seleccionada", Tone.Info)
        else Field("Cuenta elegida · Sin verificar", selected.label)
    }
    if (sameAddress && state.address.isNotBlank()) {
        Button(onClick = onChoose, enabled = state.canChangeAccount,
            modifier = Modifier.heightIn(min = 48.dp)) {
            Text(if (state.selectedAccount == null) "Elegir cuenta" else "Cambiar cuenta")
        }
        if (state.selectedAccount != null && identity == null && idle) {
            OutlinedButton(onClick = actions::retryLogin, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Reintentar verificación")
            }
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
    CardStatus(state, snapshot, actions)
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
    Button(onClick = actions::editCard, enabled = state.editable, modifier = Modifier.heightIn(min = 48.dp)) {
        Text("Editar ficha")
    }
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
    CardStatus(state, snapshot, actions)
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
        Button(onClick = { actions.navigate(Section.Connection) }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("Ir a Conexión")
        }
    } else {
        OutlinedButton(onClick = actions::refresh, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("Volver a cargar desde el servidor")
        }
    }
}

@Composable
private fun CardStatus(state: AttendeeState, snapshot: EmergencySnapshot, actions: AttendeeController) {
    val updated = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
        .withLocale(Locale.getDefault()).withZone(ZoneId.systemDefault()).format(Instant.parse(snapshot.card.updatedAt))
    Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        Status(if (state.fresh) "Fuente: servidor" else "Última lectura del servidor · Vigencia sin comprobar",
            if (state.fresh) Tone.Success else Tone.Warning)
        Text("Actualizada el $updated · Versión ${snapshot.card.version}", style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = actions::refresh, enabled = state.task == null, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("Actualizar desde el servidor")
        }
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
