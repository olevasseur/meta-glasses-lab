# Device approval integration evidence

Date: **2026-10-08**. Runtime remains Gemini; no provider/configuration migration.

- [Gemini Live reference](https://ai.google.dev/api/live), checked today: tool replies
  use functionResponses, and clientContent supports user text turns. Reused existing
  serializers; the real smoke sends pending results and app-owned approval outcomes.
- [Android expanded notifications](https://developer.android.com/develop/ui/compose/notifications/expanded),
  checked today: BigTextStyle supplies expanded text and a content intent opens the
  app. We supply complete review text and keep it plain, with private lock-screen
  visibility. Hardware approval is disabled over a conservative 4,000-character
  display threshold; that threshold is app policy, not a claim that Android displays
  every character on every phone.
- [DAT Motion at the previously verified commit](https://github.com/facebook/meta-wearables-dat-android/blob/d3159f738676221181577dd745d83d10b6ab46ba/plugins/mwdat-android/skills/motion/SKILL.md),
  checked today; integration reuses the existing single stream and on-device detector.
  Published Inputs 1.0.0 signatures and capture types remain those documented in
  [M1](m1-android-notes.md) and [hands-free background](hands-free-background.md).

Local evidence: full Python suite, Android assemble/JVM suite, backend browser/device
race tests, immutable payload/fact/meeting checks, app timer/input tests. Real
`scripts/smoke_device_approvals.py` passed Auto/PTT/Over constrained token/setup and
confirmation/result turns using temporary synthetic memory only. A generic initial
prompt yielded “please confirm” without hardware guidance; a required explicit
sentence produced the expected guidance in the final three-mode run. These are
sampled model behaviors; the hard authorization boundary is tested server/app code.

## Unverified

- Physical glasses button/Motion entitlement, false positives and device latency.
- Notification expansion/truncation and permission fallback on the actual Pixel.
- Actual Google Docs publication and Codex execution/sandbox enforcement.
- Android process death during a pending request: backend expiry is the documented
  fail-closed bound; completed-result delivery is not durable on the phone.
- A secret holder can directly call device routes; hardware attestation is not added.
