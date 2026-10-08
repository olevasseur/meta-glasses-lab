package com.olevasseur.glassesapp

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun TranslationControls(state: ConversationState, model: ConversationViewModel) {
    var selected by remember(state.translationLanguage) { mutableStateOf(state.translationLanguage ?: "es") }
    var expanded by remember { mutableStateOf(false) }
    val enabled = !state.stopping && (!state.running || state.connected)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = state.translationLanguage == null,
            onClick = { model.setTranslation(null) }, label = { Text("Assistant") }, enabled = enabled)
        FilterChip(selected = state.translationLanguage != null,
            onClick = { model.setTranslation(selected) }, label = { Text("Translate") }, enabled = enabled)
        Box {
            OutlinedButton(onClick = { expanded = true }, enabled = enabled) {
                Text(Translation.languages.getValue(selected))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                Translation.languages.forEach { (code, name) ->
                    DropdownMenuItem(text = { Text(name) }, onClick = {
                        selected = code; expanded = false
                        if (state.translationLanguage != null) model.setTranslation(code)
                    })
                }
            }
        }
    }
    if (state.translationLanguage != null) {
        Text("English ↔ ${Translation.languages.getValue(state.translationLanguage)}. Say ‘stop translation’ to return.")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = state.translationPhoneSpeaker,
                onCheckedChange = model::setTranslationSpeaker,
                enabled = enabled && Build.VERSION.SDK_INT >= 31)
            Text("Other-language translation on phone speaker")
        }
        Text(if (Build.VERSION.SDK_INT < 31) "Phone speaker option requires Android 12+."
            else if (state.translationPhoneSpeaker) "Wait for each translation to finish. Microphone pauses during phone playback; Stop cancels it."
            else "Translations play through glasses when connected.", style = MaterialTheme.typography.bodySmall)
    }
}
