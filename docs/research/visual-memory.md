# Visual memory verification

Date: **2026-10-07**. Runtime AI remains Gemini. Read alongside the
[M1](m1-android-notes.md), [M2](m2-backend-notes.md), [M3](m3-memory-notes.md),
and [M4](m4-vision-notes.md) protocol evidence.

## Model, price, and image contract

- [Gemini 3.5 Flash-Lite](https://ai.google.dev/gemini-api/docs/models/gemini-3.5-flash-lite)
  lists stable `gemini-3.5-flash-lite`, image input, text output, structured output.
- [Current pricing](https://ai.google.dev/gemini-api/docs/pricing), checked today:
  standard input $0.30 / million tokens (including image), output $2.50 / million
  tokens; both available free subject to project quota. Free-tier content is used
  to improve Google's products; paid-tier content is listed as not used for that
  purpose. No fixed per-frame price or quota guarantee is inferred.
- [Image understanding](https://ai.google.dev/gemini-api/docs/image-understanding)
  documents inline image bytes; existing google-genai provides
  `Part.from_bytes(data=jpeg, mime_type="image/jpeg")`.
- [Structured output](https://ai.google.dev/gemini-api/docs/structured-output)
  supports JSON schemas. We use `response_json_schema` and validate the response
  locally with Pydantic, following the M3 real-provider finding. No files API,
  remote vector store, or embedding service is used.

## Privacy implementation

Only whole-scene downscaled thumbnails are stored; no face extraction, face crops,
face identity model, person matching, or identity fields exist. The caption prompt
restricts output to objects/places/text and permits only “a person” for people;
it excludes names from badges/identity documents and treats scene text as data.
This is an instruction to a probabilistic captioner, not a proven privacy filter.
EXIF/GPS is stripped by re-encoding before Gemini and disk storage. An optional
phone-entered coarse label is stored locally and is not sent to the captioner.

The requested Gemini captioning necessarily transmits an image to Google.
“All data local” applies to the app's durable history on the Mac, not to inference
processing or Google's policies. No long-lived API key is sent to Android.

## Real evidence

`scripts/smoke_visual_memory.py --live-setup` used three synthetic/public-in-repo
fixtures and disposable storage. Real Gemini returned a blue coffee mug/orange
caption, a two-keys/keyring caption, and readable parking text containing B2.
`find_object` found each matching record. Deleting the last hour removed all three
records and thumbnails, including the parking pin.

The real `/token` handler plus constrained WebSocket returned `setupComplete` in
Auto, PTT, and Over with all 12 tools. No Live wire change was needed; populated
config fields remain locked with `lock_additional_fields=[]` and
`sessionResumption` remains client-owned. Sources:
[ephemeral tokens](https://ai.google.dev/gemini-api/docs/ephemeral-tokens),
[Live API reference](https://ai.google.dev/api/live).

## Unverified

- Physical glasses camera/Motion/HFP coexistence, battery cost, gyro threshold,
  actual perceptual-hash quality on real scenes, foreground UI and audible chime.
- Voice selection of these tools, long sessions/resumption, accuracy on clutter,
  distant signs and private real-world scenes; only synthetic samples were used.
- Caption instructions cannot mathematically guarantee all outputs omit identity.
  No face recognition is implemented; no real people's images were tested.
- Project's future quota, billed usage, and provider-side retention/erasure.
- Keyword search misses semantic synonyms. No embeddings were added. The original
  branch had no dashboard; the integration below supersedes that limitation.

## Integration with main — 2026-10-08

Local source/history inspection confirmed main's dashboard page registry,
cookie/CSRF middleware, shared tool registry and service-owned Android lifecycle.
Visual memory now uses those structures. The dashboard displays thumbnails and
metadata by phone-local capture day, literal search, pins, single/range deletion,
persistent retention and phone-reported ON/OFF with a 60-second stale-state cutoff.
This reuses the APIs already documented in [dashboard research](dashboard-notes.md)
and [motion research](head-gestures.md); no new external API assumptions were made.
Source references remain [FastAPI templates](https://fastapi.tiangolo.com/advanced/templates/),
[Starlette responses](https://starlette.dev/responses/), and
[Gemini Live](https://ai.google.dev/api/live).

Local checks: 368 backend tests passed (including 23 visual dashboard cases),
181 Android JVM tests passed, and debug APK assembled. Shared camera serialization,
tool ownership, prompt budget, auth/CSRF, retention and deletion were tested with
synthetic data. Browser rendering, physical combined camera/Motion/Speech/HFP,
notification visibility and status timing over a real network remain unverified.
