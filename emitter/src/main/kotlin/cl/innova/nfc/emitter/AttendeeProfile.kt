package cl.innova.nfc.emitter

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import cl.discoprotect.ui.Space

@Composable
internal fun AttendeeProfile(state: AttendeeState, actions: AttendeeController) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Text("Perfil", style = MaterialTheme.typography.headlineMedium)
        Text(state.person?.name ?: "Sin cuenta verificada", style = MaterialTheme.typography.bodyLarge)
    }
    Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        Text("Mis datos", style = MaterialTheme.typography.labelLarge)
        DestinationLink("Mi ficha", "Información para emergencias") { actions.navigate(Section.Card) }
        DestinationLink("Contactos de emergencia") { actions.navigate(Section.Contacts) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        Text("Identificación y ajustes", style = MaterialTheme.typography.labelLarge)
        DestinationLink("Identificación NFC", "Credencial e instrucciones de lectura") {
            actions.navigate(Section.Identification)
        }
        DestinationLink("Cuenta", if (state.person == null) "Verificar cuenta" else "Cambiar cuenta o cerrar sesión") {
            actions.navigate(Section.Account)
        }
        DestinationLink("Configuración", "Conexión y dirección del servidor") { actions.navigate(Section.Connection) }
        DestinationLink("Diagnósticos NFC") { actions.navigate(Section.Diagnostics) }
    }
}
