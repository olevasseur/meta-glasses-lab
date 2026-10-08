package com.olevasseur.glassesapp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun MemoryScreen(overview: Overview, status: String, refresh: () -> Unit, dashboard: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ScreenTitle("Memory & Notes", "Recent saved items. Manage the full history in your dashboard.")
        Text(status, style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = dashboard) { Text("Open dashboard") }
            OutlinedButton(onClick = refresh) { Text("Refresh") }
        }
        listOf("Facts" to overview.facts, "Notes" to overview.notes, "Jobs" to overview.jobs, "Meetings" to overview.meetings).forEach { (label, items) ->
            SectionCard(label) {
                if (items.isEmpty()) Text("No recent ${label.lowercase()} in this snapshot.")
                items.forEachIndexed { index, item ->
                    if (index > 0) HorizontalDivider()
                    Text(item.title, style = MaterialTheme.typography.titleSmall)
                    Text(item.detail, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
