package cl.innova.nfc.emitter

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import cl.discoprotect.ui.*
import com.google.zxing.BarcodeFormat
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal enum class ServiceTab(val label: String) {
    Ticket("Entrada QR"), Companions("Acompañantes"), Promotions("Promociones")
}

internal class ServiceUiState {
    var events by mutableStateOf<List<SosEvent>?>(null)
    var tab by mutableStateOf(ServiceTab.Ticket)
    val eventPanels = mutableMapOf<String, ServiceEventUi>()
}

internal class ServiceEventUi {
    var ticket by mutableStateOf<DemoTicket?>(null)
    var promotions by mutableStateOf<List<EventPromotion>?>(null)
    var companions by mutableStateOf<List<EventCompanion>?>(null)
    var invite by mutableStateOf<Invitation?>(null)
    var inviteInput by mutableStateOf("")
}

@Composable
internal fun AttendeeServicesScreen(state: AttendeeState, ui: ServiceUiState, actions: AttendeeController) {
    Text("Servicios", style = MaterialTheme.typography.headlineSmall)
    if (state.person == null) {
        Text("Verifica tu cuenta para consultar la entrada y los servicios del evento.")
        DestinationLink("Verificar cuenta") { actions.navigate(Section.Account) }
        return
    }

    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    val selected = state.sos.event?.takeIf { candidate -> ui.events?.any { it.id == candidate.id } == true }
    LaunchedEffect(ui, refresh) {
        error = null
        try {
            ui.events = actions.serviceEvents()
        } catch (failure: AttendeeFailure) {
            ui.events = null
            error = failure.kind.message
        }
    }
    error?.let { Status(it, Tone.Warning) }
    if (ui.events == null && error == null) Text("Consultando eventos…")
    if (ui.events?.isEmpty() == true) Text("Tu cuenta no tiene eventos disponibles.")
    val prioritizeQr = selected != null && ui.tab == ServiceTab.Ticket &&
        (LocalConfiguration.current.screenWidthDp <= 340 || LocalDensity.current.fontScale >= 1.3f)
    val canSelectEvent = state.sos.pending == null && state.task == null
    if (prioritizeQr && selected != null) {
        Text(selected.name, style = MaterialTheme.typography.titleMedium)
    } else {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            selected?.let {
                Text(it.name, Modifier.align(Alignment.CenterVertically), style = MaterialTheme.typography.titleMedium)
            }
            EventPicker(ui.events.orEmpty(), selected, canSelectEvent,
                compact = selected != null, select = actions::selectServiceEvent)
        }
    }
    if (state.sos.pending != null) {
        Text("Hay una solicitud pendiente de confirmar.")
        TextButton(onClick = { actions.navigate(Section.Home) }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("Revisar en Inicio")
        }
    }
    if (selected != null) key(selected.id) {
        val panels = remember(ui, selected.id) { ui.eventPanels.getOrPut(selected.id) { ServiceEventUi() } }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            ServiceTab.entries.forEach {
                FilterChip(it == ui.tab, { ui.tab = it }, { Text(it.label, style = MaterialTheme.typography.labelMedium) }, modifier = Modifier.heightIn(min = 48.dp))
            }
        }
        when (ui.tab) {
            ServiceTab.Ticket -> TicketPanel(selected.id, refresh, panels, actions, prioritizeQr)
            ServiceTab.Promotions -> PromotionsPanel(selected.id, refresh, panels, actions)
            ServiceTab.Companions -> CompanionsPanel(selected.id, refresh, panels, actions)
        }
    }
    if (prioritizeQr) {
        EventPicker(ui.events.orEmpty(), selected, canSelectEvent, compact = true, select = actions::selectServiceEvent)
    }
    TextButton(onClick = { refresh++ }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Actualizar servicios") }
}


