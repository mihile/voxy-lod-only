"""Disposable production-mappings server tests; never uses a real game/world directory."""
from pathlib import Path
import json, shutil, subprocess, queue, threading, time, sys, re, os, struct, base64
from datetime import datetime

root = Path(__file__).resolve().parents[1]
template = Path.home()/'.codex/worktrees/voxyserver-port1-release/voxyserver/build/smoke-server'
fixture = root/'build'/('isolated-'+datetime.now().strftime('%Y%m%d-%H%M%S'))
fixture.mkdir(parents=True)
for name in ['libraries', 'fabric-server-launch.jar', 'fabric-server-launcher.properties', 'server.jar']:
    src, dst = template/name, fixture/name
    if src.is_dir(): shutil.copytree(src, dst)
    else: shutil.copy2(src, dst)
(fixture/'mods').mkdir(); (fixture/'config').mkdir()
for src in [Path(os.environ.get('VOXY_TEST_JAR', str(root/'libs/voxy.jar'))), root/'libs/voxyserver.jar',
            Path(os.environ.get('VOXY_LOD_TEST_JAR', str(root/'build/libs/VoxyLodOnly-0.8.0+1.20.1.jar'))), root/'build/verification/lod-only-verification.jar',
            next((template/'mods').glob('fabric-api-*.jar'))]: shutil.copy2(src, fixture/'mods'/src.name)
storage_test_jar = os.environ.get('VOXY_STORAGE_TEST_JAR')
if storage_test_jar: shutil.copy2(storage_test_jar, fixture/'mods'/Path(storage_test_jar).name)
(fixture/'eula.txt').write_text('eula=true\n')
(fixture/'server.properties').write_text('server-ip=127.0.0.1\nserver-port=25587\nonline-mode=false\nlevel-name=verify-world\nlevel-seed=424242\nview-distance=2\nsimulation-distance=2\nmax-tick-time=180000\nspawn-protection=0\n')
(fixture/'config/voxy-lod-only.json').write_text(json.dumps({'enabled':True,'automatic':False,'radiusChunks':32,'cooldownTicks':1,'commitChunksPerTick':1,'pauseAboveTickMillis':1000,'structures':True,'dimensions':['minecraft:overworld']}))
if '--area-only' in sys.argv:
    cfg=json.loads((fixture/'config/voxy-lod-only.json').read_text())
    cfg['withoutPlayers']={'generationThreads':4,'maxChunksPerSecond':10}
    (fixture/'config/voxy-lod-only.json').write_text(json.dumps(cfg))
(fixture/'config/voxyserver.json').write_text(json.dumps({'lodStreamRadius':32,'maxSectionsPerTickPerPlayer':100,'sectionsPerPacket':50,'generateOnChunkLoad':True,'generateOnChunkUnload':True,'tickInterval':5,'workerThreads':2,'dirtyTrackingEnabled':True,'dirtyTrackingInterval':20,'hashSyncEnabled':True}))
(root/'build/verification-fixture.txt').write_text(str(fixture))
java = os.environ.get('VOXY_LOD_TEST_JAVA', r'C:\Program Files\Eclipse Adoptium\jdk-17.0.18.8-hotspot\bin\java.exe')

class Server:
    def __init__(self, phase):
        self.queue=queue.Queue(); self.lines=[]
        self.log=(fixture/f'phase-{phase}.log').open('w',encoding='utf-8')
        self.process=subprocess.Popen([java,'-Xmx3G','-jar','fabric-server-launch.jar','nogui'],cwd=fixture,
            stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,encoding='utf-8',errors='replace',bufsize=1)
        def read():
            for line in self.process.stdout:
                self.lines.append(line); self.log.write(line);self.log.flush();self.queue.put(line)
        threading.Thread(target=read,daemon=True).start()
        self.wait('Done (',180)
        self.wait('Voxy LOD Only started',30)
        if storage_test_jar: self.wait('VOXY_STORAGE_TEST PASS',120)
    def command(self, command):
        self.process.stdin.write(command+'\n');self.process.stdin.flush()
    def wait(self, pattern, timeout=180):
        deadline=time.monotonic()+timeout
        while time.monotonic()<deadline:
            if self.process.poll() is not None: raise RuntimeError(f'server exited {self.process.returncode}')
            try: line=self.queue.get(timeout=.5)
            except queue.Empty: continue
            if any(x in line for x in ['ERROR','Exception','LOD Only','VERIFY_','Done (']): print(line.rstrip(),flush=True)
            if 'VERIFY_PARALLEL FAILURE' in line: raise RuntimeError(line)
            if pattern in line:return line
        self.command('voxylodonly status')
        raise TimeoutError(pattern)
    def job(self,dimension,x,z,radius):
        self.command(f'voxylodonly generate {dimension} {x} {z} {radius}')
        self.wait('LOD Only generation started')
        return self.wait('LOD Only job finished',240)
    def stop(self):
        if self.process.poll() is None:
            self.command('stop')
            try:self.process.wait(timeout=90)
            except subprocess.TimeoutExpired:self.process.kill();self.process.wait();raise
        self.log.close()
        if self.process.returncode!=0:raise RuntimeError(f'exit {self.process.returncode}')
        errors=[line for line in self.lines if '/ERROR]' in line]
        if errors:raise RuntimeError('Server logged errors: '+''.join(errors))

