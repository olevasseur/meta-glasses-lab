"""Android-compatible single-turn Live wire client, with concurrent PCM uplink."""
import asyncio
import base64
import contextlib
import json
import time

from websockets.asyncio.client import connect
from .simulator import AndroidTools

ENDPOINT = ('wss://generativelanguage.googleapis.com/ws/'
            'google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContentConstrained')


def setup(model, handle=None):
    return {'setup':{'model':'models/'+model.removeprefix('models/'),
                     'sessionResumption':{'handle':handle} if handle else {}}}


def media(kind, data):
    return {'realtimeInput':{kind:{'mimeType':'audio/pcm;rate=16000' if kind=='audio' else 'image/jpeg',
                                 'data':base64.b64encode(data).decode()}}}


def activity(start):
    return {'realtimeInput':{'activityStart' if start else 'activityEnd':{}}}


def tool_response(call, response, jpeg=None):
    result = {'id':call['id'],'name':call['name'],'response':response}
    if jpeg:
        result['parts']=[{'inlineData':{'mimeType':'image/jpeg','data':base64.b64encode(jpeg).decode()}}]
    return {'toolResponse':{'functionResponses':[result]}}


def text_turn(text, complete=True):
    return {'clientContent':{'turns':[{'role':'user','parts':[{'text':text}]}], 'turnComplete':complete}}


async def stream_pcm(ws, pcm, *, clock=time.monotonic, sleep=asyncio.sleep):
    start=clock()
    max_lag=0.0
    for offset in range(0,len(pcm),640):
        target=start+offset/32000
        await sleep(max(0,target-clock()))
        max_lag=max(max_lag,clock()-target)
        await ws.send(json.dumps(media('audio',pcm[offset:offset+640])))
    await sleep(max(0,start+len(pcm)/32000-clock()))
    return max_lag


