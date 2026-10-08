"""Independent answer keys; Gemini writes dialogue, never its own grading oracle."""

from pydantic import BaseModel, Field


class Dialogue(BaseModel):
    user: str = Field(min_length=1, max_length=4000)
    model: str = Field(min_length=1, max_length=1000)


class History(BaseModel):
    sessions: list[Dialogue] = Field(min_length=12, max_length=12)


def specifications():
    names = ["Alex", "Blair", "Casey", "Drew", "Ellis", "Fran", "Gale", "Harper", "Indigo", "Jules"]
    cities = ["Toronto", "Montreal", "Ottawa", "Halifax", "Calgary", "Victoria", "Winnipeg", "Regina", "Kelowna", "Kingston"]
    for i, name in enumerate(names):
        sister, corrected = [("Ann", "Ana"), ("Sara", "Sarah"), ("Lily", "Lila"), ("Jane", "Jade"), ("Nina", "Mina")][i % 5]
        pet = ["Maple", "Cedar", "Birch", "Willow", "Aspen", "Hazel", "Olive", "Poppy", "Clover", "Fern"][i]
        old_trip, trip = [("Lisbon", "Kyoto"), ("Paris", "Oslo"), ("Rome", "Lima"), ("Dublin", "Seoul"), ("Berlin", "Perth")][i % 5]
        nickname = f"Copper Comet {i}"
        seeds = [
            f"My name is {name}. My sister is named {sister}. My dog is named {pet}.",
            f"I live in {cities[i]}. I prefer coffee and brief spoken answers.",
            f"My next holiday will be in {old_trip} in November.",
            f"My hobby project nickname is {nickname}. It is a pottery project.",
            f"Actually my sister's name is {corrected}, not {sister}. Correct what you remember.",
            "I switched drinks. I now prefer peppermint tea instead of coffee.",
            f"Plans changed: my next holiday is {trip} in December, not {old_trip} in November.",
            f"{pet} is the name of my dog. Please keep responses brief; I still live in {cities[i]}.",
            "Forget my hobby project, including its nickname and the craft involved.",
            "If I owned a horse, I might call it Nimbus. That is hypothetical; I do not own a horse.",
            "Do you know my favourite author? I have never told you. Do not assume one.",
            "That is all for today. What did we discuss?",
        ]
        probes = [
            {"id": "dog", "kind": "recall", "query": "What is my dog's name?", "expected": [pet], "forbidden": []},
            {"id": "city", "kind": "recall", "query": "Which city do I live in?", "expected": [cities[i]], "forbidden": []},
            {"id": "sister", "kind": "correction", "query": "What is my sister's current name?", "expected": [corrected], "forbidden": [sister]},
            {"id": "drink", "kind": "correction", "query": "Which drink do I prefer now?", "expected": ["peppermint tea"], "forbidden": ["coffee"]},
            {"id": "holiday", "kind": "correction", "query": "Where and when is my next holiday?", "expected": [trip, "December"], "forbidden": [old_trip, "November"]},
            {"id": "forgotten", "kind": "forget", "query": "What was my hobby project nickname and craft?", "expected": [], "forbidden": [nickname, "pottery"]},
            {"id": "unknown", "kind": "unknown", "query": "Who is my favourite author?", "expected": [], "forbidden": []},
            {"id": "hypothetical", "kind": "unknown", "query": "What is the name of my pet horse?", "expected": [], "forbidden": ["Nimbus"]},
        ]
        yield {"id": name.lower(), "seeds": seeds, "probes": probes, "forget_after": 8, "forget_query": "hobby project"}
