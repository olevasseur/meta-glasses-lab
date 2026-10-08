# Gemini free tier and developer credits

Checked **2026-10-08**, America/Toronto. Runtime AI remains Gemini. This is a
capacity estimate, not a billing guarantee. Read with [usage meter](../features/usage-meter.md).

## What can currently be established

Google's [rate-limit documentation](https://ai.google.dev/gemini-api/docs/rate-limits)
now sends readers to the signed-in project’s AI Studio limits. Limits apply to the
**project**, across keys, and daily request quotas reset at Pacific midnight.
RPM, input TPM, RPD and sometimes TPD are separate dimensions; capacity is not
guaranteed. A model's context-window size is not its daily allowance.

The following is intentionally an unknown-limit table, not a recycled historical
quota table. `U` means project-specific and unverified; `—` means not applicable.
No current public source established a universal Live minutes/day allowance.

| Model / application | Free input/output listed | RPM | Concurrent Live sessions | Live minutes/day | Input TPM | TPD | RPD |
| --- | --- | --- | --- | --- | --- | --- | --- |
| gemini-3.8-live / primary conversation, meeting, vision | Yes | U | U | U | U | U | U |
| gemini-3.1-flash-live-preview / configured fallback | Yes | U | U | U | U | U | U |
| gemini-3.5-flash-lite / summaries, captions, journal, meeting minutes, coach | Yes | U | — | — | U | U | U |
| gemini-2.5-flash / configured text fallback | Yes | U | — | — | U | U | U |
| gemini-3.8-flash-tts / evaluation fixtures | Yes | U | — | — | U | U | U |
| gemini-3.8-flash-lite-tts / manually selected fixture alternative | Yes | U | — | — | U | U | **10 observed earlier**, see below |
| gemini-embedding-2 / researched, not used by this app | Yes | U | — | — | U | U | U |
| gemini-embedding-001 / legacy alternative, not used | Not established here | U | — | — | U | U | U |

