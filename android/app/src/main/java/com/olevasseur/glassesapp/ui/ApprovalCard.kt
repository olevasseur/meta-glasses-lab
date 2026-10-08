package com.olevasseur.glassesapp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.olevasseur.glassesapp.DeviceApproval

@Composable
fun ApprovalCard(request: DeviceApproval, approve: (String) -> Unit, deny: (String) -> Unit) {
    Card(Modifier.fillMaxWidth().testTag(UiTags.APPROVAL),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Approval needed · Tier ${request.tier}", style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite })
            // No ellipsis: even a long canonical instruction remains fully reviewable.
            Text(request.displayText, Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()))
            Text(if (!request.hardware) "Review here and tap Approve. Hardware confirmation unavailable."
                else if (request.tier == 2) "Or press the capture button. Nod cannot approve Codex."
                else "Or nod / press the capture button. Shake denies.", style = MaterialTheme.typography.bodySmall)
            Text("60-second device timeout · spoken yes cannot approve", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { approve(request.id) }, enabled = !request.deciding,
                    modifier = Modifier.testTag(UiTags.APPROVE).heightIn(min = 48.dp)) { Text("Approve") }
                OutlinedButton(onClick = { deny(request.id) }, enabled = !request.deciding,
                    modifier = Modifier.testTag(UiTags.DENY).heightIn(min = 48.dp)) { Text("Deny") }
            }
        }
    }
}