@Composable
private fun TicketPanel(
    eventId: String, refresh: Int, ui: ServiceEventUi, actions: AttendeeController, prioritizeQr: Boolean,
) {
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
        try { ui.ticket = actions.ticket(eventId) }
        catch (failure: AttendeeFailure) { error = failure.kind.message }
        finally { loading = false }
    }
    val entry = ui.ticket
    if (!prioritizeQr) TicketHint()
    when {
        loading -> Text("Consultando entrada…")
        error != null -> Status(error!!, Tone.Warning)
        entry == null -> Text("El organizador todavía no ha emitido una entrada para esta cuenta.")
        entry.revokedAt != null -> Status("Entrada revocada", Tone.Warning)
        entry.usedAt != null -> Status("Entrada utilizada", Tone.Info)
        entry.expiresAt.toInstantOrNull()?.isAfter(now) != true -> Status("Entrada vencida", Tone.Warning)
        entry.code.matches(Regex("DP1_[A-Za-z0-9_-]{32}")) -> {
            Status("Entrada válida", Tone.Success)
            val matrix = remember(entry.code) { QRCodeWriter().encode(entry.code, BarcodeFormat.QR_CODE, 0, 0) }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                TicketQr(matrix)
            }
            if (prioritizeQr) TicketHint()
            Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                Text("Código para ingreso manual")
                SelectionContainer {
                    Text(entry.code, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                }
            }
        }
        else -> Status("La entrada recibida no es válida.", Tone.Warning)
    }
}

@Composable
private fun TicketHint() {
    Text("Presenta el QR ficticio al control de ingreso.", style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun TicketQr(matrix: BitMatrix) {
    Canvas(Modifier.size(200.dp).semantics { contentDescription = "QR de la entrada ficticia" }) {
        drawRect(Color.White)
        val module = kotlin.math.floor(size.width / matrix.width)
        val border = kotlin.math.floor((size.width - module * matrix.width) / 2)
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
            if (matrix[x, y]) {
                drawRect(Color.Black, Offset(border + x * module, border + y * module), Size(module, module))
            }
        }
    }
}

private fun String.toInstantOrNull() = runCatching { Instant.parse(this) }.getOrNull()

@Composable
private fun PromotionsPanel(eventId: String, refresh: Int, ui: ServiceEventUi, actions: AttendeeController) {
    val context = LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(eventId, refresh) {
        do {
            try {
                ui.promotions = actions.promotions(eventId)
                error = null
            } catch (failure: AttendeeFailure) {
                ui.promotions = null
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
    if (ui.promotions == null && error == null) Text("Consultando promociones…")
    val live = ui.promotions?.filter { it.endsAt.toInstantOrNull()?.isAfter(now) == true }
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
private fun CompanionsPanel(eventId: String, refresh: Int, ui: ServiceEventUi, actions: AttendeeController) {
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var unlink by remember { mutableStateOf<EventCompanion?>(null) }
    suspend fun load() { ui.companions = actions.companions(eventId) }
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
    if (ui.companions == null && error == null) Text("Consultando vínculos…")
    if (ui.companions?.isEmpty() == true) Text("Sin acompañantes vinculados.")
    ui.companions?.forEach { companion ->
        Field("Acompañante", companion.name)
        TextButton({ unlink = companion }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Desvincular") }
    }
    Button({ run { ui.invite = actions.invite(eventId) } }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
        Text("Generar invitación")
    }
    ui.invite?.let {
        Text("Comparte este código con la persona que deseas vincular. Tiene 15 minutos para aceptarlo.")
        SelectionContainer { Text(it.code) }
    }
    OutlinedTextField(ui.inviteInput, { ui.inviteInput = it.take(36) }, label = { Text("Código de invitación") }, singleLine = true)
    Button({ run { actions.acceptInvite(eventId, ui.inviteInput.trim()); ui.inviteInput = ""; load() } },
        enabled = !busy && ui.inviteInput.trim().matches(Regex("DPC_[A-Za-z0-9_-]{32}")),
        modifier = Modifier.heightIn(min = 48.dp)) { Text("Aceptar vínculo y compartir mi nombre") }
    unlink?.let { companion ->
        AlertDialog(onDismissRequest = { unlink = null }, title = { Text("¿Desvincular a ${companion.name}?") },
            text = { Text("Se retirará el permiso de consulta para ambas cuentas.") },
            confirmButton = { TextButton({ unlink = null; run { actions.unlinkCompanion(eventId, companion.id); load() } },
                modifier = Modifier.heightIn(min = 48.dp)) { Text("Desvincular") } },
            dismissButton = { TextButton({ unlink = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancelar") } })
    }
}
