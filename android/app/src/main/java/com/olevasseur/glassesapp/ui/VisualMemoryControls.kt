package com.olevasseur.glassesapp.ui

import com.olevasseur.glassesapp.*

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp

@Composable
fun VisualMemoryControls(state: ConversationState, model: ConversationViewModel) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = state.visualMemory, onCheckedChange = model::setVisualMemory, enabled = state.canCaptureVisual,
            modifier = Modifier.semantics { contentDescription = "Visual memory" })
        Text("Visual memory · 30 minutes", Modifier.padding(start = 8.dp))
    }
    Text("Frames go to Gemini for captions; history stays on your Mac. No people identification.", style = MaterialTheme.typography.bodySmall)
    var shareLocation by remember { mutableStateOf(model.visualLocation != null) }
    var location by remember { mutableStateOf(model.visualLocation ?: "") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = shareLocation, modifier = Modifier.semantics { contentDescription = "Attach a coarse place label" }, onCheckedChange = {
            shareLocation = it; model.setVisualLocation(if (it) location else null)
        })
        Text("Attach a coarse place label")
    }
    if (shareLocation) OutlinedTextField(value = location, onValueChange = {
        location = it.take(100); model.setVisualLocation(location)
    }, label = { Text("Place name, e.g. home (no GPS)") }, singleLine = true)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = model::rememberVisual, enabled = state.canCaptureVisual) { Text("Remember this") }
        OutlinedButton(onClick = model::deleteVisualHour) { Text("Delete last hour") }
    }
}