def remote_region_files():
    result=[]
    for path in (fixture/'verify-world').rglob('r.*.*.mca'):
        parts=path.name.split('.')
        # Remote test tiles (around +/-128 chunks and 256 chunks) must not become vanilla region files.
        if abs(int(parts[1]))>=3 or abs(int(parts[2]))>=3: result.append(str(path.relative_to(fixture)))
    return result

server=None
try:
    server=Server(1)
    server.command('lodonlytest structurecompat');server.wait('VERIFY_STRUCTURE_RESOURCES',60)
    if '--reload-only' in sys.argv:
        configfile=fixture/'config/voxy-lod-only.json'
        def edit(**changes):
            cfg=json.loads(configfile.read_text());cfg.update(changes);configfile.write_text(json.dumps(cfg));return cfg
        def reload():
            server.command('voxylod reload');server.wait('LOD config reload accepted;');server.wait('LOD config applied:')
        def status():
            server.command('voxylod status');return server.wait('Voxy LOD Only: automatic=')
        server.command('voxylod radius 64');server.wait('LOD automatic radius applied and saved: 64 chunks')
        assert json.loads(configfile.read_text())['radiusChunks']==64
        assert 'radiusChunks=64,' in status()
        original=configfile.read_text();configfile.write_text('{broken')
        server.command('voxylod reload');server.wait('LOD config reload failed; current settings retained:')
        assert 'radiusChunks=64,' in status();configfile.write_text(original)
        edit(withoutPlayers={'generationThreads':4,'maxChunksPerSecond':4});reload()
        server.command('voxylod 2048 2048 circle 48 start');server.wait('LOD area accepted:')
        deadline=time.monotonic()+60
        while int(re.search(r'generated=(\d+)',status()).group(1))<1:
            assert time.monotonic()<deadline;time.sleep(.15)
        server.command('voxylod pause');server.wait('LOD job paused;')
        edit(enabled=False,radiusChunks=16,generationTileSize=8,maxInFlightBatches=1,
             withoutPlayers={'generationThreads':1,'commitBudgetMillis':1,'maxChunksPerSecond':0})
        reload();line=status()
        assert 'radiusChunks=16,' in line and 'terrainThreads=1,' in line and 'state=paused' in line and 'manualPaused=true' in line,line
        count=int(re.search(r'generated=(\d+)',line).group(1));time.sleep(1)
        assert int(re.search(r'generated=(\d+)',status()).group(1))==count
        server.command('voxylod radius 24');server.wait('LOD automatic radius applied and saved: 24 chunks')
        edit(enabled=True);reload()
        line=status();assert 'state=paused' in line and 'manualPaused=true' in line and 'radiusChunks=24,' in line,line
        server.command('voxylod resume');server.wait('LOD job resumed;')
        line=server.wait('LOD Only job finished');assert 'generated=38,' in line,line
        edit(structures=False);reload()
        server.command('voxylod 2048 2048 square 0 start');server.wait('LOD area accepted:')
        line=server.wait('LOD Only job finished');assert 'generated=39,' in line,line
        server.command('voxylod 4096 4096 square 0 start');server.wait('LOD area accepted:')
        line=server.wait('LOD Only job finished');assert 'generated=40,' in line,line
        edit(structures=True);reload()
        server.command('voxylod 2048 2048 circle 48 start');server.wait('LOD area accepted:')
        line=server.wait('LOD Only job finished');assert 'generated=41,' in line,line
        # A stop arriving while reload is pending must override its automatic setting.
        edit(automatic=True);server.command('voxylod reload');server.command('voxylod stop')
        server.wait('LOD config reload accepted;');server.wait('LOD job stopped;');time.sleep(.3)
        line=status();assert 'automatic=false' in line and 'configReloadPending=false' in line and 'inFlight=0' in line and 'conversionTasks=0' in line,line
        server.stop();server=None
        assert not remote_region_files(),remote_region_files()
        checkpoints=fixture/'verify-world/voxy_lod_only'
        counts={p.name:struct.unpack('>I',p.read_bytes()[12:16])[0] for p in checkpoints.glob('*.bin')}
        assert sorted(counts.values())==[1,38],counts
        server=Server(2);line=status();assert 'radiusChunks=24,' in line,line
        server.command('voxylod stop');server.wait('LOD job stopped;');server.stop();server=None
        (fixture/'result.txt').write_text('CONFIG_RELOAD PASS\n')
        print('CONFIG_RELOAD PASS fixture='+str(fixture),flush=True);sys.exit(0)
    if '--area-only' in sys.argv:
        def status():
            server.command('voxylod status');return server.wait('Voxy LOD Only: automatic=')
        def count():return int(re.search(r'generated=(\d+)',status()).group(1))
        def expected(cx,cz,r,shape):
            result=set()
            for x in range((cx-r)//16,(cx+r)//16+1):
                for z in range((cz-r)//16,(cz+r)//16+1):
                    dx=max(x*16-cx,0,cx-(x*16+15));dz=max(z*16-cz,0,cz-(z*16+15))
                    if shape=='square' or dx*dx+dz*dz<=r*r:result.add((x,z))
            return result
        circle=expected(2048,2048,48,'circle');square=expected(-4097,4097,32,'square')
        server.command('voxylod 2048 2048 circle 48 start');server.wait('LOD area accepted:')
        server.command('voxylod pause');server.wait('LOD job paused;')
        time.sleep(1);paused=status();assert 'state=paused' in paused and 'generated=0,' in paused,paused
        server.command('voxylod resume');server.wait('LOD job resumed;')
        deadline=time.monotonic()+60
        while count()<1:
            assert time.monotonic()<deadline;time.sleep(.2)
        server.command('voxylod pause');server.wait('LOD job paused;')
        baseline=count();time.sleep(2);assert count()==baseline
        server.command('voxylod resume');server.wait('LOD job resumed;')
        line=server.wait('LOD Only job finished');assert f'generated={len(circle)},' in line,line
        server.command('lodonlytest inspect');line=server.wait('VERIFY_STORAGE dimension=minecraft:overworld')
        assert int(re.search(r'nonAir=(\d+)',line).group(1))>1000,line
        server.command('voxylod -4097 4097 square 32 start');server.wait('LOD area accepted:')
        line=server.wait('LOD Only job finished');total=len(circle)+len(square)
        assert f'generated={total},' in line,line
        server.command('execute in minecraft:the_nether run voxylod 4096 4096 square 0 start');server.wait('LOD area accepted:')
        line=server.wait('LOD Only job finished');total+=1;assert f'generated={total},' in line,line
        server.command('voxylod 8192 8192 square 8192 start');server.wait('LOD area accepted:')
        time.sleep(.2);server.command('voxylod stop');server.wait('LOD job stopped;')
        # Queue replacement while cancellation drains, and ensure old output never commits.
        server.command('voxylod -8193 -4097 circle 0 start');server.wait('LOD area accepted:')
        line=server.wait('LOD Only job finished');total+=1;assert f'generated={total},' in line,line
        final=status();assert 'state=idle' in final and 'inFlight=0' in final and 'conversionTasks=0' in final and 'automatic=false' in final,final
        server.command('voxylod 0 0 triangle 32 start');server.wait('Incorrect argument for command')
        server.command('voxylod 0 0 circle 8193 start');server.wait('Integer must not be more than 8192')
        server.stop();server=None
        assert not remote_region_files(),remote_region_files()
        def checkpoint(dimension):
            name=base64.urlsafe_b64encode(dimension.encode()).decode().rstrip('=')+'-structures.bin'
            raw=(fixture/'verify-world/voxy_lod_only'/name).read_bytes()
            magic,seed,n=struct.unpack('>IqI',raw[:16]);assert magic==0x564c4f31 and len(raw)==16+n*8
            result=set()
            for (v,) in struct.iter_unpack('>Q',raw[16:]):
                x=v&0xffffffff;z=v>>32
                result.add((x if x<2**31 else x-2**32,z if z<2**31 else z-2**32))
            return result
        assert checkpoint('minecraft:overworld')==circle|square|{(-513,-257)}
        assert checkpoint('minecraft:the_nether')=={(256,256)}
        server=Server(2)
        server.command('voxylod 2048 2048 circle 48 start');server.wait('LOD area accepted:')
        line=server.wait('LOD Only job finished');assert 'generated=0,' in line and f'skipped={len(circle)},' in line,line
        server.stop();server=None
        (fixture/'result.txt').write_text('AREA_COMMANDS PASS\n')
        print('AREA_COMMANDS PASS circle='+str(len(circle))+' square='+str(len(square))+' fixture='+str(fixture),flush=True)
        sys.exit(0)
    server.command('lodonlytest guard');server.wait('VERIFY_GUARD PASS')
    if '--skip-parallel' not in sys.argv:
        server.command('lodonlytest parallel');server.wait('VERIFY_PARALLEL_ALL PASS',600)
    if '--parallel-only' in sys.argv:
        server.stop();server=None
        assert not remote_region_files(),remote_region_files()
        print('ALL_PARALLEL_AND_CROP_TESTS PASS fixture='+str(fixture),flush=True)
        sys.exit(0)
    server.command('lodonlytest conversion');server.wait('VERIFY_CONVERSION',120)
    assert 'generated=0' in server.job('minecraft:overworld',0,0,0)
    assert 'generated=9' in server.job('minecraft:overworld',128,128,1)
    assert 'generated=10' in server.job('minecraft:the_nether',128,128,0)
    server.command('lodonlytest inspect');server.wait('VERIFY_STORAGE dimension=minecraft:the_end')
    server.command('voxylodonly generate minecraft:overworld -128 -128 2');server.wait('LOD Only generation started')
    time.sleep(.5);server.command('voxylodonly cancel');server.wait('job cancelled')
    time.sleep(3)
    server.command('voxylodonly status')
    server.stop();server=None
    assert not remote_region_files(),remote_region_files()
    print('VERIFY_NO_VANILLA_REGION_FILES PASS',flush=True)

    server=Server(2)
    server.command('lodonlytest inspect');server.wait('VERIFY_STORAGE dimension=minecraft:the_end')
    assert 'generated=0' in server.job('minecraft:overworld',128,128,1)
    server.command('lodonlytest visit');server.wait('VERIFY_NORMAL_VISIT marker=true')
    time.sleep(3)
    assert 'generated=0' in server.job('minecraft:overworld',128,128,0)
    server.command('lodonlytest inspect');server.wait('VERIFY_STORAGE dimension=minecraft:the_end')
    server.command('voxylodonly generate minecraft:overworld 256 256 4');server.wait('LOD Only generation started')
    time.sleep(.5)
    server.stop();server=None
    assert not any('r.8.8.mca' in p for p in remote_region_files())
    print('VERIFY_STOP_DURING_GENERATION PASS',flush=True)

    server=Server(3)
    server.command('lodonlytest checkmarker');server.wait('VERIFY_MARKER persisted=true')
    assert 'generated=0' in server.job('minecraft:overworld',128,128,0)
    server.stop();server=None

    server=Server(4)
    server.command('voxylodonly generate minecraft:overworld 384 384 0');server.wait('LOD Only generation started')
    time.sleep(.15);server.command('lodonlytest visitrace');server.wait('VERIFY_INFLIGHT_VISIT marker=true')
    assert 'generated=0' in server.wait('LOD Only job finished',240)
    assert 'generated=1' in server.job('minecraft:overworld',-128,-128,0)
    server.stop();server=None
    server=Server(5)
    server.command('lodonlytest checkrace');server.wait('VERIFY_INFLIGHT_VISIT_PERSISTENCE PASS')
    assert 'generated=0' in server.job('minecraft:overworld',-128,-128,0)
    server.stop();server=None
    print('VERIFY_INFLIGHT_VISIT_AND_NEGATIVE_COORDINATES PASS',flush=True)
    (fixture/'result.txt').write_text('PASS\n')
    print('ALL_SERVER_TESTS PASS fixture='+str(fixture),flush=True)
finally:
    if server is not None: server.stop()
