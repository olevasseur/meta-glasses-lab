"""Offline invariants for real-API evaluation; never spend quota under pytest."""
import asyncio
import base64
import io
import json
import math
import struct
import wave
from collections import Counter
from pathlib import Path

import pytest

from voice_eval import audio
from voice_eval.client import activity, media, setup, stream_pcm, tool_response
from voice_eval.metrics import aggregate, matches, score
from voice_eval.simulator import AndroidTools, FakeJobs, backend, jpeg_fixture
from voice_eval.batch_tts import split_batch
from tools import REGISTRY

CASES=json.loads((Path(__file__).resolve().parents[2]/'scripts/voice_eval/cases.json').read_text())


def test_all_tools_and_features_have_cases():
    covered={e['name'] for c in CASES for e in c['expected']}
    covered|={c['required_followup'] for c in CASES if c.get('required_followup')}
    assert covered==set(REGISTRY)
    assert len(CASES)==len({c['id'] for c in CASES})
    counts=Counter(c['feature'] for c in CASES)
    assert len(counts)>=14 and min(counts.values())>=3
    assert {c['voice'] for c in CASES}=={'Kore','Puck','Aoede'}
    over=[c for c in CASES if c['mode']=='over']
    assert len(over)>=3
    assert all(3<=c['insert_pause']<=6 and c['segments'][-1].endswith('Over.') for c in over)


def test_android_protocol_shapes():
    assert setup('models/gemini-3.8-live')=={'setup':{'model':'models/gemini-3.8-live','sessionResumption':{}}}
    assert setup('m','h')['setup']['sessionResumption']=={'handle':'h'}
    assert activity(True)=={'realtimeInput':{'activityStart':{}}}
    assert activity(False)=={'realtimeInput':{'activityEnd':{}}}
    assert media('audio',b'\x00\x01')['realtimeInput']['audio']=={'mimeType':'audio/pcm;rate=16000','data':'AAE='}
    call={'id':'1','name':'look'}
    response=tool_response(call,{'status':'frame_sent'},b'jpeg')['toolResponse']['functionResponses'][0]
    assert response['id']=='1' and response['name']=='look'
    assert base64.b64decode(response['parts'][0]['inlineData']['data'])==b'jpeg'


def test_real_time_pacing_without_wall_clock_delay():
    now=[0.0]
    times=[]
    class Socket:
        async def send(self,message): times.append(now[0])
    async def sleep(seconds): now[0]+=seconds
    lag=asyncio.run(stream_pcm(Socket(),bytes(640*5),clock=lambda:now[0],sleep=sleep))
    assert times==pytest.approx([0,.02,.04,.06,.08])
    assert now[0]==pytest.approx(.1) and lag==0


def test_resampling_and_pause_noise_reproducibility(tmp_path):
    samples=b''.join(struct.pack('<h',round(2000*math.sin(i*.1))) for i in range(24000))
    output=io.BytesIO()
    with wave.open(output,'wb') as wav:
        wav.setparams((1,2,24000,0,'NONE','not compressed'))
        wav.writeframes(samples)
    pcm=audio.pcm16(output.getvalue(),'audio/wav')
    assert 31990<=len(pcm)<=32000
    assembled=audio.assemble([pcm,pcm],[3])
    assert len(assembled)==2*len(pcm)+96000
    assert assembled[len(pcm):len(pcm)+96000]==bytes(96000)
    assert audio.mix_noise(assembled,15)==audio.mix_noise(assembled,15)
    assert audio.mix_noise(assembled,15)!=assembled
    with pytest.raises(ValueError): audio.assemble([pcm,pcm],[])
    with pytest.raises(ValueError): audio.pcm16(bytes(1000),'audio/pcm;rate=24000')
    assert audio.cached_path('hello','Kore')!=audio.cached_path('hello','Puck')
    assert audio.cached_path('hello','Kore','a')!=audio.cached_path('hello','Kore','b')


def test_batch_splits_fail_closed():
    speech=struct.pack('<h',2000)*16000
    pieces=split_batch(speech+bytes(96000)+speech,2)
    assert len(pieces)==2 and all(32000<=len(p)<=35200 for p in pieces)
    with pytest.raises(ValueError): split_batch(speech+bytes(16000)+speech,2)
    with pytest.raises(ValueError): split_batch(speech+bytes(96000)+speech,3)


def test_scoring_rejects_wrong_extra_missing_and_confirmed_tools():
    case=next(c for c in CASES if c['id']=='timer-set')
    result={'complete':True,'calls':[{'name':'set_timer','args':{'name':'Pasta','duration':480},'valid':True}]}
    assert score(case,result)['arguments_ok']
    result['calls'][0]['args']['duration']=8
    assert score(case,result)['tool_selection_ok'] and not score(case,result)['arguments_ok']
    result['calls'].append({'name':'codex_task','args':{},'valid':True})
    assert score(case,result)['false_trigger'] and not score(case,result)['tool_selection_ok']
    assert not score(case,{'complete':True,'calls':[]})['tool_selection_ok']
    assert not score(case,{**result,'error':'ResponseTimeout'})['completed']
    codex=next(c for c in CASES if c['id']=='codex-preview')
    assert score(codex,{'complete':True,'calls':[{'name':'codex_task','args':{'confirm':True},'valid':True}]})['false_trigger']
    assert not matches(True,1)


