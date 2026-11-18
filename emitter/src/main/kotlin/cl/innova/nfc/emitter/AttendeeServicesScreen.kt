package cl.innova.nfc.emitter

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cl.discoprotect.ui.*
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class ServiceTab(val label: String) {
    Ticket("Entrada"), Companions("Acompañantes"), Promotions("Promociones")
}

@Composable
internal fun AttendeeServicesScreen(state: AttendeeState, actions: AttendeeController) {
    Heading("Servicios del evento", "Entrada ficticia, acompañantes y promociones.")
    val person = state.person
    if (person == null) {
        Text("Verifica tu cuenta en Conexión para consultar los servicios.")
        return
    }
    key(person.id, state.address) {
        var events by remember { mutableStateOf<List<SosEvent>?>(null) }
        var error by remember { mutableStateOf<String?>(null) }
        var refresh by remember { mutableIntStateOf(0) }
        var tab by remember { mutableStateOf(ServiceTab.Ticket) }
        val selected = state.sos.event?.takeIf { candidate -> events?.any { it.id == candidate.id } == true }
        LaunchedEffect(refresh) {
            error = null
            try {
                events = actions.serviceEvents()
            } catch (failure: AttendeeFailure) {
                events = null
                error = failure.kind.message
            }
        }
        error?.let { Status(it, Tone.Warning) }
        if (events == null && error == null) Text("Consultando eventos…")
        if (events?.isEmpty() == true) Text("Tu cuenta no tiene eventos disponibles.")
        events?.forEach { event ->
            FilterChip(selected?.id == event.id, { actions.selectServiceEvent(event) }, { Text(event.name) },
                enabled = state.sos.pending == null, modifier = Modifier.heightIn(min = 48.dp))
        }
        if (state.sos.pending != null) Text("Hay un envío pendiente. Puedes revisarlo desde SOS.")
        OutlinedButton(onClick = { refresh++ }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Actualizar servicios") }
        if (selected != null) key(selected.id) {
            ServiceTab.entries.forEach {
                FilterChip(it == tab, { tab = it }, { Text(it.label) }, modifier = Modifier.heightIn(min = 48.dp))
            }
            when (tab) {
                ServiceTab.Ticket -> TicketPanel(selected.id, refresh, actions)
                ServiceTab.Promotions -> PromotionsPanel(selected.id, refresh, actions)
                ServiceTab.Companions -> CompanionsPanel(selected.id, refresh, actions)
            }
        }
    }
}

@Composable
private fun TicketPanel(eventId: String, refresh: Int, actions: AttendeeController) {
    var ticket by remember { mutableStateOf<DemoTicket?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(eventId) {
        while (true) {
            now = Instant.now()
            delay(1000)
        }
    }
    LaunchedEffect(eventId, refresh) {
        loading = true
        error = null
        try { ticket = actions.ticket(eventId) }
        catch (failure: AttendeeFailure) { error = failure.kind.message }
        finally { loading = false }
    }
    val entry = ticket
    Heading("Entrada ficticia", "Presenta el QR al control de ingreso.")
    when {
        loading -> Text("Consultando entrada…")
        error != null -> Status(error!!, Tone.Warning)
        entry == null -> Text("El organizador todavía no ha emitido una entrada para esta cuenta.")
        entry.revokedAt != null -> Status("Entrada revocada", Tone.Warning)
        entry.usedAt != null -> Status("Entrada utilizada", Tone.Info)
        entry.expiresAt.toInstantOrNull()?.isAfter(now) != true -> Status("Entrada vencida", Tone.Warning)
        entry.code.matches(Regex("DP1_[A-Za-z0-9_-]{32}")) -> {
            val matrix = remember(entry.code) { QRCodeWriter().encode(entry.code, BarcodeFormat.QR_CODE, 0, 0) }
            Canvas(Modifier.size(228.dp).semantics { contentDescription = "QR de la entrada ficticia" }) {
                drawRect(Color.White)
                val module = kotlin.math.floor(size.width / matrix.width)
                val border = kotlin.math.floor((size.width - module * matrix.width) / 2)
                for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
                    if (matrix[x, y]) drawRect(Color.Black, Offset(border + x * module, border + y * module), Size(module, module))
                }
            }
            Text("Código para ingreso manual")
            SelectionContainer { Text(entry.code) }
        }
        else -> Status("La entrada recibida no es válida.", Tone.Warning)
    }
}

private fun String.toInstantOrNull() = runCatching { Instant.parse(this) }.getOrNull()

@Composable
private fun PromotionsPanel(eventId: String, refresh: Int, actions: AttendeeController) {
    val context = LocalContext.current
    var items by remember { mutableStateOf<List<EventPromotion>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(eventId, refresh) {
        do {
            try {
                items = actions.promotions(eventId)
                error = null
            } catch (failure: AttendeeFailure) {
                items = null
                error = failure.kind.message
            }
            delay(30_000)
        } while (true)
    }
    LaunchedEffect(eventId) {
        while (true) {
            now = Instant.now()
            delay(1000)
        }
    }
    Heading("Promociones", "Contenido del organizador.")
    error?.let { Status(it, Tone.Warning) }
    if (items == null && error == null) Text("Consultando promociones…")
    val live = items?.filter { it.endsAt.toInstantOrNull()?.isAfter(now) == true }
    if (live?.isEmpty() == true) Text("No hay promociones vigentes.")
    live?.forEach { promotion ->
        Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(promotion.title, style = MaterialTheme.typography.titleMedium)
                Text(promotion.description)
                listOf("Abrir enlace" to promotion.linkUrl, "Ver imagen" to promotion.imageUrl).forEach { (label, raw) ->
                    val uri = raw?.let(Uri::parse)
                    if (uri?.scheme == "https" && uri.userInfo == null && !uri.host.isNullOrBlank()) {
                        TextButton(onClick = {
                            try { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                            catch (_: ActivityNotFoundException) { error = "No hay una aplicación para abrir el enlace." }
                        }, modifier = Modifier.heightIn(min = 48.dp)) { Text(label) }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompanionsPanel(eventId: String, refresh: Int, actions: AttendeeController) {
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<EventCompanion>?>(null) }
    var invite by remember { mutableStateOf<Invitation?>(null) }
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var unlink by remember { mutableStateOf<EventCompanion?>(null) }
    suspend fun load() { list = actions.companions(eventId) }
    LaunchedEffect(eventId, refresh) {
        try { load() }
        catch (failure: AttendeeFailure) { error = failure.kind.message }
    }
    fun run(command: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try { command() }
            catch (failure: AttendeeFailure) { error = failure.kind.message }
            finally { busy = false }
        }
    }
    Heading("Acompañantes", "El vínculo requiere que otra cuenta del mismo evento acepte tu invitación. El guardia asignado a tu SOS puede consultar los nombres asociados durante la atención.")
    error?.let { Status(it, Tone.Warning) }
    if (list == null && error == null) Text("Consultando vínculos…")
    if (list?.isEmpty() == true) Text("Sin acompañantes vinculados.")
    list?.forEach { companion ->
        Field("Acompañante", companion.name)
        TextButton({ unlink = companion }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Desvincular") }
    }
    Button({ run { invite = actions.invite(eventId) } }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
        Text("Generar invitación")
    }
    invite?.let {
        Text("Comparte este código con la persona que deseas vincular. Tiene 15 minutos para aceptarlo.")
        SelectionContainer { Text(it.code) }
    }
    OutlinedTextField(input, { input = it.take(36) }, label = { Text("Código de invitación") }, singleLine = true)
    Button({ run { actions.acceptInvite(eventId, input.trim()); input = ""; load() } },
        enabled = !busy && input.trim().matches(Regex("DPC_[A-Za-z0-9_-]{32}")),
        modifier = Modifier.heightIn(min = 48.dp)) { Text("Aceptar vínculo y compartir mi nombre") }
    unlink?.let { companion ->
        AlertDialog(onDismissRequest = { unlink = null }, title = { Text("¿Desvincular a ${companion.name}?") },
            text = { Text("Se retirará el permiso de consulta para ambas cuentas.") },
            confirmButton = { TextButton({ unlink = null; run { actions.unlinkCompanion(eventId, companion.id); load() } },
                modifier = Modifier.heightIn(min = 48.dp)) { Text("Desvincular") } },
            dismissButton = { TextButton({ unlink = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancelar") } })
    }
}
