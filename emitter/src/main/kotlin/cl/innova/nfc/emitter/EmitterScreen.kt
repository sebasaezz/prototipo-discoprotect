package cl.innova.nfc.emitter

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import cl.discoprotect.ui.*
import cl.innova.nfc.protocol.NfcProtocol

@Composable
internal fun EmitterScreen(diagnostics: Diagnostics, onRefresh: () -> Unit, onSettings: () -> Unit) {
    NfcMark()
    Heading("Identificación NFC", "Permite que el personal de seguridad identifique tu ficha de demostración.")
    val status = when {
        !diagnostics.available -> "NFC no disponible" to Tone.Error
        !diagnostics.hce -> "Emulación de tarjeta no compatible" to Tone.Error
        !diagnostics.enabled -> "NFC desactivado" to Tone.Warning
        else -> "Listo para compartir" to Tone.Success
    }
    Status(status.first, status.second)

    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(Space.lg), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            Text("Acerca los teléfonos", style = MaterialTheme.typography.titleMedium)
            Text("Mantén el teléfono despierto y desbloqueado. Junta la parte posterior con la del teléfono del personal hasta que confirme la lectura.")
            if (diagnostics.available && diagnostics.hce && !diagnostics.enabled) {
                Button(onClick = onSettings, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Abrir ajustes de NFC")
                }
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        Text("Identificador de demostración", style = MaterialTheme.typography.titleMedium)
        SelectionContainer {
            Text(NfcProtocol.DEMO_PERSON_ID, fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        Text("El teléfono transmite solo este identificador. La ficha se consulta en el lector.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    DiagnosticsPanel {
        Text("Adaptador NFC: ${diagnostics.available.label()}")
        Text("NFC activado: ${diagnostics.enabled.label()}")
        Text("Emulación de tarjeta (HCE): ${diagnostics.hce.label()}")
        Text("Secure NFC compatible: ${diagnostics.secureSupported.label()}")
        Text("Secure NFC activado: ${diagnostics.secureEnabled.label()}")
        Text("La emulación funciona con la aplicación en segundo plano. El bloqueo y la pantalla apagada dependen del equipo y de Secure NFC.")
        OutlinedButton(onClick = onRefresh, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("Actualizar diagnóstico")
        }
    }
}

private fun Boolean?.label(): String = when (this) {
    true -> "Sí"
    false -> "No"
    null -> "No disponible"
}
