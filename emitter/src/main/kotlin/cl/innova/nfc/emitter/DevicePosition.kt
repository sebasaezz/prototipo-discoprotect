package cl.innova.nfc.emitter

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.os.CancellationSignal
import cl.discoprotect.ui.GeoLocation
import cl.discoprotect.ui.GeoSource
import java.time.Instant
import kotlin.coroutines.resume
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

internal sealed interface PositionResult {
    data class Found(val location: GeoLocation, val approximate: Boolean) : PositionResult
    enum class Unavailable(val label: String) : PositionResult {
        Denied("Permiso de ubicación denegado. Puedes elegir un punto manual."),
        Disabled("La ubicación del dispositivo está desactivada."),
        NoSignal("Sin señal de ubicación. Puedes elegir un punto manual."),
        Timeout("Se agotó el tiempo para obtener tu ubicación."),
    }
}

internal fun Context.granted(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

@SuppressLint("MissingPermission")
internal suspend fun currentPosition(context: Context): PositionResult {
    val precise = context.granted(Manifest.permission.ACCESS_FINE_LOCATION)
    if (!precise && !context.granted(Manifest.permission.ACCESS_COARSE_LOCATION)) return PositionResult.Unavailable.Denied
    val manager = context.getSystemService(LocationManager::class.java)
    if (!LocationManagerCompat.isLocationEnabled(manager)) return PositionResult.Unavailable.Disabled
    val providers = listOf("fused", LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        .filter { provider -> runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false) }
    if (providers.isEmpty()) return PositionResult.Unavailable.NoSignal
    fun Location.result() = PositionResult.Found(
        GeoLocation(latitude, longitude, if (hasAccuracy()) accuracy.toDouble() else null,
            GeoSource.GPS, Instant.ofEpochMilli(time).toString()),
        approximate = !precise,
    )
    try {
        val cached = providers.mapNotNull { provider ->
            // An enabled provider can still require a higher permission level on older Android versions.
            try {
                manager.getLastKnownLocation(provider)
            } catch (_: SecurityException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        }
            .filter { (SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos) in 0..30_000_000_000L }
            .minByOrNull { if (it.hasAccuracy()) it.accuracy else Float.MAX_VALUE }
        if (cached != null) return cached.result()
        return withTimeoutOrNull(15_000) {
            coroutineScope {
                val fixes = Channel<PositionResult.Found?>(providers.size)
                val requests = providers.map { provider ->
                    launch {
                        val fix = try {
                            providerFix(context, manager, provider)
                        } catch (_: SecurityException) {
                            null
                        } catch (_: IllegalArgumentException) {
                            null
                        }
                        fixes.send(fix?.result())
                    }
                }
                try {
                    repeat(providers.size) {
                        val fix = fixes.receive()
                        if (fix != null) return@coroutineScope fix
                    }
                    PositionResult.Unavailable.NoSignal
                } finally {
                    requests.forEach { it.cancel() }
                }
            }
        } ?: PositionResult.Unavailable.Timeout
    } catch (_: SecurityException) {
        return PositionResult.Unavailable.Denied
    }
}

@SuppressLint("MissingPermission")
private suspend fun providerFix(context: Context, manager: LocationManager, provider: String): Location? =
    suspendCancellableCoroutine { continuation ->
        val cancellation = CancellationSignal()
        continuation.invokeOnCancellation { cancellation.cancel() }
        LocationManagerCompat.getCurrentLocation(manager, provider, cancellation, ContextCompat.getMainExecutor(context)) { result ->
            if (continuation.isActive) continuation.resume(result)
        }
    }
