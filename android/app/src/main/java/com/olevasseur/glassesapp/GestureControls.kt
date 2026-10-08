package com.olevasseur.glassesapp

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlin.math.roundToInt

@Composable
fun GestureControls(state: HeadGesturesState, onEnabled: (Boolean) -> Unit, onSensitivity: (Float) -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(state.enabled, onEnabled, modifier = Modifier.semantics { contentDescription = "Head gestures" })
            Text("Head gestures · confirmations")
        }
        Text("${if (state.live) "●" else "○"} ${state.status}",
            color = if (state.live) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.enabled) {
            Text("Sensitivity: ${(state.sensitivity * 100).roundToInt()}%")
            Slider(state.sensitivity, onSensitivity, modifier = Modifier.fillMaxWidth()
                .semantics { contentDescription = "Head gesture calibration sensitivity" })
            Text("Hold still briefly, then nod down and back, or shake left–right–center. Higher sensitivity needs less movement.",
                style = MaterialTheme.typography.bodySmall)
            Text("Last gesture: ${state.lastGesture}", style = MaterialTheme.typography.bodySmall)
            state.question?.let { Text("Confirmation: $it", style = MaterialTheme.typography.bodyMedium) }
        }
    }
}
