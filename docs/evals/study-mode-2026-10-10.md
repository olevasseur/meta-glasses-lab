# Study mode evaluation — 2026-10-10

**Result:** deterministic backend journey passed; **819 backend tests** and **301 Android JVM tests** passed. The APK assembled successfully. Real Google Docs and spoken Gemini/physical hardware behavior remain unverified.

## Fixture and method

`backend/tests/fixtures/study_notes_tabs.json` contains **self-written personal notes**, not copied book text: eight headings covering identity, goals/systems, motivation/friction, stacking, environment, a two-minute start, recovery, and a weekly experiment. It uses the real `tabs[].documentTab.body` response shape, without a top-level `body`, containing Docs API paragraphs/headings/bullets. No personal document is read or written.

`scripts/eval_study_mode.py` replaces only the Google service/data store and exercises real authenticated FastAPI `/tools/{name}` dispatch, request validation and SQLite persistence. It does not enter application lifespan or start personal background jobs. The eval checks opening by normalized nickname, retrieval, absence of evidence, review navigation, process-restart persistence, quiz history, duplicate grading, stop, and a revised document. Grades in this deterministic journey are fixture inputs: it does **not** evaluate Gemini's grading intelligence.

| Measure | Result |
|---|---|
| Heading retrieval, top 1 | **8/8** |
| Unsupported query returns not_covered | **1/1** (`quantum entanglement`) |
| Authenticated backend tool calls | **35**, all successful |
| Maximum fixture tool result | **1,629 compact UTF-8 JSON bytes**, ~543 tokens using a conservative bytes/3 prose estimate |
| Review round trip | Section 4 → next → previous; text matches |
| Restart | Last document and section 4 recovered from SQLite |
| Quiz question kinds | Recall, explanation, application, comparison all supplied |
| Pending quiz replay | Same attempt ID, no duplicate question allocation |
| Duplicate grading | already_recorded; exactly four recorded attempts |
| Stop quiz | normal mode; bookmark/history retained |
| Revision refresh | Detected and refreshed on next tool call |
| Google creates/appends | **0** |
| Runtime text/embedding model calls | **0** |

The eight retrieval questions are committed as `QUERIES` in the eval script. The suite is a small functional fixture, not a held-out benchmark or evidence of universal recall. Keyword-only search can miss paraphrases; low-overlap candidate passages still require model-side support checking and abstention.

## Automated validation

```text
cd backend
UV_CACHE_DIR=/private/tmp/study-uv-cache uv run pytest -q
819 passed, 3 warnings in 10.06s

cd android
./gradlew --no-daemon testDebugUnitTest assembleDebug
BUILD SUCCESSFUL in 5s
45 actionable tasks: 1 executed, 44 up-to-date
301 JVM tests; 0 failures, 0 errors, 0 skipped (test task up-to-date)
```

Android used JDK 17, the installed Android SDK and writable temporary Gradle/Android caches. No local.properties, .env, backend/secrets or doc_aliases.json edits.

**103 study pytest cases** cover parsing nested tabs/tables/headings/lists, lossless chunking of huge words and Unicode/control text, fixture retrieval, unsupported queries, nickname/title ambiguity, read-only Inbox behavior, chunk navigation, durable/separate bookmarks, revision caching and absent revision IDs, revision/removal recovery, unchanged/changed mastery, stale and cross-document grades, concurrent idempotency, scheduling over days, stop, untrusted framing, result caps/pagination, API argument validation/authentication, locked configuration in Auto/PTT/Over, fixture evaluation and simulator isolation. **Two new JVM tests** exercise all six backend tool routes/function responses and the Over completion gate after tool results.

The full backend suite retained three existing dependency warnings: Starlette/httpx, audioop deprecation, and the experimental Gemini token API. Existing exact-tool-set assertions were extended to include Study; translator tools remain unchanged.

## Reproduction and optional model probes

From repository root:

```sh
uv run --project backend python scripts/eval_study_mode.py
uv run --project backend python scripts/eval_study_mode.py --gemini
```

The optional run has **five** cases: grounded identity answer, motivation answer, unsupported sales question, source prompt injection, and grading a wrong habit-stacking answer. It uses the configured summary model (default gemini-3.5-flash-lite), one attempt per case, no fallback/retry or token-count API. It checks the usage.py evaluation allowance before each request, records attempts, and caches responses keyed by model/prompt/evidence under gitignored `backend/data/study-eval-gemini.json`. Maximum five requests per uncached invocation; cached responses spend none. Share USAGE_DB across worktrees if running parallel evals.

**Actual model result: skipped, credentials_unavailable.** This worktree has no `.env` and the process has no GEMINI_API_KEY. No provider inference occurred. A preflight bug initially treated successful budget reservation as false, and a later local attempt failed before obtaining a key; both were fixed before the final run. Local usage reservations were conservatively retained. The final report says `new_calls: 0`. No claim of model grounding, scoring quality, injection resistance or voice latency is made.

Six voice routing cases are ready in `scripts/voice_eval/study-cases.json`. The existing simulator now isolates Study with this fixture, a disposable SQLite DB and a seeded pending quiz; it cannot reach the user's Google account or study state. They were not synthesized or run against Live, preserving quota.

```sh
PYTHONPATH=scripts uv run --project backend python -m voice_eval \
  --label study-over --cases scripts/voice_eval/study-cases.json --case study-open --workers 1
```

## Hardware and live follow-up

1. Use a real nickname in Over mode; verify outline, spoken ambiguity selection, and short review chunks over glasses HFP. Say next/previous/repeat/goto and interrupt playback.
2. Answer a quiz slowly with pauses, ending in “over.” Verify one question at a time, no early grade, audible feedback without another user turn, and stop quiz returning to normal conversation.
3. Restart backend/phone and force a Live reconnect; verify the saved section/chunk and pending question. Check “how am I doing?” the next day.
4. Edit a heading/passage in Google Docs, then query again. Verify fresh evidence, preserved unchanged concepts and refused stale grades; check shared read-only documents without revisionId.
5. Run the optional five text probes and a small voice sample with valid credentials/budget. Manually assess grounding, grading, injection resistance and exact provider token counts. Mocked GETs and byte caps cannot establish these.

## Independent-review fixes — 2026-10-10

All eleven findings have regression coverage. Added 34 cases (103 study cases total) and switched both the eval and voice simulator to the tabs-only fixture. Coverage includes conversational/numeric/ordinal goto, shallow leaf matches, tab-label exclusion from retrieval, stable paragraph/sentence IDs through top/middle insertions, mastery and bookmark retention, repeat/current after reconnect, filtered reads with extracted-text limits, network/parse outside SQLite locks, concurrent revision rechecks, Study-specific storage/timeout errors, relative due output, provider-only revision churn, related compare passages, local/DST midnight scheduling, table rows and smart-chip display text.

Final offline eval: **PASS**, retrieval **8/8**, unsupported abstention **1/1**, **35** backend tool calls, maximum response **1,629 bytes**, **0** model calls. Final backend pytest: **819 passed**, three existing warnings. Requested Android Gradle command succeeded; the unchanged JVM task reused its passing **301-test** results, and the APK assembled. Nonfatal Android warnings concerned analytics-directory permissions and SDK XML version compatibility. No live Google/Gemini or physical-device checks ran for these fixes.
