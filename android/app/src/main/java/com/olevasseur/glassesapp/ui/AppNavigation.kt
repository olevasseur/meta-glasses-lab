package com.olevasseur.glassesapp.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp

// Stable tags are shared by instrumentation and the real-API smoke scenario.
object UiTags {
    const val START_STOP = "talk_start_stop"
    const val TRANSCRIPT = "talk_transcript"
    const val LAST_TOOL = "talk_last_tool"
    const val GEMINI = "status_gemini"
    const val OVER = "talk_over"
    const val APPROVAL = "approval_card"
    const val APPROVE = "approval_approve"
    const val DENY = "approval_deny"
    const val SETUP_LATER = "setup_later"
    fun nav(destination: Destination) = "nav_${destination.name.lowercase()}"
}

enum class Destination(val label: String, val symbol: String) {
    TALK("Talk", "◉"), FEATURES("Features", "▦"), MEMORY("Memory & Notes", "▤"), SETTINGS("Settings", "⚙")
}

@Composable
fun AppNavigation(
    approvalId: String?,
    privacy: Boolean,
    capturing: Boolean,
    content: @Composable (Destination) -> Unit,
) {
    var selected by rememberSaveable { mutableStateOf(Destination.TALK) }
    LaunchedEffect(approvalId) { if (approvalId != null) selected = Destination.TALK }
    BackHandler(selected != Destination.TALK) { selected = Destination.TALK }
    Scaffold(
        bottomBar = {
            NavigationBar(Modifier.semantics { isTraversalGroup = true; traversalIndex = 1f }) {
                Destination.entries.forEach { destination ->
                    NavigationBarItem(selected = selected == destination, onClick = { selected = destination },
                        modifier = Modifier.testTag(UiTags.nav(destination)).heightIn(min = 64.dp),
                        icon = { Text(destination.symbol, Modifier.clearAndSetSemantics {}) },
                        label = { Text(destination.label, maxLines = 2) })
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
            .semantics { isTraversalGroup = true; traversalIndex = 0f }) {
            if (privacy) PrivacyIndicator(capturing)
            content(selected)
        }
    }
}

@Composable
fun PrivacyIndicator(capturing: Boolean) {
    Surface(color = MaterialTheme.colorScheme.errorContainer) {
        Text(if (capturing) "● Visual memory ON · capturing" else "● Visual memory ON · waiting for capture",
            Modifier.fillMaxWidth().padding(12.dp).testTag("privacy_indicator")
                .semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun ScreenTitle(title: String, subtitle: String) {
    Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun SectionCard(title: String, example: String? = null, content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            content()
            example?.let { Text("Try saying “$it”", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary) }
        }
    }
}
