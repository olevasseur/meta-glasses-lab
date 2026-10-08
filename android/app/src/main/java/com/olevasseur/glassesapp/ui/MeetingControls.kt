package com.olevasseur.glassesapp.ui

import com.olevasseur.glassesapp.*

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment

@Composable
fun MeetingControls(state: ConversationState, model: ConversationViewModel) {
    Text("Meeting / lecture", style = MaterialTheme.typography.titleMedium)
    if (!state.meeting) {
        OutlinedTextField(value = state.meetingTitle, onValueChange = { model.meetingOptions(title = it) },
            label = { Text("Meeting title (optional)") }, enabled = !state.stopping, singleLine = true)
        OutlinedTextField(value = state.meetingDoc, onValueChange = { model.meetingOptions(doc = it) },
            label = { Text("Append to exact Doc title / alias (optional)") }, enabled = !state.stopping, singleLine = true)
        LabeledSwitch("Phone mic on table", state.meetingPhone, !state.stopping) { model.meetingOptions(phone = it) }
        OutlinedTextField(value = state.settings.meetingWakePhrase, onValueChange = { model.meetingOptions(wakePhrase = it) },
            label = { Text("Wake phrase") }, enabled = !state.stopping, singleLine = true)
    }
    Button(onClick = { if (state.meeting) model.stopMeeting() else model.startMeeting() },
        enabled = state.settingsLoaded && state.permissions && !state.stopping) {
        Text(if (state.meeting) "Stop meeting & prepare minutes" else "Start meeting")
    }
    if (state.meeting) {
        Button(onClick = model::askMeetingAssistant, enabled = !state.stopping && !state.connected) { Text("Ask assistant") }
        Text("Recording on device. Say ‘${state.settings.meetingWakePhrase}, …’ or hold capture to open one assistant reply. Recording continues during the reply and offline.")
    }
    Text("Glasses focus on the wearer; other speakers may be faint. Phone mic is recommended for a group.",
        style = MaterialTheme.typography.bodySmall)
    if (state.meetingResult.isNotEmpty()) Text(state.meetingResult)
}
