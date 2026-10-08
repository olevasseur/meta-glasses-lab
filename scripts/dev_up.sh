#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export PATH="$ANDROID_HOME/platform-tools:$JAVA_HOME/bin:$PATH"
fail() { echo "dev_up: $*" >&2; exit 1; }
command -v uv >/dev/null || fail "Install uv, then retry."
command -v adb >/dev/null || fail "Install Android SDK platform-tools; set ANDROID_HOME."
[[ -x "$JAVA_HOME/bin/java" ]] || fail "JAVA_HOME must point to JDK 17."
"$JAVA_HOME/bin/java" -version 2>&1 | head -1 | grep -Eq 'version "17[.\"]' || fail "Set JAVA_HOME to JDK 17."
[[ -d "$ANDROID_HOME/platforms/android-36" ]] || fail "Install Android SDK Platform 36 in SDK Manager."
adb start-server >/dev/null
if [[ -z "${ANDROID_SERIAL:-}" ]]; then
  count="$(adb devices | awk 'NR>1 && NF>=2 {n++} END {print n+0}')"
  [[ "$count" == 1 ]] || fail "Connect exactly one phone by USB (or set ANDROID_SERIAL). Enable Developer options → USB debugging."
fi
[[ "$(adb get-state 2>/dev/null || true)" == device ]] || fail "Phone offline/unauthorized: unlock it, enable USB debugging, and accept the computer authorization prompt."
cd "$ROOT/backend"
uv sync
uv run python ../scripts/dev_config.py
server_pid=""
server_log="$(mktemp -t glasses-dev-server.XXXXXX)"
cleanup() {
  if [[ -n "$server_pid" ]]; then kill "$server_pid" 2>/dev/null || true; wait "$server_pid" 2>/dev/null || true; fi
  rm -f "$server_log"
}
trap cleanup EXIT
trap 'exit 130' INT TERM
if uv run python ../scripts/tail_logs.py --health; then
  echo "Reusing authenticated backend on 127.0.0.1:8787."
else
  uv run uvicorn app:app --host 127.0.0.1 --port 8787 >"$server_log" 2>&1 &
  server_pid=$!
  ready=false
  for attempt in $(seq 1 30); do
    kill -0 "$server_pid" 2>/dev/null || fail "Backend exited. Check port 8787, .env, and single-worker/job-runner configuration. Run backend manually for details."
    if uv run python ../scripts/tail_logs.py --health; then ready=true; break; fi
    sleep 1
  done
  [[ "$ready" == true ]] || fail "Backend not ready after 30 attempts. Check port 8787 and APP_SHARED_SECRET."
fi
adb reverse tcp:8787 tcp:8787
cd "$ROOT/android"
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -W -n com.olevasseur.glassesapp/.MainActivity
echo "Open Diagnostics on the phone; live viewer: http://localhost:8787/dashboard/live-logs"
echo "Ctrl-C stops this tail and the backend only if this command started it."
cd "$ROOT/backend"
uv run python ../scripts/tail_logs.py
