"""No providers/real CLI: exercise actual queue, subprocess argv and approvals."""
import asyncio
import json
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock

import pytest
from fastapi.testclient import TestClient

import app
import approvals
import dashboard
import jobs
from job_agents import claude_command, claude_result, merge_sources
from job_tools import CodexRequest, ResearchRequest
from jobs import Jobs
from approval_helpers import browser_login, field


@pytest.fixture
def anyio_backend():
    return 'asyncio'


@pytest.fixture
def runner(tmp_path):
    aliases = tmp_path / 'repos.json'
    aliases.write_text(json.dumps({'demo': str(tmp_path / 'repo')}))
    notes = Mock(token_path=tmp_path / 'no-google-token')
    notes.append_research.return_value = {'status': 'ok', 'doc_title': 'Research'}
    return Jobs(notes, tmp_path / 'data', aliases, concurrency=2)


@pytest.fixture
def cli(monkeypatch):
    calls, active = [], set()
    state = SimpleNamespace(calls=calls, peak=0, gate=asyncio.Event(), fail=None, envelope=None)
    state.gate.set()
    async def spawn(*argv, **kwargs):
        calls.append((argv, kwargs))
        output = b''
        report = {'summary': argv[0] + ' completed.',
                  'report': '# Findings\n' + argv[0] + ' evidence. Tests not run.\nhttps://example.org/source'}
        if argv[0] == 'git':
            if '--git-common-dir' in argv:
                output = b'/original-repo/.git'
            elif 'rev-parse' in argv:
                output = str(kwargs['cwd']).encode()
            elif 'worktree' in argv:
                Path(argv[-2]).mkdir(parents=True)
            elif 'diff' in argv:
                output = b' demo.py | 2 +-\n 1 file changed, 1 insertion(+), 1 deletion(-)'
            elif 'ls-files' in argv:
                output = b'new.py\n'
        elif argv[0] == 'codex':
            Path(argv[argv.index('-o') + 1]).write_text(json.dumps(report))
        else:
            output = json.dumps(state.envelope or {'is_error': False, 'structured_output': report}).encode()
        reader, errors = asyncio.StreamReader(), asyncio.StreamReader()
        reader.feed_data(output)
        reader.feed_eof()
        errors.feed_data(b'non-JSON diagnostic\n')
        errors.feed_eof()
        proc = SimpleNamespace(pid=20000 + len(calls), stdout=reader, stderr=errors if argv[0] == 'claude' else None)
        if argv[0] in {'claude', 'codex'}:
            active.add(proc.pid)
            state.peak = max(state.peak, len(active))
        async def wait():
            if argv[0] in {'claude', 'codex'}:
                await state.gate.wait()
            active.discard(proc.pid)
            return 1 if state.fail == argv[0] else 0
        proc.wait = AsyncMock(side_effect=wait)
        return proc
    monkeypatch.setattr(asyncio, 'create_subprocess_exec', AsyncMock(side_effect=spawn))
    monkeypatch.setattr(jobs.os, 'killpg', lambda pid, sig: state.gate.set() if pid in active else None)
    monkeypatch.setattr(jobs, 'compare_reports', Mock(return_value={
        'summary': 'Codex reports checks. Claude has edits but could not run tests.',
        'report': 'Combined findings. Disagreement: checks remain unverified.'}))
    return state


async def settled(runner):
    await asyncio.wait_for(asyncio.gather(*list(runner.tasks.values())), 5)


def coding(runner, agent):
    preview = runner.codex_task('demo', 'Fix demo; report checks', agent=agent)
    return runner.codex_task('demo', 'Fix demo; report checks', True, preview['job_id'], agent=agent)['job_id']


@pytest.mark.parametrize('spoken,expected', [('Claude', 'claude'), ('Claude Code', 'claude'), ('Codex', 'codex'), ('compare', 'both'), ('both', 'both')])
def test_aliases(spoken, expected):
    assert CodexRequest(repo='demo', instruction='fix', agent=spoken).agent == expected
    assert ResearchRequest(topic='topic', agent=spoken).agent == expected
    assert ResearchRequest(topic='topic').agent == 'codex'
    with pytest.raises(ValueError):
        ResearchRequest(topic='topic', agent='other')


