package com.olevasseur.glassesapp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.olevasseur.glassesapp.*

@Composable
fun FeaturesScreen(state: ConversationState, model: ConversationViewModel, overview: Overview, timers: List<String>, dashboard: () -> Unit) {
    val workout by model.workout.state.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ScreenTitle("Features", "Ask naturally in Talk, or capture reading notes silently.")
        SilentPageSettings(state, model)
        SectionCard("Vision", "What am I looking at?") {
            LabeledSwitch("Watch mode · 2 minute limit", state.watchMode,
                !state.meeting && state.translationLanguage == null && state.connected && state.handsFree.shouldRun, model::setWatchMode)
            Text(state.camera, style = MaterialTheme.typography.bodySmall)
            Text("A single look captures on demand. Watch checks the scene periodically.")
        }
        SectionCard("Visual memory", "Remember where I put my keys") {
            VisualMemoryControls(state, model)
        }
        SectionCard("Translator", "Translate Spanish") { TranslationControls(state, model) }
        SectionCard("Meeting", "Start a meeting called Design review") { MeetingControls(state, model) }
        SectionCard("Cooking / DIY", "Start a pasta recipe, then set a pasta timer for 8 minutes") {
            Text(overview.project ?: "No active project. Ask to start a recipe or DIY project.")
            timers.forEach { Text(it) }
            Text("Say next step, repeat that, double the recipe, or cancel the pasta timer.")
            LabeledSwitch("Nod advances steps", state.settings.coachNodNavigation, !state.running && !state.stopping && state.settingsLoaded) {
                model.setSettings(state.settings.copy(coachNodNavigation = it))
            }
            TextButton(onClick = { CoachAlarm.openAlarms(model.getApplication()) }) { Text("Allow precise timers") }
        }
        SectionCard("Workout", "Start 12 squats for 3 sets") {
            WorkoutControls(workout, { model.workout.stop() }, model::setWorkoutSensitivity)
        }
        SectionCard("Read-to-me", "Read this menu") {
            Text("Use your voice to choose verbatim reading, a summary, English translation, or a question about the page.")
            Text("Say continue, repeat, next page, or stop reading. Hold still for a clear capture.")
        }
        SectionCard("Jobs", "How are my jobs doing?") {
            Text("Ask for research or a Codex task by voice. Review the exact request in Talk before approving.")
            overview.jobs.forEach { Text("${it.title}\n${it.detail}") }
            OutlinedButton(onClick = dashboard) { Text("Review jobs in dashboard") }
        }
    }
}
