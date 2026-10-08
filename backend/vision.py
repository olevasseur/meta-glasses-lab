"""Client-executed tools; declarations go to Gemini, execution stays on Android."""

from google.genai import types

CLIENT_TOOLS = [
    types.FunctionDeclaration(
        name="look",
        description=(
            "Client-executed: capture a fresh glasses camera frame for a visual question. "
            "After frame_sent, answer the question using the image sent to the live video stream."
        ),
        parameters=types.Schema(
            type="OBJECT",
            properties={"question": types.Schema(type="STRING", description="What to look for.")},
            required=["question"],
        ),
    ),
    types.FunctionDeclaration(
        name="set_watch_mode",
        description=(
            "Client-executed: enable or disable glasses camera frames at up to 1 fps. "
            "Only enable when the user asks to watch continuously. Automatically stops after 2 minutes."
        ),
        parameters=types.Schema(
            type="OBJECT",
            properties={"on": types.Schema(type="BOOLEAN", description="True to start, false to stop.")},
            required=["on"],
        ),
    ),
]
CLIENT_TOOL_NAMES = [tool.name for tool in CLIENT_TOOLS]