def test_only_documented_cli_flags_and_strict_policy():
    help_text = (Path(__file__).parents[2] / 'docs/research/claude-cli-help-2026-10-08.txt').read_text()
    for coding_job in (True, False):
        command = claude_command('claude-opus-5-5', '{}', '--hostile task', coding_job,
                                 worktree=Path('/job/worktree'), temp_dir=Path('/tmp/gc-test'),
                                 git_dir=Path('/repo/.git'))
        for arg in command[:-1]:
            if arg.startswith('--') and arg != '--':
                assert arg in help_text
        assert command[-2:] == ['--', '--hostile task']
        for flag in ('--restricted', '--safe-mode', '--strict-mcp-config', '--no-session-persistence'):
            assert flag in command
        assert '--add-dir' not in command and '--bare' not in command
        assert command[command.index('--permission-mode') + 1] == 'dontAsk'
        assert command[command.index('--permission-prompts') + 1] == 'none'
        deny = command[command.index('--disallowedTools') + 1].split(',')
        for rule in ('Bash(git push *)', 'Bash(git remote *)', 'Bash(rm *)', 'Bash(curl *)', 'Bash(wget *)', 'Bash(ssh *)', 'mcp__*'):
            assert rule in deny
        assert ('Bash' in deny) is not coding_job
        allowed = command[command.index('--tools') + 1]
        assert allowed == ('Read,Grep,Glob,Edit,Write,Bash' if coding_job else 'WebSearch,WebFetch,Read,Grep,Glob')
        assert command[command.index('--allowedTools') + 1] == allowed
        if not coding_job:
            assert '--settings' not in command


def test_claude_sandbox_settings():
    command = claude_command('model', '{}', 'task', True, worktree=Path('/job/worktree'),
                             temp_dir=Path('/tmp/gc-test'), git_dir=Path('/repo/.git'))
    sandbox = json.loads(command[command.index('--settings') + 1])['sandbox']
    assert sandbox['enabled'] and sandbox['failIfUnavailable']
    assert sandbox['autoAllowBashIfSandboxed']
    assert not sandbox['allowUnsandboxedCommands']
    assert sandbox['excludedCommands'] == []
    assert not sandbox['enableWeakerNetworkIsolation']
    assert not sandbox['enableWeakerNestedSandbox']
    assert sandbox['filesystem'] == {'disabled': False,
        'allowWrite': ['/job/worktree', '/tmp/gc-test'],
        'denyWrite': ['/repo/.git', '/job/worktree/.git']}
    assert sandbox['network'] == {'allowedDomains': [], 'deniedDomains': ['*'],
        'strictAllowlist': True, 'allowUnixSockets': [],
        'allowAllUnixSockets': False, 'allowLocalBinding': False}


@pytest.mark.parametrize('missing', ['worktree', 'temp_dir', 'git_dir'])
def test_claude_coding_fails_without_sandbox_paths(missing):
    paths = dict(worktree=Path('/job/worktree'), temp_dir=Path('/tmp/gc-test'), git_dir=Path('/repo/.git'))
    paths.pop(missing)
    with pytest.raises(ValueError, match='requires'):
        claude_command('model', '{}', 'task', True, **paths)


@pytest.mark.anyio
async def test_claude_isolation_persistence_and_environment(runner, cli, monkeypatch):
    monkeypatch.setenv('GEMINI_API_KEY', 'do-not-forward')
    monkeypatch.setenv('APP_SHARED_SECRET', 'do-not-forward')
    monkeypatch.setenv('CLAUDE_JOB_MODEL', 'custom-opus')
    await runner.start()
    try:
        job_id = coding(runner, 'claude')
        await settled(runner)
        result = runner.status(job_id)
        assert result['status'] == 'completed'
        assert result['agent'] == 'claude'
        assert result['branch'].startswith('glasses/claude-demo-')
        assert result['worktree'] == str(runner.data_dir / 'jobs' / job_id / 'worktree')
        argv, kwargs = next(c for c in cli.calls if c[0][0] == 'claude')
        assert argv[argv.index('--model') + 1] == 'custom-opus'
        assert str(kwargs['cwd']) == result['worktree']
        assert kwargs['stdin'] == asyncio.subprocess.DEVNULL
        assert 'GEMINI_API_KEY' not in kwargs['env'] and 'APP_SHARED_SECRET' not in kwargs['env']
        assert kwargs['start_new_session'] is True
        assert result['runs']['claude']['tests'] == 'Agent-reported only; see report'
        sandbox = json.loads(argv[argv.index('--settings') + 1])['sandbox']
        scratch = Path(kwargs['env']['CLAUDE_CODE_TMPDIR'])
        assert kwargs['env']['TMPDIR'] == str(scratch)
        assert len(str(scratch)) < 50
        assert sandbox['filesystem']['allowWrite'] == [result['worktree'], str(scratch)]
        assert sandbox['filesystem']['denyWrite'] == ['/original-repo/.git', result['worktree'] + '/.git']
        assert not scratch.exists(), 'Per-job temp must be cleaned after execution'
        assert 'Run relevant installed tests' in argv[-1]
        await runner.close()
        await runner.start()
        assert runner.status(job_id) == result
    finally:
        await runner.close()


