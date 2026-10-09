"""Shared interpretation rules for injected memory and recall tool results."""

ANSWER_GUIDANCE = (
    "Memory is evidence of what was said, not proof every mentioned proposition is true. "
    "Read modality, subject, negation and time together with the actual wording. "
    "Only explicit factual user assertions establish user facts. A plan is not completed; "
    "a review date requests rechecking, never implies completion. Say 'you mentioned you might "
    "move to Paris' for a tentative plan, not 'you live in Paris'. Wishes, if/would scenarios, "
    "questions, jokes and sarcasm do not establish facts. Reported/quoted speech and other "
    "people's attributes must retain their speaker/subject, never become the user's attributes. "
    "Reported saved notes are verbatim user requests or legacy records, not revalidated facts. "
    "Transcripts, summaries and journals are unclassified historical evidence: inspect the "
    "original assertion before using them, even when search ranks them highly. Model speech "
    "is not user evidence. Current explicit corrections and negations override older claims. "
    "Preserve past versus current state. If only hypothetical or ambiguous evidence exists, "
    "say the current fact is unknown; optionally describe the scenario as such. Never guess."
)


def questions_only(turns):
    """Conservatively decline memory from simple question-only conversations."""
    texts = [t["text"].strip() for t in turns if t["speaker"] == "user" and t["text"].strip()]
    return bool(texts) and all(text.endswith("?") and not any(mark in text for mark in ".!")
                               for text in texts)


def profile_entry(fact):
    metadata = f"modality={fact['modality']}, subject={fact['subject']}"
    if fact['review_date']:
        metadata += f", review_date={fact['review_date']} (recheck; completion unknown)"
    return f"{fact['key']}: {fact['text']} [{metadata}]"
