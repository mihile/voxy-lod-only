"""Classify execution samples inside the measured generation window of a closed JFR."""
from pathlib import Path
from datetime import datetime, timezone, timedelta
import collections, json, re, subprocess, sys

fixture=Path(sys.argv[1]).resolve()
java_home=Path(r'C:\Java\graalvm-25.4.4.1.1+1.1')
sample_file=fixture/'execution-samples.json'
if len(sys.argv)>2:sample_file=Path(sys.argv[2]).resolve()
elif not sample_file.exists():
    with sample_file.open('w',encoding='utf-8') as out:
        subprocess.run([str(java_home/'bin/jfr.exe'),'print','--json','--events','jdk.ExecutionSample',str(fixture/'scheduler.jfr')],stdout=out,check=True)
lines=(fixture/'server.log').read_text(encoding='utf-8').splitlines()
start=[l for l in lines if 'LOD Only generation started' in l][-1]
end=[l for l in lines if 'LOD Only job finished' in l][-1]
date=fixture.parent.name[:8]
def stamp(line):return datetime.strptime(date+' '+re.match(r'\[(\d+:\d+:\d+)\]',line).group(1),'%Y%m%d %H:%M:%S').replace(tzinfo=timezone(timedelta(hours=9)))
lower,upper=stamp(start),stamp(end)+timedelta(seconds=1)
threads=collections.Counter();stages=collections.Counter();worker_ids=set()
labels={'lambda$generate$1':'structure starts','lambda$generate$2':'structure references','lambda$generate$3':'biomes',
        'lambda$generate$4':'noise','lambda$generate$5':'surface','lambda$generate$6':'carvers'}
events=json.loads(sample_file.read_text(encoding='utf-8-sig'))['recording']['events']
for event in events:
    v=event['values'];at=datetime.fromisoformat(v['startTime'])
    if not lower<=at<upper:continue
    name=v['sampledThread']['javaName'];threads[name]+=1
    if name=='Voxy LOD Only terrain':worker_ids.add(v['sampledThread']['javaThreadId'])
    frames=(v.get('stackTrace') or {}).get('frames',[])
    own=[f['method']['name'] for f in frames if f['method']['type']['name'].endswith('/InMemoryGenerator')]
    found=next((labels[n] for n in own if n in labels),None)
    if found:stages[found]+=1
    elif name=='Voxy LOD Only terrain':stages['terrain stage (stack not resolved)']+=1
    elif name.startswith('Worker-Main-'):stages['vanilla background executor']+=1
    elif name=='Voxy LOD Only coordinator':stages['serial decoration/light/allocation']+=1
    elif name=='Server thread':stages['server tick (including conversion/commit)']+=1
    else:stages['other threads']+=1
result={'window_start':lower.isoformat(),'window_end':upper.isoformat(),'execution_samples':sum(threads.values()),
        'sampled_terrain_worker_ids':sorted(worker_ids),'thread_samples':dict(threads),'stage_samples':dict(stages),
        'note':'Sampling counts, not exact CPU times; whole server tick and coordinator categories contain other work.'}
(fixture/'profile-summary.json').write_text(json.dumps(result,indent=2))
print(json.dumps(result,indent=2))
