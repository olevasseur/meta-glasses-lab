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

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'backend'))
from jobs import Jobs


async def run(timeout):
    root = Path(tempfile.mkdtemp(prefix='glasses-claude-smoke-')).resolve()
    repo = root / 'repo'
    repo.mkdir()
    env = {key: os.environ[key] for key in ('PATH', 'HOME', 'USER', 'TMPDIR') if key in os.environ}
    def git(*args):
        subprocess.run(['git', '-c', 'core.hooksPath=/dev/null', '-C', str(repo), *args],
                       env=env, check=True, stdin=subprocess.DEVNULL,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    git('init')
    (repo / 'README.md').write_text('Throwaway Claude background-job smoke.\n')
    git('add', 'README.md')
    git('-c', 'user.name=Glasses smoke', '-c', 'user.email=smoke@localhost',
        '-c', 'commit.gpgsign=false', 'commit', '-m', 'Initialize throwaway smoke repo')
    aliases = root / 'repos.json'
    aliases.write_text(json.dumps({'smoke': str(repo)}))
    notes = SimpleNamespace(token_path=root / 'absent-google-token', credentials=None)
    runner = Jobs(notes, root / 'data', aliases, concurrency=1)
    print('Private smoke artifacts:', root, flush=True)
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
        instruction = 'Create smoke.txt containing exactly: claude smoke ok (followed by a newline). Do not edit other files. Report tests as not run.'
        preview = runner.codex_task('smoke', instruction, agent='claude')
        # Running this script is explicit local approval of this fixed tiny task.
        queued = runner.codex_task('smoke', instruction, True, preview['job_id'], agent='claude')
        coding = await finish(queued['job_id'])
        worktree = Path(coding['worktree'])
        assert worktree != repo and coding['branch'].startswith('glasses/claude-smoke-')
        assert (worktree / 'smoke.txt').read_text() == 'claude smoke ok\n'
        assert not (repo / 'smoke.txt').exists(), 'Original checkout was modified'
        print('PASS: sourced research, JSON summaries, isolated edit, original checkout unchanged. No Docs write.', flush=True)
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