async def evaluate(case, pcm, http, secret, timeout=45, settle_seconds=5):
    android=AndroidTools(case)
    # Initial simulated state only; never provide the expected tool/arguments to Gemini.
    if case['id']=='timer-set':
        android.timers.clear()
    request={'mode':case['mode'],'silence_ms':2000}
    if case.get('persona'):
        request.update(persona=case['persona'],language=case['language'])
    response=await asyncio.to_thread(http.post,'/token',json=request,headers={'X-App-Secret':secret})
    if response.status_code!=200:
        raise RuntimeError(f'Token HTTP {response.status_code}')
    token=response.json()
    result={'id':case['id'],'feature':case['feature'],'mode':case['mode'],
            'calls':[],'input_transcript':'','output_transcript':'','audio_bytes':0,
            'response_times':[],'first_audio_s':None,'speech_end_s':len(pcm)/32000,
            'activity_end_s':None,'complete':False,'error':None,'cancelled_calls':[],
            'resumption_updates':0,'usage':[],'max_send_lag_s':0}
    async with connect(ENDPOINT,additional_headers={'Authorization':'Token '+token['token']},
                       open_timeout=20,max_size=8*1024*1024) as ws:
        await ws.send(json.dumps(setup(token['model'])))
        async with asyncio.timeout(20):
            while True:
                event=json.loads(await ws.recv())
                if 'error' in event:
                    raise RuntimeError('Live setup rejected')
                if 'setupComplete' in event:
                    break
        if case.get('context'):
            await ws.send(json.dumps(text_turn(case['context'],False)))
        started=time.monotonic()
        sent=asyncio.Event()
        done=asyncio.Event()
        pending={}
        cached={}
        cancelled=set()
        gesture_sent=False
        settling=None

        async def send_tools(call):
            name=call['name']
            args=call.get('args',{})
            record={'name':name,'args':args,'time_s':time.monotonic()-started}
            result['calls'].append(record)
            jpeg=None
            try:
                if name in token.get('client_tools',[]):
                    reply,jpeg=android.call(name,args)
                else:
                    res=await asyncio.to_thread(http.post,'/tools/'+name,json=args,
                                               headers={'X-App-Secret':secret})
                    record['http_status']=res.status_code
                    reply=res.json() if res.status_code==200 else {'status':'error','message':'Backend rejected arguments'}
                record['response']=reply
                record['valid']=reply.get('status')!='error' and record.get('http_status',200)==200
                if call['id'] in cancelled:
                    return
                if jpeg:
                    await ws.send(json.dumps(media('video',jpeg)))
                await ws.send(json.dumps(tool_response(call,reply,jpeg)))
                record['response_sent_s']=time.monotonic()-started
                cached[call['id']]=(call,reply if jpeg is None else {'status':'error','message':'Request a fresh look'})
                # Android closes and fetches a new persona/meeting token at this point.
                # Each eval case ends at this boundary; the next case starts fresh.
                if reply.get('status')=='ok' and name in ('start_translation','stop_translation','start_meeting','stop_meeting'):
                    result['complete']=True
                    result['end_reason']='client_mode_switch'
                    done.set()
            except asyncio.CancelledError:
                raise
            except Exception as exc:
                record.update(valid=False,error=type(exc).__name__)
                await ws.send(json.dumps(tool_response(call,{'status':'error','message':'Simulated tool rejected arguments'})))

        async def finish_turn():
            nonlocal gesture_sent
            # Provider can close its tool-call generation just after our fast tool
            # response. Keep listening for the continuation instead of closing early.
            await asyncio.shield(asyncio.gather(*pending.values(),return_exceptions=True))
            await asyncio.sleep(settle_seconds)
            if not sent.is_set():
                return
            if case.get('gesture') and android.pending_question and not gesture_sent:
                gesture_sent=True
                gesture=case['gesture']
                await ws.send(json.dumps(text_turn(f'[gesture: {gesture} = {"yes" if gesture=="nod" else "no"}]')))
                result['gesture_sent']=gesture
            else:
                result['complete']=True
                result['end_reason']='turnComplete_settled'
                done.set()

        async def receive():
            nonlocal settling
            while True:
                event=json.loads(await ws.recv())
                now=time.monotonic()-started
                if 'error' in event or 'goAway' in event:
                    result['error']='LiveError' if 'error' in event else 'GoAwayNotResumed'
                    done.set()
                    return
                if 'sessionResumptionUpdate' in event:
                    result['resumption_updates']+=1
                if 'usageMetadata' in event:
                    result['usage'].append(event['usageMetadata'])
                content=event.get('serverContent',{})
                if (content or 'toolCall' in event) and settling is not None:
                    settling.cancel()
                    settling=None
                result['input_transcript']+=content.get('inputTranscription',{}).get('text','')
                output=content.get('outputTranscription',{}).get('text','')
                result['output_transcript']+=output
                parts=content.get('modelTurn',{}).get('parts',[])
                audio=[p['inlineData'] for p in parts if p.get('inlineData',{}).get('mimeType','').startswith('audio/pcm')]
                calls=event.get('toolCall',{}).get('functionCalls',[])
                if audio or output or calls:
                    result['response_times'].append(now)
                if audio:
                    if result['first_audio_s'] is None:
                        result['first_audio_s']=now
                    result['audio_bytes']+=sum(len(base64.b64decode(p['data'])) for p in audio)
                for id in event.get('toolCallCancellation',{}).get('ids',[]):
                    cancelled.add(id)
                    result['cancelled_calls'].append(id)
                    if id in pending:
                        pending[id].cancel()
                for call in calls:
                    id=call['id']
                    if id in cancelled:
                        continue
                    if id in cached:
                        old,reply=cached[id]
                        await ws.send(json.dumps(tool_response(old,reply)))
                    elif id not in pending:
                        pending[id]=asyncio.create_task(send_tools(call))
                if content.get('turnComplete'):
                    settling=asyncio.create_task(finish_turn())

        receiver=asyncio.create_task(receive())
        try:
            if case['mode'] in ('over','ptt'):
                await ws.send(json.dumps(activity(True)))
            result['max_send_lag_s']=await stream_pcm(ws,pcm)
            result['speech_end_s']=time.monotonic()-started
            if case['mode'] in ('over','ptt'):
                # Oracle sign-off position, plus Android's 1.2 s 'over' grace.
                if case['mode']=='over':
                    await stream_pcm(ws,bytes(38400))
                result['activity_end_s']=time.monotonic()-started
                sent.set()
                await ws.send(json.dumps(activity(False)))
            else:
                sent.set()
                # Continuous microphone silence lets real VAD finish (no synthetic text turn).
                await stream_pcm(ws,bytes(32000*5))
            done_task=asyncio.create_task(done.wait())
            try:
                finished,_=await asyncio.wait([done_task,receiver],timeout=timeout,return_when=asyncio.FIRST_COMPLETED)
                if receiver in finished:
                    await receiver
                if not finished:
                    result['error']='ResponseTimeout'
            finally:
                done_task.cancel()
                with contextlib.suppress(asyncio.CancelledError):
                    await done_task
        except Exception as exc:
            result['error']=type(exc).__name__
        finally:
            receiver.cancel()
            if settling is not None:
                settling.cancel()
            for task in pending.values():
                task.cancel()
            await asyncio.gather(receiver,*pending.values(),*([settling] if settling else []),return_exceptions=True)
    boundary=result['activity_end_s'] if case['mode']=='over' else result['speech_end_s']
    result['premature']=any(t < boundary for t in result['response_times']) if boundary is not None else None
    result['first_audio_latency_s']=(result['first_audio_s']-result['speech_end_s'] if result['first_audio_s'] is not None else None)
    return result
