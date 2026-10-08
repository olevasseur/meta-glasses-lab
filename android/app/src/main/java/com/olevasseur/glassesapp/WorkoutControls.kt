package com.olevasseur.glassesapp

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlin.math.roundToInt

@Composable
fun WorkoutControls(state: WorkoutState, onStop: () -> Unit, onSensitivity: (Float) -> Unit) {
    Text("Workout coach", style = MaterialTheme.typography.titleMedium)
    Text(state.summary)
    if (state.active) {
        if (state.motion.isNotEmpty()) Text(state.motion)
        if (state.needsMotion) {
            Text("Rep sensitivity: ${(state.sensitivity * 100).roundToInt()}%")
            Slider(state.sensitivity, onSensitivity, modifier = Modifier.semantics {
                contentDescription = "Workout rep calibration sensitivity"
            })
            Text("Higher sensitivity accepts smaller movements. Calibrate while stationary.",
                style = MaterialTheme.typography.bodySmall)
        }
        OutlinedButton(onClick = onStop) { Text("Stop workout") }
    }
}
