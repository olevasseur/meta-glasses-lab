package com.olevasseur.glassesapp

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.olevasseur.glassesapp.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Offline UI checks: no service, DAT, credentials or Gemini calls. */
@RunWith(AndroidJUnit4::class)
class AppUiTest {
    @get:Rule val ui = createComposeRule()

    @Test fun navigationAndNewApprovalReturnToTalkAndKeepPrivacyVisible() {
        var approval by mutableStateOf<String?>(null)
        ui.setContent {
            GlassesTheme {
                AppNavigation(approval, privacy = true, capturing = true) { Text("Screen: ${it.label}") }
            }
        }
        Destination.entries.forEach { destination ->
            ui.onNodeWithTag(UiTags.nav(destination)).performClick().assertIsSelected()
            ui.onNodeWithText("Screen: ${destination.label}").assertIsDisplayed()
            ui.onNodeWithTag("privacy_indicator").assertIsDisplayed()
        }
        ui.runOnIdle { approval = "new-request" }
        ui.onNodeWithTag(UiTags.nav(Destination.TALK)).assertIsSelected()
        ui.onNodeWithText("Screen: Talk").assertIsDisplayed()
    }

    @Test fun approvalShowsTierExactActionAndBindsDecisionToDisplayedId() {
        val request = DeviceApproval("request-a", 1, "Forget fact", "Exact fact: My dog is Maple", "hash", hardware = true)
        var approved: String? = null
        var denied: String? = null
        ui.setContent { GlassesTheme { ApprovalCard(request, { approved = it }, { denied = it }) } }
        ui.onNodeWithText("Approval needed · Tier 1").assertIsDisplayed()
        ui.onNodeWithText(request.displayText).assertIsDisplayed()
        ui.onNodeWithText("Or nod / press the capture button. Shake denies.").assertIsDisplayed()
        ui.onNodeWithTag(UiTags.APPROVE).performClick()
        ui.onNodeWithTag(UiTags.DENY).performClick()
        ui.runOnIdle { assertEquals("request-a", approved); assertEquals("request-a", denied) }
    }

    @Test fun codexNeverOffersNodAndPendingSubmissionDisablesBothButtons() {
        var request by mutableStateOf(DeviceApproval("request-b", 2, "Codex task", "repo: /example\ninstruction: fix tests", "hash", hardware = true))
        ui.setContent { GlassesTheme { ApprovalCard(request, {}, {}) } }
        ui.onNodeWithText("Or press the capture button. Nod cannot approve Codex.").assertIsDisplayed()
        ui.runOnIdle { request = request.copy(deciding = true) }
        ui.onNodeWithTag(UiTags.APPROVE).assertIsNotEnabled()
        ui.onNodeWithTag(UiTags.DENY).assertIsNotEnabled()
    }

    @Test fun unavailableHardwareRequiresScreenAndLongReviewCanScroll() {
        val details = (1..80).joinToString("\n") { "Exact instruction line $it" }
        val request = DeviceApproval("request-c", 1, "Review", details, "hash")
        var approved = false
        ui.setContent { GlassesTheme { ApprovalCard(request, { approved = true }, {}) } }
        ui.onNodeWithText(request.displayText).assertExists().performTouchInput { swipeUp() }
        ui.onNodeWithText("Review here and tap Approve. Hardware confirmation unavailable.").assertIsDisplayed()
        ui.onNodeWithTag(UiTags.APPROVE).assertIsDisplayed().performClick()
        ui.runOnIdle { assertTrue(approved) }
    }

    @Test fun modeChipsRequestOneSwitchAndReflectSelection() {
        var mode by mutableStateOf(ConversationMode.AUTO)
        ui.setContent { GlassesTheme { ModeSelector(mode, true) { mode = it } } }
        ui.onNodeWithTag("mode_over").performClick().assertIsSelected()
        ui.runOnIdle { assertEquals(ConversationMode.OVER, mode) }
        ui.onNodeWithTag("mode_ptt").performClick().assertIsSelected()
    }
}
