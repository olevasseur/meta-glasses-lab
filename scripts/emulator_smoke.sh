#!/usr/bin/env bash
# Real Gemini smoke. Owns only its AVD, adb port, backend process and temporary data.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
export JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}
export ANDROID_HOME=${ANDROID_HOME:-"$HOME/Library/Android/sdk"}
export GRADLE_USER_HOME=${GRADLE_USER_HOME:-/tmp/mgl-smoke-gradle}
export ANDROID_USER_HOME=${ANDROID_USER_HOME:-/tmp/mgl-smoke-android}
export ANDROID_AVD_HOME="$ANDROID_USER_HOME/avd"
export UV_CACHE_DIR=${UV_CACHE_DIR:-/tmp/mgl-smoke-uv}
export PATH="$ANDROID_HOME/platform-tools:$JAVA_HOME/bin:$PATH"
OUT=${SMOKE_OUTPUT:-"$ROOT/docs/evals/emulator"}
mkdir -p "$OUT" "$ANDROID_AVD_HOME"
RUN=$(mktemp -d /tmp/mgl-smoke.XXXXXX)
chmod 700 "$RUN"
PORT=${SMOKE_EMULATOR_PORT:-5580}
SERIAL="emulator-$PORT"
BACKEND_PORT=${SMOKE_BACKEND_PORT:-18787}
AVD="mgl-smoke-api36-$PORT"
APP=com.olevasseur.glassesapp
BACKEND_PID= EMULATOR_PID= LOCAL_SAVED=false LOCAL_CREATED=false
adb_cmd() { adb -s "$SERIAL" "$@"; }
adb_cleanup() {
    python3 - "$SERIAL" "$@" <<'PY_TIMEOUT'
import subprocess, sys
try:
    subprocess.run(['adb', '-s', *sys.argv[1:]], timeout=8, check=False)
except subprocess.TimeoutExpired:
    pass
PY_TIMEOUT
}
cleanup() {
    code=$?
    trap - EXIT
    trap '' INT TERM
    if [[ -n "$EMULATOR_PID" ]]; then
        adb_cleanup logcat -d > "$OUT/logcat.log" 2>&1 || true
        adb_cleanup logcat -d -b crash > "$OUT/crash.log" 2>&1 || true
        adb_cleanup pull "/sdcard/Android/data/$APP/files/smoke/." "$OUT/" >/dev/null 2>&1 || true
        adb_cleanup emu kill >/dev/null 2>&1 || true
        kill "$EMULATOR_PID" 2>/dev/null || true
        wait "$EMULATOR_PID" 2>/dev/null || true
    fi
    if [[ -n "$BACKEND_PID" ]]; then kill "$BACKEND_PID" 2>/dev/null || true; wait "$BACKEND_PID" 2>/dev/null || true; fi
    if [[ "$LOCAL_SAVED" == true ]]; then cp "$RUN/local.properties" "$ROOT/android/local.properties"
    elif [[ "$LOCAL_CREATED" == true ]]; then rm -f "$ROOT/android/local.properties"; fi
    printf 'Exit: %s. Artifacts: %s. Private runtime data: %s\n' "$code" "$OUT" "$RUN"
    exit "$code"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
for tool in sdkmanager avdmanager adb uv python3; do command -v "$tool" >/dev/null || { echo "Missing $tool" >&2; exit 1; }; done
if [[ "$(uname -s)" == Darwin ]] &&
    { ! sysctl -n hw.cachelinesize >/dev/null 2>&1 || ! sysctl -n hw.optional.neon >/dev/null 2>&1; }; then
    echo 'Emulator CPU capability queries are blocked. Run this script in a terminal with sysctl and Hypervisor access.' >&2
    exit 1
fi
if adb devices | awk '{print $1}' | grep -qx "$SERIAL"; then echo "$SERIAL is already in use" >&2; exit 1; fi
python3 - "$BACKEND_PORT" "$PORT" <<'PY'
import socket, sys
for port in (int(sys.argv[1]), int(sys.argv[2]), int(sys.argv[2]) + 1):
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', port))
PY
IMAGE='system-images;android-36;google_apis;arm64-v8a'
if [[ ! -f "$ANDROID_HOME/system-images/android-36/google_apis/arm64-v8a/package.xml" || ! -x "$ANDROID_HOME/emulator/emulator" ]]; then
    sdkmanager --sdk_root="$ANDROID_HOME" "$IMAGE" emulator > "$OUT/sdk-install.log" 2>&1
fi
[[ -x "$ANDROID_HOME/emulator/emulator" && -f "$ANDROID_HOME/system-images/android-36/google_apis/arm64-v8a/package.xml" ]] || { echo 'SDK installation incomplete; see sdk-install.log' >&2; exit 1; }
# avdmanager discovers images via its toolsdir JVM property, not ANDROID_HOME.
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export AVDMANAGER_OPTS="\"-Dcom.android.sdkmanager.toolsdir=$ANDROID_HOME/cmdline-tools/latest\""
printf 'no\n' | avdmanager create avd --force --name "$AVD" --package "$IMAGE" --path "$ANDROID_AVD_HOME/$AVD.avd" > "$OUT/avd.log" 2>&1
# A small display keeps test screenshots and emulator GPU cost low.
cat >> "$ANDROID_AVD_HOME/$AVD.avd/config.ini" <<'CONFIG'
hw.lcd.width=720
hw.lcd.height=1280
hw.lcd.density=240
hw.ramSize=2048
hw.keyboard=yes
CONFIG
if [[ -f "$ROOT/android/local.properties" ]]; then cp "$ROOT/android/local.properties" "$RUN/local.properties"; LOCAL_SAVED=true; fi
LOCAL_CREATED=true
uv run --project "$ROOT/backend" python - "$ROOT" "$ANDROID_HOME" "$BACKEND_PORT" <<'PY'
from pathlib import Path
from dotenv import dotenv_values
import os, sys
root, sdk, port = Path(sys.argv[1]), sys.argv[2], sys.argv[3]
config = {**dotenv_values(root / '.env'), **os.environ}
assert config.get('GEMINI_API_KEY'), 'GEMINI_API_KEY missing'
secret = config.get('APP_SHARED_SECRET')
assert secret, 'APP_SHARED_SECRET missing'
def prop(value):
    return value.replace('\\', '\\\\').replace('\n', '\\n').replace('\r', '\\r').replace('=', '\\=').replace(':', '\\:').replace(' ', '\\ ')
path = root / 'android/local.properties'
path.write_text(f'sdk.dir={prop(sdk)}\nBACKEND_URL=http://127.0.0.1:{port}\nAPP_SHARED_SECRET={prop(secret)}\n')
path.chmod(0o600)
PY
(cd "$ROOT/android" && ./gradlew assembleDebug testDebugUnitTest assembleDebugAndroidTest) > "$OUT/build.log" 2>&1
uv run --project "$ROOT/backend" python - "$ROOT" "$RUN" "$BACKEND_PORT" > "$OUT/backend.log" 2>&1 <<'PY' &
from pathlib import Path
from dotenv import load_dotenv
import os, shutil, sys
root, run, port = Path(sys.argv[1]), Path(sys.argv[2]), int(sys.argv[3])
load_dotenv(root / '.env')
# Never bring personal DBs, OAuth credentials, or Codex repo allowlists into a smoke run.
target = run / 'backend'
target.mkdir()
for source in (root / 'backend').iterdir():
    if source.suffix == '.py': shutil.copy2(source, target / source.name)
for folder in ('prompts', 'templates', 'tools'):
    shutil.copytree(root / 'backend' / folder, target / folder, ignore=shutil.ignore_patterns('__pycache__'))
os.environ['JOURNAL_ENABLED'] = 'false'
os.environ['COACH_DB_PATH'] = str(target / 'data/coach.sqlite3')
os.chdir(target)
sys.path.insert(0, str(target))
import uvicorn
uvicorn.run('app:app', host='127.0.0.1', port=port, timeout_graceful_shutdown=10)
PY
BACKEND_PID=$!
python3 - "$BACKEND_PORT" <<'PY'
import socket, sys, time
for _ in range(120):
    try:
        with socket.create_connection(('127.0.0.1', int(sys.argv[1])), timeout=1): break
    except OSError: time.sleep(1)
else: raise SystemExit('Backend failed to start; see backend.log')
PY
"$ANDROID_HOME/emulator/emulator" -avd "$AVD" -port "$PORT" -no-window -no-audio -no-snapshot -wipe-data -no-boot-anim -gpu swiftshader -memory 2048 > "$OUT/emulator.log" 2>&1 &
EMULATOR_PID=$!
BOOT_DEADLINE=$((SECONDS + 600))
until [[ "$(adb_cmd shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]]; do
    kill -0 "$EMULATOR_PID" 2>/dev/null || { echo 'Emulator exited; see emulator.log' >&2; exit 1; }
    (( SECONDS < BOOT_DEADLINE )) || { echo 'Emulator boot timed out' >&2; exit 1; }
    sleep 3
done
adb_cmd shell input keyevent 82
adb_cmd shell settings put global window_animation_scale 0
adb_cmd shell settings put global transition_animation_scale 0
adb_cmd shell settings put global animator_duration_scale 0
adb_cmd reverse "tcp:$BACKEND_PORT" "tcp:$BACKEND_PORT"
adb_cmd install -r "$ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
adb_cmd install -r "$ROOT/android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
for permission in RECORD_AUDIO READ_PHONE_STATE BLUETOOTH_CONNECT POST_NOTIFICATIONS; do
    adb_cmd shell pm grant "$APP" "android.permission.$permission"
done
adb_cmd logcat -c
python3 - "$SERIAL" "$OUT/instrumentation.log" <<'PY'
import subprocess, sys
with open(sys.argv[2], 'w') as out:
    subprocess.run(['adb', '-s', sys.argv[1], 'shell', 'am', 'instrument', '-w', '-r',
                    '-e', 'realApi', 'true', '-e', 'class', 'com.olevasseur.glassesapp.EmulatorSmokeTest',
                    'com.olevasseur.glassesapp.test/androidx.test.runner.AndroidJUnitRunner'],
                   stdout=out, stderr=subprocess.STDOUT, timeout=900, check=True)
PY
adb_cmd logcat -d > "$OUT/logcat.log"
adb_cmd logcat -d -b crash > "$OUT/crash.log"
cat "$OUT/instrumentation.log"
grep -q 'OK (1 test)' "$OUT/instrumentation.log"
if grep -Eq 'FAILURES|INSTRUMENTATION_FAILED|Process crashed' "$OUT/instrumentation.log"; then exit 1; fi
if grep -q "$APP" "$OUT/crash.log" || grep -Eq "ANR in $APP|FATAL EXCEPTION|Fatal signal" "$OUT/logcat.log"; then
    echo 'Crash/ANR found in logcat' >&2; exit 1
fi
echo 'PASS: launch, DAT inputs/motion, real Gemini WAV reply, look, Over gating, stop, no crash.'
