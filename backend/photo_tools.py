from google.genai import types

SAVE_PHOTO = types.FunctionDeclaration(
    name="save_photo",
    description=("Client-executed: take a full-resolution glasses still and save it to the Mac. "
                 "Use for 'take a picture and send it to my Mac', 'save a photo to my computer', "
                 "or 'snap a photo'. No approval. Does not analyze, caption, or send images to Gemini. "
                 "Optional short label only, never a destination path. Confirm the returned result; "
                 "queued means saved on phone, not yet on Mac. Never repeat the capture to retry delivery."),
    parameters=types.Schema(type="OBJECT", properties={
        "label": types.Schema(type="STRING", description="Optional short descriptive filename label (up to 80 characters)."),
    }),
)
