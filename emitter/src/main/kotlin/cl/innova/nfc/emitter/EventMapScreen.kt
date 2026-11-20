package cl.innova.nfc.emitter

import android.Manifest
import android.app.ActivityManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cl.discoprotect.ui.GeoLocation
import cl.discoprotect.ui.GeoSource
import cl.discoprotect.ui.Space
import java.time.Instant
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job

private enum class MapEdit(val source: GeoSource) { Manual(GeoSource.MANUAL), Demo(GeoSource.DEMO) }

@Composable
internal fun ColumnScope.EventMapScreen(event: SosEvent, sos: SosState, actions: AttendeeController) {
    var map by remember(event.id) { mutableStateOf<VenueMap?>(null) }
    var loading by remember(event.id) { mutableStateOf(true) }
    var error by remember(event.id) { mutableStateOf<String?>(null) }
    var request by remember(event.id) { mutableIntStateOf(0) }
    LaunchedEffect(event.id, request) {
        loading = true
        error = null
        try {
            map = actions.venueMap(event.id)
        } catch (_: AttendeeFailure) {
            error = "No se pudo actualizar el mapa del evento."
        } finally {
            loading = false
        }
    }
    val config = map
    if (config == null) {
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(if (loading) "Cargando recinto…" else error ?: "Este evento aún no tiene un mapa disponible.")
        if (!loading) OutlinedButton(onClick = { request++ }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Reintentar") }
        TextButton(onClick = { actions.navigate(Section.Home) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Ir al SOS") }
        return
    }

    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val owner = LocalLifecycleOwner.current
    var view by remember(config.boundary) { mutableStateOf<ParkViewport?>(null) }
    var basemap by remember(config.boundary) { mutableStateOf(Basemap.Loading) }
    var edit by remember(event.id) { mutableStateOf<MapEdit?>(null) }
    var candidate by remember(event.id) { mutableStateOf<GeoLocation?>(null) }
    var selectedPlace by remember(event.id) { mutableStateOf<MapPlace?>(null) }
    var showPlaces by remember(event.id) { mutableStateOf(false) }
    var status by remember(event.id) { mutableStateOf<String?>(null) }
    var gpsRequest by remember(event.id) { mutableIntStateOf(0) }
    var requesting by remember(event.id) { mutableStateOf(false) }
    val frozen = (sos.pending as? SosAttempt.Create)?.body?.geoLocation
    val current = candidate ?: if (sos.pending != null) frozen else sos.selectedLocation
    val editable = sos.pending == null
    val coarse = Manifest.permission.ACCESS_COARSE_LOCATION
    val fine = Manifest.permission.ACCESS_FINE_LOCATION
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (context.granted(coarse) || context.granted(fine)) gpsRequest++
        else status = PositionResult.Unavailable.Denied.label
    }
    LaunchedEffect(gpsRequest) {
        if (gpsRequest == 0) return@LaunchedEffect
        requesting = true
        edit = null
        candidate = null
        status = "Buscando tu ubicación…"
        val requestJob = currentCoroutineContext().job
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) requestJob.cancel()
        }
        owner.lifecycle.addObserver(observer)
        try {
            when (val result = currentPosition(context)) {
                is PositionResult.Found -> {
                    candidate = result.location
                    val inside = config.boundary.contains(result.location.latitude, result.location.longitude)
                    if (inside) view?.focus(result.location)
                    status = when {
                        !inside ->
                            "Fuera del recinto. Puedes enviar la posición GPS o elegir un punto manual."
                        result.approximate || (result.location.accuracyMeters ?: Double.MAX_VALUE) > 100 ->
                            "Precisión baja. Puedes corregir la posición manualmente."
                        else -> "Revisa la posición GPS antes de confirmarla."
                    }
                }
                is PositionResult.Unavailable -> status = result.label
            }
        } finally {
            owner.lifecycle.removeObserver(observer)
            requesting = false
        }
    }
    fun choose(mode: MapEdit) {
        edit = mode
        candidate = null
        status = if (mode == MapEdit.Demo) "Ubicación simulada · Toca un punto dentro del recinto."
            else "Toca un punto del mapa dentro del recinto."
    }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(config.venue, style = MaterialTheme.typography.titleMedium)
            Text(config.name, style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = { showPlaces = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Lugares") }
    }
    Box(Modifier.fillMaxWidth().weight(1f).clip(MaterialTheme.shapes.large)) {
        key(config.boundary) {
            AndroidView(
                factory = { ctx ->
                    val graphics = ctx.getSystemService(ActivityManager::class.java).deviceConfigurationInfo.reqGlEsVersion
                    if (graphics >= 0x30000) ParkMapView(ctx, config.boundary) { basemap = it }.also { view = it }
                    else ParkOutlineView(ctx, config.boundary).also {
                        view = it
                        basemap = Basemap.Basic
                    }
                },
                modifier = Modifier.fillMaxSize().semantics { contentDescription = "Mapa interactivo del recinto" },
                update = { platformView ->
                    val mapView = platformView as ParkViewport
                    mapView.editing = edit != null && editable
                    mapView.onPlace = { selectedPlace = it }
                    mapView.onPoint = { lat, lon ->
                        val mode = edit
                        if (mode != null && editable) {
                            if (config.boundary.contains(lat, lon)) {
                                candidate = GeoLocation(lat, lon, source = mode.source, capturedAt = Instant.ofEpochMilli(System.currentTimeMillis()).toString())
                                status = if (mode == MapEdit.Demo) "Punto simulado seleccionado." else "Punto manual seleccionado."
                            } else {
                                candidate = null
                                status = "Fuera del recinto. Elige un punto dentro del perímetro morado."
                            }
                        }
                    }
                    mapView.render(config, current)
                },
            )
        }
        Surface(Modifier.align(Alignment.TopEnd).padding(Space.xs), shape = MaterialTheme.shapes.medium, shadowElevation = 2.dp) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                TextButton(onClick = { view?.center() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Centrar") }
                TextButton(onClick = { view?.zoom(1.0) }, modifier = Modifier.size(48.dp)
                    .semantics { contentDescription = "Acercar mapa" }) { Text("+") }
                TextButton(onClick = { view?.zoom(-1.0) }, modifier = Modifier.size(48.dp)
                    .semantics { contentDescription = "Alejar mapa" }) { Text("−") }
            }
        }
        if (basemap == Basemap.Loading) CircularProgressIndicator(Modifier.align(Alignment.Center))
        Surface(Modifier.align(Alignment.BottomStart), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)) {
            TextButton(onClick = { uri.openUri("https://www.openstreetmap.org/copyright") },
                modifier = Modifier.heightIn(min = 48.dp)) {
                Text("© OpenStreetMap · OpenFreeMap", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    (view as? ParkMapView)?.let { mapView ->
        DisposableEffect(mapView, owner) {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> mapView.onStart()
                    Lifecycle.Event.ON_RESUME -> mapView.onResume()
                    Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                    Lifecycle.Event.ON_STOP -> mapView.onStop()
                    else -> Unit
                }
            }
            owner.lifecycle.addObserver(observer)
            onDispose {
                owner.lifecycle.removeObserver(observer)
                if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onPause()
                if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStop()
                mapView.onDestroy()
            }
        }
    }

    Column(Modifier.fillMaxWidth().heightIn(max = 270.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        val mapMessage = when (basemap) {
            Basemap.Loading -> "Cargando cartografía…"
            Basemap.Ready -> null
            Basemap.Offline -> "Sin conexión cartográfica · Perímetro y lugares disponibles."
            Basemap.Basic -> "Vista básica del recinto · OpenGL ES 3 requerido para las calles."
            Basemap.Error -> "Error al cargar el mapa. El SOS sigue disponible."
        }
        (error ?: mapMessage)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (!editable) Text("La ubicación del SOS pendiente está guardada para el reintento.")
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        current?.let { location ->
            val heading = if (candidate == null && editable) "Ubicación guardada" else "Ubicación"
            val precision = location.accuracyMeters?.let { " · ±${it.roundToInt()} m" }.orEmpty()
            Text("$heading · ${location.source.label}$precision", style = MaterialTheme.typography.titleSmall)
            val nearest = config.nearestZone(location)
            Text(nearest?.let { "Zona más cercana · ${it.name}" } ?: coordinatesLabel(location),
                style = MaterialTheme.typography.bodySmall)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
            OutlinedButton(onClick = {
                if (context.granted(coarse) || context.granted(fine)) gpsRequest++ else permission.launch(arrayOf(fine, coarse))
            }, enabled = editable && !requesting, modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                contentPadding = PaddingValues(horizontal = 6.dp)) { Text("Usar mi ubicación", maxLines = 2) }
            OutlinedButton(onClick = { choose(MapEdit.Manual) }, enabled = editable && !requesting,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 6.dp)) {
                Text("Elegir punto", maxLines = 2)
            }
        }
        val pendingChoice = edit != null || candidate != null
        if (pendingChoice) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                Button(onClick = {
                    val choice = candidate ?: return@Button
                    if (actions.selectLocation(event.id, choice)) actions.navigate(Section.Home)
                }, enabled = editable && candidate != null, modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp)) { Text("Confirmar ubicación") }
                TextButton(onClick = { candidate = null; edit = null; status = null },
                    modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancelar") }
            }
        } else {
            if (BuildConfig.DEBUG && config.demoEnabled) {
                TextButton(onClick = { choose(MapEdit.Demo) }, enabled = editable && !requesting,
                    modifier = Modifier.heightIn(min = 48.dp)) { Text("Simular ubicación en el recinto") }
            }
            if (editable && sos.selectedLocation != null) {
                TextButton(onClick = { actions.selectLocation(event.id, null) },
                    modifier = Modifier.heightIn(min = 48.dp)) { Text("Quitar ubicación del SOS") }
            }
        }
    }
    if (showPlaces) {
        AlertDialog(
            onDismissRequest = { showPlaces = false },
            title = { Text("Lugares del recinto") },
            text = {
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(config.zones + config.points + config.landmarks) { place ->
                        DestinationLink(place.name, place.kindLabel + if (place.position == null) " · Sin coordenadas" else "") {
                            showPlaces = false
                            selectedPlace = place
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showPlaces = false }) { Text("Cerrar") } },
        )
    }
    selectedPlace?.let { place ->
        AlertDialog(
            onDismissRequest = { selectedPlace = null },
            title = { Text(place.name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                    Text(place.kindLabel + if (place.position?.simulated == true) " · Marcador simulado" else "")
                    place.description?.let { Text(it) }
                    place.reference?.takeIf { it != place.description }?.let { Text(it) }
                }
            },
            confirmButton = { TextButton(onClick = { selectedPlace = null }) { Text("Cerrar") } },
        )
    }
}

internal fun coordinatesLabel(location: GeoLocation) =
    String.format(Locale.ROOT, "%.5f, %.5f", location.latitude, location.longitude)
