package cl.innova.nfc.reader

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
        key(state.configuring, state.incidentsVisible, state.scan is ScanState.Found) {
            NfcPage("Personal") {
                if (state.configuring) {
                    ConnectionSetup(state, actions)
                } else if (state.incidentsVisible) {
                    IncidentsPage(state, actions)
                } else {
                    ReadingPage(state, nfc, actions, onSettings)
                }
            }
        }
    }
}

@Composable
private fun IncidentsPage(state: ReaderUiState, actions: ReaderController) {
    var confirmation by remember(state.event?.id, state.staffId) { mutableStateOf<Pair<Incident, IncidentAction>?>(null) }
    var chatId by remember(state.event?.id, state.staffId) { mutableStateOf<String?>(null) }
    var note by remember(confirmation) { mutableStateOf("") }
    Heading("Incidentes", "Solicitudes SOS registradas en el evento.")
    state.staffName?.let { Field("Cuenta activa", it) }
    EventSelector(state, actions::selectEvent)
    OutlinedButton(onClick = actions::refreshIncidents, enabled = !state.incidentsLoading && !state.incidentWriting,
        modifier = Modifier.heightIn(min = 48.dp)) {
        Text("Actualizar incidentes")
    }
    if (state.incidentsLoading && state.incidents == null) {
        Status("Consultando incidentes", Tone.Info)
        LinearProgressIndicator(Modifier.fillMaxWidth())
    }
    state.lastIncidentRead?.let { Field("Última consulta", incidentTime(it.toString())) }
    if (state.incidentWriting) Status("Enviando operación", Tone.Info)
    state.incidentNotice?.let { Text(it) }
    if (state.pendingIncident && !state.incidentWriting) {
        Status("Resultado sin confirmar", Tone.Warning)
        OutlinedButton(onClick = actions::retryIncident, enabled = state.incidentsCurrent,
            modifier = Modifier.heightIn(min = 48.dp)) { Text("Reintentar la misma operación") }
    }
    state.incidentProblem?.let { problem ->
        val message = if (problem == ApiProblem.NotFound) "La consulta de incidentes no está disponible para este evento."
            else problem.description()
        Status(message, Tone.Warning)
        if (state.incidents != null) Text("Se muestra la última consulta disponible.")
    }
    TextButton(onClick = actions::continueReading, modifier = Modifier.heightIn(min = 48.dp)) { Text("Ir al lector NFC") }
    TextButton(onClick = actions::configure, modifier = Modifier.heightIn(min = 48.dp)) { Text("Configuración y evento") }
    state.incidents?.let { incidents ->
        if (incidents.isEmpty()) Text("Este evento aún no tiene solicitudes registradas.")
        val groups = listOf(
            "Pendientes" to setOf(IncidentStatus.OPEN),
            "Asignadas y en atención" to setOf(IncidentStatus.ASSIGNED, IncidentStatus.IN_PROGRESS),
            "Cerradas" to setOf(IncidentStatus.RESOLVED, IncidentStatus.CANCELLED),
        )
        groups.forEach { (label, statuses) ->
            val items = incidents.filter { it.status in statuses }
            if (items.isNotEmpty()) Text(label, style = MaterialTheme.typography.titleMedium)
            items.forEach { incident ->
                IncidentCard(incident) {
                    incident.dangerAlert?.alert?.let { Status(it, Tone.Warning) }
                    incident.triage?.let { triage ->
                        val review = triage.latestReview
                        Text(if (review != null) "Revisión humana" else triage.status.label)
                        val category = review?.category ?: triage.suggestion?.category ?: triage.fallback?.category
                        val urgency = review?.urgency ?: triage.suggestion?.urgency ?: triage.fallback?.urgency
                        if (category != null && urgency != null) {
                            Text("${category.label} · Prioridad ${urgency.label.lowercase()}")
                        }
                    }
                    state.actionFor(incident)?.let { action ->
                        Button(onClick = {
                            if (action == IncidentAction.Start) actions.operateIncident(incident.id, incident.version, action)
                            else confirmation = incident to action
                        }, enabled = state.incidentsCurrent && !state.pendingIncident,
                            modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(if (action == IncidentAction.Claim && incident.hasConversation) "Asumir conversación" else action.label)
                        }
                    }
                    if (incident.hasConversation) {
                        val open = chatId == incident.id
                        TextButton(onClick = { chatId = if (open) null else incident.id },
                            modifier = Modifier.heightIn(min = 48.dp)) { Text(if (open) "Cerrar conversación" else "Ver conversación") }
                        if (open) key(state.staffId, state.event?.id, incident.id) {
                            IncidentChat(incident.id, attendee = false, staffId = state.staffId,
                                load = { actions.conversation(incident.id, it) }, send = { actions.message(incident.id, it) })
                        }
                    }
                    if (incident.triage != null) {
                        if (state.triageId == incident.id) {
                            TriagePanel(state, incident, actions)
                        } else {
                            TextButton(onClick = { actions.openTriage(incident.id) },
                                enabled = !state.triageWriting, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text("Ver triaje")
                            }
                        }
                    }
                }
            }
        }
    }
    confirmation?.let { (incident, action) ->
        val valid = state.incidentsCurrent && !state.pendingIncident &&
            state.incidents?.any { it.id == incident.id && it.version == incident.version && state.actionFor(it) == action } == true
        AlertDialog(onDismissRequest = { confirmation = null },
            title = { Text(if (action == IncidentAction.Claim) "¿Asumir esta solicitud?" else "¿Resolver esta solicitud?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                    if (!valid) Text("La solicitud cambió o requiere una consulta vigente.")
                    else if (action == IncidentAction.Claim) Text(
                        if (incident.hasConversation) "El caso quedará asignado a tu cuenta y podrás responder en el chat."
                        else "Quedarás a cargo de la atención de este SOS.")
                    else OutlinedTextField(note, { if (it.length <= 240) note = it },
                        label = { Text("Nota de cierre (opcional)") }, supportingText = { Text("${note.length}/240") })
                }
            },
            confirmButton = {
                Button(onClick = {
                    actions.operateIncident(incident.id, incident.version, action, note)
                    confirmation = null
                }, enabled = valid, modifier = Modifier.heightIn(min = 48.dp)) { Text(action.label) }
            },
            dismissButton = { TextButton(onClick = { confirmation = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Volver") } })
    }
}

@Composable
private fun TriagePanel(state: ReaderUiState, incident: Incident, actions: ReaderController) {
    val triage = requireNotNull(incident.triage)
    val suggestion = triage.suggestion
    val review = triage.latestReview
    var editing by remember(incident.id, triage.triageVersion) { mutableStateOf(false) }
    var category by remember(incident.id, triage.triageVersion) {
        mutableStateOf(review?.category ?: suggestion?.category ?: TriageCategory.UNKNOWN)
    }
    var urgency by remember(incident.id, triage.triageVersion) {
        mutableStateOf(review?.urgency ?: suggestion?.urgency ?: TriageUrgency.UNDETERMINED)
    }
    val canEdit = state.canReview(incident) && state.triageDetail != null && !state.triagePending
    TextButton(onClick = actions::closeTriage, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cerrar triaje") }
    if (state.triageLoading) Text("Consultando detalle…")
    state.triageNotice?.let { Text(it) }
    state.triageDetail?.let { Field("Relato original", it.description ?: "Sin descripción") }
    if (!state.triageLoading && state.triageDetail == null) {
        TextButton(onClick = { actions.openTriage(incident.id) }, enabled = !state.triageWriting,
            modifier = Modifier.heightIn(min = 48.dp)) { Text("Volver a consultar el detalle") }
    }
    review?.let { Field("Última revisión", "${it.actor.name} · ${incidentTime(it.reviewedAt)}") }
    (suggestion ?: triage.fallback)?.let { result ->
        Field(if (suggestion != null) "Resumen sugerido por IA" else "Verificación manual", result.summary)
        Field("Incertidumbre", result.uncertainty.label)
        if (result.missingInformation.isNotEmpty()) {
            Field("Información faltante", result.missingInformation.joinToString("\n") { it.label })
        }
    }
    if (state.triageWriting) Text("Guardando revisión…")
    if (state.triagePending && !state.triageWriting) {
        OutlinedButton(onClick = actions::retryTriage, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("Reintentar la misma revisión")
        }
    }
    if (canEdit && !editing) {
        if (suggestion != null) {
            OutlinedButton(onClick = { actions.reviewTriage(triage.triageVersion, ReviewDecision.CONFIRM) },
                modifier = Modifier.heightIn(min = 48.dp)) { Text("Confirmar sugerencia de IA") }
        }
        OutlinedButton(onClick = { editing = true }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(if (suggestion == null && review == null) "Clasificar manualmente" else "Corregir clasificación")
        }
    }
    if (canEdit && editing) {
        TriageMenu("Categoría", category, TriageCategory.entries, { it.label }) { category = it }
        TriageMenu("Prioridad", urgency, TriageUrgency.entries, { it.label }) { urgency = it }
        Button(onClick = {
            actions.reviewTriage(triage.triageVersion, ReviewDecision.CORRECT, category, urgency)
        }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Guardar clasificación") }
        TextButton(onClick = { editing = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancelar edición") }
    }
}

@Composable
private fun <T> TriageMenu(label: String, selected: T, options: List<T>, text: (T) -> String, select: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("$label · ${text(selected)}")
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(text(option)) }, onClick = {
                    expanded = false
                    select(option)
                }, modifier = Modifier.heightIn(min = 48.dp))
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
    if (!state.local) {
        TextButton(onClick = actions::openIncidents, modifier = Modifier.heightIn(min = 48.dp)) { Text("Ver incidentes") }
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
