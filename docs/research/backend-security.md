# Backend security verification

Date: **2026-10-08**. Scope: Python backend and browser boundary; all 14 feature
specifications, architecture, decisions and the existing protocol notes reviewed.

Sources opened on this date:

- https://cheatsheetseries.owasp.org/cheatsheets/Transaction_Authorization_Cheat_Sheet.html
  — authorization belongs on the server, binds significant transaction data, and
  uses expiring credentials unique to each operation. Here a browser session,
  CSRF token and single-use server nonce authorize stored request data; model
  confirmation fields never do.
- https://cheatsheetseries.owasp.org/cheatsheets/Server_Side_Request_Forgery_Prevention_Cheat_Sheet.html
  — validate resolved destinations and disable redirect following. Existing
  pinned HTTPS sockets already prevent a second hostname lookup; added rejection
  for multicast/reserved and IPv6 translation/tunnel addresses.
- https://docs.python.org/3/library/ipaddress.html
  — `is_global` alone is not equivalent to “ordinary public unicast endpoint.”
  Address properties are exercised against the installed Python 3.12.13 runtime;
  the currently published documentation also describes later Python versions.

Local evidence: `backend/tests/test_security.py` exercises hostile inputs with
`test_sec_*` cases. Existing tests now perform actual browser approval where
execution is expected. Real local child processes verify bounded/redacted logs;
Codex/Git execution itself is mocked. SQL arguments remain parameterized;
filesystem identifiers, JSON deserialization, templates and auth were inspected.

Real Gemini check: disposable memory DB, no backend lifespan/background jobs,
`TestClient(base_url="http://localhost")`, authenticated `/token`, then the
constrained WebSocket with `Authorization: Token <name>` and minimal model plus
`sessionResumption:{}` setup. **Auto/PTT/Over all returned setupComplete**.
No tokens, keys or private memory were printed; no Docs or Codex action ran.
`lock_additional_fields=[]` and client-owned session resumption remain intact.

Unverified: physical phone/glasses and browser UI interaction, real Google Docs
writes, hostile-repository isolation in a real Codex invocation, OS-level resource
quotas, arbitrary network NAT/proxy configurations, backup/SSD erasure and long
session rollover. Tests are not a claim that natural-language prompt injection
is solved: reviewed operations can still contain misleading source material.
