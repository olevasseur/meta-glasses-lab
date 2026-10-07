from unittest.mock import Mock

import pytest

import google_auth
from notes import SCOPES


@pytest.fixture
def oauth(monkeypatch, tmp_path):
    secret_dir = tmp_path / "secrets"
    secret_dir.mkdir()
    (secret_dir / "client_secret.json").write_text("{}")
    token_path = secret_dir / "token.json"
    monkeypatch.setattr(google_auth, "BACKEND_DIR", tmp_path)
    monkeypatch.setattr(google_auth, "TOKEN_PATH", token_path)
    credentials = Mock(refresh_token="fake-refresh-token")
    credentials.has_scopes.return_value = True
    credentials.to_json.return_value = '{"refresh_token":"fake-refresh-token"}'
    flow = Mock()
    flow.run_local_server.return_value = credentials
    factory = Mock(return_value=flow)
    monkeypatch.setattr(google_auth.InstalledAppFlow, "from_client_secrets_file", factory)
    return factory, flow, credentials, token_path


def test_oauth_desktop_flow_and_instructions(oauth, capsys):
    factory, flow, credentials, token_path = oauth
    google_auth.main()
    factory.assert_called_once_with(str(token_path.parent / "client_secret.json"), SCOPES)
    flow.run_local_server.assert_called_once_with(
        host="localhost", port=0, access_type="offline", prompt="consent"
    )
    credentials.has_scopes.assert_called_once_with(SCOPES)
    assert token_path.stat().st_mode & 0o777 == 0o600
    output = capsys.readouterr().out
    for instruction in ("Cloud project", "Docs API", "Drive API", "External", "PUBLISH TO PRODUCTION", "Desktop", "re-consent"):
        assert instruction in output
    assert "fake-refresh-token" not in output


@pytest.mark.parametrize("missing", ["refresh_token", "scope"])
def test_oauth_incomplete_consent_preserves_existing_token(oauth, missing):
    _, _, credentials, token_path = oauth
    token_path.write_text("existing-credentials")
    if missing == "refresh_token":
        credentials.refresh_token = None
    else:
        credentials.has_scopes.return_value = False
    with pytest.raises(SystemExit, match="both scopes are required"):
        google_auth.main()
    assert token_path.read_text() == "existing-credentials"
