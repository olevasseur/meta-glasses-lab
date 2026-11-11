"""One-time Google Desktop OAuth consent. Run with the backend's uv environment."""

import sys
from pathlib import Path

from google_auth_oauthlib.flow import InstalledAppFlow

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "backend"))
from notes import SCOPES, save_credentials
from secret_paths import CLIENT_SECRET_PATH, TOKEN_PATH

INSTRUCTIONS = f"""
Google Docs setup (personal account):
1. Create a Google Cloud project: https://console.cloud.google.com/
2. Enable both Google Docs API and Google Drive API.
3. Configure the OAuth consent screen / Google Auth Platform Audience as External.
4. PUBLISH TO PRODUCTION (Publish app / In production) before consent.
   External Testing refresh tokens for these scopes expire after 7 days.
   Personal use can remain unverified; Google may show an unverified-app screen.
5. Create a Desktop app OAuth client and download its JSON to:
   {CLIENT_SECRET_PATH}
   (outside the repository; set GLASSES_SECRETS_DIR to use another folder)
6. Grant both documents and drive.metadata.readonly when the browser opens.
   If you consented in Testing, re-run this script and re-consent after publishing.
   Publishing does not make refresh tokens permanent: they can still be revoked.
"""


def main() -> None:
    print(INSTRUCTIONS)
    client_path = CLIENT_SECRET_PATH
    if not client_path.is_file():
        raise SystemExit(f"Missing {client_path}. Complete steps 1–5, then re-run.")
    flow = InstalledAppFlow.from_client_secrets_file(str(client_path), SCOPES)
    credentials = flow.run_local_server(
        host="localhost", port=0, access_type="offline", prompt="consent"
    )
    if not credentials.refresh_token or not credentials.has_scopes(SCOPES):
        raise SystemExit("Offline access and all requested scopes are required. Re-run and grant every permission.")
    save_credentials(credentials, TOKEN_PATH)
    print(f"Saved {TOKEN_PATH} (owner-only permissions). Restart the backend.")


if __name__ == "__main__":
    main()
