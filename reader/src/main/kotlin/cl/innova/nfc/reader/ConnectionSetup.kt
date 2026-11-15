package cl.innova.nfc.reader

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import cl.discoprotect.ui.Field
import cl.discoprotect.ui.Heading
import cl.discoprotect.ui.Space
import cl.discoprotect.ui.Status
import cl.discoprotect.ui.Tone

@Composable
internal fun ConnectionSetup(state: ReaderUiState, actions: ReaderController) {
    var address by remember(state.address) { mutableStateOf(state.address) }
    val validated = serverAddress(address, BuildConfig.DEBUG)?.toString()
    val sameAddress = validated == state.address
    val idle = state.task == null
    val focus = LocalFocusManager.current

    Heading("Conexión del lector", "Conecta AppPersonal al servidor del evento para consultar fichas por NFC.")
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(Space.md), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            Text("Servidor", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = address,
                onValueChange = { address = it.filterNot { char -> char == '\n' || char == '\r' } },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Dirección del servidor") },
                placeholder = { Text(if (BuildConfig.DEBUG) "http://IP-del-PC:3000" else "https://servidor") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                maxLines = 3,
                enabled = idle,
            )
            if (!sameAddress || state.server == ServerState.Unconfigured) {
                Text("Usa la dirección del computador en la red del evento.", style = MaterialTheme.typography.bodyMedium)
            } else if (state.server == ServerState.Connected) {
                Status("Servidor conectado", Tone.Success)
            } else if (state.server == ServerState.Unreachable) {
                Status("Servidor sin conexión", Tone.Warning)
            }
            OutlinedButton(onClick = { focus.clearFocus(); actions.check(address) },
                enabled = idle && address.isNotBlank(), modifier = Modifier.heightIn(min = 48.dp)) {
                Text(if (state.server == ServerState.Unreachable && sameAddress) "Reintentar conexión" else "Comprobar conexión")
            }
        }
    }

    if (state.task != null) {
        Status(if (state.task == SetupTask.Checking) "Comprobando conexión" else "Validando sesión",
            Tone.Info)
        LinearProgressIndicator(Modifier.fillMaxWidth())
    }
    state.notice?.let { Status(it, Tone.Warning) }

    if (sameAddress && state.server == ServerState.Connected && !state.signedIn) {
        GuardLogin(idle, actions::login)
    }
    if (sameAddress && state.signedIn) {
        Status("Guardia autenticado", Tone.Success)
        EventSelector(state, actions::selectEvent)
        if (state.event != null) {
            Button(onClick = actions::openIncidents, enabled = idle, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Ver incidentes")
            }
        }
        if (state.canContinue) {
            Button(onClick = actions::continueReading, enabled = idle, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Ir al lector NFC")
            }
        }
        TextButton(onClick = actions::logout, enabled = idle, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("Cerrar sesión")
        }
    }

    HorizontalDivider()
    TextButton(onClick = actions::useLocal, enabled = idle, modifier = Modifier.heightIn(min = 48.dp)) {
        Text("Usar modo demo local")
    }
}

@Composable
private fun GuardLogin(idle: Boolean, signIn: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    val focus = LocalFocusManager.current
    Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        Text("Sesión de guardia", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = pin,
            onValueChange = { if (it.length <= 128) pin = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("PIN de guardia") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true,
            enabled = idle,
        )
        Button(onClick = {
            val value = pin
            pin = ""
            focus.clearFocus()
            signIn(value)
        }, enabled = idle && pin.length >= 4, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("Iniciar sesión")
        }
    }
}

@Composable
internal fun EventSelector(state: ReaderUiState, select: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        if (state.events.size == 1) {
            state.event?.let { Field("Evento autorizado", "${it.name} · ${it.venue}") }
        } else if (state.events.isNotEmpty()) {
            Text("Selecciona un evento", style = MaterialTheme.typography.titleMedium)
            Column(Modifier.selectableGroup()) {
                state.events.forEach { event ->
                    val selected = event.id == state.event?.id
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .selectable(selected, enabled = state.task == null, role = Role.RadioButton) { select(event.id) }
                        .padding(vertical = Space.sm),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = selected, onClick = null, enabled = state.task == null)
                        Column(Modifier.padding(start = Space.sm)) {
                            Text(event.name, style = MaterialTheme.typography.labelLarge)
                            Text(event.venue, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}
