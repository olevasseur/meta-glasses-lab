"""Secret file locations, kept outside the repository so copies of it never include them."""

import os
from pathlib import Path

SECRETS_DIR = Path(os.getenv("GLASSES_SECRETS_DIR") or Path.home() / ".config" / "meta-glasses-lab")
ENV_PATH = SECRETS_DIR / ".env"
TOKEN_PATH = SECRETS_DIR / "token.json"
CLIENT_SECRET_PATH = SECRETS_DIR / "client_secret.json"
