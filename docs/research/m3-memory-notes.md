# M3 memory verification

Date: **2026-10-07**. Sources checked against current Google documentation and
installed `google-genai 2.28.0`; real calls used fake transcript data only.

## Text model and JSON

- [Gemini 3.5 Flash-Lite](https://ai.google.dev/gemini-api/docs/models/gemini-3.5-flash-lite)
  lists stable `gemini-3.5-flash-lite`, text output and structured outputs.
  [Pricing](https://ai.google.dev/gemini-api/docs/pricing) lists free-tier input and
  output. This is the summary default; project quota remains account-dependent.
- [Structured outputs](https://ai.google.dev/gemini-api/docs/structured-output)
  documents JSON schema and Pydantic. The installed
  [Python SDK](https://googleapis.github.io/python-genai/) supports
  `client.models.generate_content` with `GenerateContentConfig`.
- **Provider finding:** passing a Pydantic class with `extra="forbid"` through
  `response_schema` serialized `additional_properties` inside the API's Schema
  object. The real provider returned HTTP 400 “Unknown name additional_properties.”
  Use `response_json_schema=Summary.model_json_schema()` instead. Real Gemini
  accepted this and returned validated structured facts. Local validation keeps
  nonempty strings, length/count bounds and forbidden-extra checks.
- No embedding model/API was added; keyword overlap and existing RapidFuzz are
  sufficient for this personal local database.

## Real verification

`cd backend && uv run python ../scripts/smoke_memory.py --live-setup` succeeded.
Disposable SQLite DB, fake user statements: dog Maple, home Toronto, short spoken
answers. Gemini extracted all three durable facts; recall found Maple.

Real `/token` plus the existing constrained Live WebSocket endpoint accepted the
memory system instruction and append_to_doc/remember/recall/forget declarations:
`setupComplete` received. `Authorization: Token <name>`, minimal setup,
`lock_additional_fields=[]` and client-owned resumption were preserved. No secrets
or tokens printed. [Ephemeral-token guide](https://ai.google.dev/gemini-api/docs/ephemeral-tokens),
[Live reference](https://ai.google.dev/api/live).

## Unverified

- Spoken tool selection/confirmation, transcript completeness and Android process
  recovery on a real Pixel/glasses; hardware was not used.
- Accuracy of semantic fact updates and suppression of every paraphrase after
  forgetting. Tests verify deterministic key/text behavior, not perfect extraction.
- Multi-worker operation, large-history performance, exact prompt token counts,
  account-specific future free quotas, and full historical/privacy erasure.
- Live resumption rollover with the added prompt/tools (setup only was exercised).
