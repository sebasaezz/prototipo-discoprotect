package cl.innova.nfc.reader

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cl.discoprotect.ui.*

@Composable
fun ReaderScreen(
    state: ReaderState,
    debug: NfcDebug,
    onReset: () -> Unit,
    onSettings: () -> Unit,
    nfcUsable: Boolean,
) {
    DiscoprotectTheme {
        NfcPage("Personal") {
            if (state is ReaderState.ProfileFound) {
                Status("Ficha encontrada", Tone.Success)
                EmergencyProfile(state.profile)
            } else {
                NfcMark()
                Heading("Lector NFC", "Consulta la ficha de emergencia local de un asistente.")
                when (state) {
                    ReaderState.Ready -> {
                        Status("Listo para leer", Tone.Success)
                        ReaderInstructions("Acerca un teléfono", "Junta la parte posterior de ambos teléfonos. Mantén abierta esta pantalla hasta terminar la lectura.")
                    }
                    ReaderState.Reading -> {
                        Status("Leyendo NFC", Tone.Info)
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("Mantén ambos teléfonos juntos y quietos.")
                    }
                    is ReaderState.UnknownPerson -> {
                        Status("Identidad desconocida", Tone.Warning)
                        ReaderInstructions("Ficha no disponible", "La credencial recibida no está asociada a los perfiles de demostración de este teléfono.")
                    }
                    is ReaderState.Error -> {
                        Status("Lectura no disponible", Tone.Error)
                        ReaderInstructions("Revisa la conexión NFC", localizedError(state.message))
                        if (state.message == "Enable NFC in device settings, then return here.") {
                            Button(onClick = onSettings, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text("Abrir ajustes de NFC")
                            }
                        }
                    }
                    is ReaderState.ProfileFound -> Unit
                }
            }
            if (nfcUsable && state != ReaderState.Ready && state != ReaderState.Reading) {
                Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                    Button(onClick = onReset, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Nueva lectura")
                    }
                    Text("Separa los teléfonos antes de volver a acercarlos.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            DiagnosticsPanel {
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
            }
        }
    }
}

@Composable
private fun ReaderInstructions(title: String, description: String) {
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(Space.lg), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description)
        }
    }
}

@Composable
private fun EmergencyProfile(profile: HealthProfile) {
    val colors = MaterialTheme.colorScheme
    Heading(profile.name, "${profile.age} años · Ficha local de demostración")
    Field("Grupo sanguíneo autodeclarado", profile.bloodType)
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
        Field("Contacto de emergencia", "${profile.emergencyContact.name} · ${profile.emergencyContact.relationship}\n${profile.emergencyContact.phone}")
    }
}

private fun List<String>.recorded() = joinToString("\n").ifEmpty { "Sin registro" }

private fun localizedError(message: String) = when (message) {
    "This device has no NFC adapter." -> "Este dispositivo no tiene adaptador NFC."
    "Enable NFC in device settings, then return here." -> "Activa NFC en los ajustes del dispositivo y vuelve a esta pantalla."
    "NFC access is unavailable. Check device settings." -> "El acceso a NFC no está disponible. Revisa los ajustes."
    "Could not enable NFC reader mode. Return to this screen to retry." -> "No se pudo activar el lector. Sal de esta pantalla y vuelve a abrirla."
    "Detected tag does not support ISO-DEP." -> "La tarjeta detectada no es compatible con ISO-DEP."
    "Emitter returned an invalid NFC response." -> "El otro dispositivo devolvió una respuesta NFC inválida."
    "NFC connection lost or timed out. Hold the phones together and try again." -> "Se perdió la conexión NFC. Junta los teléfonos y vuelve a intentar."
    "NFC access is unavailable. Check device NFC settings." -> "Revisa si el dispositivo permite el acceso a NFC."
    "Could not close the NFC connection. Try again." -> "No se pudo cerrar la conexión NFC. Vuelve a intentar."
    "No person ID received." -> "No se recibió un identificador."
    else -> message
}
