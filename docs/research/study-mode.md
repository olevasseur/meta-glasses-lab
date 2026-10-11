# Study mode research and design

Date: **2026-10-10**. Scope: one personal Google Doc at a time. Runtime AI remains Gemini Live. Inspected existing notes/nickname resolution, SQLite memory, usage ledger, token registry, read-to-me, Over, tool-reply and GeminiSession routing before implementation.

## Evidence and decisions

Google [`documents.get`](https://developers.google.com/workspace/docs/api/reference/rest/v1/documents/get) supports `includeTabsContent=true` and suggestion preview modes. Read all tabs, including child tabs, in document order; choose `PREVIEW_WITHOUT_SUGGESTIONS` so proposed edits are not presented as established notes. Parse native heading styles and bullets, recurse through table cells, omit duplicated tables of contents. [Tabs guide](https://developers.google.com/workspace/docs/api/how-tos/tabs), verified 2026-10-10.

The [Document resource](https://developers.google.com/workspace/docs/api/reference/rest/v1/documents) describes `revisionId` as opaque, available only to editors, and guaranteed valid for 24 hours. An unchanged ID means unchanged content; a changed ID can reflect internal changes. Each evidence operation performs a metadata GET. Unchanged IDs reuse parsed SQLite content. Changed or absent IDs fetch full content; missing revision IDs use a content hash. Network/auth failure fails closed rather than silently answering from stale notes. Full reads and metadata may race: the full response's revision is stored, then checked on the next operation.

Reuse `doc_nicknames.find_alias`/`alias_key` and existing Google clients/lock. Study additionally normalizes punctuation/whitespace for exact titles; it never fuzzy-matches. Duplicates require a spoken choice, with selection IDs checked against matching destinations. Study deliberately avoids `Notes._resolve`, whose Inbox path can create a document. OAuth scopes stay unchanged; Study invokes GET/list only, with no batchUpdate/create or alias mutation.

For local retrieval, use [BM25](https://nlp.stanford.edu/IR-book/html/htmledition/okapi-bm25-a-non-binary-model-1.html), k1=1.2, b=.75, positive smoothed IDF, heading terms counted twice. English stopwords and a small plural normalizer suppress conversational filler. Return up to three passages. This is relevance ranking, not proof the question is answered. Live must verify support, name the heading, and abstain when unsupported. Keyword reformulation once is allowed. No embeddings: the local corpus is small, lexical retrieval is inspectable, and embedding cache/quota machinery adds no demonstrated value on the fixture.

[SuperMemo's original method](https://www.supermemo.com/en/blog/application-of-a-computer-to-improve-the-results-obtained-in-working-with-the-supermemo-method) motivates longer review intervals after successful recall. We use a simpler **Leitner-style policy, not SM-2**: scores 0/1 reset to box 0 and are immediately eligible for a remedial question; 2/3 advance one box, with intervals 1, 3, 7, 14, 30 days. Due failed concepts precede overdue learned concepts, then unseen concepts. No early review of future-due concepts; return caught_up. These exact intervals/priorities are product choices, not validated learning efficacy claims.

Concepts are heading-aware passage units, not model-extracted ontologies. At most ~1,000 UTF-8/JSON bytes per passage; source identity hashes the section key plus normalized passage text, without an index. Each paragraph stays independent; oversized paragraphs split into independent sentences, with bounded word/UTF-8 splitting for oversized sentences. Unchanged units retain performance; edited units start unseen, old history remains auditable. Parsed-section digest changes cancel pending grades; provider-only revision churn does not. Heading IDs preserve bookmarks through section insertion; removal clamps the bookmark. Independent unchanged paragraphs retain IDs through insertions. Identical normalized passages within one section share mastery. Legacy index-based IDs remain in history but do not transfer mastery on the one-time cache upgrade. This avoids any extraction, embedding or question-generation text calls.

[Live tool use](https://ai.google.dev/gemini-api/docs/live-api/tools) supports returning function results for model continuation. Existing Android backend routing and OverReplyWatchdog already handle study calls. Six declarations are backend-owned and Tier 0. No new microphone, VAD, session-resumption, approval or tool-reply mechanism is needed. System policy distinguishes a spoken ambiguity choice from device approval, and stop quiz from ending the conversation.

[Google rate limits](https://ai.google.dev/gemini-api/docs/rate-limits) are project/model/tier dependent; actual project limits require AI Studio. Treat the user's 65K Live TPM / low text RPD figures as design constraints, not newly verified project quotas. Runtime adds **zero** text or embedding requests. Tool envelopes cap compact serialized UTF-8 JSON at **3,600 bytes**, targeting roughly <=1,200 prose tokens; byte count is not a Gemini tokenizer guarantee. Review text is never silently truncated; long passages become separately navigable chunks. Lists are bounded/paginated. Ordinary fixture maximum is recorded in the eval.

## Persistence and trust boundaries

`backend/data/study.sqlite3` (already gitignored) holds documents/revision/chunks/bookmarks, the last active document/mode/pending attempt, mastery, and attempt history. Separate from conversational memory: no study scores become user facts. SQLite transactions serialize concurrent advances/grades. Pending attempts survive process restart; repeated quiz_next returns the same attempt; repeated grading cannot double-count. Stop cancels the pending attempt without grading. One personal account/active context is an intentional limitation.

Labels, passages and feedback are untrusted data inside JSON with a fixed source policy. Instructions in notes cannot confer approval or authorize tools. Prompt policy requires grounded answers and grading. This framing reduces risk; it is not a deterministic guarantee of model injection resistance. The local eval verifies framing and absence of Google writes, not model obedience.

## Unverified

- Real Google account access, Docs API suggestion/tab behavior and latency on personal documents; API clients are mocked in tests.
- Gemini spoken grounding, question diversity, scoring fairness and adversarial injection resistance. Optional five-call text probes are implemented but could not run without credentials in this worktree.
- Physical Pixel/glasses HFP recognition, Over sign-offs during long answers, audible tool continuation and reconnect timing.
- Exact Gemini token counts on arbitrary languages/control-heavy documents. Byte caps are tested; approximate prose target is explicit.
- Long-term learning benefit and retrieval on synonyms absent from the notes. No semantic search, image OCR, footnote/header/footer extraction, Markdown-heading inference, dashboard, or multi-doc synthesis.

## Review corrections verified 2026-10-10

Rechecked the official [tabs guide](https://developers.google.com/workspace/docs/api/how-tos/tabs): with `includeTabsContent=true`, content lives under `tabs[].documentTab.body`, with recursive `childTabs`. The new `study_notes_tabs.json` fixture has no top-level body, and is used by both the backend eval and voice simulator. Single-tab names are omitted; meaningful multi-tab labels are separate from headings and retrieval terms.

The [Document schema](https://developers.google.com/workspace/docs/api/reference/rest/v1/documents) exposes person properties (`name`, `email`), rich-link properties (`title`, `uri`), and `dateElement.dateElementProperties.displayText`. Extraction uses these display values without dereferencing links. Tables render one row per line with ` | ` between cells. The read fields mask selects prose/structure and omits ordinary formatting; recursive table cell content and descendants beyond the explicitly masked tab levels remain whole subtrees so nesting cannot silently drop text. The size limit is 1.5 MB extracted text, independent of metadata.

Fetch/parse precedes the SQLite write transaction, followed by an optimistic revision/content/state check and bounded retry on a concurrent refresh. Parsed content hashes determine pending-attempt validity. Bookmarks persist chunk identity plus a nearest-position fallback. Day intervals use local calendar midnight (`JOURNAL_TIMEZONE` or host timezone); tool responses describe relative due time. Deterministic tests cover concurrent refreshes, pending answers after reconnect/current/repeat, edits at top/middle, provider-only revision changes, and DST boundaries.

Still unverified: live Google acceptance of the fields mask and personal-account chip/tab responses; spoken Gemini behavior and physical hardware. No live Google or Gemini request was required for these regressions.
