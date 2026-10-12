from pathlib import Path
import subprocess,threading,queue,time,shutil,json
from datetime import datetime
root=Path(__file__).resolve().parents[1]
fixture=Path((root/'build/verification-fixture.txt').read_text())
reference=Path.home()/'.codex/worktrees/voxyserver-port1-release/voxyserver'
game=root/'build'/('graphical-client-'+datetime.now().strftime('%Y%m%d-%H%M%S'))
mods=game/'mods';mods.mkdir(parents=True)
for src in [root/'libs/voxy.jar',root/'libs/voxyserver.jar',root/'build/verification/lod-only-client-verification.jar',
            next((fixture/'mods').glob('fabric-api-*.jar')),
            next((Path.home()/'.gradle/caches/modules-2/files-2.1/maven.modrinth/sodium').rglob('sodium-mc1.20.1-0.5.13-fabric.jar'))]:
    shutil.copy2(src,mods/src.name)
(game/'config').mkdir()
(game/'config/voxy-config.json').write_text(json.dumps({'enabled':True,'enable_rendering':True,'ingest_enabled':True,'section_render_distance':16,'service_threads':2,'sub_division_size':64.0,'dont_use_sodium_builder_threads':False}))
cfg=json.loads((fixture/'config/voxy-lod-only.json').read_text());cfg['radiusChunks']=8;cfg['automatic']=False
(fixture/'config/voxy-lod-only.json').write_text(json.dumps(cfg))
args=[line.strip('"') for line in (reference/'build/production-client.args').read_text().splitlines()]
args[args.index('--gameDir')+1]=game.as_posix()
argfile=root/'build/graphical-client.args'
argfile.write_text('\n'.join('"'+arg+'"' for arg in args))
(root/'build/graphical-client-directory.txt').write_text(str(game))
java=r'C:\Program Files\Eclipse Adoptium\jdk-17.0.18.8-hotspot\bin\java.exe'
events=queue.Queue();procs=[];logs=[];server=None;client=None
def launch(name,args,cwd):
    process=subprocess.Popen(args,cwd=cwd,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,encoding='utf-8',errors='replace',bufsize=1)
    log=(root/f'build/graphical-{name}.log').open('w',encoding='utf-8');logs.append(log);procs.append((name,process))
    def read():
        for line in process.stdout:
            log.write(line);log.flush();events.put((name,line))
    threading.Thread(target=read,daemon=True).start()
    return process
def command(text):server.stdin.write(text+'\n');server.stdin.flush()
try:
    server=launch('server',[java,'-Xmx3G','-jar','fabric-server-launch.jar','nogui'],fixture)
    deadline=time.monotonic()+240;requested=False;generated=False;disconnected=False
    while time.monotonic()<deadline:
        try:name,line=events.get(timeout=.5)
        except queue.Empty:name,line='',''
        if any(x in line for x in ['LOD_ONLY_CLIENT_TEST','ERROR','Exception','LOD Only','joined the game','lost connection']):print(name+': '+line.rstrip(),flush=True)
        if name=='server' and 'Voxy LOD Only started' in line:
            command('voxylodonly generate minecraft:overworld 256 256 1');requested=True
        if name=='server' and requested and 'LOD Only job finished' in line and not generated:
            assert 'generated=9' in line,line
            generated=True;client=launch('client',[java,'@'+str(argfile)],game)
        if name=='server' and 'joined the game' in line: command('op LifecycleTest')
        if name=='server' and 'lost connection' in line:
            disconnected=True
        if client is not None and client.poll() is not None:
            assert client.returncode==0
            assert (game/'lod-only-client-result.txt').read_text().splitlines()[-1]=='PASS'
            time.sleep(2);command('voxylodonly status')
            deadline2=time.monotonic()+15
            while time.monotonic()<deadline2:
                try:name,line=events.get(timeout=.5)
                except queue.Empty:continue
                if name=='server' and 'Voxy LOD Only: automatic=' in line:
                    print(line.rstrip(),flush=True)
                    assert 'state=idle' in line,line
                    break
            else:raise AssertionError('no idle status after client disconnect')
            break
        if server.poll() is not None:raise RuntimeError('server exited early')
    else:raise TimeoutError('graphical test')
    (root/'build/graphical-result.txt').write_text('PASS\n')
    print('GRAPHICAL_TEST PASS',flush=True)
finally:
    if server is not None and server.poll() is None:command('stop')
    for name,p in procs:
        if p.poll() is None:
            try:p.wait(timeout=90 if name=='server' else 15)
            except subprocess.TimeoutExpired:p.kill();p.wait()
    for log in logs:log.close()
    print('FINAL_EXITS '+str([(name,p.returncode) for name,p in procs]),flush=True)
