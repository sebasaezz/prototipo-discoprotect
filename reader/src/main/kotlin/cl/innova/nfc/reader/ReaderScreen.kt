package cl.innova.nfc.reader

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cl.discoprotect.ui.*
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable
internal fun ReaderScreen(state: ReaderUiState, nfc: NfcAvailability, actions: ReaderController, onSettings: () -> Unit) {
    DiscoprotectTheme {
        key(state.configuring, state.scan is ScanState.Found) {
            NfcPage("Personal") {
                if (state.configuring) {
                    ConnectionSetup(state, actions)
                } else {
                    ReadingPage(state, nfc, actions, onSettings)
                }
            }
        }
    }
}

@Composable
private fun ReadingPage(state: ReaderUiState, nfc: NfcAvailability, actions: ReaderController, onSettings: () -> Unit) {
    val scan = state.scan
    if (state.local) {
        Status("DEMO LOCAL — DATOS NO SINCRONIZADOS", Tone.Warning)
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            if (state.server == ServerState.Connected) Status("Servidor conectado", Tone.Success)
            else Status("Servidor sin conexión", Tone.Warning)
            state.event?.let { Text(it.name, style = MaterialTheme.typography.labelLarge) }
        }
    }

    if (scan is ScanState.Found) {
        Status("Ficha encontrada", Tone.Success)
        EmergencyProfile(scan.profile, scan.source)
    } else {
        NfcMark()
        Heading("Lector NFC", "Identifica a un asistente para consultar su ficha de emergencia.")
        ScanFeedback(scan, nfc, onSettings, state.server == ServerState.Unreachable)
        if (state.local && scan == ScanState.Ready) {
            Text("Los perfiles ficticios de este teléfono pueden diferir de los del servidor.",
                style = MaterialTheme.typography.bodyMedium)
        }
    }

    if (nfc == NfcAvailability.Ready && scan != ScanState.Ready && scan != ScanState.Reading) {
        Button(onClick = actions::newScan, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(if (scan == ScanState.Resolving) "Cancelar consulta" else "Nueva lectura")
        }
    }

    if (scan is ScanState.RemoteError && scan.problem.isConnectivityError()) {
        OutlinedButton(onClick = actions::useLocal, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("Usar modo demo local")
        }
    }

    TextButton(onClick = actions::configure, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(if (state.local) "Volver al modo conectado" else "Configuración y evento")
    }

    DiagnosticsPanel {
        val debug = state.debug
        Text("Etiqueta detectada: ${if (debug.tagDetected) "Sí" else "No"}")
        Text("IsoDep conectado: ${if (debug.isoDepConnected) "Sí" else "No"}")
        val select = when (debug.selectResult) {
            "Success" -> "Correcto"
            "Failure" -> "Falló"
            else -> "Sin enviar"
        }
        Text("SELECT: $select")
        Text("Identificador: ${debug.personId ?: "Sin recibir"}")
        Text("Tiempo de lectura: ${debug.elapsedMs} ms")
        if (scan is ScanState.Found && scan.source == Source.Server) {
            Text("Versión en el servidor: ${scan.profile.version}")
        }
    }
}

@Composable
private fun ScanFeedback(scan: ScanState, nfc: NfcAvailability, onSettings: () -> Unit, reconnecting: Boolean) {
    when (scan) {
        ScanState.Ready -> when (nfc) {
            NfcAvailability.Ready -> {
                if (!reconnecting) Status("Listo para leer", Tone.Success)
                Text(if (reconnecting) "Acerca los teléfonos para reintentar la consulta." else
                    "Junta la parte posterior de ambos teléfonos y mantenlos quietos hasta completar la lectura NFC.")
            }
            NfcAvailability.Missing -> Status("Este dispositivo no tiene adaptador NFC", Tone.Error)
            NfcAvailability.Disabled -> {
                Status("NFC desactivado", Tone.Warning)
                Button(onClick = onSettings, modifier = Modifier.heightIn(min = 48.dp)) { Text("Abrir ajustes de NFC") }
            }
            NfcAvailability.Error -> {
                Status("No se pudo iniciar el lector NFC", Tone.Error)
                Text("Revisa los ajustes y vuelve a abrir AppPersonal.")
            }
        }
        ScanState.Reading -> {
            Status("Leyendo NFC", Tone.Info)
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("Mantén ambos teléfonos juntos y quietos.")
        }
        ScanState.Resolving -> {
            Status("Consultando servidor", Tone.Info)
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("Identificador NFC recibido. Ya puedes separar los teléfonos.")
        }
        ScanState.UnknownLocal -> Status("Identidad sin ficha en la demo local", Tone.Warning)
        is ScanState.NfcError -> {
            Status("Error de lectura NFC", Tone.Error)
            Text(localizedError(scan.message))
        }
        is ScanState.RemoteError -> {
            val title = when (scan.problem) {
                ApiProblem.Forbidden -> "Acceso denegado"
                ApiProblem.NotFound -> "Identidad no encontrada"
                ApiProblem.Network, ApiProblem.Timeout -> "Error de red"
                else -> "Error de consulta"
            }
            Status(title, Tone.Error)
            Text(scan.problem.description())
            if (scan.problem != ApiProblem.InvalidId) Text("El identificador NFC se obtuvo correctamente.")
        }
        is ScanState.Found -> Unit
    }
}

@Composable
private fun EmergencyProfile(profile: HealthProfile, source: Source) {
    val colors = MaterialTheme.colorScheme
    val sourceLabel = if (source == Source.Server) "Fuente: servidor" else "Fuente: demo local"
    val subtitle = profile.age?.let { "$it años · $sourceLabel" } ?: "Edad sin registro · $sourceLabel"
    Heading(profile.name, subtitle)
    Field("Grupo sanguíneo autodeclarado", profile.bloodType ?: "Sin registro")
    Surface(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = if (profile.allergies.isEmpty()) colors.surface else colors.errorContainer,
        contentColor = if (profile.allergies.isEmpty()) colors.onSurface else colors.onErrorContainer,
    ) {
        Column(Modifier.padding(Space.lg), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            Text("Alergias declaradas", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            Text(profile.allergies.recorded(), style = MaterialTheme.typography.bodyLarge)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
        Field("Condiciones relevantes", profile.conditions.recorded())
        Field("Medicamentos", profile.medications.recorded())
        HorizontalDivider()
        Text("Contactos de emergencia", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
        if (profile.contacts.isEmpty()) Text("Sin contactos registrados")
        profile.contacts.forEach { Field(it.name, "${it.relationship}\n${it.phone}") }
        profile.updatedAt?.let {
            val time = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
                .withLocale(Locale.getDefault()).withZone(ZoneId.systemDefault()).format(it)
            Text("Actualizada el $time · Versión ${profile.version}", style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant)
        }
    }
}

private fun List<String>.recorded() = joinToString("\n").ifEmpty { "Sin registro" }

private fun localizedError(message: String) = when (message) {
    "Detected tag does not support ISO-DEP." -> "La tarjeta detectada no es compatible con ISO-DEP."
    "Emitter returned an invalid NFC response." -> "El otro dispositivo devolvió una respuesta NFC inválida."
    "NFC connection lost or timed out. Hold the phones together and try again." -> "Se perdió la conexión NFC. Junta los teléfonos y vuelve a intentar."
    "NFC access is unavailable. Check device NFC settings." -> "Revisa si el dispositivo permite el acceso a NFC."
    "Could not close the NFC connection. Try again." -> "No se pudo cerrar la conexión NFC. Vuelve a intentar."
    else -> "No se recibió un identificador. Separa los teléfonos y prueba otra vez."
}
