"""Run OUTSIDE the Codex sandbox: cd backend && uv run python ../scripts/smoke_claude_jobs.py.

Uses two sequential Claude subscription jobs, no Gemini/Google calls. Only the
throwaway runner's allowlist changes. Artifacts remain in a private temp directory.
"""
import argparse
import asyncio
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import xml.etree.ElementTree as ET

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'backend'))
from jobs import Jobs


async def run(timeout):
    root = Path(tempfile.mkdtemp(prefix='glasses-claude-smoke-')).resolve()
    print('Private smoke artifacts:', root, flush=True)
    repo = root / 'repo'
    repo.mkdir()
    env = {key: os.environ[key] for key in ('PATH', 'HOME', 'USER', 'TMPDIR') if key in os.environ}
    def git(*args):
        subprocess.run(['git', '-c', 'core.hooksPath=/dev/null', '-C', str(repo), *args],
                       env=env, check=True, stdin=subprocess.DEVNULL,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    git('init')
    (repo / 'README.md').write_text('Throwaway Claude background-job smoke.\n')
    outside = root / 'outside-sentinel.txt'
    outside.write_text('unchanged\n')
    shared_git_probe = repo / '.git' / 'sandbox-write-probe'
    # Positive controls distinguish sandbox denials from missing tools/offline host.
    subprocess.run([sys.executable, '-m', 'pytest', '--version'], env=env, check=True)
    subprocess.run(['curl', '--fail', '--silent', '--show-error', '--max-time', '15',
                    'https://example.com'], env=env, check=True, stdout=subprocess.DEVNULL)
    test_source = '''import os
from pathlib import Path
import subprocess
import urllib.request
import pytest


def test_edit():
    assert Path('smoke.txt').read_text() == 'claude smoke ok\\n'


def test_private_temp():
    scratch = Path(os.environ['TMPDIR']).resolve()
    assert scratch.is_relative_to(Path(os.environ['CLAUDE_CODE_TMPDIR']).resolve())
    (scratch / 'pytest-temp-probe').write_text('ok')


def test_no_network():
    curl = subprocess.run(['curl', '--fail', '--silent', '--show-error', '--max-time', '8',
                           'https://example.com'], capture_output=True, text=True)
    assert curl.returncode != 0, 'curl reached the network'
    print('curl blocked:', curl.returncode, curl.stderr)
    # A different client exercises the sandbox, independent of Bash(curl *) rules.
    with pytest.raises(OSError):
        urllib.request.urlopen('https://example.com', timeout=8)


@pytest.mark.parametrize('target', OUTSIDE_PATHS)
def test_no_outside_write(target):
    with pytest.raises(PermissionError):
        Path(target).write_text('escaped')
'''.replace('OUTSIDE_PATHS', repr([str(outside), str(shared_git_probe)]))
    (repo / 'test_smoke.py').write_text(test_source)
    git('add', 'README.md', 'test_smoke.py')
    git('-c', 'user.name=Glasses smoke', '-c', 'user.email=smoke@localhost',
        '-c', 'commit.gpgsign=false', 'commit', '-m', 'Initialize throwaway smoke repo')
    aliases = root / 'repos.json'
    aliases.write_text(json.dumps({'smoke': str(repo)}))
    notes = SimpleNamespace(token_path=root / 'absent-google-token', credentials=None)
    runner = Jobs(notes, root / 'data', aliases, concurrency=1)
    await runner.start()
    async def finish(job_id):
        try:
            await asyncio.wait_for(asyncio.shield(runner.tasks[job_id]), timeout)
        except TimeoutError:
            await runner.cancel(job_id)
            raise RuntimeError('Smoke timed out; process group stopped') from None
        result = runner.status(job_id)
        print(result['agent'], result['kind'], result['status'], flush=True)
        if result['status'] != 'completed':
            raise RuntimeError('Inspect private job logs in the artifact directory')
        return result
    try:
        research = await finish(runner.deep_research(
            'What is the capital of Canada? Use one official source. Return a tiny report with its URL and access date.',
            agent='claude')['job_id'])
        report = research['runs']['claude']['report']
        assert 'https://' in report, 'Research did not include a source URL'
        instruction = (
            'Create smoke.txt containing exactly: claude smoke ok (followed by a newline). '
            'Run python -m pytest -q -s test_smoke.py --junitxml=smoke-results.xml using Bash. '
            'These fixed tests intentionally attempt curl https://example.com, a second network client, '
            'and writes to disposable sentinels outside the worktree; they must be blocked by the sandbox. '
            'Do not modify test_smoke.py or other source files, fake results, install dependencies, '
            'or retry outside the sandbox. If a check fails, report that failure. '
            'Include the actual pytest output in your report.')
        preview = runner.codex_task('smoke', instruction, agent='claude')
        # Running this script is explicit local approval of this fixed tiny task.
        queued = runner.codex_task('smoke', instruction, True, preview['job_id'], agent='claude')
        coding = await finish(queued['job_id'])
        worktree = Path(coding['worktree'])
        assert worktree != repo and coding['branch'].startswith('glasses/claude-smoke-')
        assert (worktree / 'smoke.txt').read_text() == 'claude smoke ok\n'
        assert (worktree / 'test_smoke.py').read_text() == test_source, 'Smoke tests changed'
        suites = ET.parse(worktree / 'smoke-results.xml').getroot().findall('testsuite')
        assert sum(int(s.attrib['tests']) for s in suites) == 5, 'Missing pytest cases'
        assert all(int(s.attrib[k]) == 0 for s in suites for k in ('failures', 'errors', 'skipped')), 'Pytest did not pass all probes'
        assert outside.read_text() == 'unchanged\n', 'Outside write escaped the sandbox'
        assert not shared_git_probe.exists(), 'Shared git write escaped the sandbox'
        assert not (repo / 'smoke.txt').exists(), 'Original checkout was modified'
        print('PASS: sourced research, isolated edit, five pytest cases, private temp, curl/urllib blocked, '
              'outside/shared-git writes blocked, original checkout unchanged. No Docs write.', flush=True)
    finally:
        await runner.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--timeout', type=int, default=240, help='Deadline per smoke job in seconds (default 240)')
    args = parser.parse_args()
    if not 1 <= args.timeout <= 3600:
        parser.error('--timeout must be 1–3600 seconds')
    try:
        asyncio.run(run(args.timeout))
    except (Exception, KeyboardInterrupt):
        print('FAIL: inspect private smoke artifacts; no automatic retry.', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
