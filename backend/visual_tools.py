"""Visual memory declarations, kept separate from the Live protocol."""
from google.genai import types


def declaration(name, description, properties, required):
    return types.FunctionDeclaration(name=name, description=description,
        parameters=types.Schema(type="OBJECT", properties=properties, required=required))


CLIENT_TOOLS = [
    declaration("set_visual_memory", "Client-executed: opt-in periodic visual memory. Only enable on explicit request. Pause visual memory means on=false. Defaults to 30 minutes, maximum 120.",
                {"on": types.Schema(type="BOOLEAN"), "minutes": types.Schema(type="INTEGER")}, ["on"]),
    declaration("remember_this", "Client-executed: capture now and pin an object/place snapshot, e.g. remember where I parked. Never identify people or faces.",
                {"label": types.Schema(type="STRING")}, []),
    declaration("delete_visual_memory", "Client-executed: pause capture and delete recent visual memories including pins. 'Delete the last hour' means minutes=60. Only on explicit request.",
                {"minutes": types.Schema(type="INTEGER")}, []),
]
SEARCH_TOOLS = [declaration(name,
    "Search historical object/place/text sightings by specific keywords. Never search for or identify people/faces. Report the sighting time and uncertainty; no match means unknown, never guess a location.",
    {"query": types.Schema(type="STRING")}, ["query"])
    for name in ("find_object", "when_did_i_see")]
