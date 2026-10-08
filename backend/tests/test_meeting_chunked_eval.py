import importlib.util
from pathlib import Path

spec = importlib.util.spec_from_file_location('chunked_eval', Path(__file__).parents[2] / 'scripts/eval_meeting_chunked.py')
eval = importlib.util.module_from_spec(spec)
spec.loader.exec_module(eval)


def test_word_error_metrics_distinguish_duplicates_and_omissions():
    result = eval.word_errors('Launch on Friday with forty people', 'Launch on Friday Friday with people')
    assert result['insertions'] == 1
    assert result['deletions'] == 1
    assert result['substitutions'] == 0
    assert result['wer'] == 2/6


def test_eval_chunks_have_same_two_second_overlap_as_device(tmp_path):
    from meeting_chunks import wav_duration
    data = bytes(range(256)) * 8000
    (tmp_path / 'meeting.pcm').write_bytes(data)
    chunks = list(eval.chunks(tmp_path))
    assert len(chunks) == 2
    assert chunks[0][:2] == (0, 0)
    assert chunks[1][:2] == (1, 58000)
    assert chunks[0][2][-64000:] == chunks[1][2][44:64044]
    assert wav_duration(chunks[0][2]) == 60000
