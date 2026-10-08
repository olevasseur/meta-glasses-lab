"""Server-owned Gemini Live session configuration and ephemeral tokens."""

from datetime import UTC, datetime, timedelta
from pathlib import Path

from google import genai
from google.genai import types

from tools import CLIENT_TOOL_NAMES, DECLARATIONS

DEFAULT_MODEL = "gemini-3.8-live"
PROMPT_PATH = Path(__file__).parent / "prompts" / "system.md"


def token_config(
    model: str, mode: str, system_instruction: str | None = None, *, silence_ms: int = 2000,
) -> types.CreateAuthTokenConfig:
    now = datetime.now(UTC)
    detection = (
        {"disabled": True}
        if mode in ("ptt", "over")
        else {
            "disabled": False,
            "start_of_speech_sensitivity": "START_SENSITIVITY_LOW",
            "end_of_speech_sensitivity": "END_SENSITIVITY_LOW",
            "prefix_padding_ms": 300,
            "silence_duration_ms": max(500, min(5000, silence_ms)),
        }
    )
    return types.CreateAuthTokenConfig(
        uses=1,
        expire_time=now + timedelta(minutes=30),
        new_session_expire_time=now + timedelta(seconds=60),
        # lock_additional_fields=[] locks only the fields populated below (sent as
        # fieldMask). sessionResumption is deliberately left out so the app can pass
        # its latest resumption handle on reconnect; a global lock would ignore it.
        lock_additional_fields=[],
        live_connect_constraints=types.LiveConnectConstraints(
            model=model,
            config=types.LiveConnectConfig(
                system_instruction=system_instruction or PROMPT_PATH.read_text(encoding="utf-8"),
                response_modalities=["AUDIO"],
                speech_config={
                    "voice_config": {"prebuilt_voice_config": {"voice_name": "Kore"}}
                },
                input_audio_transcription={},
                output_audio_transcription={},
                realtime_input_config={"automatic_activity_detection": detection},
                context_window_compression={"sliding_window": {}},
                tools=[types.Tool(function_declarations=DECLARATIONS)],
            ),
        ),
    )


def create_token(
    api_key: str, model: str, mode: str, system_instruction: str | None = None, *, silence_ms: int = 2000,
) -> dict:
    config = token_config(model, mode, system_instruction, silence_ms=silence_ms)
    with genai.Client(
        api_key=api_key,
        vertexai=False,
        http_options=types.HttpOptions(api_version="v1beta", timeout=30_000),
    ) as client:
        token = client.auth_tokens.create(config=config)
    if not token.name:
        raise RuntimeError("Gemini returned no ephemeral token")
    return {
        "token": token.name,
        "model": model,
        "expires_at": token.expire_time or config.expire_time.isoformat(),
        "client_tools": CLIENT_TOOL_NAMES,
    }
