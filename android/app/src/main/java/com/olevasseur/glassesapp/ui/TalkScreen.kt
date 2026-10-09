package com.olevasseur.glassesapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.olevasseur.glassesapp.*

@Composable
fun TalkScreen(state: ConversationState, model: ConversationViewModel, approval: DeviceApproval?,
               backendStatus: String, overview: Overview, timers: List<String>, setup: () -> Unit) {
    val workout by model.workout.state.collectAsState()
    val list = rememberLazyListState()
    var followTranscript by remember { mutableStateOf(true) }
    // Follow new chunks only while the reader is at the end; leave older messages readable.
    LaunchedEffect(list) {
        snapshotFlow { list.isScrollInProgress to list.canScrollForward }.collect { (moving, more) ->
            if (moving) followTranscript = !more
        }
    }
    LaunchedEffect(state.transcript, state.lastTool, approval?.id) {
        if (approval == null && followTranscript)
            list.animateScrollToItem(if (state.transcript.isEmpty()) 1 else state.transcript.size)
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("the glasses app", style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(top = 12.dp).semantics { heading() })
        if (state.handsFree.armed && !state.handsFree.inputsAvailable)
            Text("Glasses controls unavailable. Use screen or notification controls.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        if (approval != null) {
            // The full approval owns the review area until resolved, with Stop still reachable.
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { ApprovalCard(approval, { model.approvals.decide(it, ApprovalMethod.SCREEN) }, model.approvals::reject) }
                item { Text("Review the exact action before approving. Other features remain available below.") }
            }
        } else {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusPill("Glasses", if (state.mock) "Mock" else if (state.glasses.contains(" · connected · ")) "Connected" else "Check setup",
                    "Glasses: ${state.glasses}")
                StatusPill("Gemini", state.gemini, "Gemini: ${state.gemini}", UiTags.GEMINI)
                StatusPill("Backend", backendStatus, "Backend: $backendStatus")
            }
            ModeSelector(state.settings.mode, !state.meeting && !state.stopping && state.settingsLoaded, model::changeMode)
        }
        Button(
            onClick = { if (state.handsFree.conversation && state.handsFree.pauses.isEmpty()) model.stop() else model.start() },
            enabled = !state.stopping && state.permissions && state.settingsLoaded,
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag(UiTags.START_STOP),
        ) {
            Text(if (state.stopping) "Stopping…" else if (state.handsFree.conversation && state.handsFree.pauses.isNotEmpty()) "Resume"
                else if (state.handsFree.conversation) "Stop" else "Start", style = MaterialTheme.typography.titleLarge)
        }
        if (approval == null) {
            if (!state.permissions) TextButton(onClick = setup) { Text("Set up microphone access") }
            if (state.handsFree.pauses.isNotEmpty() && state.handsFree.conversation)
                Text("Paused: ${state.handsFree.pauses.joinToString()}", color = MaterialTheme.colorScheme.error)
            if (!state.meeting && state.running && state.settings.mode == ConversationMode.AUTO)
                TextButton(onClick = model::toggleInput, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(if (state.handsFree.muted) "Unmute microphone" else "Mute microphone")
                }
            if (!state.meeting && state.settings.mode == ConversationMode.OVER && state.running)
                Text(state.overStatus, Modifier.testTag(UiTags.OVER), style = MaterialTheme.typography.labelLarge)
            if (state.ptt && !state.meeting) {
                HoldToTalk(state.connected && state.handsFree.shouldRun, state.talking, model::talk, model::finishTalking)
                if (state.talking) TextButton(onClick = model::finishTalking) { Text("Finish speaking") }
            }
            ActiveBanners(state, workout, overview, timers)
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            Text("Live transcript", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag(UiTags.TRANSCRIPT), state = list,
                contentPadding = PaddingValues(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (state.transcript.isEmpty()) item {
                    Text("Start, then say hello. Your conversation will appear here.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                itemsIndexed(state.transcript) { index, line ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (line.speaker == "You") Arrangement.End else Arrangement.Start) {
                        Surface(shape = MaterialTheme.shapes.large,
                            color = if (line.speaker == "You") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.fillMaxWidth(0.92f).testTag("transcript_$index")) {
                            Column(Modifier.padding(12.dp).semantics(mergeDescendants = true) {}) {
                                Text(line.speaker, style = MaterialTheme.typography.labelMedium)
                                Text(line.text)
                            }
                        }
                    }
                }
                item {
                    Column(Modifier.testTag(UiTags.LAST_TOOL)) {
                        Text("Last tool result", style = MaterialTheme.typography.labelMedium)
                        Text(state.lastTool, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusPill(name: String, value: String, description: String, tag: String = "status_${name.lowercase()}") {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.testTag(tag).semantics(mergeDescendants = true) { contentDescription = description }) {
        Text("$name · $value", Modifier.padding(horizontal = 10.dp, vertical = 8.dp), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun ModeSelector(mode: ConversationMode, enabled: Boolean, change: (ConversationMode) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ConversationMode.entries.forEach {
            FilterChip(selected = mode == it, onClick = { change(it) }, enabled = enabled,
                modifier = Modifier.heightIn(min = 48.dp).testTag("mode_${it.wireName}"),
                label = { Text(if (it == ConversationMode.PTT) "PTT" else it.label) })
        }
    }
}

@Composable
private fun ActiveBanners(state: ConversationState, workout: WorkoutState, overview: Overview, timers: List<String>) {
    val banners = buildList {
        if (state.meeting) add("● Meeting · ${if (state.connected && state.handsFree.shouldRun) "recording" else "paused / connecting"} · ${state.meetingTitle.ifBlank { "Untitled" }}")
        state.translationLanguage?.let { add("Translator · ${it.uppercase()} ↔ EN") }
        if (state.watchMode) add("● Watch mode · camera active · 2 minute limit")
        if (workout.active) add("Workout · ${workout.summary}")
        overview.project?.let { add("Cooking / DIY · $it") }
        addAll(timers)
    }
    if (banners.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        banners.forEach { text ->
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
                Text(text, Modifier.widthIn(max = 280.dp).padding(10.dp), maxLines = 3,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun HoldToTalk(enabled: Boolean, talking: Boolean, onHold: (Boolean) -> Unit, onFinish: () -> Unit) {
    val currentHold by rememberUpdatedState(onHold)
    Box(Modifier.fillMaxWidth().heightIn(min = 64.dp)
        .background(if (talking) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.shapes.medium)
        .semantics {
            role = Role.Button
            if (!enabled) disabled()
            onClick(if (talking) "Finish speaking" else "Start speaking") {
                if (enabled) { if (talking) onFinish() else currentHold(true); true } else false
            }
        }.pointerInput(enabled) {
            if (enabled) detectTapGestures(onPress = {
                currentHold(true)
                try { awaitRelease() } finally { currentHold(false) }
            })
        }, contentAlignment = Alignment.Center) {
        Text(if (!enabled) "Connect to talk" else if (talking) "Listening…" else "Hold to talk")
    }
}