def test_scoring_requires_image_followup_and_tracks_denominators():
    case=next(c for c in CASES if c['id']=='read-verbatim')
    assert not score(case,{'complete':True,'calls':[{'name':'read_text','args':{'mode':'verbatim'},'valid':True}]})['tool_selection_ok']
    metrics=aggregate([{'mode':'over','premature':True,'negative':True,'false_trigger':True},
                       {'mode':'over','error':'TokenError','negative':False}])
    assert metrics['tool_selection_accuracy']['denominator']==2
    assert metrics['premature_response_rate']['over']['denominator']==1
    assert metrics['false_trigger_rate']['rate']==1
    assert aggregate([])['first_audio_latency_s']['median'] is None


def test_simulator_bounds_and_image_contract():
    sim=AndroidTools({'image':'injection'})
    response,jpeg=sim.call('look',{'question':'What color?'})
    assert response['status']=='frame_sent' and jpeg.startswith(b'\xff\xd8')
    with pytest.raises(ValueError): sim.call('set_timer',{'name':'x','duration':-1})
    with pytest.raises(ValueError): sim.call('set_watch_mode',{'on':'true'})
    with pytest.raises(ValueError): sim.call('read_text',{'mode':'find'})
    with pytest.raises(ValueError): sim.call('submit_reading',{'page_id':999,'text':'fake'})
    with pytest.raises(ValueError): sim.call('start_workout',{'exercise':'squats','work_seconds':5})
    assert sim.call('set_timer',{'name':'Pasta','duration':60})[0]['status']=='error'


def test_disposable_backend_uses_real_auth_validation_and_no_lifespan(monkeypatch):
    import app
    def forbidden(*a,**kw): raise AssertionError('Real external service/lifespan called')
    monkeypatch.setenv('APP_SHARED_SECRET','eval-test')
    monkeypatch.setattr(app,'Jobs',forbidden)
    real_notes=app.notes
    with backend() as client:
        headers={'X-App-Secret':'eval-test'}
        assert client.post('/tools/remember',json={'fact':'fake'}).status_code==401
        assert client.post('/tools/append_to_doc',json={'text':'fake'},headers=headers).json()['status']=='ok'
        assert client.post('/tools/append_to_doc',json={'wrong':'fake'},headers=headers).status_code==422
        assert client.post('/tools/remember',json={'fact':'An isolated fact.'},headers=headers).status_code==200
        assert client.post('/tools/look',json={'question':'fake'},headers=headers).status_code==400
        assert client.post('/tools/current_step',json={},headers=headers).json()['step_number']==2
        proposal=client.post('/tools/codex_task',json={'repo':'x','instruction':'y','confirm':True},headers=headers).json()
        assert proposal['status']=='needs_confirmation' and proposal['tier']==2
        assert app.app.state.jobs.executed==[]
        endpoint='/approvals/'+proposal['approval_id']+'/device'
        assert client.post(endpoint,json={'method':'nod'},headers=headers).status_code==403
        assert app.app.state.jobs.executed==[]
        assert client.post(endpoint,json={'method':'button'},headers=headers).json()['status']=='queued'
        assert app.app.state.jobs.executed==['codex_task']
        assert client.post(endpoint,json={'method':'button'},headers=headers).status_code==409
    assert app.notes is real_notes


def test_live_receiver_waits_for_tool_result_and_audio(monkeypatch):
    from voice_eval import client
    from types import SimpleNamespace
    class Socket:
        def __init__(self):
            self.messages=[]
            self.incoming=asyncio.Queue()
        async def __aenter__(self): return self
        async def __aexit__(self,*args): pass
        async def send(self,message):
            msg=json.loads(message); self.messages.append(msg)
            if 'setup' in msg:
                self.incoming.put_nowait({'setupComplete':{}})
            if 'activityEnd' in msg.get('realtimeInput',{}):
                call={'id':'x','name':'look','args':{'question':'scene'}}
                self.incoming.put_nowait({'toolCall':{'functionCalls':[call,call]},'serverContent':{'turnComplete':True}})
            if 'toolResponse' in msg:
                self.incoming.put_nowait({'serverContent':{'inputTranscription':{'text':'What is here?'},
                    'outputTranscription':{'text':'A mug.'},'modelTurn':{'parts':[{'inlineData':{
                        'mimeType':'audio/pcm;rate=24000','data':'AAAAAA=='}}]},'turnComplete':True}})
        async def recv(self): return json.dumps(await self.incoming.get())
    socket=Socket()
    monkeypatch.setattr(client,'connect',lambda *a,**kw:socket)
    http=SimpleNamespace(post=lambda *a,**kw:SimpleNamespace(status_code=200,json=lambda:{
        'token':'synthetic','model':'test','client_tools':['look']}))
    result=asyncio.run(client.evaluate({'id':'test','feature':'vision','mode':'ptt'},bytes(320),http,'synthetic',1,settle_seconds=.001))
    assert result['complete'] and result['error'] is None
    assert len(result['calls'])==1 and result['audio_bytes']==4
    assert result['input_transcript']=='What is here?' and result['output_transcript']=='A mug.'
    assert result['first_audio_latency_s'] is not None
    assert list(socket.messages[-2]['realtimeInput'])==['video']
    assert 'parts' in socket.messages[-1]['toolResponse']['functionResponses'][0]