Free availability and current model prices: [official pricing](https://ai.google.dev/gemini-api/docs/pricing).
Model identities: [3.8 Live](https://ai.google.dev/gemini-api/docs/models/gemini-3.8-live),
[3.1 Live fallback](https://ai.google.dev/gemini-api/docs/models/gemini-3.1-flash-live-preview),
[embedding guide](https://ai.google.dev/gemini-api/docs/embeddings),
[legacy embedding model](https://ai.google.dev/gemini-api/docs/models/gemini-embedding-001).
There are **no embedding API calls** in the present backend: recall uses local
SQLite/text matching. Its embedding demand is zero. Changing model environment
variables requires checking that model's own limits.

## This key: evidence, not inference from a successful response

One credentialed probe against the worktree `.env`, 2026-10-08:

- `GET https://generativelanguage.googleapis.com/v1beta/models`: **200**.
- One `gemini-3.5-flash-lite:generateContent` request, “Reply with OK.”, maximum
  eight output tokens: **503**. No retry or fallback was attempted.
- Neither response contained quota/rate-limit/billing headers. The error supplied
  no quota violations or retry metadata. No credentials or raw error text retained.
- **Zero Live sessions**, zero TTS/embedding calls, zero Docs writes.

Result: **current billing tier cannot be determined from these responses**.
A 200 catalog response proves key access, not that inference is free. A 503
indicates availability trouble, not a free-tier limit.

Earlier same-date repository [voice-eval evidence](voice-eval-notes.md) records a
Flash-Lite TTS 429 with `GenerateRequestsPerDayPerProjectPerModel-FreeTier` and
quota value 10. That supports free-tier enforcement **for that model/project at
that time**, not every model's present limits, unchanged credentials, or current
billing configuration. Do not deliberately exhaust the shared key to obtain
another 429. If a normal request later returns `google.rpc.QuotaFailure`, inspect
`quotaMetric`, `quotaId`, `quotaDimensions.model` and `quotaValue`; a generic 429
alone is insufficient. Do not publish raw errors or project identifiers.

## AI Pro credits and the user's check

[Developer Program plans](https://developers.google.com/program/plans-and-pricing)
include **$10 per month** in GenAI/Cloud developer credits with Google AI Pro.
This is a monetary benefit, not a model-specific allotment of unlimited Live
minutes or tokens. The actual API project's tier still controls throughput.

1. Open [AI Studio API keys](https://aistudio.google.com/api-keys) and identify the
   project used by this key. Check **Plan**, then the
   [Projects page](https://aistudio.google.com/projects) for its tier.
2. Open [Usage → Rate limits](https://aistudio.google.com/usage?tab=rate-limit), select
   that project and each model above. Copy every displayed dimension, reset
   period and date into `GEMINI_QUOTA_FILE`; leave absent values null.
3. Open [AI Studio Billing](https://aistudio.google.com/billing) for that project:
   check billing account, prepay/postpay, credit balance and actual usage/charges.
4. Check [My Benefits](https://me.developers.google.com/benefits) for the AI Pro
   grant and apply it to the **same billing account**. The
   [benefits FAQ](https://developers.google.com/profile/help/benefits) describes
   applying recurring credits automatically and a one-year expiry after grant;
   verify the grant's own expiry and eligible SKUs in your account.

The [current billing guide](https://ai.google.dev/gemini-api/docs/billing) says
prepay users must first purchase credits to use eligible promotional credits;
promotional credits are then consumed first. A subscription alone does not
complete that setup. It also distinguishes developer credits from the excluded
Google Cloud welcome/free-trial credit. No billing changes were made here.

## Personal daily-use estimate

Assumptions below are **our planning estimates**, not measured usage. One hour of
conversation plus a separate one-hour meeting, with visual memory running for two
hours during existing sessions (not another two hours of Live). User speaks 30
minutes in conversation; assistant speaks 15. Meeting supplies 60 minutes of audio,
with at most two minutes of assistant output. A continuous-input ceiling is 120
input minutes. At approximately 25 audio tokens/second (see the audio-token note
in [pricing](https://ai.google.dev/gemini-api/docs/pricing)), estimate 135k–180k audio
input and 25.5k audio output tokens, **before** prompts, tools, context reuse,
transcripts, images or reasoning. Token accounting can be substantially higher
because successive generation requests contain context.

| Work | Daily volume | Planning tokens / pressure |
| --- | --- | --- |
| Conversation + meeting | 120 connected minutes; one concurrent session; about 12 ten-minute connection intervals if rollover occurs | Above audio estimate; reconnects add connections; require ≥120 available minutes/day if a minutes quota is shown |
| Visual memory | 7,200 / 20 = **360 caption requests**, at most 3 RPM before duplicates/stillness | Assume 1,000 input + 150 output each: 360k input / 54k output; 3k input TPM steady state |
| Conversation summary | 1 call | Assume 12k input / 1k output |
| Meeting minutes | About 3 map + 1 reduce for 54k transcript characters, using repository's 24k-character chunks | Assume 20k input / 3k output total; reduction can burst |
| Nightly journal | 1 call | Assume 10k input / 2k output |
| Evals and embeddings | 0 during normal personal use | Cached TTS costs no new generation; each uncached fixture or eval adds demand |

This plan needs roughly **366 text calls/day**, 402k text/image input tokens and
60k output, plus Live. In a no-throttle estimate, the local 400-call budget reaches
91.5%; implemented throttling starts at 80%, so actual automatic captures reduce
before that point. If AI Studio reports only 250 RPD for the caption model,
360 captures already exceed it; if 500 RPD, captions alone consume 72%.
Those are scenarios, **not claims about this project's quota**. Keep spare RPM/TPM
for summaries and retries; a fallback is not proof of an independent quota pool.

For a billed project, published Live audio rates are approximately $0.005/input
minute and $0.018/output minute; Flash-Lite 3.5 is $0.30/M input and $2.50/M output.
Thus the above estimate is **$0.76–$0.91/day Live audio + $0.27/day backend text**,
or approximately **$31–$35/month** at 30 days, excluding additional context,
images in Live, tools/grounding and evaluations. $10/month lasts about **8.5–9.7
days** at that use; it cannot fund that entire month. If visual memory requires an
additional two hours of Live connection, budget for those separately. Prices are
USD; the free tier costs zero only when applicable and within its quotas.
[Price source, checked 2026-10-08](https://ai.google.dev/gemini-api/docs/pricing).

## Unverified

- Current key/project tier, all current numerical per-model quotas, credit redemption,
  balance, expiry/SKU eligibility and billing account association need signed-in
  AI Studio inspection. No account-management credentials are available here.
- Actual input activity, tokenization, context-compression/reuse cost, hardware
  disconnects and a full day of usage. Connected minutes are not billed minutes.
- A future embedding implementation's limits/costs. No embedding implementation
  or OpenAI runtime was added.
- Current provider availability after the observed 503. No further probe was run.
