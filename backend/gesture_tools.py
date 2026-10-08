"""Client-executed gesture tool; no backend side effects."""

from google.genai import types

AWAIT_GESTURE_CONFIRMATION = types.FunctionDeclaration(
    name="await_gesture_confirmation",
    description=(
        "For ordinary conversational yes/no questions only, check and arm "
        "head gestures on the phone. Supply the exact question, then ask it aloud. "
        "The phone returns ready or unavailable; use spoken answers if unavailable. "
        "Never use for backend action approval or an ambiguous choice among multiple items."
    ),
    parameters=types.Schema(
        type="OBJECT",
        properties={"question": types.Schema(type="STRING", description="One yes/no question, at most 500 characters.")},
        required=["question"],
    ),
)
