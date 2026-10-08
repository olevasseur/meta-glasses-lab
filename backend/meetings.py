"""Meeting lifecycle. Each end poll does at most one bounded Gemini or Docs operation.

Progress lives beside the existing memory transcript, so restarts resume map/reduce.
"""
import json
from datetime import UTC, datetime
from threading import Lock

from memory import MemoryConflict
from meeting_docs import prepare_document, write_minutes
from meeting_summary import CHUNK_CHARS, REDUCE_FAN_IN, pack_summaries, render_minutes, summarize_meeting


class Meetings:
    def __init__(self, memory, notes):
        self.memory, self.notes = memory, notes
        # ponytail: single personal backend worker; durable job queue if multi-worker is needed.
        self.lock = Lock()

    def _load(self, db, meeting_id):
        db.execute('''CREATE TABLE IF NOT EXISTS meetings (
            id TEXT PRIMARY KEY REFERENCES sessions(id), data TEXT NOT NULL)''')
        row = db.execute('SELECT data FROM meetings WHERE id=?', (meeting_id,)).fetchone()
        return json.loads(row[0]) if row else None

    @staticmethod
    def _save(db, m):
        db.execute('INSERT OR REPLACE INTO meetings VALUES (?,?)', (m['id'], json.dumps(m)))

    def start(self, meeting_id: str, title: str, doc_name: str | None = None):
        with self.lock, self.memory.lock, self.memory.db() as db:
            existing = self._load(db, meeting_id)
            if existing:
                if (existing['title'], existing['doc_name']) != (title, doc_name):
                    raise MemoryConflict('Meeting metadata cannot change')
                return {'status': 'ok', 'meeting_id': meeting_id}
            now = datetime.now(UTC).isoformat()
            session = db.execute('SELECT ended_at FROM sessions WHERE id=?', (meeting_id,)).fetchone()
            if session and session['ended_at']:
                raise MemoryConflict('Session already ended')
            db.execute('INSERT OR IGNORE INTO sessions(id,started_at) VALUES (?,?)', (meeting_id, now))
            self._save(db, {'id': meeting_id, 'title': title, 'doc_name': doc_name, 'started_at': now,
                            'phase': 'recording', 'offset': 0, 'partials': [], 'reduced': [], 'document': None})
        return {'status': 'ok', 'meeting_id': meeting_id}

    def reject_export(self, meeting_id):
        with self.lock, self.memory.lock, self.memory.db() as db:
            meeting = self._load(db, meeting_id)
            if meeting and meeting['phase'] == 'document' and not meeting['document']:
                meeting['export_rejected'] = True
                self._save(db, meeting)

    def step(self, meeting_id: str, *, allow_export=True, approved_snapshot=None):
        with self.lock:
            with self.memory.lock, self.memory.db() as db:
                m = self._load(db, meeting_id)
                if not m:
                    raise MemoryConflict('Unknown meeting')
                if m.get('export_rejected'):
                    return {'status': 'rejected', 'meeting_id': meeting_id}
                if approved_snapshot is not None and any(m.get(k) != v for k, v in approved_snapshot.items()):
                    raise MemoryConflict('Meeting changed since approval')
                if not allow_export and not m['doc_name'] and not m['document'] and m['phase'] in {'document', 'write'}:
                    return self._result(m)
                if m['phase'] == 'done':
                    return self._result(m)
                # The Android outbox invokes end only after all chunks are acknowledged.
                if m['phase'] == 'recording':
                    db.execute('UPDATE sessions SET ended_at=? WHERE id=?', (datetime.now(UTC).isoformat(), meeting_id))
                    m['phase'] = 'map'
                    self._save(db, m)
                # Read one bounded segment; no last-N truncation or giant RAM transcript.
                # ponytail: rescan earlier rows per map; use a sequence cursor for day-long archives.
                # Offset is a character offset in ordered user text, across chunk boundaries.
                rows = db.execute("SELECT text FROM turns WHERE session_id=? AND speaker='user' ORDER BY seq", (meeting_id,))
                skip, parts, remaining = m['offset'], [], CHUNK_CHARS
                if m['phase'] == 'map':
                    for row in rows:
                        text = row['text']
                        if skip >= len(text):
                            skip -= len(text)
                            continue
                        piece = text[skip:skip + remaining]
                        parts.append(piece)
                        remaining -= len(piece)
                        skip = 0
                        if not remaining:
                            break
                rows.close()
            if m['phase'] == 'map':
                text = ''.join(parts)
                if text:
                    m['partials'].append(summarize_meeting(text))
                    m['offset'] += len(text)
                else:
                    if not m['partials']:
                        m['partials'] = [{'summary': 'No audible transcript was captured.', 'decisions': [],
                                          'action_items': [], 'open_questions': [],
                                          'recap': ['No audible transcript was captured.', 'There are no recorded decisions or action items.']}]
                    m['phase'] = 'reduce'
            elif m['phase'] == 'reduce':
                if len(m['partials']) == 1 and not m['reduced']:
                    m['result'] = m['partials'][0]
                    m['phase'] = 'document'
                else:
                    batch = m['partials'][:REDUCE_FAN_IN]
                    m['reduced'].append(batch[0] if len(batch) == 1 else summarize_meeting(pack_summaries(batch), reducing=True))
                    m['partials'] = m['partials'][REDUCE_FAN_IN:]
                    if not m['partials']:
                        m['partials'], m['reduced'] = m['reduced'], []
            elif m['phase'] == 'document':
                m['document'] = prepare_document(self.notes, m)
                m['phase'] = 'write'
            elif m['phase'] == 'write':
                write_minutes(self.notes, m, m['document'], m['result'])
                m['phase'] = 'done'
            with self.memory.lock, self.memory.db() as db:
                # Store summaries as historical meeting evidence, never third-party user facts.
                if m.get('result'):
                    text = f"Meeting: {m['title']}\n{render_minutes(m['result'])}"
                    if not self.memory._blocked(text, self.memory._forgotten(db)):
                        db.execute('INSERT OR REPLACE INTO summaries VALUES (?,?,?)',
                                   (meeting_id, text, datetime.now(UTC).isoformat()))
                self._save(db, m)
            return self._result(m)

    @staticmethod
    def _result(m):
        result = {'status': 'ok' if m['phase'] == 'done' else 'processing', 'phase': m['phase'], 'meeting_id': m['id']}
        if m.get('result'):
            result['minutes'] = m['result']
        if m['document']:
            result['doc_url'] = f"https://docs.google.com/document/d/{m['document']['id']}/edit"
        return result
