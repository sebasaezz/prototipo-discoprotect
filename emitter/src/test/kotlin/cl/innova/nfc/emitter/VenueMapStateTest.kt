package cl.innova.nfc.emitter

import org.junit.Assert.*
import org.junit.Test

class VenueMapStateTest {
    @Test fun `selection uses a concave longitude latitude polygon and includes edges`() {
        val polygon = MapBoundary("Polygon", listOf(listOf(
            listOf(-70.66, -33.47), listOf(-70.65, -33.47), listOf(-70.65, -33.46),
            listOf(-70.655, -33.465), listOf(-70.66, -33.46), listOf(-70.66, -33.47),
        )))
        assertTrue(polygon.valid())
        assertTrue(polygon.contains(-33.468, -70.655))
        assertTrue(polygon.contains(-33.465, -70.655))
        assertFalse(polygon.contains(-33.461, -70.655))
        assertFalse(polygon.contains(-33.461, Double.NaN))
        assertFalse(polygon.copy(coordinates = listOf(polygon.coordinates.first().dropLast(1))).valid())
    }
}
