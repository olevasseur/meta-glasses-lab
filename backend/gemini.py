"""Server-owned Gemini Live session configuration and ephemeral tokens."""

from datetime import UTC, datetime, timedelta
from pathlib import Path

from google import genai
from google.genai import types

from translation import START_TRANSLATION, STOP_TRANSLATION, translator_instruction

DEFAULT_MODEL = "gemini-3.8-live"
PROMPT_PATH = Path(__file__).parent / "prompts" / "system.md"

APPEND_TO_DOC = types.FunctionDeclaration(
    name="append_to_doc",
    description=(
        "Append a requested note to a Google Doc. On needs_confirmation, ask the "
        "user to choose a returned candidate before supplying confirmed_doc_id. "
        "On not_found, offer Inbox. Do not automatically retry failed writes."
    ),
    parameters=types.Schema(
        type="OBJECT",
        properties={
            "doc_name": types.Schema(
                type="STRING", description="Document title or alias; defaults to Inbox."
            ),
            "text": types.Schema(type="STRING", description="The note to append."),
            "confirmed_doc_id": types.Schema(
                type="STRING",
                description="ID from a candidate the user explicitly selected.",
            ),
        },
        required=["text"],
    ),
)


def token_config(model: str, mode: str, persona: str = "assistant", language: str | None = None) -> types.CreateAuthTokenConfig:
    if persona not in ("assistant", "translator") or (persona == "translator") != (language is not None):
        raise ValueError("Translator persona requires a language; assistant does not")
    now = datetime.now(UTC)
    detection = (
        {"disabled": True}
        if mode == "ptt"
        else {
            "disabled": False,
            "start_of_speech_sensitivity": "START_SENSITIVITY_LOW",
            "end_of_speech_sensitivity": "END_SENSITIVITY_LOW",
            "prefix_padding_ms": 300,
            "silence_duration_ms": 1200,
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
                system_instruction=(translator_instruction(language) if persona == "translator"
                                    else PROMPT_PATH.read_text(encoding="utf-8")),
                response_modalities=["AUDIO"],
                speech_config={
                    "voice_config": {"prebuilt_voice_config": {"voice_name": "Kore"}}
                },
                input_audio_transcription={},
                output_audio_transcription={},
                realtime_input_config={"automatic_activity_detection": detection},
                context_window_compression={"sliding_window": {}},
                tools=[types.Tool(function_declarations=(
                    [START_TRANSLATION, STOP_TRANSLATION] if persona == "translator"
                    else [APPEND_TO_DOC, START_TRANSLATION, STOP_TRANSLATION]
                ))],
            ),
        ),
    )


def create_token(api_key: str, model: str, mode: str, persona: str = "assistant", language: str | None = None) -> dict:
    config = token_config(model, mode, persona, language)
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
    }
