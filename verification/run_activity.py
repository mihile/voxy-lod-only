"""Real-client occupancy transitions on disposable worlds; addon never installed client-side."""
from pathlib import Path
from datetime import datetime
import hashlib,json,os,re,shutil,subprocess,threading,time,sys

ROOT=Path(__file__).resolve().parents[1]
stamp=datetime.now().strftime('%Y%m%d-%H%M%S')
source=Path(os.environ.get('VOXY_ACTIVITY_FIXTURE',str(ROOT/'build/isolated-graphics-v04-20261013-151629')))
folder=ROOT/'build'/('activity-'+stamp)
shutil.copytree(source,folder)
voxy=Path(os.environ.get('VOXY_TEST_JAR',str(ROOT/'libs/voxy.jar')))
addon=Path(os.environ.get('VOXY_LOD_TEST_JAR',str(ROOT/'build/libs/VoxyLodOnly-0.8.0+1.20.1.jar')))
bridge=Path(os.environ.get('VOXY_SERVER_TEST_JAR',str(ROOT/'libs/voxyserver.jar')))
for pattern in ['VoxyLodOnly-*.jar','voxy-*.jar']:
    for old in (folder/'mods').glob(pattern): old.unlink() # Fresh test copy only.
shutil.copy2(addon,folder/'mods'/addon.name)
shutil.copy2(voxy,folder/'mods'/voxy.name)
for old in (folder/'mods').glob('*.jar'):
    if old.name.lower().startswith('voxyserver'):old.unlink() # Fresh fixture only.
shutil.copy2(bridge,folder/'mods'/bridge.name)
cfg=json.loads((folder/'config/voxy-lod-only.json').read_text())
cfg.update(automatic=False,radiusChunks=8,continueAutomaticWhenEmpty=True,
    withPlayers=dict(enabled=True,generationThreads=1,cooldownTicks=1,commitChunksPerTick=2,
                     commitBudgetMillis=1,pauseAboveTickMillis=1000,maxChunksPerSecond=2),
    withoutPlayers=dict(enabled=False,generationThreads=4,cooldownTicks=1,commitChunksPerTick=64,
                        commitBudgetMillis=10,pauseAboveTickMillis=1000,maxChunksPerSecond=4))
(folder/'config/voxy-lod-only.json').write_text(json.dumps(cfg,indent=2))
game=ROOT/'build'/('activity-client-'+stamp)
(game/'mods').mkdir(parents=True);(game/'config').mkdir()
reference=Path.home()/'.codex/worktrees/voxyserver-port1-release/voxyserver'
for jar in [voxy,bridge,ROOT/'build/verification/lod-only-client-verification.jar',
            next((folder/'mods').glob('fabric-api-*.jar')),
            next((Path.home()/'.gradle/caches/modules-2/files-2.1/maven.modrinth/sodium').rglob('sodium-mc1.20.1-0.5.13-fabric.jar'))]:
    shutil.copy2(jar,game/'mods'/jar.name)
(game/'config/voxy-config.json').write_text(json.dumps(dict(enabled=True,enable_rendering=True,ingest_enabled=True,
    section_render_distance=16,service_threads=2,sub_division_size=64.0,dont_use_sodium_builder_threads=False)))
args=[line.strip('"') for line in (reference/'build/production-client.args').read_text().splitlines()]
args[args.index('--gameDir')+1]=game.as_posix()
argfile=game/'client.args';argfile.write_text('\n'.join('"'+arg+'"' for arg in args))
client_java=r'C:\Program Files\Eclipse Adoptium\jdk-17.0.18.8-hotspot\bin\java.exe'
server_java=os.environ.get('VOXY_LOD_TEST_JAVA',client_java)
history=[];processes=[];logs=[];server=None;client=None

def launch(name,args,cwd):
    proc=subprocess.Popen(args,cwd=cwd,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,
                          text=True,encoding='utf-8',errors='replace',bufsize=1)
    log=(folder/(name+'.log')).open('w',encoding='utf-8');logs.append(log);processes.append((name,proc))
    def read():
        for line in proc.stdout:
            history.append((time.monotonic(),name,line));log.write(line);log.flush()
            if any(x in line for x in ['LOD activity profile','LOD Only job finished','LOD_ONLY_CLIENT_TEST','/ERROR]']):
                print(name+': '+line.rstrip(),flush=True)
    threading.Thread(target=read,daemon=True).start()
    return proc

