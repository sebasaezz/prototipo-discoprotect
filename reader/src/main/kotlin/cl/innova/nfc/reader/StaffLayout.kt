package cl.innova.nfc.reader

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cl.discoprotect.ui.*

internal enum class StaffPage(val title: String, val icon: WorkspaceIconKind? = null) {
    Home("Inicio", WorkspaceIconKind.Home),
    Incidents("Incidentes", WorkspaceIconKind.Help),
    Tools("Herramientas", WorkspaceIconKind.Services),
    Profile("Perfil", WorkspaceIconKind.Profile),
    Nfc("Lector NFC"),
    Tickets("Control de entradas"),
    Venue("Recinto y zonas"),
    Search("Búsqueda autorizada"),
    Connection("Conexión y cuenta"),
    Event("Evento autorizado"),
    Diagnostics("Diagnósticos NFC"),
    Detail("Detalle del incidente"),
    Chat("Conversación");

    val main get() = icon != null
}

@Composable
internal fun StaffLayout(
    page: StaffPage,
    caseId: String?,
    open: (StaffPage) -> Unit,
    back: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Scaffold(
        modifier = Modifier.imePadding(),
        containerColor = colors.background,
        contentColor = colors.onBackground,
        topBar = {
            Surface(color = colors.background) {
                Column(Modifier.statusBarsPadding()) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = Space.md),
                        horizontalArrangement = Arrangement.spacedBy(Space.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (page.main) {
                            Box(Modifier.weight(1f)) {
                                StaffBrand(Modifier.width(160.dp).height(48.dp).semantics { heading() })
                            }
                            Text("Personal", style = MaterialTheme.typography.labelLarge)
                        } else {
                            TextButton(onClick = back, modifier = Modifier.heightIn(min = 48.dp)) { Text("‹ Volver") }
                            Text(page.title, Modifier.weight(1f).semantics { heading() },
                                style = MaterialTheme.typography.titleMedium, color = colors.primary)
                        }
                    }
                    HorizontalDivider()
                }
            }
        },
        bottomBar = {
            if (page.main) BoxWithConstraints {
                val wideLabels = maxWidth < 380.dp && LocalDensity.current.fontScale > 1.15f
                NavigationBar(containerColor = colors.surface) {
                    StaffPage.entries.filter { it.main }.forEach { destination ->
                        val width = if (!wideLabels) 1f else when (destination) {
                            StaffPage.Incidents -> 1.12f
                            StaffPage.Tools -> 1.36f
                            else -> .76f
                        }
                        NavigationBarItem(
                            selected = page == destination, onClick = { open(destination) },
                            modifier = Modifier.weight(width),
                            icon = { WorkspaceIcon(requireNotNull(destination.icon)) },
                            label = { Text(destination.title, maxLines = 2, textAlign = TextAlign.Center) },
                        )
                    }
                }
            }
        },
    ) { insets ->
        Box(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets), contentAlignment = Alignment.TopCenter) {
            key(page, caseId.takeIf { page == StaffPage.Detail || page == StaffPage.Chat }) {
                val body = if (page == StaffPage.Chat) Modifier.fillMaxHeight() else Modifier.verticalScroll(rememberScrollState())
                Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().then(body).padding(Space.md),
                    verticalArrangement = Arrangement.spacedBy(Space.md), content = content)
            }
        }
    }
}

@Composable
internal fun StaffLink(title: String, description: String? = null, prominent: Boolean = false, open: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = open, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        shape = MaterialTheme.shapes.medium,
        color = if (prominent) colors.primaryContainer else colors.surface,
        contentColor = if (prominent) colors.onPrimaryContainer else colors.onSurface,
    ) {
        Row(
            Modifier.padding(horizontal = Space.md, vertical = Space.sm),
            horizontalArrangement = Arrangement.spacedBy(Space.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                description?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
            Text("›", Modifier.clearAndSetSemantics {}, style = MaterialTheme.typography.headlineSmall)
        }
    }
}
