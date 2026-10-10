# Conversation recovery without amnesia

Updated 2026-10-09. No new settings or environment variables. Update the backend
before installing this APK: older backends reject the new reconnect_context field.

In Over mode, speak a note and end with “Over.” If the connection drops, the app
retains its last usable Gemini checkpoint and reuses an unexpired token. A
provider update saying the current generation cannot resume no longer erases the
previous checkpoint. Ordinary transport failures no longer throw away a valid
handle merely because three retries occurred.

An opaque policy rejection during resume gets one attempt with fresh credentials.
A second rejection abandons that handle and starts a fresh session with recent
context. Explicit expired/invalid handles also take this path. The context is a
bounded, session-local recap of the last ten user/model/app text entries (up to
1,200 characters each) and twenty compact tool outcomes. Transcript fragments
merge; interrupted replies are labelled potentially partial. Started operations
without a result remain outcome_unknown and are never automatically retried.
The new token supplies the current nickname list from the backend. The serialized
recap is capped at 24,000 characters by dropping oldest outcomes/entries as needed.

Android supplies the recap in the optional reconnect_context string on /token.
The backend JSON-quotes it and labels it untrusted historical data, never
instructions, new requests or approval, in the locked session context. No
clientContent turn is sent and setup does not request a reply. Over continues
its normal activityStart/activityEnd flow after setupComplete. A short recovery cue signals
that an interrupted reply was cut off and the connection is back. Stop or an
intentional session/persona switch clears the recap; it is never written to disk.

Names such as “small talk,” “Small-Talk,” and “smalltalk” resolve to the same
registered nickname. Other document titles match exactly apart from case.
Missing-name responses include existing nicknames, and each assistant session
starts with a JSON nickname list labelled untrusted data. Nickname edits preserve
stored display spelling and reject equivalent-name collisions; existing ambiguous
alias files must be repaired in Settings before use.

The model must issue a real tool call and receive needs_confirmation with an
approval_id before asking for action approval. Spoken function-call syntax or a
historical approval does not establish a pending action. A short capture press or
unclaimed nod gives a distinct negative tone, displays “Nothing to confirm,” and
logs the input source. Actual approvals, explicit yes/no questions, and enabled
project navigation retain precedence. Capture HOLD retains conversation control.
Feedback requires an active, unpaused assistant session; nod detection requires
head gestures enabled and available.

Validation: 299 JVM tests, zero failures/errors/skips; testDebugUnitTest and
assembleDebug passed. Backend pytest: 716 passed, three existing dependency
warnings. Regression tests model the supplied drop → two policy closes → fresh
session sequence, verify context-before-Over ordering, no repeated append, silent
session-context seeding, bounded retention, session reset, nickname equivalence/collisions,
and confirmation input precedence.

The precise provider cause of the original 1008 closes remains unconfirmed.
Read-only real Live probes succeeded with original/fresh tokens, past the
new-session token window, and after a drop during generation. Hardware retesting
is still needed. See [dated research and source URLs](../research/live-recovery-context.md).