def wait(pattern,name='server',after=0,timeout=120):
    deadline=time.monotonic()+timeout;cursor=after
    while time.monotonic()<deadline:
        entries=history[cursor:];cursor+=len(entries)
        for item in entries:
            if item[1]==name and pattern in item[2]:return item
            if item[1].startswith('server') and '/ERROR]' in item[2]:raise AssertionError(item)
        if server is not None and server.poll() is not None:raise AssertionError('server exited early')
        if client is not None and client.poll() is not None and pattern!='PASS':raise AssertionError('client exited early')
        time.sleep(.05)
    raise TimeoutError(pattern)

def command(text):server.stdin.write(text+'\n');server.stdin.flush()
def status():
    mark=len(history);command('voxylodonly status');_,_,line=wait('Voxy LOD Only: automatic=',after=mark)
    return line,int(re.search(r'generated=(\d+)',line).group(1))
def until_count(n):
    deadline=time.monotonic()+90
    while time.monotonic()<deadline:
        line,count=status()
        if count>=n:return line,count
        time.sleep(.2)
    raise AssertionError('no LOD progress')
def trigger(name):(game/name).write_text('go')

result={'fixture':str(folder),'client':str(game),'addon_sha256':hashlib.sha256(addon.read_bytes()).hexdigest(),'checks':[]}
try:
    server=launch('server',[server_java,'-XX:ActiveProcessorCount=4','-Xmx3G','-jar','fabric-server-launch.jar','nogui'],folder)
    wait('Voxy LOD Only started');wait('mode=withoutPlayers')
    client=launch('client',[client_java,'-Dvoxy.lod.activitytest=true','@'+str(argfile)],game)
    wait('joined the game');command('op LifecycleTest')
    wait('ACTIVITY_READY','client');mark=len(history)
    if '--reload-policies' in sys.argv:
        command('voxylod radius 12');wait('LOD automatic radius applied and saved:',after=mark)
        cfg=json.loads((folder/'config/voxy-lod-only.json').read_text())
        cfg['withPlayers']['generationThreads']=2
        (folder/'config/voxy-lod-only.json').write_text(json.dumps(cfg))
        command('voxylod reload');wait('LOD config applied:',after=mark)
        line,_=status();assert 'radiusChunks=12,' in line and 'terrainThreads=2,' in line,line
        result['checks'].append({'live_player_radius_and_workers_reload':True})
        mark=len(history)
    command('voxylodonly generate minecraft:overworld 1024 1024 1')
    wait('LOD Only generation started',after=mark)
    line,count=until_count(1);assert 'mode=withPlayers' in line and ('terrainThreads=2' if '--reload-policies' in sys.argv else 'terrainThreads=1') in line
    trigger('activity-disconnect');wait('ACTIVITY_DISCONNECTED','client',after=mark)
    wait('mode=withoutPlayers',after=mark);time.sleep(.5)
    paused,baseline=status();assert 'state=paused' in paused and 'terrainThreads=4' in paused
    time.sleep(2);same,after=status();assert after==baseline,(baseline,after)
    result['checks'].append({'pause_retains_manual_job':True,'generated_at_pause':baseline})
    mark=len(history);trigger('activity-reconnect')
    wait('mode=withPlayers',after=mark);wait('ACTIVITY_RECONNECTED','client',after=mark)
    _,_,finished=wait('LOD Only job finished',after=mark)
    assert 'generated=9' in finished,finished
    line,count=status();assert count==9 and 'state=idle' in line
    result['checks'].append({'reconnect_resumes_exactly_once':True,'generated':count})
    # Follow-player job is retained when the final player leaves if explicitly configured.
    mark=len(history);command('tp LifecycleTest 32776 180 32776');time.sleep(1)
    command('voxylodonly automatic on')
    until_count(10)
    if '--area-takeover' in sys.argv:
        command('voxylod 24576 24576 circle 0 start');wait('LOD area accepted:',after=mark)
    trigger('activity-disconnect');wait('ACTIVITY_DISCONNECTED','client',after=mark)
    wait('mode=withoutPlayers',after=mark);time.sleep(.5)
    line,count=status();assert 'state=paused' in line,line
    if '--area-takeover' in sys.argv:
        assert 'centerBlocks=24576,24576' in line,line
        command('voxylod stop')
    else:command('voxylodonly automatic off')
    time.sleep(2)
    line,count=status();assert 'state=idle' in line and 'inFlight=0' in line and 'conversionTasks=0' in line,line
    result['checks'].append({'manual_area_takeover_and_disconnect_safe' if '--area-takeover' in sys.argv else 'last_player_job_retained_and_cancelled_cleanly':True})
    trigger('activity-finish');wait('LOD_ONLY_CLIENT_TEST PASS','client',after=mark)
    client.wait(timeout=20);assert client.returncode==0;client=None
    command('stop');server.wait(timeout=90);assert server.returncode==0;server=None
    # Second boot: enable empty-server profile; cap its writes independently at four per second.
    cfg['withoutPlayers']['enabled']=True
    (folder/'config/voxy-lod-only.json').write_text(json.dumps(cfg,indent=2))
    mark=len(history)
    server=launch('server-idle',[server_java,'-XX:ActiveProcessorCount=4','-Xmx3G','-jar','fabric-server-launch.jar','nogui'],folder)
    wait('Voxy LOD Only started','server-idle',after=mark);wait('mode=withoutPlayers','server-idle',after=mark)
    command('voxylodonly generate minecraft:overworld 1280 1280 1')
    first=None;last=None;counts=[];deadline=time.monotonic()+90
    while time.monotonic()<deadline:
        current=len(history);command('voxylodonly status')
        t,_,line=wait('Voxy LOD Only: automatic=','server-idle',after=current)
        count=int(re.search(r'generated=(\d+)',line).group(1));counts.append([t,count])
        if count and first is None:first=t
        if count==9 and 'state=idle' in line:last=t;break
        time.sleep(.08)
    else:raise AssertionError('idle profile did not finish')
    assert last-first>=1.75,(first,last,counts) # 8 further admissions, 250ms apart; polling tolerance.
    result['checks'].append({'idle_profile_generated':9,'elapsed_first_to_finish':last-first,'samples':counts})
    command('stop');server.wait(timeout=90);assert server.returncode==0;server=None
    target_regions={(x,z) for x in (31,32) for z in (31,32)} | {(x,z) for x in (39,40) for z in (39,40)}
    for path in (folder/'verify-world').rglob('r.*.*.mca'):
        _,x,z,_=path.name.split('.')
        assert (int(x),int(z)) not in target_regions,'private target persisted FULL: '+str(path)
    errors=[item for item in history if item[1].startswith('server') and '/ERROR]' in item[2]]
    assert not errors,errors
    assert not any('Network handle null' in item[2] for item in history)
    assert not list((game/'.voxy/saves').glob('UNKNOWN'))
    result['checks'].append({'client_has_no_addon':not list((game/'mods').glob('VoxyLodOnly*')),
                             'no_unknown_or_null_network':True,'no_target_full_regions':True})
    client_hash_errors=[line.strip() for _,name,line in history if name=='client' and '/ERROR]' in line and 'failed to flush hash sidecar' in line]
    result['client_hash_io_errors']=client_hash_errors
    result['status']='FUNCTIONAL_PASS_WITH_CLIENT_HASH_IO_ERRORS' if client_hash_errors else 'PASS'
    print('ACTIVITY_TEST '+result['status']+' '+str(folder),flush=True)
finally:
    if server is not None and server.poll() is None:command('stop')
    for name,p in processes:
        if p.poll() is None:
            try:p.wait(timeout=90 if name.startswith('server') else 15)
            except subprocess.TimeoutExpired:p.kill();p.wait()
    for log in logs:log.close()
    result['exits']=[(name,p.returncode) for name,p in processes]
    (folder/'result.json').write_text(json.dumps(result,indent=2))
    print('ACTIVITY_EXITS '+str(result['exits']),flush=True)
