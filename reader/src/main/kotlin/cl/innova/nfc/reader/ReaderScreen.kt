package cl.innova.nfc.reader

import androidx.activity.compose.BackHandler
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
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

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
    var selectedId by remember(state.event?.id, state.staffId) { mutableStateOf<String?>(null) }
    var chatVisible by remember(selectedId) { mutableStateOf(false) }
    val selected = state.incidents?.firstOrNull { it.id == selectedId }
    BackHandler(selected != null) {
        if (chatVisible) chatVisible = false else selectedId = null
    }
    var note by remember(confirmation) { mutableStateOf("") }
    if (selected != null && chatVisible) {
        Heading("Conversación", selected.requesterName)
        TextButton(onClick = { chatVisible = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Volver a los detalles") }
        key(state.staffId, state.event?.id, selected.id) {
            IncidentChat(selected.id, attendee = false, outbox = actions.chatOutbox, staffId = state.staffId,
                load = { actions.conversation(selected.id, it) }, send = { actions.message(selected.id, it) })
        }
        return
    }
    if (selected == null) {
        Heading("Incidentes", "Solicitudes SOS registradas en el evento.")
        state.staffName?.let { Field("Cuenta activa", it) }
        EventSelector(state, actions::selectEvent)
        Heading("Mi atención activa", if (state.guardBusy) "Tienes una solicitud asignada." else "Sin solicitudes asignadas.")
        state.activeIncident?.let { own ->
            QueueCard(own, if (own.eventId == state.event?.id) "Abrir mi atención" else "Ir al evento de mi atención") {
                if (own.eventId == state.event?.id) selectedId = own.id else actions.selectEvent(own.eventId)
            }
        }
    } else {
        TextButton(onClick = { selectedId = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Volver a incidentes") }
        Heading("Detalle de la solicitud", selected.requesterName)
    }
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
    if (selected == null) {
        TextButton(onClick = actions::continueReading, modifier = Modifier.heightIn(min = 48.dp)) { Text("Ir al lector NFC") }
        TextButton(onClick = actions::configure, modifier = Modifier.heightIn(min = 48.dp)) { Text("Configuración y evento") }
        state.incidents?.let { incidents ->
            val (active, history) = incidents.partition { it.status.active }
            Heading("Cola del evento", if (state.guardBusy) "Puedes consultar otros casos mientras estás ocupado." else "Solicitudes activas.")
            val others = active.filterNot { it.id == state.activeIncident?.id }
            if (others.isEmpty()) Text("No hay otras solicitudes activas.")
            others.forEach { incident -> QueueCard(incident, "Ver detalles") { selectedId = incident.id } }
            if (history.isNotEmpty()) {
                Heading("Historial", "Solicitudes terminadas de este evento.")
                history.forEach { incident -> QueueCard(incident, "Ver detalles") { selectedId = incident.id } }
            }
        }
    } else {
        IncidentCard(selected) {
            selected.dispatch?.let { DispatchBadge(it) }
            selected.dangerAlert?.alert?.let { Status(it, Tone.Warning) }
            if (selected.hasConversation) {
                Button(onClick = { chatVisible = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Abrir conversación")
                }
            }
            listOf(IncidentAction.Claim, IncidentAction.Start, IncidentAction.Close).filter { state.permits(selected, it) }.forEach { action ->
                Button(onClick = {
                    if (action == IncidentAction.Start) actions.operateIncident(selected.id, selected.version, action)
                    else confirmation = selected to action
                }, enabled = state.incidentsCurrent && !state.pendingIncident,
                    modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(if (action == IncidentAction.Claim && selected.hasConversation) "Asumir conversación" else action.label)
                }
            }
        }
        selected.dispatch?.let { DispatchPanel(it, selected, actions, state.staffId) }
        key(state.staffId, state.event?.id, selected.id) {
            VenuePanel(selected.id, actions)
        }
        if (selected.triage != null && !selected.hasConversation) {
            if (state.triageId == selected.id) TriagePanel(state, selected, actions)
            else TextButton(onClick = { actions.openTriage(selected.id) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Ver triaje")
            }
        }
    }
    confirmation?.let { (incident, action) ->
        val valid = state.incidentsCurrent && !state.pendingIncident &&
            state.incidents?.any { it.id == incident.id && it.version == incident.version && state.permits(it, action) } == true
        AlertDialog(onDismissRequest = { confirmation = null },
            title = { Text(when (action) {
                IncidentAction.Claim -> "¿Asumir esta solicitud?"
                IncidentAction.Close -> "¿Cerrar esta solicitud?"
                else -> "¿Resolver esta solicitud?"
            }) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                    if (!valid) Text("La solicitud cambió o requiere una consulta vigente.")
                    else if (action == IncidentAction.Claim) Text(
                        if (incident.hasConversation) "El caso quedará asignado a tu cuenta y podrás responder en el chat."
                        else "Quedarás a cargo de la atención de este SOS.")
                    else OutlinedTextField(note, { if (it.length <= 240) note = it },
                        label = { Text("Motivo de cierre") }, supportingText = { Text("${note.length}/240") })
                }
            },
            confirmButton = {
                Button(onClick = {
                    actions.operateIncident(incident.id, incident.version, action, note)
                    confirmation = null
                }, enabled = valid && (action != IncidentAction.Close || note.isNotBlank()),
                    modifier = Modifier.heightIn(min = 48.dp)) { Text(action.label) }
            },
            dismissButton = { TextButton(onClick = { confirmation = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Volver") } })
    }
}

@Composable
private fun QueueCard(incident: Incident, label: String, open: () -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(Space.sm), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Text(incident.requesterName, style = MaterialTheme.typography.titleMedium)
            Status(incident.status.label, incident.status.tone)
            incident.dispatch?.let {
                DispatchBadge(it)
                Text(it.displayedLocation())
                if (incident.status.active) Text("${it.elapsedSeconds / 60} min ${it.elapsedSeconds % 60} s desde la emisión")
            }
            TextButton(onClick = open, modifier = Modifier.heightIn(min = 48.dp)) { Text(label) }
        }
    }
}

@Composable
private fun DispatchPanel(dispatch: IncidentDispatch, incident: Incident, actions: ReaderController, staffId: String?) {
    dispatch.operational?.let { report ->
        Field("Situación reportada", report.situation)
        Field("Por verificar", report.pending.joinToString(" · "))
        if (report.guidance != null) Text("Orientación de emergencia registrada")
        report.risks.forEach { risk ->
            Field("Mensaje ${risk.messageSeq}", "«${risk.quote}»" + if (risk.uncertain) " · Reporte incierto" else "")
        }
    }
    val analysis = dispatch.analysis
    if (analysis != null) {
        Field("Resumen sugerido por IA", analysis.summary)
        Field("Categoría sugerida", analysis.category.label)
        if (analysis.uncertainty.isNotEmpty()) Field("Por verificar", analysis.uncertainty.joinToString(" · "))
    }
    dispatch.declaredLocation?.let { Field("Ubicación declarada", it.label()) }
    dispatch.confirmedLocation?.let { Field("Ubicación confirmada por el guardia", it.label()) }
    dispatch.attendeeLocation?.let { location ->
        Field(location.source.label, location.zoneName)
        Field("Evidencia original", "«${location.evidence.quote}»")
        if (dispatch.confirmedLocation == null) Field("Estado de la ubicación", "Pendiente de verificación en terreno.")
    }
    dispatch.inferredLocation?.let {
        Field("Ubicación probable", it.label())
        analysis?.locationEvidence?.let { evidence ->
            val feature = evidence.feature?.let { clue -> " En esta zona se describe «$clue»." }.orEmpty()
            Field("Motivo", "El asistente dijo «${evidence.quote}».$feature")
        }
        Field("Estado de la inferencia", "Inferida por IA, pendiente de verificación en terreno.")
    }

    if (incident.assignedStaff?.id == null || incident.assignedStaff.id != staffId ||
        incident.status !in setOf(IncidentStatus.ASSIGNED, IncidentStatus.IN_PROGRESS)) return
    var editing by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<DispatchReviewBody?>(null) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<DispatchDetail?>(null) }
    val scope = rememberCoroutineScope()
    notice?.let { Text(it) }
    TextButton(onClick = {
        if (editing) editing = false
        else {
            editing = true
            if (detail == null) {
                scope.launch {
                    try { detail = actions.dispatch(incident.id) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { notice = "No se pudo consultar el catálogo. Cierra y vuelve a abrir la corrección." }
                }
            }
        }
    }, enabled = pending == null && !busy, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(if (editing) "Cerrar corrección" else "Corregir prioridad y ubicación")
    }
    if (!editing) return
    val loaded = detail ?: return
    val current = if (dispatch.revision >= loaded.dispatch.revision) dispatch else loaded.dispatch
    key(current.revision) {
        var priority by remember { mutableStateOf(current.humanPriority) }
        var zone by remember { mutableStateOf(current.confirmedLocation?.zoneId) }
        var reference by remember { mutableStateOf(current.confirmedLocation?.text.orEmpty()) }
        var danger by remember { mutableStateOf(DangerAssessment.UNCHANGED) }
        var reason by remember { mutableStateOf("") }
        val editable = pending == null && !busy
        DispatchChoice(priority?.label ?: "Sin prioridad humana", editable, listOf(null) + DispatchPriority.entries,
            { it?.label ?: "Sin prioridad humana" }) { priority = it }
        val zoneName = loaded.zones.firstOrNull { it.id == zone }?.name
            ?: current.confirmedLocation?.takeIf { it.zoneId == zone }?.zoneName
        DispatchChoice(zoneName ?: "Zona por confirmar", editable,
            listOf(null) + loaded.zones, { it?.name ?: "Zona por confirmar" }) { zone = it?.id }
        OutlinedTextField(reference, { if (it.length <= 160) reference = it }, enabled = editable,
            label = { Text("Referencia confirmada") }, modifier = Modifier.fillMaxWidth())
        DispatchChoice(danger.label, editable, DangerAssessment.entries, { it.label }) { danger = it }
        OutlinedTextField(reason, { if (it.length <= 240) reason = it }, enabled = editable,
            label = { Text("Motivo de la revisión") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = {
            val location = if (zone != null || reference.isNotBlank()) DispatchLocationInput(zone, reference.trim().ifEmpty { null }) else null
            val command = pending ?: DispatchReviewBody(current.revision, UUID.randomUUID().toString(), priority, location, danger, reason.trim())
            pending = command
            busy = true
            notice = null
            scope.launch {
                try {
                    val reply = actions.reviewDispatch(incident.id, command)
                    detail = loaded.copy(dispatch = reply.dispatch)
                    pending = null
                    editing = false
                    notice = "Corrección registrada."
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: ApiFailure) {
                    if (failure.problem in setOf(ApiProblem.Conflict, ApiProblem.Forbidden, ApiProblem.InvalidInput, ApiProblem.NotFound)) {
                        pending = null
                        detail = null
                        editing = false
                        actions.refreshIncidents()
                        notice = "La revisión requiere consultar el despacho vigente."
                    } else notice = "Revisión sin confirmar. Reintenta la misma operación."
                } finally {
                    busy = false
                }
            }
        }, enabled = !busy && (pending != null || reason.isNotBlank()), modifier = Modifier.heightIn(min = 48.dp)) {
            Text(if (pending != null && !busy) "Reintentar revisión" else "Guardar revisión")
        }
    }
}

@Composable
private fun VenuePanel(id: String, actions: ReaderController) {
    var context by remember { mutableStateOf<StaffVenue?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    TextButton(onClick = {
        if (context != null) context = null
        else {
            loading = true
            error = false
            scope.launch {
                try { context = actions.venue(id) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { error = true }
                finally { loading = false }
            }
        }
    }, enabled = !loading, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(if (context == null) "Ver recinto y puntos operativos" else "Cerrar información del recinto")
    }
    if (loading) Text("Consultando recinto…")
    if (error) Text("No se pudo consultar el recinto. Vuelve a intentar.")
    context?.let { info ->
        Field("Recinto", info.venue.venue)
        info.venue.description?.let { Field("Descripción operativa", it) }
        info.venue.accessNotes?.let { Field("Referencias de acceso", it) }
        info.venue.responseNotes?.let { Field("Referencias para emergencias", it) }
        info.zones.forEach { zone ->
            listOfNotNull(zone.description, zone.reference).takeIf { it.isNotEmpty() }?.let {
                Field(zone.name, it.joinToString(" · "))
            }
        }
        info.points.forEach { point ->
            Field("${point.kind.label} · ${point.name}", "${point.zoneName} · ${point.reference}")
        }
        if (info.points.isEmpty()) Text("Sin puntos operativos activos registrados.")
    }
}

@Composable
private fun <T> DispatchChoice(label: String, enabled: Boolean, values: List<T>, title: (T) -> String, change: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, enabled = enabled,
            modifier = Modifier.heightIn(min = 48.dp)) { Text(label) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            values.forEach { value ->
                DropdownMenuItem(text = { Text(title(value)) }, onClick = { open = false; change(value) },
                    modifier = Modifier.heightIn(min = 48.dp))
            }
        }
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
