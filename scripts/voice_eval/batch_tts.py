"""Optional free-tier efficient TTS batching, split only at unambiguous long silences.

Never guesses a boundary: missing/ambiguous pauses preserve the batch and fail.
Cached clips must still be checked against Live's input transcripts in the report.
"""
import argparse
import audioop
import hashlib
import json
import os
import time
import wave
from pathlib import Path

from dotenv import load_dotenv
from google import genai
from .audio import CACHE, MODEL, RATE, cached_path, read, specification, synthesize



def split_batch(pcm, count):
    # 20 ms windows; >1 s silence is intentionally not natural intra-sentence timing.
    silence=[]
    start=None
    for i in range(0,len(pcm),640):
        quiet=audioop.rms(pcm[i:i+640],2)<100
        if quiet and start is None:
            start=i
        if not quiet and start is not None:
            if i-start>=32000 and start>6400:
                silence.append((start,i))
            start=None
    if len(silence)!=count-1:
        raise ValueError(f'Expected {count-1} long silence boundaries, found {len(silence)}')
    boundaries=[0]+[(a+b)//4*2 for a,b in silence]+[len(pcm)]
    # Keep only 100 ms padding at the cuts.
    return [pcm[max(boundaries[i],silence[i-1][1]-3200) if i else 0:
                min(boundaries[i+1],silence[i][0]+3200) if i<len(silence) else len(pcm)]
            for i in range(count)]


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--size',type=int,default=8)
    parser.add_argument('--gap',type=float,default=22)
    args=parser.parse_args()
    if not 1<=args.size<=8 or args.gap<0:
        parser.error('Batch size must be 1–8 and gap nonnegative')
    load_dotenv(Path(__file__).resolve().parents[2]/'.env')
    cases=json.loads(Path(__file__).with_name('cases.json').read_text())
    failures=0
    with genai.Client(api_key=os.environ['GEMINI_API_KEY'],vertexai=False,
                      http_options={'api_version':'v1beta','timeout':180000}) as client:
        for voice in ('Kore','Puck','Aoede'):
            texts=list(dict.fromkeys(s for c in cases if c['voice']==voice for s in c['segments']))
            for offset in range(0,len(texts),args.size):
                batch=texts[offset:offset+args.size]
                if all(cached_path(t,voice,MODEL).exists() for t in batch):
                    continue
                joined=' <pause for 3 seconds> '.join(batch)
                started=time.monotonic()
                try:
                    path=synthesize(client,joined,voice,MODEL,cache=CACHE/'batches')
                    pcm=read(path)
                    reviewed=json.loads(Path(__file__).with_name('batch-review.json').read_text())
                    match=next((r for r in reviewed if r['source_pcm_sha256']==hashlib.sha256(pcm).hexdigest()),None)
                    if match:
                        all_pieces=split_batch(pcm,match['segments'])
                        pieces=[all_pieces[i] for i in match['keep']]
                    else:
                        pieces=split_batch(pcm,len(batch))
                    for text,pcm in zip(batch,pieces,strict=True):
                        target=cached_path(text,voice,MODEL)
                        with wave.open(str(target),'wb') as wav:
                            wav.setparams((1,2,RATE,0,'NONE','not compressed'))
                            wav.writeframes(pcm)
                        metadata=specification(text,voice,MODEL)
                        metadata.update(batch_file=path.name,pcm_sha256=hashlib.sha256(pcm).hexdigest())
                        target.with_suffix('.json').write_text(json.dumps(metadata,indent=2)+'\n')
                    print(f'OK batch {voice} {offset} clips={len(batch)}',flush=True)
                except Exception as exc:
                    print(f'FAILED batch {voice} {offset}: {type(exc).__name__}',flush=True)
                    failures+=1
                    if getattr(exc,'code',None)==429 and 'PerDay' in json.dumps(getattr(exc,'details',{})):
                        raise SystemExit(1) from None
                time.sleep(max(0,args.gap-(time.monotonic()-started)))

    raise SystemExit(1 if failures else 0)

if __name__=='__main__':
    main()
