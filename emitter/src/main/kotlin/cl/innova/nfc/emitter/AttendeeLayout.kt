package cl.innova.nfc.emitter

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cl.discoprotect.ui.*

@Composable
internal fun AttendeeLayout(
    state: AttendeeState,
    actions: AttendeeController,
    showConversation: Boolean,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val main = state.section.main
    val showNav = state.draft == null && !showConversation && state.section == main
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
                        if (!showNav) {
                            TextButton(onClick = onBack, enabled = !state.sosWriting,
                                modifier = Modifier.heightIn(min = 48.dp)) { Text("‹ Volver") }
                        }
                        if (showNav) {
                            AttendeeBrand(Modifier.width(160.dp).height(48.dp).semantics { heading() })
                        } else {
                            Text(
                                if (showConversation) "Solicitud de ayuda" else state.section.label,
                                Modifier.semantics { heading() },
                                style = MaterialTheme.typography.titleMedium, color = colors.primary,
                            )
                        }
                    }
                    HorizontalDivider()
                }
            }
        },
        bottomBar = {
            if (showNav) NavigationBar(containerColor = colors.surface) {
                listOf(Section.Home, Section.Sos, Section.Services, Section.Profile).forEach { destination ->
                    NavigationBarItem(
                        selected = main == destination, onClick = { actions.navigate(destination) },
                        enabled = !state.sosWriting, alwaysShowLabel = true,
                        icon = { WorkspaceIcon(when (destination) {
                            Section.Home -> WorkspaceIconKind.Home
                            Section.Sos -> WorkspaceIconKind.Help
                            Section.Services -> WorkspaceIconKind.Services
                            else -> WorkspaceIconKind.Profile
                        }) },
                        label = { Text(destination.label, maxLines = 2) },
                    )
                }
            }
        },
    ) { inset ->
        Box(
            Modifier.fillMaxSize().padding(inset).consumeWindowInsets(inset),
            contentAlignment = Alignment.TopCenter,
        ) {
            key(state.section, state.draft?.javaClass, state.conversationId) {
                val body = Modifier.widthIn(max = 560.dp).fillMaxWidth()
                val scroll = if (showConversation) Modifier.fillMaxHeight()
                    else Modifier.verticalScroll(rememberScrollState())
                Column(
                    body.then(scroll).padding(
                        horizontal = Space.md, vertical = if (showConversation) Space.sm else Space.md,
                    ),
                    verticalArrangement = Arrangement.spacedBy(Space.md),
                    content = content,
                )
            }
        }
    }
}

@Composable
internal fun DestinationLink(
    title: String, description: String? = null, prominent: Boolean = false, onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
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
