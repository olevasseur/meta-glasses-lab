"""Real Gemini check with fictional data only; never writes to Google Docs or personal memory.

cd backend && uv run python ../scripts/smoke_meeting.py [--live]
--live uses macOS say/afconvert to create disposable PCM speech samples.
"""
import asyncio
import base64
import json
import os
from pathlib import Path
import subprocess
import sys
from tempfile import TemporaryDirectory
import wave

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'backend'))
import app  # Loads ignored .env.
import gemini
from meeting_summary import CHUNK_CHARS, REDUCE_FAN_IN, pack_summaries, summarize_meeting


async def live(directory):
    from websockets.asyncio.client import connect
    async def speech(text, name):
        aiff, wav = Path(directory) / f'{name}.aiff', Path(directory) / f'{name}.wav'
        subprocess.run(['say', '-v', 'Samantha', '-o', str(aiff), text], check=True)
        subprocess.run(['afconvert', '-f', 'WAVE', '-d', 'LEI16@16000', '-c', '1', str(aiff), str(wav)], check=True)
        with wave.open(str(wav)) as f:
            return f.readframes(f.getnframes())
    ordinary = await speech('Maya here. We agreed to launch the pilot on Friday. Alex will send the budget tomorrow. Does the team agree?', 'ordinary')
    wake = await speech('Hey assistant, what day did we agree to launch the pilot? Answer briefly.', 'wake')
    # Use exactly the app token configuration, without personal memory injection.
    token = gemini.create_token(os.environ['GEMINI_API_KEY'], gemini.DEFAULT_MODEL, 'meeting')
    endpoint = 'wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContentConstrained'
    handle = None
    async with connect(endpoint, additional_headers={'Authorization': 'Token ' + token['token']}) as ws:
        await ws.send(json.dumps({'setup': {'model': 'models/' + token['model'], 'sessionResumption': {}}}))
        assert 'setupComplete' in json.loads(await asyncio.wait_for(ws.recv(), 20))
        async def probe(pcm, label):
            nonlocal handle
            async def send():
                data = bytes(16000) + pcm + bytes(32000 * 4)
                for i in range(0, len(data), 3200):
                    await ws.send(json.dumps({'realtimeInput': {'audio': {'mimeType': 'audio/pcm;rate=16000', 'data': base64.b64encode(data[i:i+3200]).decode()}}}))
                    await asyncio.sleep(.1)
            sender = asyncio.create_task(send())
            transcript, output, audio_bytes, nonzero = [], [], 0, 0
            async def decline_tools(message):
                calls = message.get('toolCall', {}).get('functionCalls', [])
                if calls:
                    print('Probe declined tools (no side effects): ' + ', '.join(c['name'] for c in calls), flush=True)
                    await ws.send(json.dumps({'toolResponse': {'functionResponses': [
                        {'id': c['id'], 'name': c['name'], 'response': {'status': 'ignored', 'message': 'Read-only synthetic probe; no tools executed.'}}
                        for c in calls]}}))
            try:
                async with asyncio.timeout(len(pcm)/32000 + 12):
                    while True:
                        message = json.loads(await ws.recv())
                        await decline_tools(message)
                        update = message.get('sessionResumptionUpdate', {})
                        if update.get('resumable') and update.get('newHandle'):
                            handle = update['newHandle']
                        c = message.get('serverContent', {})
                        if c.get('inputTranscription'): transcript.append(c['inputTranscription'].get('text', ''))
                        if c.get('outputTranscription'): output.append(c['outputTranscription'].get('text', ''))
                        for part in c.get('modelTurn', {}).get('parts', []):
                            if part.get('inlineData'):
                                pcm_out = base64.b64decode(part['inlineData']['data'])
                                audio_bytes += len(pcm_out)
                                nonzero += sum(x != 0 for x in pcm_out)
            except TimeoutError:
                pass
            await sender
            if label == 'wake' and nonzero == 0 and 'assistant' in ''.join(transcript).lower():
                text = 'Assistant, respond to this directly addressed request, using tools if needed: ' + json.dumps(''.join(transcript))
                await ws.send(json.dumps({'clientContent': {'turns': [{'role': 'user', 'parts': [{'text': text}]}], 'turnComplete': True}}))
                try:
                    async with asyncio.timeout(15):
                        while True:
                            message = json.loads(await ws.recv())
                            await decline_tools(message)
                            c = message.get('serverContent', {})
                            if c.get('outputTranscription'): output.append(c['outputTranscription'].get('text', ''))
                            for part in c.get('modelTurn', {}).get('parts', []):
                                if part.get('inlineData'):
                                    pcm_out = base64.b64decode(part['inlineData']['data'])
                                    audio_bytes += len(pcm_out)
                                    nonzero += sum(x != 0 for x in pcm_out)
                            if c.get('turnComplete') and nonzero: break
                except TimeoutError:
                    pass
                print('Applied recognized-wake text fallback.', flush=True)
            assert transcript, 'No transcript received'
            result = dict(probe=label, input=''.join(transcript), output=''.join(output), pcm_bytes=audio_bytes,
                          nonzero_pcm_bytes=nonzero, android_gate_would_allow=('assistant' in ''.join(transcript).lower()))
            print(json.dumps(result, ensure_ascii=False), flush=True)
            if label == 'wake':
                assert nonzero > 0, 'No spoken response to direct address'
                assert 'assistant' in ''.join(transcript).lower(), 'Wake phrase missing from transcript; Android correctly fails closed'
        await probe(ordinary, 'ordinary')
        await probe(wake, 'wake')
    if handle:
        # A reconnect check, not a two-hour endurance test. Never print the handle.
        async with connect(endpoint, additional_headers={'Authorization': 'Token ' + token['token']}) as ws:
            await ws.send(json.dumps({'setup': {'model': 'models/' + token['model'], 'sessionResumption': {'handle': handle}}}))
            assert 'setupComplete' in json.loads(await asyncio.wait_for(ws.recv(), 20))
        print('PASS: real Live reconnect accepted saved resumption handle.', flush=True)
    else:
        print('UNVERIFIED: no resumable handle arrived during short probe.', flush=True)


def main():
    text = (Path(__file__).parent / 'fixtures/meeting-20min.txt').read_text()
    # Force multiple chunks in this smoke to exercise real map AND reduce, even for 20 minutes.
    size = min(CHUNK_CHARS, 8000)
    partials = [summarize_meeting(text[i:i+size]) for i in range(0, len(text), size)]
    maps = len(partials)
    while len(partials) > 1:
        partials = [summarize_meeting(pack_summaries(partials[i:i+REDUCE_FAN_IN]), reducing=True)
                    for i in range(0, len(partials), REDUCE_FAN_IN)]
    print(f'PASS: fictional 20-minute meeting, {len(text.split())} words, {maps} map calls; final structured minutes:', flush=True)
    print(json.dumps(partials[0], indent=2, ensure_ascii=False), flush=True)
    if '--live' in sys.argv:
        with TemporaryDirectory(prefix='meeting-live-') as directory:
            asyncio.run(live(directory))


if __name__ == '__main__':
    try:
        main()
    except Exception as exc:
        raise SystemExit(f'FAILED: meeting smoke ({type(exc).__name__}); upstream details suppressed.') from None
