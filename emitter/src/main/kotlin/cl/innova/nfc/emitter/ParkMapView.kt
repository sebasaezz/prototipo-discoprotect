package cl.innova.nfc.emitter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import androidx.core.view.doOnLayout
import cl.discoprotect.ui.GeoLocation
import kotlinx.serialization.json.Json
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon

internal enum class Basemap { Loading, Ready, Offline, Basic, Error }

private const val parkSource = "dp-park"
private const val placeSource = "dp-places"
private const val positionSource = "dp-position"
private const val placeLayer = "dp-place-pins"
private const val offlineStyle = """{"version":8,"sources":{},"layers":[{"id":"background","type":"background","paint":{"background-color":"#F5F1FA"}}]}"""

internal class ParkMapView(
    context: Context,
    private val boundary: MapBoundary,
    private val status: (Basemap) -> Unit,
) : MapView(context, options(context)), ParkViewport {
    private val density = resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable { useFallback() }
    private var fallback = false
    private var loaded = false
    private var closed = false
    private var camera: MapLibreMap? = null
    private var boundaryBounds: LatLngBounds? = null
    private var entries = emptyMap<String, MapPlace>()
    private var position: GeoLocation? = null
    override var editing = false
    override var onPlace: (MapPlace) -> Unit = {}
    override var onPoint: (Double, Double) -> Unit = { _, _ -> }

    init {
        onCreate(null)
        addOnDidFailLoadingMapListener { if (!fallback) useFallback() else if (!loaded) status(Basemap.Error) }
        addOnDidFinishRenderingMapListener { fully ->
            if (fully && !closed && !fallback && !loaded && camera?.style?.getLayer(placeLayer) != null) {
                loaded = true
                handler.removeCallbacks(timeout)
                status(Basemap.Ready)
            }
        }
        getMapAsync { map ->
            if (closed) return@getMapAsync
            camera = map
            map.uiSettings.isLogoEnabled = false
            map.uiSettings.setAttributionGravity(Gravity.BOTTOM or Gravity.END)
            val attributionInset = (12 * density).toInt()
            map.uiSettings.setAttributionMargins(0, 0, attributionInset, attributionInset)
            map.uiSettings.isRotateGesturesEnabled = false
            map.uiSettings.isTiltGesturesEnabled = false
            map.setMaxZoomPreference(19.0)
            val bounds = boundary.coordinates.first().fold(LatLngBounds.Builder()) { builder, point ->
                builder.include(LatLng(point[1], point[0]))
            }.build()
            boundaryBounds = bounds
            map.setLatLngBoundsForCameraTarget(bounds)
            doOnLayout {
                val padding = (density * 24).toInt()
                val overview = map.getCameraForLatLngBounds(bounds, intArrayOf(padding, padding, padding, padding))
                if (overview != null) {
                    map.setMinZoomPreference(overview.zoom)
                    map.moveCamera(CameraUpdateFactory.newCameraPosition(overview))
                } else center()
            }
            map.addOnMapClickListener { at ->
                if (editing) {
                    onPoint(at.latitude, at.longitude)
                } else {
                    val pixel = map.projection.toScreenLocation(at)
                    val radius = 24 * density
                    val hits = map.queryRenderedFeatures(
                        RectF(pixel.x - radius, pixel.y - radius, pixel.x + radius, pixel.y + radius),
                        placeLayer,
                    )
                    hits.firstNotNullOfOrNull { feature -> entries[feature.id()] }?.let(onPlace)
                }
                true
            }
            handler.postDelayed(timeout, 22_000)
            map.setStyle(Style.Builder().fromUri(BuildConfig.VENUE_MAP_STYLE)) { style ->
                if (!closed && !fallback) installOverlays(style)
            }
        }
    }

    override fun onDestroy() {
        closed = true
        handler.removeCallbacks(timeout)
        super.onDestroy()
    }

    override fun center() {
        val bounds = boundaryBounds ?: return
        camera?.easeCamera(CameraUpdateFactory.newLatLngBounds(bounds, (density * 24).toInt()), 250)
    }

    override fun focus(location: GeoLocation) {
        camera?.easeCamera(CameraUpdateFactory.newLatLng(LatLng(location.latitude, location.longitude)), 250)
    }

    override fun zoom(delta: Double) { camera?.easeCamera(CameraUpdateFactory.zoomBy(delta), 200) }

    override fun render(map: VenueMap, location: GeoLocation?) {
        val markers = (map.zones.map { "zone-${it.id}" to it } +
            map.points.map { "point-${it.id}" to it } + map.landmarks.map { "landmark-${it.id}" to it })
            .filter { (_, place) -> place.position != null }.toMap()
        if (markers == entries && location == position) return
        entries = markers
        position = location
        camera?.style?.let(::updateSources)
    }

    private fun useFallback() {
        val map = camera ?: return
        if (fallback || closed) return
        fallback = true
        loaded = false
        handler.removeCallbacks(timeout)
        map.setStyle(Style.Builder().fromJson(offlineStyle)) { style ->
            if (!closed) {
                installOverlays(style)
                loaded = true
                status(Basemap.Offline)
            }
        }
    }

    private fun installOverlays(style: Style) {
        style.addSource(GeoJsonSource(parkSource, Polygon.fromJson(Json.encodeToString(MapBoundary.serializer(), boundary))))
        style.addSource(GeoJsonSource(placeSource))
        style.addSource(GeoJsonSource(positionSource))
        style.addLayer(FillLayer("dp-park-fill", parkSource)
            .withProperties(fillColor("#AE7EDB"), fillOpacity(0.15f)))
        style.addLayer(LineLayer("dp-park-outline", parkSource)
            .withProperties(lineColor("#402078"), lineWidth(3f)))
        for ((name, look) in pinStyles) style.addImage(name, pin(look.first, look.second))
        style.addLayer(SymbolLayer(placeLayer, placeSource).withProperties(
            iconImage(Expression.get("icon")), iconAllowOverlap(true), iconIgnorePlacement(true)))
        style.addLayer(SymbolLayer("dp-selected-pin", positionSource).withProperties(
            iconImage(Expression.get("icon")), iconAllowOverlap(true), iconIgnorePlacement(true)))
        updateSources(style)
    }

    private fun updateSources(style: Style) {
        val features = entries.map { (id, place) ->
            val point = requireNotNull(place.position)
            feature(point.longitude, point.latitude, markerKind(place.kind), id)
        }
        style.getSourceAs<GeoJsonSource>(placeSource)?.setGeoJson(FeatureCollection.fromFeatures(features))
        val selected = position?.let { listOf(feature(it.longitude, it.latitude, it.source.name, "selection")) }.orEmpty()
        style.getSourceAs<GeoJsonSource>(positionSource)?.setGeoJson(FeatureCollection.fromFeatures(selected))
    }

    private fun feature(lon: Double, lat: Double, icon: String, id: String) =
        Feature.fromGeometry(Point.fromLngLat(lon, lat), null, id).apply { addStringProperty("icon", icon) }

    private fun pin(letter: String, color: String): Bitmap {
        val bitmap = Bitmap.createBitmap(36, 36, Bitmap.Config.ARGB_8888).apply { density = 160 }
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.WHITE
        canvas.drawCircle(18f, 18f, 17f, paint)
        paint.color = Color.parseColor(color)
        canvas.drawCircle(18f, 18f, 14f, paint)
        paint.color = Color.WHITE
        paint.textSize = 18f
        paint.textAlign = Paint.Align.CENTER
        paint.isFakeBoldText = true
        canvas.drawText(letter, 18f, 18f - (paint.ascent() + paint.descent()) / 2f, paint)
        return bitmap
    }

    private companion object {
        fun options(context: Context): MapLibreMapOptions {
            MapLibre.getInstance(context.applicationContext)
            return MapLibreMapOptions.createFromAttributes(context).textureMode(true)
        }

    }
}