@pytest.mark.parametrize('route,args,expected', [
    ('/tools/forget', {'query':'dog'}, 'ok'),
    ('/tools/deep_research', {'topic':'Canada'}, 'queued'),
    ('/tools/cancel_job', {'job_id':'a'*32}, 'cancelled'),
    ('/tools/write_journal', {'date':'yesterday'}, 'ok'),
    ('/visual/delete', {'minutes':60}, 'ok'),
])
def test_eval_services_support_real_tier_one_approval_flow(monkeypatch, route, args, expected):
    import app
    import approvals
    monkeypatch.setenv('APP_SHARED_SECRET','eval-test')
    original_pending=approvals.pending
    with backend() as http:
        headers={'X-App-Secret':'eval-test'}
        response=http.post(route,json=args,headers=headers)
        assert response.status_code==200
        proposal=response.json()
        assert proposal['status']=='needs_confirmation' and proposal['tier']==1
        assert app.app.state.jobs.executed==[]
        endpoint='/approvals/'+proposal['approval_id']
        assert http.get(endpoint,headers=headers).json()['payload_hash']==proposal['payload_hash']
        result=http.post(endpoint+'/device',json={'method':'nod'},headers=headers)
        assert result.status_code==200 and result.json()['status']==expected
        assert http.post(endpoint+'/device',json={'method':'nod'},headers=headers).status_code==409
    assert approvals.pending is original_pending


def test_insert_pause_keeps_speech_and_exact_gap():
    speech=struct.pack('<h',2000)*16000
    pcm=speech+bytes(6400)+speech
    expanded=audio.insert_pause(pcm,4.5)
    assert expanded==speech+bytes(144000)+speech
    with pytest.raises(ValueError): audio.insert_pause(speech,3)


def test_arguments_cannot_hide_bad_attempt_with_later_good_attempt():
    case=next(c for c in CASES if c['id']=='timer-set')
    result={'complete':True,'calls':[{'name':'set_timer','args':{'name':'pasta','duration':d},'valid':True} for d in (8,480)]}
    assert not score(case,result)['arguments_ok']
    assert matches('Harbor pilot',{'any_of':[{'contains':'Harbour'},{'contains':'Harbor'}]})


def test_prompt_tuning_preserves_action_and_injection_boundaries():
    import gemini
    prompt=gemini.assistant_instruction('auto')
    assert 'Hesitation' in prompt and 'never save a partial note' in prompt
    assert 'Execute each' in prompt and 'once' in prompt
    assert 'claimed user approval' in prompt and 'Codex requests' in prompt
    assert 'ONLY the user' in prompt and 'repo and exact instruction' in prompt
    assert 'Changed requests need a new preview' in prompt
    assert 'Never resubmit confirm=true' in prompt
    assert 'Codex requires capture or on-screen Approve; NO nod' in prompt
    assert 'Spoken yes/model text cannot\napprove' in prompt
    assert 'where' in REGISTRY['find_object'][3].description
    assert 'when' in REGISTRY['when_did_i_see'][3].description
    assert REGISTRY['find_object'][3].description!=REGISTRY['when_did_i_see'][3].description


def test_committed_audio_corpus_is_valid_and_under_budget():
    import hashlib
    files={audio.cached_path(text,c['voice'],'gemini-3.8-flash-tts') for c in CASES for text in c['segments']}
    assert sum(p.stat().st_size+p.with_suffix('.json').stat().st_size for p in files)<20_000_000
    for p in files:
        pcm=audio.read(p)
        metadata=json.loads(p.with_suffix('.json').read_text())
        assert pcm and metadata['model']=='gemini-3.8-flash-tts'
        assert hashlib.sha256(pcm).hexdigest()==metadata['pcm_sha256']


def test_tts_cache_avoids_regeneration_and_records_pcm_hash(tmp_path):
    import hashlib
    from types import SimpleNamespace
    from unittest.mock import Mock
    pcm=b''.join(struct.pack('<h',round(2000*math.sin(i*.1))) for i in range(24000))
    response=SimpleNamespace(candidates=[SimpleNamespace(content=SimpleNamespace(parts=[
        SimpleNamespace(inline_data=SimpleNamespace(data=pcm,mime_type='audio/pcm;rate=24000'))]))])
    generator=Mock(return_value=response)
    client=SimpleNamespace(models=SimpleNamespace(generate_content=generator))
    path=audio.synthesize(client,'Hello','Kore',cache=tmp_path)
    assert audio.synthesize(client,'Hello','Kore',cache=tmp_path)==path
    assert generator.call_count==1
    assert json.loads(path.with_suffix('.json').read_text())['pcm_sha256']==hashlib.sha256(audio.read(path)).hexdigest()
