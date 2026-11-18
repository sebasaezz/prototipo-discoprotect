package cl.innova.nfc.reader

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cl.discoprotect.ui.*
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class StaffService(val label: String) {
    Venue("Recinto"), Tickets("Control de entradas"), Search("Búsqueda autorizada")
}

@Composable
internal fun StaffServicesScreen(actions: ReaderController, openIncident: (String) -> Unit) {
    var service by remember { mutableStateOf(StaffService.Venue) }
    StaffService.entries.forEach { option ->
        FilterChip(service == option, { service = option }, { Text(option.label) }, modifier = Modifier.heightIn(min = 48.dp))
    }
    when (service) {
        StaffService.Venue -> StaffVenuePanel(actions)
        StaffService.Tickets -> StaffTicketsPanel(actions)
        StaffService.Search -> StaffSearchPanel(actions, openIncident)
    }
}

@Composable
private fun StaffVenuePanel(actions: ReaderController) {
    var venue by remember { mutableStateOf<StaffVenue?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    LaunchedEffect(revision) {
        error = null
        try { venue = actions.venue() }
        catch (failure: ApiFailure) { venue = null; error = failure.problem.description() }
    }
    error?.let { Status(it, Tone.Warning) }
    if (venue == null && error == null) Text("Consultando recinto…")
    OutlinedButton({ revision++ }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Actualizar recinto") }
    venue?.let { data ->
        Heading(data.venue.venue, data.venue.description ?: data.venue.name)
        data.venue.accessNotes?.let { Field("Acceso", it) }
        data.venue.responseNotes?.let { Field("Respuesta", it) }
        if (data.zones.isEmpty()) Text("Sin zonas activas.")
        data.zones.forEach { zone -> Field(zone.name, listOfNotNull(zone.description, zone.reference).joinToString(" · ").ifEmpty { "Sin referencias" }) }
        data.points.forEach { point -> Field(point.name, "${point.kind.label} · ${point.zoneName} · ${point.reference}") }
    }
}

@Composable
private fun StaffTicketsPanel(actions: ReaderController) {
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<TicketCheck?>(null) }
    var result by remember { mutableStateOf<TicketResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { photo ->
        if (photo == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            try {
                val scanned = withContext(Dispatchers.Default) {
                    val pixels = IntArray(photo.width * photo.height)
                    photo.getPixels(pixels, 0, photo.width, 0, 0, photo.width, photo.height)
                    val source = RGBLuminanceSource(photo.width, photo.height, pixels)
                    try { QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source))).text }
                    catch (_: ReaderException) { null }
                }
                if (scanned == null) error = "No se pudo leer el QR. Acércalo a la cámara o ingresa el código."
                else { code = scanned.take(120); error = null; result = null }
            } finally { busy = false }
        }
    }
    Heading("Control de entradas", "Captura el QR de la entrada o escribe el código. Al validar, el servidor registra el ingreso.")
    OutlinedButton(onClick = {
        try { camera.launch(null) }
        catch (_: ActivityNotFoundException) { error = "No hay una cámara disponible. Ingresa el código manualmente." }
    }, enabled = !busy && pending == null, modifier = Modifier.heightIn(min = 48.dp)) { Text("Capturar QR") }
    OutlinedTextField(code, { code = it.take(120); result = null }, enabled = !busy && pending == null,
        label = { Text("Código de entrada") }, singleLine = true)
    Button(onClick = {
        val attempt = pending ?: TicketCheck(code.trim(), UUID.randomUUID().toString())
        pending = attempt
        result = null
        error = null
        busy = true
        scope.launch {
            try {
                result = actions.validateTicket(attempt).result
                pending = null
                code = ""
            } catch (failure: ApiFailure) { error = failure.problem.description() }
            finally { busy = false }
        }
    }, enabled = code.isNotBlank() && !busy, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(if (pending != null && !busy) "Reintentar la misma validación" else "Validar ingreso")
    }
    if (pending != null && !busy) TextButton(onClick = { pending = null; code = ""; error = null },
        modifier = Modifier.heightIn(min = 48.dp)) { Text("Descartar intento sin confirmar") }
    if (busy) Text("Procesando entrada…")
    result?.let { Status(it.label, if (it == TicketResult.VALID) Tone.Success else Tone.Warning) }
    error?.let { Status(it, Tone.Warning) }
}

private enum class SearchType(val api: String, val label: String) {
    Incidents("incidents", "Incidentes"), Zones("zones", "Zonas"), Staff("staff", "Guardias autorizados")
}

@Composable
private fun StaffSearchPanel(actions: ReaderController, openIncident: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var type by remember { mutableStateOf(SearchType.Incidents) }
    var term by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<IncidentStatus?>(null) }
    var statusMenu by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<StaffSearchResult>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var offset by remember { mutableIntStateOf(0) }
    fun search(next: Int) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                results = actions.search(type.api, term.trim(), status, next)
                offset = next
            } catch (failure: ApiFailure) { results = null; error = failure.problem.description() }
            finally { busy = false }
        }
    }
    Heading("Búsqueda autorizada", "Encuentra información del evento seleccionado.")
    SearchType.entries.forEach { option ->
        FilterChip(type == option, { type = option; term = ""; status = null; results = null },
            { Text(option.label) }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp))
    }
    OutlinedTextField(term, { term = it.take(80); results = null }, enabled = !busy, singleLine = true,
        label = { Text(if (type == SearchType.Incidents) "ID de incidente (opcional)" else "Nombre (opcional)") })
    if (type == SearchType.Incidents) {
        OutlinedButton({ statusMenu = true }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("Estado · ${status?.label ?: "Todos"}")
        }
        DropdownMenu(statusMenu, { statusMenu = false }) {
            DropdownMenuItem({ Text("Todos") }, { status = null; results = null; statusMenu = false })
            IncidentStatus.entries.forEach { entry ->
                DropdownMenuItem({ Text(entry.label) }, { status = entry; results = null; statusMenu = false })
            }
        }
    }
    Button({ search(0) }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Buscar") }
    if (busy) Text("Buscando…")
    error?.let { Status(it, Tone.Warning) }
    results?.let { found ->
        if (found.isEmpty()) Text("Sin coincidencias.")
        found.forEach { item ->
            if (type == SearchType.Incidents) TextButton({ openIncident(item.id) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("${item.status?.label.orEmpty()} · ${item.id}")
            } else Text(item.name ?: item.id)
        }
        if (offset > 0) TextButton({ search(offset - 25) }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Anterior") }
        if (found.size == 25) TextButton({ search(offset + 25) }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Siguiente") }
    }
}

@Composable
internal fun StaffCompanions(id: String, actions: ReaderController) {
    val scope = rememberCoroutineScope()
    var names by remember { mutableStateOf<List<EventCompanion>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    TextButton(onClick = {
        busy = true
        names = null
        error = null
        scope.launch {
            try { names = actions.companions(id) }
            catch (failure: ApiFailure) { error = failure.problem.description() }
            finally { busy = false }
        }
    }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Consultar acompañantes autorizados") }
    error?.let { Status(it, Tone.Warning) }
    names?.let {
        if (it.isEmpty()) Text("Sin acompañantes vinculados.")
        else Field("Acompañantes", it.joinToString("\n") { companion -> companion.name })
    }
}
