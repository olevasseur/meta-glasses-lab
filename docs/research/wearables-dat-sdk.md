# Meta Wearables Device Access Toolkit (DAT) — research notes

- **Researched:** 2026-10-06 (subagent web research; re-verify anything marked unverified)
- **Primary sources:**
  - Full docs dump: https://wearables.developer.meta.com/llms.txt?full=true
  - Android repo + changelog: https://github.com/facebook/meta-wearables-dat-android (CHANGELOG.md)
  - iOS repo + changelog: https://github.com/facebook/meta-wearables-dat-ios (CHANGELOG.md)
  - FAQ: https://developers.meta.com/wearables/faq/

## TL;DR
- SDK **1.0.0** (2026-09-24), still labelled "developer preview". Android is the easy path (Maven Central, no token, no Xcode).
- **"Hey Meta, start {APP NAME}"** can launch a third-party app (experimental, needs approval). Free-form commands ("add this note to X") are **not** known to be forwarded; plan on: launch → app captures speech itself.
- Mic is Bluetooth HFP 8 kHz mono (beamformed). Better audio only via the beta camera-stream audio path (16/44.1/48 kHz).
- Single tap / tap-and-hold are reserved by the glasses; use **capture-button hold/double-press** or **touchpad double-tap** as triggers.

## 1. Version, install, access
- Releases: 0.7.0 (May 14) → 0.8.0 (Jun 25) → 0.9.0 (Aug 3) → **1.0.0 (Sep 24, 2026)**.
- **Android:** Maven Central, group `com.meta.wearable`, modules `mwdat-core`, `mwdat-camera`, `mwdat-display`, `mwdat-inputs`, `mwdat-motion`, `mwdat-speech`, `mwdat-mockdevice` (all 1.0.0). No GitHub Packages token anymore. Manifest needs Developer Center `APPLICATION_ID` (can be omitted / `0` in Developer Mode).
- **iOS:** SPM `https://github.com/facebook/meta-wearables-dat-ios`, Xcode 14+.
- Phone: iOS 15.2+ / Android 10+. 1.0.0 requires Meta AI app **v290** and glasses firmware **v128**.
- **Developer Mode:** Meta AI app → Settings → App Info → tap version 5× → enable Developer Mode. App registers via deep link into Meta AI app; appears under App connections → Developer mode apps. **Only one dev-mode app registered at a time.**
- Supported glasses: Ray-Ban Meta Gen 1/Gen 2, Optics, Meta Ray-Ban Display, Oakley Meta HSTN & Vanguard.

## 2. Capabilities
| Area | Details | Status |
|---|---|---|
| Camera stream | 720×1280 / 504×896 / 360×640 @ 2/7/15/24/30 fps over BT Classic; auto-degrades (floor 15 fps); heavy compression; photo grab mid-stream | Stable |
| Standalone photo | Up to ~1080p / full res | Experimental (not publishable) |
| Mic | Standard BT **HFP 8 kHz mono**, beamformed to wearer | OS-level |
| Speaker | A2DP 44.1/48 kHz; drops to 8 kHz when HFP active | OS-level |
| Stream audio | 16/44.1/48 kHz audio alongside camera stream | Beta |
| Inputs | Touchpad swipes + double-tap ("select"); capture button short/hold/double; Neural Band (Display). Single tap & tap-hold **reserved** (pause/stop session). No volume/"Back" yet. Action button not validated on all HW | Beta |
| Motion | Accel, gyro, magnetometer, orientation | Beta |
| Speech | On-glasses STT → partial + final transcripts | Beta |
| Device state | Battery, worn/donned, hinge, thermal | Stable |
| Display | Ray-Ban Display only, 600×600: layouts, text, images, buttons, icons, MP4; button callbacks | — |

## 3. Triggers / "Hey Meta"
- **Voice Invocation (experimental, 1.0.0):** "Hey Meta, start {YOUR APP NAME}" launches app even when closed. App gets a single `LaunchApp` request and must answer success/failure + optional text. Requires "Voice Invocation" permission approval in Developer Center; app name is the keyword (Meta advises no "AI" in the name). Android can detect voice launch via the launch intent.
- **Unverified:** whether trailing speech is forwarded. Assume not.
- Trigger options, ranked:
  1. Capture button hold / double-press (Inputs)
  2. Touchpad double-tap (Inputs)
  3. Phone-side wake word (Picovoice / openWakeWord) on HFP mic — works but battery + keeps 8 kHz audio
  4. Phone OS assistant (Android App Actions / iOS Shortcuts) — bypasses glasses
- **Meta AI built-ins:** Meta AI app 292.1.0 (2026-10-02) added email + calendar (Google Calendar, Outlook via App connections; Google Workspace for business accounts). **No Drive/Docs/Keep notes integration found.** "Meta AI Connectors" exist as a sign-up developer preview — mechanics/voice reachability unverified.
  - https://www.meta.com/help/ai-glasses/1468274331180316/
  - https://mixed-news.com/en/meta-ai-292-1-0-required-companion-app-glasses-email-calendar/

## 4. Samples & community projects
- Official (`samples/` in both repos): **CameraAccess** (stream/photo/video+audio/background), **DisplayAccess** (Display glasses guided content, phone/Chrome preview), **BirdSpotter** (camera+motion+inputs+voice+display, mocked, runs on simulated device).
- Community:
  - https://github.com/Intent-Lab/VisionClaw — Gemini Live + OpenClaw, iOS & Android, ~1 fps video + 16 kHz audio, on-screen button start (~2.6k★, updated 2026-09-26; Android still uses old GH Packages token)
  - https://github.com/kirill-markin/meta-glasses-ios-openai — iOS, OpenAI Realtime over HFP (last updated Jan 2026)
  - https://github.com/thorwebdev/meta-wearables-dat-android/tree/thor/gemini-live-livekit — Gemini Live via LiveKit, Android
  - https://github.com/ChunkyTofuStudios/meta_wearables — Flutter wrapper

## 5. Distribution
- Personal use: Developer Mode + adb sideload, no review. ✅
- Sharing: one private invite-only "Beta" release channel per integration (testers need Meta accounts; cap shown in Users tab — unverified number).
- App Store publishing not supported; Apple rejects (ExternalAccessory / MFi).
- Experimental/beta features (photo, stream audio, Inputs, Motion, Speech, Voice Invocation) can't ship to production channels. Store-style listings "coming soon".

## 6. Platform choice
**Android first.** No Xcode needed, token-free Gradle, adb sideload. Samples need Android Studio Narwhal (2025.1.1)+, JDK 17, SDK 36. Extra hooks (voice-launch intent, voice stream state).

## Open questions / unverified
- Does "Hey Meta" forward trailing speech?
- Meta AI Connectors mechanics & glasses voice reachability
- Release-channel tester cap; public publishing timeline
