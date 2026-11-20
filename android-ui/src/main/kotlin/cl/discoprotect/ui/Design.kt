package cl.discoprotect.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object Space {
    val xs = 4.dp
    val sm = 8.dp
    val md = 16.dp
    val lg = 24.dp
    val xl = 32.dp
    val xxl = 40.dp
}

private val palette = lightColorScheme(
    primary = Color(0xFF402078), onPrimary = Color.White,
    primaryContainer = Color(0xFFEDE3F8), onPrimaryContainer = Color(0xFF402078),
    secondary = Color(0xFF68448B), onSecondary = Color.White,
    secondaryContainer = Color(0xFFAE7EDB), onSecondaryContainer = Color(0xFF241735),
    tertiary = Color(0xFF254C81), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE9F0FA), onTertiaryContainer = Color(0xFF254C81),
    background = Color.White, onBackground = Color(0xFF241735),
    surface = Color(0xFFF7F4FA), onSurface = Color(0xFF241735),
    surfaceVariant = Color(0xFFEDE7F2), onSurfaceVariant = Color(0xFF564965),
    outline = Color(0xFF786C84), outlineVariant = Color(0xFFDDD5E5),
    error = Color(0xFF9D2435), onError = Color.White,
    errorContainer = Color(0xFFFCEBED), onErrorContainer = Color(0xFF832234),
)

private fun type(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) =
    TextStyle(fontFamily = FontFamily.SansSerif, fontSize = size.sp, lineHeight = line.sp, fontWeight = weight)

private val typography = Typography(
    headlineLarge = type(36, 42, FontWeight.Bold),
    headlineMedium = type(28, 34, FontWeight.Bold),
    headlineSmall = type(24, 30, FontWeight.Bold),
    titleMedium = type(18, 24, FontWeight.SemiBold),
    bodyLarge = type(16, 24),
    bodyMedium = type(14, 21),
    labelLarge = type(14, 20, FontWeight.SemiBold),
    labelMedium = type(12, 18, FontWeight.SemiBold),
)

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp), small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp), large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

enum class Tone { Success, Info, Warning, Error }
private data class StatusColors(val fill: Color, val ink: Color)
private val statusColors = mapOf(
    Tone.Success to StatusColors(Color(0xFFEAF5EE), Color(0xFF205638)),
    Tone.Info to StatusColors(palette.tertiaryContainer, palette.onTertiaryContainer),
    Tone.Warning to StatusColors(Color(0xFFFFF1DC), Color(0xFF774600)),
    Tone.Error to StatusColors(palette.errorContainer, palette.onErrorContainer),
)

@Composable
fun DiscoprotectTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = palette, typography = typography, shapes = shapes, content = content)
}

@Composable
fun NfcPage(
    role: String,
    brand: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Scaffold(containerColor = colors.background, contentColor = colors.onBackground) { insets ->
        Column(Modifier.fillMaxSize().padding(insets)) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Row(
                    Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(
                        horizontal = Space.lg, vertical = if (brand == null) Space.md else Space.xs,
                    ),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f).padding(end = Space.sm), contentAlignment = Alignment.CenterStart) {
                        if (brand != null) brand()
                        else Text("Discoprotect", style = MaterialTheme.typography.titleMedium, color = colors.primary)
                    }
                    Text(role, style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
                }
            }
            HorizontalDivider()
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Column(
                    Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(horizontal = Space.lg, vertical = Space.xl),
                    verticalArrangement = Arrangement.spacedBy(Space.lg),
                    content = content,
                )
            }
        }
    }
}

@Composable
fun Heading(title: String, description: String) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineLarge)
        Text(description, style = MaterialTheme.typography.bodyLarge, color = palette.onSurfaceVariant)
    }
}

@Composable
fun Status(text: String, tone: Tone) {
    val colors = statusColors.getValue(tone)
    Surface(
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        shape = MaterialTheme.shapes.small, color = colors.fill, contentColor = colors.ink,
    ) {
        Text(text, Modifier.padding(horizontal = Space.md, vertical = Space.sm), style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun NfcMark() {
    val colors = MaterialTheme.colorScheme
    Surface(shape = MaterialTheme.shapes.large, color = colors.primaryContainer, contentColor = colors.primary) {
        Canvas(Modifier.size(72.dp).padding(Space.md)) {
            val line = 2.dp.toPx()
            val center = Offset(size.width * .5f, size.height * .5f)
            drawCircle(colors.primary, radius = line, center = center)
            listOf(.19f, .32f, .45f).forEach { fraction ->
                val radius = size.width * fraction
                drawArc(colors.primary, -51f, 102f, false,
                    topLeft = Offset(center.x - radius, center.y - radius),
                    size = Size(radius * 2, radius * 2), style = Stroke(line, cap = StrokeCap.Round))
                drawArc(colors.primary, 129f, 102f, false,
                    topLeft = Offset(center.x - radius, center.y - radius),
                    size = Size(radius * 2, radius * 2), style = Stroke(line, cap = StrokeCap.Round))
            }
        }
    }
}

@Composable
fun DiagnosticsPanel(content: @Composable ColumnScope.() -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        HorizontalDivider()
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(if (expanded) "Ocultar diagnóstico NFC" else "Ver diagnóstico NFC")
        }
        if (expanded) {
            Column(Modifier.fillMaxWidth().padding(bottom = Space.sm),
                verticalArrangement = Arrangement.spacedBy(Space.sm), content = content)
        }
    }
}

@Composable
fun Field(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}