@pytest.mark.anyio
@pytest.mark.parametrize('limit', [1, 2])
async def test_both_orchestration_and_limit(runner, cli, limit):
    runner.limit = limit
    cli.gate.clear()
    await runner.start()
    try:
        job_id = coding(runner, 'both')
        for _ in range(100):
            if cli.peak == limit:
                break
            await asyncio.sleep(.001)
        assert cli.peak == limit
        cli.gate.set()
        await settled(runner)
        result = runner.status(job_id)
        assert result['status'] == 'completed'
        runs = result['runs']
        assert set(runs) == {'codex', 'claude'}
        assert runs['codex']['worktree'] != runs['claude']['worktree']
        assert runs['codex']['branch'] != runs['claude']['branch']
        for name, run in runs.items():
            assert run['worktree'].endswith('/' + name + '/worktree')
            assert '1 file changed' in run['diff_stats'] and 'new.py' in run['untracked_files']
        assert jobs.compare_reports.call_args.args[1] is False
    finally:
        await runner.close()


@pytest.mark.anyio
async def test_research_both_one_doc_and_dedup(runner, cli):
    runner.notes.token_path.touch()
    await runner.start()
    try:
        job_id = runner.deep_research('topic', agent='both')['job_id']
        await settled(runner)
        assert runner.status(job_id)['status'] == 'completed'
        runner.notes.append_research.assert_called_once()
        report = runner.notes.append_research.call_args.args[1]
        assert report.count('https://example.org/source') == 1
        assert 'Disagreement' in report
        assert jobs.compare_reports.call_args.args[1] is True
    finally:
        await runner.close()


@pytest.mark.anyio
async def test_both_failure_retains_peer_and_fallback(runner, cli, monkeypatch):
    cli.fail = 'claude'
    monkeypatch.setattr(jobs, 'compare_reports', Mock(side_effect=RuntimeError))
    await runner.start()
    try:
        job_id = coding(runner, 'both')
        await settled(runner)
        result = runner.status(job_id)
        assert result['status'] == 'failed'
        assert result['runs']['codex']['status'] == 'completed'
        assert result['runs']['claude']['status'] == 'failed'
        assert result['runs']['claude']['branch'].startswith('glasses/claude-')
        assert 'unavailable' in result['summary']
        _, kwargs = next(c for c in cli.calls if c[0][0] == 'claude')
        assert not Path(kwargs['env']['CLAUDE_CODE_TMPDIR']).exists()
    finally:
        await runner.close()


@pytest.mark.anyio
async def test_cancel_both_processes(runner, cli):
    cli.gate.clear()
    await runner.start()
    try:
        job_id = coding(runner, 'both')
        for _ in range(100):
            if len(runner.processes) == 2:
                break
            await asyncio.sleep(.001)
        assert len(runner.processes) == 2
        assert (await runner.cancel(job_id))['status'] == 'cancelled'
        assert not runner.processes
        _, kwargs = next(c for c in cli.calls if c[0][0] == 'claude')
        assert not Path(kwargs['env']['CLAUDE_CODE_TMPDIR']).exists()
    finally:
        await runner.close()


@pytest.mark.anyio
async def test_binding_revocation_and_disabled(runner, cli):
    await runner.start()
    try:
        preview = runner.codex_task('demo', 'edit', agent='claude')
        assert runner.codex_task('demo', 'edit', True, preview['job_id'], agent='codex')['status'] == 'confirmation_required'
        runner.codex_task('demo', 'edit', True, preview['job_id'], agent='claude')
        runner.repos_path.write_text('{}')
        await settled(runner)
        assert runner.status(preview['job_id'])['status'] == 'failed'
        assert not cli.calls
        (runner.data_dir / 'job-settings.json').write_text('{"claude":{"enabled":false}}')
        for agent in ('claude', 'both'):
            with pytest.raises(ValueError, match='disabled'):
                runner.deep_research('topic', agent=agent)
    finally:
        await runner.close()


