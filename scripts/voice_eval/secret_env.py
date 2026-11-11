"""The backend's secret .env location, for eval modules that do not import the backend."""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'backend'))
from secret_paths import ENV_PATH  # noqa: E402
