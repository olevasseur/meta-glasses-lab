"""Client-executed gesture tool; no backend side effects."""

from google.genai import types

AWAIT_GESTURE_CONFIRMATION = types.FunctionDeclaration(
    name="await_gesture_confirmation",
    description=(
        "Before asking one explicit yes/no confirmation question, check and arm "
        "head gestures on the phone. Supply the exact question, then ask it aloud. "
        "The phone returns ready or unavailable; use spoken answers if unavailable. "
        "Never use for an ambiguous choice among multiple items."
    ),
    parameters=types.Schema(
        type="OBJECT",
        properties={"question": types.Schema(type="STRING", description="One yes/no question, at most 500 characters.")},
        required=["question"],
    ),
)
