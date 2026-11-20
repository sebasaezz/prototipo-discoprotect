package cl.discoprotect.ui

import java.time.Instant
import kotlinx.serialization.Serializable

@Serializable
internal enum class GeoSource(val label: String) {
    GPS("GPS"),
    MANUAL("Manual"),
    DEMO("Simulada"),
}

@Serializable
internal data class GeoLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double? = null,
    val source: GeoSource,
    val capturedAt: String,
) {
    fun valid() = latitude in -90.0..90.0 && longitude in -180.0..180.0 &&
        (accuracyMeters == null || source == GeoSource.GPS && accuracyMeters.isFinite() && accuracyMeters >= 0) &&
        runCatching { Instant.parse(capturedAt) }.isSuccess

    fun matches(other: GeoLocation?) = other != null && runCatching {
        copy(capturedAt = Instant.ofEpochMilli(Instant.parse(capturedAt).toEpochMilli()).toString()) ==
            other.copy(capturedAt = Instant.ofEpochMilli(Instant.parse(other.capturedAt).toEpochMilli()).toString())
    }.getOrDefault(false)
}
