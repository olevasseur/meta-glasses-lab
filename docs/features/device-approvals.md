# Hands-free device approvals

Implemented and verified **2026-10-08** on `feat/secreview`, following decision 20.
Gemini remains the runtime assistant. Approval authority belongs to Android's
physical-input/card callbacks or the authenticated dashboard, never the model.

## Voice and buttons

- **Tier 0:** “Remember my dog is Maple,” “Add this to Work,” recall, timers,
  workout, look/read, cooking navigation and translation continue immediately.
  Notes must resolve to an existing Doc/Inbox. Ambiguous titles return candidates;
  choose a title by voice. Unmatched titles do not create arbitrary documents.
- **Tier 1:** forget, research, journal replacement, visual-memory deletion,
  creation of Inbox, export of a meeting to a new document, and job cancellation
  show a card and phone notification. Gemini asks you to **nod or press capture**.
  **Approve** on the card also works. **Shake**, **Deny**, or 60 seconds without
  approval rejects the request. On-demand `write_journal` is a per-day replacement
  operation and always uses this tier, including generating that day's first entry.
- **Tier 2:** “Ask Codex to fix the tests in repo-name.” Review the canonical repo
  path and complete instruction in the phone notification/card, then **press
  capture or tap Approve**. A nod never approves Codex. It is consumed without
  triggering cooking navigation or answering an unrelated model question.
- **Dashboard fallback:** sign in at `/dashboard`, open **Approvals**, review the
  stored action and click Approve/Reject. Android polls the result while its card
  is pending. Browser nonces remain session-bound, CSRF-protected and single-use.

Enable **Head gestures** and wait for live Motion for nod/shake. Existing detector
calibration, disabled-during-workout behavior and the shared DAT session remain.
Any DAT capture-button event takes priority over capture HOLD's normal conversation
start/stop behavior while an approval is pending; unrelated touchpad events cannot
approve. The app does not speak the request itself or use TTS. Gemini receives the
initial pending tool response and later an app-generated approval outcome/result.
Failed WebSocket delivery is retained in memory for a reconnect/turn boundary.

Only one device card is active. Additional concurrent requests are denied instead
of replacing the action under a finger or queueing actions behind a single nod.
Replayed approval IDs do not rearm the timer. Screen taps are bound to the displayed
ID. A head movement started before displaying the card cannot carry into the new
decision: the detector resets when the snapshot arrives.

## API and security boundary

A proposal returns `needs_confirmation`, `approval_id`, `summary`, `details`,
`tier`, `allowed_methods`, `payload_hash` and `approval_url`. Preparation resolves
facts/doc IDs/canonical repo and freezes the action before returning the ID.
A SHA-256 digest covers the action name, execution args and displayed details.
Meetings additionally compare the reviewed title/destination/minutes with storage;
relative journal dates are resolved before confirmation.

```text
GET  /approvals/{id}          # authenticated snapshot or completed result
POST /approvals/{id}/device   {"method":"nod|button|screen"}
POST /approvals/{id}/deny     {}
```

All three routes require `X-App-Secret`. Only the three exact method strings are
accepted; extra decision fields fail validation. Tier 2 rejects nod at the backend
as well as on Android. The app fetches the authoritative snapshot before displaying
it; it never obtains decision methods or action payloads from model arguments.
`/tools/{name}` accepts registered tool names only. `confirm=true`, old job/fact
confirmation tokens or an injected `approval_id` cannot execute a protected action.
Old compatible argument fields are ignored when preparing a fresh proposal; unknown
fields are rejected. There is no approval tool in the registry.

The server atomically consumes an approval before awaiting any side effect. Browser
and device races have one winner; errors do not restore it. Requests expire after
five minutes, on restart or secret rotation. Up to 32 pending approvals and 32
short-lived completed results are retained; existing request/job/size limits remain.
Android normally denies after 60 seconds. If the app dies or cannot reach the
backend, the server's five-minute expiry remains the final bound. Never retry an
uncertain write automatically; inspect its destination.

Meeting finalization retains its phone outbox while approval is pending. Existing
resolved destinations export without approval. Declining a new export durably stops
its automatic export attempts; transcript/minutes stay on the backend. There is
currently no phone button to reopen a declined meeting export.

## Configuration, limits and evidence

No new environment settings or dependencies. Existing `APP_SHARED_SECRET`, backend
address, exact allowed hosts, DAT registration/Motion capability and notification
permission apply. The owner/app secret is trusted; the backend does not remotely
attest that a physical sensor event occurred. The security boundary separates
model tool access from app-owned HTTP calls.

If notifications are disabled or the complete review exceeds 4,000 characters,
hardware approval is disabled for that card; open the full on-screen card or use
the dashboard. The complete text is supplied to BigTextStyle, but Android controls
expansion, lock-screen privacy and truncation. Device rendering must be checked on
the actual phone before relying on its presentation. Actual glasses nod accuracy,
button entitlement/latency and notification display are not verified by JVM tests.

Full backend suite: **443 passed**. Android assemble passes; **194 JVM tests pass**, with zero failures, errors or skips. New cases cover tier policy, secret/body
validation, registry isolation, snapshot tampering, browser/device races, expiry,
repeat submission, no auto-rearm, timeout/shake, stale taps, notification fallback,
capture priority, meeting outbox retention and durable export denial.

`scripts/smoke_device_approvals.py` verified real `/token` and constrained Live
setup in **Auto, PTT and Over**, then actual Gemini spoken hardware confirmation
and app-result acknowledgment without tool replay: synthetic forget approved via
nod (Auto), denied (PTT), approved via button (Over). Early probes found a vague
“please confirm” response; explicit prompt wording fixed it in the final run.
The script uses an isolated temporary memory DB and does not start the personal app
lifespan. No real Google writes, Codex execution, device sensors or Android UI ran.
`lock_additional_fields=[]` and client-owned `sessionResumption` are unchanged.

Sources checked **2026-10-08**: [Gemini Live wire reference](https://ai.google.dev/api/live),
[Android expanded notifications](https://developer.android.com/develop/ui/compose/notifications/expanded),
[DAT Motion documentation at the verified SDK commit](https://github.com/facebook/meta-wearables-dat-android/blob/d3159f738676221181577dd745d83d10b6ab46ba/plugins/mwdat-android/skills/motion/SKILL.md).
See [research evidence and unverified items](../research/device-approvals.md).

## Combined merge verification

Integrated with main on **2026-10-08**. Diagnostics and approvals share the existing
client/backend routes and service lifecycle. A new approval opens the conversation
card from Diagnostics. Pending approvals suppress job/workout/meeting announcements;
approval outcomes wait for playback and use the existing Over-mode turn cleanup.
Settings remain in main's DataStore, and all 42 assistant tools remain in its registry.
The tuned prompt retains every feature while replacing model-held confirmation
instructions with device approval. `lock_additional_fields=[]` and client-owned
`sessionResumption` are unchanged. Both decision rows and every research entry remain.

Backend `uv run pytest`: **473 passed, 3 dependency warnings in 6.24s**.
Android `assembleDebug testDebugUnitTest`: **BUILD SUCCESSFUL in 22s**,
**45 tasks executed**, **216 JVM tests, zero failures/errors/skips**. Builds used
JDK 17, the installed SDK and writable temporary caches. Voice-evaluation fixtures
now exercise real approval proposals/consumption with isolated state and simulated
external effects. Assertions were adapted to the merged contract, not weakened.
No credentialed Gemini/Docs, emulator or physical-device checks ran during this merge.
