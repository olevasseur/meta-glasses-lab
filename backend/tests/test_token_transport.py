from types import SimpleNamespace
from unittest.mock import MagicMock

import gemini
from token_transport import TokenTransport


def test_connections_reused_but_tokens_and_constraints_are_fresh(monkeypatch):
    factory = MagicMock()
    client = factory.return_value.__enter__.return_value
    client.auth_tokens.create.side_effect = [SimpleNamespace(name=f'token-{i}', expire_time=None) for i in range(2)]
    monkeypatch.setattr(gemini.genai, 'Client', factory)
    pool = TokenTransport()
    monkeypatch.setattr(gemini, 'token_transport', pool)
    try:
        first = gemini.create_token('synthetic', 'model', 'auto', system_instruction='first memory')
        second = gemini.create_token('synthetic', 'model', 'over', system_instruction='new memory')
        assert first['token'] != second['token']
        assert factory.call_count == 1
        limits = factory.call_args.kwargs['http_options'].client_args['limits']
        assert limits.keepalive_expiry == 120 and limits.max_connections == 2
        configs = [call.kwargs['config'] for call in client.auth_tokens.create.call_args_list]
        assert all(c.uses == 1 and c.lock_additional_fields == [] for c in configs)
        assert configs[0].live_connect_constraints.config.system_instruction == 'first memory'
        assert configs[1].live_connect_constraints.config.system_instruction == 'new memory'
        assert configs[1].live_connect_constraints.config.realtime_input_config.automatic_activity_detection.disabled
        factory.return_value.__exit__.assert_not_called()
    finally:
        pool.close()
    factory.return_value.__exit__.assert_called_once()


def test_key_timeout_rotation_and_close_release_transport(monkeypatch):
    factory = MagicMock()
    monkeypatch.setattr(gemini.genai, 'Client', factory)
    pool = TokenTransport()
    for key, timeout in [('a', 1000), ('a', 1000), ('b', 1000), ('b', 2000)]:
        with pool.client(key, timeout):
            pass
    assert factory.call_count == 3
    assert factory.return_value.__exit__.call_count == 2
    pool.close()
    pool.close()
    assert factory.return_value.__exit__.call_count == 3
    with pool.client('b', 2000):
        pass
    assert factory.call_count == 4
    pool.close()