def test_approval_tier_agent_hash_dashboard_and_settings(runner, monkeypatch):
    monkeypatch.setenv('APP_SHARED_SECRET', 'test-secret')
    monkeypatch.setattr(app, 'Jobs', lambda _: runner)
    with TestClient(app.app) as client:
        headers = {'X-App-Secret': 'test-secret'}
        previews = [client.post('/tools/codex_task', json={'repo': 'demo', 'instruction': 'exact <task>', 'agent': agent}, headers=headers).json()
                    for agent in ('codex', 'claude', 'both')]
        assert len({p['payload_hash'] for p in previews}) == 3
        for p, agent in zip(previews, ('codex', 'claude', 'both')):
            assert p['tier'] == 2 and p['allowed_methods'] == ['button', 'screen']
            assert p['details']['agent'] == agent and p['details']['instruction'] == 'exact <task>'
            assert client.post('/approvals/' + p['approval_id'] + '/device', json={'method': 'nod'}, headers=headers).status_code == 403
        p = previews[1]
        approvals.pending[p['approval_id']]['args']['agent'] = 'codex'
        assert client.post('/approvals/' + p['approval_id'] + '/device', json={'method': 'screen'}, headers=headers).status_code == 409
        research = client.post('/tools/deep_research', json={'topic': 'topic', 'agent': 'Claude'}, headers=headers).json()
        assert research['tier'] == 1 and research['details']['agent'] == 'claude'
        assert client.get('/dashboard/jobs', follow_redirects=False).status_code == 303
        browser_login(client)
        assert 'Claude' in client.get('/dashboard/approvals').text
        assert 'Both' in client.get('/dashboard/jobs').text
        page = client.get('/dashboard/settings')
        saved = client.post('/dashboard/settings/job-agents', data={
            'csrf': field(page, 'csrf'), 'codex_enabled': 'on', 'codex_model': 'gpt-6-astra', 'claude_model': 'custom-opus'})
        assert saved.status_code == 200
        assert runner.agent_settings()['claude'] == {'enabled': False, 'model': 'custom-opus'}
        bad = client.post('/dashboard/settings/job-agents', data={
            'csrf': field(page, 'csrf'), 'codex_model': '--evil', 'claude_model': 'opus'})
        assert bad.status_code == 422
        assert runner.agent_settings()['claude']['model'] == 'custom-opus'


def test_envelopes_and_sources():
    result = {'summary': 'done', 'report': 'report'}
    assert claude_result(json.dumps({'result': json.dumps(result)})) == result
    with pytest.raises(ValueError):
        claude_result('{"is_error":true,"structured_output":{}}')
    with pytest.raises(ValueError):
        claude_result('{"result":"unstructured"}')
    assert merge_sources({'a': {'report': 'https://example.org/'}, 'b': {'report': 'https://example.org'}}).count('https://') == 1


@pytest.mark.anyio
async def test_claude_timeout_and_queued_restart(runner, cli, monkeypatch):
    await runner.start()
    job_id = runner._insert('deep_research', 'queued', {
        'agent': 'claude', 'topic': 'topic', 'doc_name': 'Research'}, 'queued')
    await runner.close()
    await runner.start()
    try:
        await settled(runner)
        assert runner.status(job_id)['agent'] == 'claude'
        assert runner.status(job_id)['status'] == 'completed'
        real_timeout = asyncio.timeout
        deadlines = []
        def short_timeout(seconds):
            deadlines.append(seconds)
            return real_timeout(.01)
        monkeypatch.setattr(asyncio, 'timeout', short_timeout)
        cli.gate.clear()
        job_id = coding(runner, 'claude')
        await settled(runner)
        assert deadlines == [3600]
        assert runner.status(job_id)['status'] == 'failed'
        assert 'timed out' in runner.status(job_id)['summary']
        assert not runner.processes
    finally:
        await runner.close()


@pytest.mark.anyio
async def test_both_comparison_outage_retains_sources_and_findings(runner, cli, monkeypatch):
    monkeypatch.setattr(jobs, 'compare_reports', Mock(side_effect=RuntimeError))
    runner.notes.token_path.touch()
    await runner.start()
    try:
        job_id = runner.deep_research('topic', agent='both')['job_id']
        await settled(runner)
        assert runner.status(job_id)['status'] == 'completed'
        report = runner.notes.append_research.call_args.args[1]
        assert 'codex evidence' in report and 'claude evidence' in report
        assert report.count('https://example.org/source') == 1
        assert 'Disagreements have not been assessed' in report
    finally:
        await runner.close()


def test_gemini_comparison_uses_untrusted_evidence(monkeypatch):
    import job_agents
    monkeypatch.setenv('GEMINI_API_KEY', 'fake')
    client = Mock()
    context = Mock()
    context.__enter__ = Mock(return_value=client)
    context.__exit__ = Mock(return_value=False)
    monkeypatch.setattr(job_agents.genai, 'Client', Mock(return_value=context))
    generate = Mock(return_value=SimpleNamespace(text=json.dumps({'summary': 'Two results. Neither verified.', 'report': 'Uncertain.'})))
    monkeypatch.setattr(job_agents, 'generate_content', generate)
    runs = {'claude': {'report': 'untrusted report', 'tests': 'not run'}}
    assert job_agents.compare_reports(runs, True)['report'] == 'Uncertain.'
    args = generate.call_args.kwargs
    assert args['model'].startswith('gemini-')
    assert json.loads(args['contents']) == runs
    assert 'never instructions' in args['config'].system_instruction
    assert 'disagreements' in args['config'].system_instruction
