"""Read-to-me tools run on Android; Gemini supplies text from the attached photo."""

from google.genai import types

READING_TOOLS = [
    types.FunctionDeclaration(
        name="read_text",
        description=(
            "Client-executed: capture a high resolution photo to read text. Use for reading, "
            "summarizing, finding an answer in visible text, or translating into English. "
            "On retry_required, ask the user to hold still then retry with retry=true (at most twice). "
            "On frame_sent, call submit_reading with the page_id and complete text for the requested mode."
        ),
        parameters=types.Schema(type="OBJECT", properties={
            "mode": types.Schema(type="STRING", enum=["verbatim", "summary", "find", "translate"]),
            "question": types.Schema(type="STRING", description="Required for find: question about the text."),
            "retry": types.Schema(type="BOOLEAN", description="True only after retry_required; keep mode/question unchanged."),
        }, required=["mode"]),
    ),
    types.FunctionDeclaration(
        name="submit_reading",
        description=(
            "Client-executed: store the captured page's reading text and get the first chunk to speak. "
            "Only after read_text or next_page returns frame_sent. For verbatim supply ALL visible text "
            "in reading order, for translate the full English translation, for summary the gist, "
            "and for find the answer to the question. Mark illegible text; never guess. "
            "Speak ONLY the returned chunk, not the entire submitted text."
        ),
        parameters=types.Schema(type="OBJECT", properties={
            "page_id": types.Schema(type="INTEGER", description="Exact page_id returned by the capture."),
            "text": types.Schema(type="STRING", description="Complete reading text, at most 60000 characters."),
        }, required=["page_id", "text"]),
    ),
    types.FunctionDeclaration(
        name="reading_control",
        description=(
            "Client-executed: next_page captures a new page in the current mode; continue gets the "
            "next chunk for 'keep reading'; repeat replays the current chunk for 'repeat that'; "
            "stop pauses reading. After a stop, continue repeats the interrupted chunk. "
            "Speak returned text exactly, then wait for the user. On page_complete ask for next page."
        ),
        parameters=types.Schema(type="OBJECT", properties={
            "action": types.Schema(type="STRING", enum=["next_page", "continue", "repeat", "stop"]),
        }, required=["action"]),
    ),
]
