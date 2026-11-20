package cl.innova.nfc.emitter

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import cl.discoprotect.ui.GeoLocation
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.pow

internal interface ParkViewport {
    var editing: Boolean
    var onPlace: (MapPlace) -> Unit
    var onPoint: (Double, Double) -> Unit
    fun render(map: VenueMap, location: GeoLocation?)
    fun center()
    fun focus(location: GeoLocation)
    fun zoom(delta: Double)
}

internal val pinStyles = mapOf(
    "STAGE" to ("E" to "#402078"),
    "ACCESS" to ("A" to "#6B397D"),
    "SERVICES" to ("S" to "#514068"),
    "MEDICAL" to ("+" to "#A43A55"),
    "SECURITY" to ("P" to "#263969"),
    "MEETING" to ("M" to "#63427D"),
    "LANDMARK" to ("R" to "#4A6760"),
    "OTHER" to ("Z" to "#574574"),
    "GPS" to ("G" to "#226298"),
    "MANUAL" to ("X" to "#402078"),
    "DEMO" to ("D" to "#8D3A82"),
)

internal fun markerKind(kind: String) = when (kind) {
    "ENTRANCE", "EXIT" -> "ACCESS"
    "STANDS" -> "SERVICES"
    in pinStyles -> kind
    else -> "OTHER"
}

// Keeps the real perimeter and the venue catalog usable on devices without an ES 3 renderer.
internal class ParkOutlineView(context: Context, private val boundary: MapBoundary) : View(context), ParkViewport {
    private val outline = boundary.coordinates.first()
    private val west = outline.minOf { it[0] }
    private val east = outline.maxOf { it[0] }
    private val south = outline.minOf { it[1] }
    private val north = outline.maxOf { it[1] }
    private val longitudeScale = cos(Math.toRadians((north + south) / 2))
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var latitude = (north + south) / 2
    private var longitude = (east + west) / 2
    private var magnification = 1.0
    private var places = emptyList<MapPlace>()
    private var selection: GeoLocation? = null
    private val scale get() = magnification * min(
        (width - 36 * density).coerceAtLeast(1f) / ((east - west) * longitudeScale),
        (height - 36 * density).coerceAtLeast(1f) / (north - south),
    )

    override var editing = false
    override var onPlace: (MapPlace) -> Unit = {}
    override var onPoint: (Double, Double) -> Unit = { _, _ -> }

    private val gesture = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent) = true

        override fun onScroll(start: MotionEvent?, end: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (!pinch.isInProgress) {
                longitude = (longitude + distanceX / (scale * longitudeScale)).coerceIn(west, east)
                latitude = (latitude - distanceY / scale).coerceIn(south, north)
                invalidate()
            }
            return true
        }

        override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
            performClick()
            if (editing) onPoint(latitude - (event.y - height / 2f) / scale,
                longitude + (event.x - width / 2f) / (scale * longitudeScale))
            else places.filter { it.position != null }.minByOrNull { place ->
                val position = requireNotNull(place.position)
                hypot(event.x - x(position.longitude), event.y - y(position.latitude))
            }?.let { place ->
                val position = requireNotNull(place.position)
                if (hypot(event.x - x(position.longitude), event.y - y(position.latitude)) <= 24 * density) onPlace(place)
            }
            return true
        }

        override fun onDoubleTap(event: MotionEvent): Boolean {
            zoom(1.0)
            return true
        }
    })
    private val pinch = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            setZoom(magnification * detector.scaleFactor)
            return true
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        pinch.onTouchEvent(event)
        gesture.onTouchEvent(event)
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun center() {
        latitude = (north + south) / 2
        longitude = (east + west) / 2
        setZoom(1.0)
    }

    override fun focus(location: GeoLocation) {
        latitude = location.latitude.coerceIn(south, north)
        longitude = location.longitude.coerceIn(west, east)
        invalidate()
    }

    override fun zoom(delta: Double) = setZoom(magnification * 2.0.pow(delta))

    private fun setZoom(value: Double) {
        magnification = value.coerceIn(1.0, 16.0)
        invalidate()
    }

    override fun render(map: VenueMap, location: GeoLocation?) {
        places = map.zones + map.points + map.landmarks
        selection = location
        invalidate()
    }

    private fun x(lon: Double) = (width / 2 + (lon - longitude) * scale * longitudeScale).toFloat()
    private fun y(lat: Double) = (height / 2 + (latitude - lat) * scale).toFloat()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.parseColor("#F5F1FA"))
        path.reset()
        path.fillType = Path.FillType.EVEN_ODD
        for (ring in boundary.coordinates) {
            path.moveTo(x(ring[0][0]), y(ring[0][1]))
            for ((lon, lat) in ring.drop(1)) path.lineTo(x(lon), y(lat))
            path.close()
        }
        paint.style = Paint.Style.FILL
        paint.color = Color.parseColor("#E8DAF5")
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2 * density
        paint.color = Color.parseColor("#402078")
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.FILL
        for (place in places) place.position?.let {
            drawPin(canvas, x(it.longitude), y(it.latitude), markerKind(place.kind))
        }
        selection?.let { drawPin(canvas, x(it.longitude), y(it.latitude), it.source.name) }
    }

    private fun drawPin(canvas: Canvas, x: Float, y: Float, kind: String) {
        val (letter, color) = pinStyles.getValue(kind)
        paint.color = Color.WHITE
        canvas.drawCircle(x, y, 14 * density, paint)
        paint.color = Color.parseColor(color)
        canvas.drawCircle(x, y, 12 * density, paint)
        paint.color = Color.WHITE
        paint.textSize = 13 * density
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(letter, x, y - (paint.ascent() + paint.descent()) / 2, paint)
    }
}
