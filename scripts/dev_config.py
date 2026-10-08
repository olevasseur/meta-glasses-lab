"""Prepare ignored debug properties without exposing secrets in shell arguments."""
import os
from pathlib import Path
from dotenv import load_dotenv

ROOT = Path(__file__).resolve().parents[1]


def property_value(value):
    escaped = []
    controls = {"\n": r"\n", "\r": r"\r", "\t": r"\t", "\f": r"\f"}
    for character in value:
        if character in controls:
            escaped.append(controls[character])
        elif ord(character) > 127:
            units = character.encode("utf-16-be")
            escaped.extend("\\u%04x" % int.from_bytes(units[i:i + 2], "big") for i in range(0, len(units), 2))
        elif character in "\\:=#! ":
            escaped.append("\\" + character)
        else:
            escaped.append(character)
    return "".join(escaped)


def configure(root=ROOT):
    load_dotenv(root / ".env")
    secret = os.getenv("APP_SHARED_SECRET", "")
    if not secret or not os.getenv("GEMINI_API_KEY"):
        raise SystemExit("Set APP_SHARED_SECRET and GEMINI_API_KEY in repo-root .env first.")
    path = root / "android" / "local.properties"
    values = {"sdk.dir": os.environ["ANDROID_HOME"], "BACKEND_URL": "http://127.0.0.1:8787", "APP_SHARED_SECRET": secret}
    lines = path.read_text().splitlines() if path.exists() else []
    lines = [line for line in lines if line.lstrip().split("=", 1)[0].split(":", 1)[0].strip() not in values]
    lines += [f"{key}={property_value(value)}" for key, value in values.items()]
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as output:
        os.fchmod(output.fileno(), 0o600)
        output.write("\n".join(lines) + "\n")
    print("Prepared ignored android/local.properties for localhost debugging (secret not printed).")


if __name__ == "__main__":
    configure()
