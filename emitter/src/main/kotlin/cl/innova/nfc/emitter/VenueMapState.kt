package cl.innova.nfc.emitter

import cl.discoprotect.ui.GeoLocation
import kotlin.math.abs
import kotlin.math.hypot
import kotlinx.serialization.Serializable

@Serializable internal data class MapReply(val eventId: String, val map: VenueMap?)
@Serializable internal data class MapBoundary(val type: String, val coordinates: List<List<List<Double>>>) {
    fun valid() = type == "Polygon" && coordinates.isNotEmpty() && coordinates.all { ring ->
        ring.size >= 4 && ring.all { it.size == 2 && it[0] in -180.0..180.0 && it[1] in -90.0..90.0 } &&
            ring.first() == ring.last() && run {
                val (x0, y0) = ring.first()
                ring.zipWithNext().sumOf { (a, b) -> (a[0] - x0) * (b[1] - y0) - (b[0] - x0) * (a[1] - y0) } != 0.0
            }
    }

    fun contains(latitude: Double, longitude: Double): Boolean {
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0 || coordinates.isEmpty()) return false
        return ringPosition(coordinates.first(), longitude, latitude) != RingPosition.Outside &&
            coordinates.drop(1).none { ringPosition(it, longitude, latitude) == RingPosition.Inside }
    }
}

private enum class RingPosition { Outside, Inside, Edge }

private fun ringPosition(ring: List<List<Double>>, x: Double, y: Double): RingPosition {
    var inside = false
    for (i in 1 until ring.size) {
        val (ax, ay) = ring[i - 1]
        val (bx, by) = ring[i]
        val tolerance = 1e-10
        if (abs((x - ax) * (by - ay) - (y - ay) * (bx - ax)) <= tolerance * hypot(bx - ax, by - ay) &&
            x in minOf(ax, bx) - tolerance..maxOf(ax, bx) + tolerance &&
            y in minOf(ay, by) - tolerance..maxOf(ay, by) + tolerance
        ) return RingPosition.Edge
        if ((ay > y) != (by > y) && x < ax + (bx - ax) * (y - ay) / (by - ay)) inside = !inside
    }
    return if (inside) RingPosition.Inside else RingPosition.Outside
}

@Serializable internal data class MapPosition(val latitude: Double, val longitude: Double, val simulated: Boolean)
@Serializable internal data class MapPlace(
    val id: String,
    val name: String,
    val kind: String,
    val description: String? = null,
    val reference: String? = null,
    val position: MapPosition? = null,
) {
    val kindLabel get() = when (kind) {
        "STAGE" -> "Escenario"
        "ACCESS", "ENTRANCE" -> "Acceso"
        "EXIT" -> "Salida"
        "SERVICES", "STANDS" -> "Servicios"
        "MEDICAL" -> "Punto médico"
        "SECURITY" -> "Seguridad"
        "MEETING" -> "Punto de encuentro"
        "LANDMARK" -> "Lugar de referencia"
        else -> "Zona"
    }
}

@Serializable internal data class VenueMap(
    val name: String,
    val venue: String,
    val city: String,
    val boundary: MapBoundary,
    val demoEnabled: Boolean,
    val zones: List<MapPlace>,
    val points: List<MapPlace>,
    val landmarks: List<MapPlace> = emptyList(),
) {
    fun valid() = boundary.valid() && name.isNotBlank() && venue.isNotBlank() &&
        (zones + points + landmarks).all { place -> place.name.isNotBlank() &&
            (place.position?.let { boundary.contains(it.latitude, it.longitude) } ?: true) }
}

internal fun VenueMap.nearestZone(location: GeoLocation): MapPlace? {
    if (!boundary.contains(location.latitude, location.longitude)) return null
    val longitudeScale = kotlin.math.cos(Math.toRadians(location.latitude))
    return zones.filter { it.position != null }.minByOrNull { zone ->
        val point = requireNotNull(zone.position)
        hypot((point.longitude - location.longitude) * longitudeScale, point.latitude - location.latitude)
    }
}
