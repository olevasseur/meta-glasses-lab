"""Real Gemini extraction check; no credentials/source text logged. Run from backend/."""

import sys
from pathlib import Path

from dotenv import load_dotenv

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "backend"))
from coach import extract_project

load_dotenv(Path(__file__).resolve().parents[1] / ".env")


def main():
    project = extract_project("cooking", "Two servings of simple pasta. Ingredients: 200 g pasta, 1 tbsp olive oil, salt to taste. Equipment: pot and colander. Steps: Bring water to a boil. Cook the pasta for 8 minutes. Drain and toss with olive oil and salt.")
    assert len(project.steps) >= 3
    assert any(step.duration_seconds == 480 for step in project.steps)
    assert any(item.name.lower() == "pasta" and item.quantity == "200" and item.unit == "g" for item in project.ingredients)
    assert project.tools
    print(f"PASS: real Gemini extraction; {len(project.steps)} steps, {len(project.ingredients)} ingredients; pasta quantity and 480-second duration verified")


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print(f"FAIL: coach extraction ({type(exc).__name__}); upstream details withheld")
        raise SystemExit(1)
