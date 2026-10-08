package com.olevasseur.glassesapp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
fun ConversationSettingsControls(settings: ConversationSettings, enabled: Boolean, onChange: (ConversationSettings) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ConversationMode.entries.forEach { mode ->
            FilterChip(selected = settings.mode == mode, onClick = { onChange(settings.copy(mode = mode)) },
                label = { Text(mode.label) }, enabled = enabled)
        }
    }
    when (settings.mode) {
        ConversationMode.AUTO -> {
            Text("Patience: ${settings.silenceMs} ms of silence")
            Slider(value = settings.silenceMs.toFloat(), onValueChange = { onChange(settings.copy(silenceMs = it.roundToInt())) },
                valueRange = 500f..5000f, steps = 17, enabled = enabled)
        }
        ConversationMode.PTT -> Text("Hold on screen to speak. On glasses, hold capture once to start, again to finish.")
        ConversationMode.OVER -> {
            Text("End your turn with:")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ConversationSettings.KEYWORDS.forEach { keyword ->
                    FilterChip(selected = settings.keyword == keyword, onClick = { onChange(settings.copy(keyword = keyword)) },
                        label = { Text(keyword) }, enabled = enabled)
                }
            }
            Text("Pause as long as you like. Say ‘over and out’ to stop the session.")
            Text("Safety limit: ${settings.safetySeconds} seconds of continuous speech")
            Slider(value = settings.safetySeconds.toFloat(), onValueChange = { onChange(settings.copy(safetySeconds = it.roundToInt())) },
                valueRange = 10f..120f, steps = 21, enabled = enabled)
        }
    }
}
