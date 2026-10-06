# Voice → Google Docs notes ("add this note to <doc>")

- **Researched:** 2026-10-06 (subagent web research; snippets are untested)

## TL;DR
- No native path: Meta AI has **no** Drive/Docs/Keep integration ("Hey Meta, take a note" saves inside Meta AI only).
- Build a single function tool `append_to_doc(doc_name, text, confirmed_doc_id?)` executed **on our backend**. The voice model never sees Google IDs or tokens.
- Auth: one **Desktop OAuth client**, consent once on the laptop, refresh token stored on the backend. **Publish the OAuth app to "In production" (unverified)** to avoid 7-day token expiry.
- Start with a single **Inbox doc** + an alias map; add fuzzy matching after.
- Keep API is **Workspace-only** — not usable with @gmail.com.

## 1. Auth
- **Testing-mode trap:** External apps in "Testing" get refresh tokens that expire after **7 days** (unless only name/email/profile scopes). https://developers.google.com/identity/protocols/oauth2
- **Fix:** click "Publish app" → In production, without verification. OK for personal use (<100 users), shows an "unverified app" warning at consent. https://support.google.com/cloud/answer/13464323
  - **Re-run consent after publishing** — tokens issued in Testing keep the 7-day expiry.
  - Production tokens can still die after 6 months unused, on revoke, or past the per-client token limit.
- "Internal" user type needs a Workspace/Cloud Identity org — not available for plain Gmail.
- Android Google Sign-In only matters if the phone calls Google directly; needs server-auth-code exchange to get a refresh token to the backend **[current Android flow unverified]**.

### Scopes
https://developers.google.com/workspace/drive/api/guides/api-specific-auth · https://developers.google.com/workspace/docs/api/auth

| Scope | Class | Allows |
|---|---|---|
| `drive.file` | non-sensitive | Only files the app created or user picked via Picker |
| `documents` | sensitive | Edit any Doc **if you know its ID** (no search) |
| `drive.metadata.readonly` / `drive.readonly` / `drive` | restricted | List/search all of Drive |

- Search existing docs by name → `documents` + `drive.metadata.readonly`.
- Avoid restricted scopes → `drive.file` only, register target docs once (create or Picker), keep a name→ID map.
- Restricted-scope security assessment only applies to verified apps; irrelevant for a personal unverified app.

## 2. API calls
- Drive `name contains 'X'` is **prefix-only** (`'World'` won't match "HelloWorld"); `fullText` matches whole words. → List docs and fuzzy-match client-side. https://developers.google.com/workspace/drive/api/guides/ref-search-terms
- Append: `endOfSegmentLocation` with empty `segmentId` = end of body; omit `tabId` for first tab. https://developers.google.com/workspace/docs/api/reference/rest/v1/documents/request

```python
from google.oauth2.credentials import Credentials
from googleapiclient.discovery import build
from rapidfuzz import process, fuzz
import datetime, json

SCOPES = ["https://www.googleapis.com/auth/documents",
          "https://www.googleapis.com/auth/drive.metadata.readonly"]
# One-time, on a laptop:
# InstalledAppFlow.from_client_secrets_file("client.json", SCOPES).run_local_server()
creds = Credentials.from_authorized_user_info(json.load(open("token.json")), SCOPES)
drive, docs = build("drive", "v3", credentials=creds), build("docs", "v1", credentials=creds)

def list_docs():
    q = "mimeType='application/vnd.google-apps.document' and trashed=false"
    r = drive.files().list(q=q, fields="files(id,name)", pageSize=1000,
                           orderBy="modifiedTime desc").execute()
    return r["files"]

def append_note(doc_id, text):
    ts = datetime.datetime.now().strftime("%Y-%m-%d %H:%M")
    docs.documents().batchUpdate(documentId=doc_id, body={"requests": [
        {"insertText": {"endOfSegmentLocation": {"segmentId": ""},
                        "text": f"\n{ts}\n{text}\n"}}]}).execute()

def create_doc(title):  # if not found and the user confirms
    return docs.documents().create(body={"title": title}).execute()["documentId"]
```
Styled timestamp header: add `updateParagraphStyle` (`HEADING_3`) using the end-of-body index from `documents.get`.

## 3. Fuzzy matching (speech-to-text mangles names)
1. **Alias map first:** `{"grocery": id, "groceries": id, "work log": id}`.
2. **rapidfuzz** over aliases + cached doc names (refresh every few minutes):
   `process.extract(spoken, names, scorer=fuzz.token_set_ratio, limit=3)`
3. Thresholds:
   - ≥85 and ≥10 pts ahead of runner-up → append.
   - 60–85 or close tie → tool returns `needs_confirmation` + candidates; model asks "Groceries or Grocery list?"
   - <60 → offer to create the doc or drop into Inbox.
4. Optional: phonetic normalization (jellyfish metaphone); put doc names in the voice model's instructions as transcription hints.

## 4. Alternatives considered
- **Google Keep API:** Workspace-only (admin / domain-wide delegation). ❌ for @gmail.com. https://developers.google.com/workspace/keep/api/guides · https://workspaceupdates.googleblog.com/2021/05/keep-audit-logs-and-api.html
- **Single Inbox doc:** simplest; no name matching. ✅ good v0.
- **Apps Script web app** (`doPost` → `DocumentApp.openById(id).getBody().appendParagraph(...)`, Execute as me / Anyone): zero infra, but anyone with the URL can write; no auth for anonymous callers. If used: long shared secret in POST body, length cap, doc-ID allow-list. Cold starts + 302 redirect. https://developers.google.com/apps-script/guides/web
- **Google Workspace MCP servers** (Developer Preview since May 2026, membership required): Drive `https://drivemcp.googleapis.com/mcp/v1` (`search_files`, `create_file`…), Docs `https://docsmcp.googleapis.com/mcp/v1` (`read_doc`, `update_doc` — append unconfirmed). Bring your own OAuth client. **[unverified: personal Gmail support]**. OpenAI Realtime can call it directly but we'd lose fuzzy match/confirm logic. https://workspaceupdates.googleblog.com/2026/05/agent-tools-and-security-updates-for-workspace-developers.html · https://developers.google.com/workspace/guides/configure-mcp-servers
- Community: `mcp-google-suite` https://mcpservers.org/servers/adexltd/mcp-google-suite
- **Recommendation:** plain function tool → our backend.

## 5. Meta AI native
- No Drive/Docs/Keep. "Hey Meta, take a note" stays inside Meta AI. https://www.meta.com/ai-glasses/productivity-with-ai-glasses/
- Calendar linking (Google/Outlook) exists. https://www.meta.com/help/ai-glasses/1468274331180316/
- **Future route:** "Meta AI Connectors" (API/MCP) + WebMCP in developer preview with signup — could someday let "Hey Meta" call our endpoint directly. https://developers.meta.com/wearables/faq · https://developers.meta.com/blog/meta-connect-recap-ai-glasses/
