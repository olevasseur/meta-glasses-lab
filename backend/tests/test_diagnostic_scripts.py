import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess

import pytest

ROOT = Path(__file__).resolve().parents[2]


def module(name):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'scripts' / f'{name}.py')
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


def executable(path, text):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text('#!/bin/bash\n' + text)
    path.chmod(0o755)


def test_debug_config_preserves_other_properties_and_escapes(monkeypatch, tmp_path):
    (tmp_path / 'android').mkdir()
    path = tmp_path / 'android/local.properties'
    path.write_text('custom=true\nAPP_SHARED_SECRET=old\nBACKEND_URL=https://old.invalid\n')
    monkeypatch.setenv('APP_SHARED_SECRET', 'new=secret\\value')
    monkeypatch.setenv('GEMINI_API_KEY', 'backend-only-key')
    monkeypatch.setenv('ANDROID_HOME', '/sdk path')
    config = module('dev_config')
    assert config.property_value('🔑\t') == r'\ud83d\udd11\t'
    config.configure(tmp_path)
    value = path.read_text()
    assert 'custom=true' in value and 'backend-only-key' not in value
    assert 'new\\=secret\\\\value' in value
    assert 'sdk.dir=/sdk\\ path' in value and 'old' not in value
    assert path.stat().st_mode & 0o777 == 0o600


@pytest.mark.parametrize('device,success', [('device', True), ('unauthorized', False), ('absent', False)])
def test_dev_up_checks_device_and_runs_install_in_order(tmp_path, device, success):
    root = tmp_path / 'repo with spaces'
    (root / 'scripts').mkdir(parents=True)
    (root / 'backend').mkdir()
    (root / 'android').mkdir()
    shutil.copy(ROOT / 'scripts/dev_up.sh', root / 'scripts/dev_up.sh')
    sdk = tmp_path / 'sdk'
    (sdk / 'platforms/android-36').mkdir(parents=True)
    commands = tmp_path / 'commands'
    executable(sdk / 'platform-tools/adb', '''echo "adb $*" >> "$COMMAND_LOG"
case "$1" in
 devices) echo 'List of devices attached'; [[ "$MOCK_DEVICE" != absent ]] && echo "serial $MOCK_DEVICE"; exit 0;;
 get-state) echo "$MOCK_DEVICE";;
esac
''')
    java = tmp_path / 'jdk'
    executable(java / 'bin/java', 'echo \'openjdk version "17.0.1"\' >&2\n')
    bin_path = tmp_path / 'bin'
    executable(bin_path / 'uv', 'echo "uv $*" >> "$COMMAND_LOG"\nexit 0\n')
    executable(root / 'android/gradlew', 'echo "gradle $*" >> "$COMMAND_LOG"\n')
    env = {**os.environ, 'PATH': str(bin_path) + ':/usr/bin:/bin', 'JAVA_HOME': str(java),
           'ANDROID_HOME': str(sdk), 'COMMAND_LOG': str(commands), 'MOCK_DEVICE': device}
    env.pop('ANDROID_SERIAL', None)
    result = subprocess.run(['bash', str(root / 'scripts/dev_up.sh')], env=env, text=True, capture_output=True, timeout=10)
    calls = commands.read_text()
    assert (result.returncode == 0) == success
    if success:
        steps = ['adb reverse', 'gradle assembleDebug', 'adb install -r', 'adb shell am start', 'uv run python ../scripts/tail_logs.py\n']
        positions = [calls.index(step) for step in steps]
        assert positions == sorted(positions)
    else:
        assert 'USB debugging' in result.stderr and 'adb install' not in calls


def test_tail_rejects_unsafe_origins_and_formats_filtered_rows(monkeypatch, capsys):
    tail = module('tail_logs')
    monkeypatch.setenv('APP_SHARED_SECRET', 'test-secret')
    for url in ['http://remote.invalid', 'https://user:pass@remote.invalid', 'https://host/path']:
        with pytest.raises(ValueError):
            tail.request('/logs', url)
    monkeypatch.setattr('sys.argv', ['tail_logs.py', '--once', '--feature', 'audio', '--level', 'WARN'])
    paths = []
    def get(path, url):
        paths.append(path)
        return {'entries': [{'timestamp': 'now', 'level': 'WARN', 'source': 'android', 'feature': 'audio', 'event': 'route lost'}], 'cursor': 7}
    monkeypatch.setattr(tail, 'request', get)
    assert tail.main() == 0
    assert 'feature=audio&level=WARN' in paths[0]
    assert 'android/audio: route lost' in capsys.readouterr().out
