package com.olevasseur.glassesapp

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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = state.meetingPhone, onCheckedChange = { model.meetingOptions(phone = it) }, enabled = !state.stopping)
            Text("Phone mic on table")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = state.meetingOver, onCheckedChange = { model.meetingOptions(over = it) }, enabled = !state.stopping)
            Text("Allow ‘over’ to address assistant")
        }
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
