"""Meeting export with a durable document ID and a retry marker in the same Docs write."""
import json
from hashlib import sha256

from meeting_summary import render_minutes


def prepare_document(notes, meeting: dict) -> dict:
    with notes.lock:
        notes._connect()
        if meeting['doc_name']:
            # Existing aliases/exact titles only: never silently append to a fuzzy match.
            aliases = json.loads(notes.aliases_path.read_text(encoding='utf-8'))
            doc_id = next((v for k, v in aliases.items() if k.casefold() == meeting['doc_name'].casefold()), None)
            if doc_id:
                return notes._document(doc_id)
            matches = [d for d in notes._list_documents(refresh=True)
                       if d['title'].casefold() == meeting['doc_name'].casefold()]
            if len(matches) != 1:
                raise ValueError('Meeting destination must be an unambiguous exact title or alias')
            return matches[0]
        files = notes.drive.files()
        marker_id = sha256(meeting['id'].encode()).hexdigest()
        # Session IDs are API-validated; appProperties recover a lost create reply.
        found = files.list(q="trashed=false and appProperties has { key='glassesMeeting' and value='" + marker_id + "' }",
                           fields='files(id,name),incompleteSearch', pageSize=100).execute()
        if found.get('incompleteSearch'):
            raise RuntimeError('Incomplete meeting search')
        if found.get('files'):
            f = found['files'][0]
            return {'id': f['id'], 'title': f['name']}
        folders = files.list(q="trashed=false and mimeType='application/vnd.google-apps.folder' and name='Glasses Meetings' and appProperties has { key='glassesMeetingsFolder' and value='true' }",
                             fields='files(id),incompleteSearch', pageSize=100).execute()
        if folders.get('incompleteSearch'):
            raise RuntimeError('Incomplete folder search')
        folder = (folders.get('files') or [None])[0]
        if folder is None:
            folder = files.create(body={'name': 'Glasses Meetings', 'mimeType': 'application/vnd.google-apps.folder',
                                        'appProperties': {'glassesMeetingsFolder': 'true'}}, fields='id').execute()
        title = f"Meeting – {meeting['title']} – {meeting['started_at'][:10]}"
        doc = files.create(body={'name': title, 'mimeType': 'application/vnd.google-apps.document',
                                 'parents': [folder['id']], 'appProperties': {'glassesMeeting': marker_id}},
                           fields='id').execute()
        notes.cache_expires_at = 0
        return {'id': doc['id'], 'title': title}


def write_minutes(notes, meeting: dict, document: dict, result: dict):
    with notes.lock:
        notes._connect()
        marker = f"[glasses-meeting:{meeting['id']}]"
        current = notes.docs.documents().get(documentId=document['id']).execute()
        # Marker and minutes are one atomic batch. A lost reply is safe to retry.
        if marker in json.dumps(current, ensure_ascii=False):
            return
        text = f"\nMeeting – {meeting['title']} – {meeting['started_at'][:10]}\n{render_minutes(result)}\n{marker}\n"
        notes.docs.documents().batchUpdate(documentId=document['id'], body={
            'writeControl': {'requiredRevisionId': current['revisionId']},
            'requests': [{'insertText': {'endOfSegmentLocation': {'segmentId': ''}, 'text': text}}],
        }).execute()
