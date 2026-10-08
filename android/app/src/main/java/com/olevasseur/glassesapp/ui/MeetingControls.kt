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
        LabeledSwitch("Allow ‘over’ to address assistant", state.meetingOver, !state.stopping) { model.meetingOptions(over = it) }
    }
    Button(onClick = { if (state.meeting) model.stopMeeting() else model.startMeeting() },
        enabled = state.settingsLoaded && state.permissions && !state.stopping) {
        Text(if (state.meeting) "Stop meeting & prepare minutes" else "Start meeting")
    }
    if (state.meeting) Text("Silent capture: say ‘assistant, …’${if (state.meetingOver) " or end your request with ‘over’" else ""}. Capture continues in the foreground service with the screen off.")
    Text("Glasses focus on the wearer; other speakers may be faint. Phone mic is recommended for a group.",
        style = MaterialTheme.typography.bodySmall)
    if (state.meetingResult.isNotEmpty()) Text(state.meetingResult)
}
