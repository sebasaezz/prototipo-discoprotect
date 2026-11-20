package cl.discoprotect.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

internal enum class WorkspaceIconKind { Home, Help, Services, Profile }

@Composable
internal fun WorkspaceIcon(destination: WorkspaceIconKind) {
    val ink = LocalContentColor.current
    Canvas(Modifier.size(23.dp)) {
        val step = size.width / 24f
        val outline = Stroke(width = 1.8f * step, cap = StrokeCap.Round)
        fun point(x: Float, y: Float) = Offset(x * step, y * step)
        fun line(x: Float, y: Float, endX: Float, endY: Float) {
            drawLine(ink, point(x, y), point(endX, endY), outline.width, cap = StrokeCap.Round)
        }

        when (destination) {
            WorkspaceIconKind.Home -> drawPath(Path().apply {
                moveTo(3 * step, 11 * step)
                lineTo(12 * step, 3 * step)
                lineTo(21 * step, 11 * step)
                lineTo(19 * step, 11 * step)
                lineTo(19 * step, 21 * step)
                lineTo(14 * step, 21 * step)
                lineTo(14 * step, 15 * step)
                lineTo(10 * step, 15 * step)
                lineTo(10 * step, 21 * step)
                lineTo(5 * step, 21 * step)
                lineTo(5 * step, 11 * step)
                close()
            }, ink, style = outline)
            WorkspaceIconKind.Help -> {
                drawCircle(ink, 9 * step, point(12f, 12f), style = outline)
                line(12f, 8f, 12f, 16f)
                line(8f, 12f, 16f, 12f)
            }
            WorkspaceIconKind.Services -> {
                for (row in 0..1) for (column in 0..1) {
                    drawRect(ink, point(4f + column * 9f, 4f + row * 9f),
                        Size(7 * step, 7 * step), style = outline)
                }
            }
            WorkspaceIconKind.Profile -> {
                drawCircle(ink, 3.6f * step, point(12f, 7f), style = outline)
                drawArc(ink, 180f, 180f, false, point(4f, 14f),
                    Size(16 * step, 12 * step), style = outline)
            }
        }
    }
}
