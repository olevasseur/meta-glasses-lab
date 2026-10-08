# Diagnostics / first hardware session

Verified **2026-10-08**, America/Toronto.

- Inspected actual DAT core 1.0.0 API jar using `javap`: `Device` exposes
  `batteryLevel`, `donState`, `linkState`, compatibility, thermal/charging/hinge
  state; `DeviceSession.deviceInfo` is a StateFlow. Reused existing selected
  device metadata subscription instead of adding an extra connection.
  Source: https://repo.maven.apache.org/maven2/com/meta/wearable/mwdat-core/1.0.0/
- Actual audio routing is `AudioRecord.routedDevice` / `AudioTrack.routedDevice`;
  a preferred/selected device alone does not prove which device carries audio.
  `sampleRate` describes configured PCM; `AudioDeviceInfo.sampleRates` advertises
  capabilities rather than measuring HFP link rate. Diagnostic labels separate them.
  Sources: https://developer.android.com/reference/android/media/AudioRecord
  and https://developer.android.com/reference/android/media/AudioTrack
- htmx supports `hx-trigger="every 2s"`; the existing dashboard already loads
  pinned htmx with CSP and SRI. Poll full page, select only `#log-rows`, include
  current filter form so the query survives every replacement. No new JS needed.
  Source: https://htmx.org/docs/#polling
- Rechecked official Google Cloud setup: Google Auth platform → Branding (initial
  Get Started wizard), Audience, Data Access, Clients → Create Client → Desktop
  app. APIs are enabled from APIs & Services → Library. Actual merged backend
  scopes include `drive.file`, `documents`, `drive.metadata.readonly`; earlier M2
  README's two-scope list predates meeting folder creation. Production publishing
  before offline consent avoids the documented External Testing seven-day lifetime;
  production refresh tokens still have other expiry/revocation rules.
  Sources: https://developers.google.com/workspace/guides/configure-oauth-consent
  https://developers.google.com/workspace/guides/create-credentials
  https://developers.google.com/workspace/guides/enable-apis
  https://developers.google.com/identity/protocols/oauth2#expiration
- Real smoke: authenticated health/logs/dashboard cookie/filter HTML passed using
  disposable storage; real backend token HTTP 200 and constrained WebSocket
  `setupComplete` passed with `Authorization: Token` and minimal setup including
  `sessionResumption:{}`. No token/secret logged; no Docs writes or personal jobs.

## Unverified

- No phone attached to adb: physical HFP route/rates, SCO flag interpretation on
  modern routing, real battery/worn semantics/units, mic calibration, gyro axes,
  camera permission/preview and lifecycle audio cleanup need the hardware plan.
- Speech-energy → first received audio is an estimate, not server VAD timing or
  speaker audibility. Bluetooth/output buffering requires physical measurement.
- htmx authenticated HTML tested through ASGI; actual browser polling/rendering not
  exercised. No Cloud Console account consent or real Docs write in this task.
- Full dev_up USB flow is covered by mocked command-order/prerequisite tests only;
  no hardware install/launch ran. Journal/job startup remains normal app behavior
  when the user launches a real backend via dev_up.

Final automated validation: **187 Android JVM tests**, successful debug APK build;
**378 backend tests**, two existing dependency warnings. Added byte-budget testing
for Unicode log batches so the phone cannot wedge on the server's upload limit.
`dev_up.sh` syntax and no-device failure path checked; command-order/device denial
paths also covered with mocked tools. Full USB flow still unverified.
