"""Disposable production-mappings server tests; never uses a real game/world directory."""
from pathlib import Path
import json, shutil, subprocess, queue, threading, time, sys, re
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
for src in [root/'libs/voxy.jar', root/'libs/voxyserver.jar',
            root/'build/libs/VoxyLodOnly-0.1.0+1.20.1.jar', root/'build/verification/lod-only-verification.jar',
            next((template/'mods').glob('fabric-api-*.jar'))]: shutil.copy2(src, fixture/'mods'/src.name)
(fixture/'eula.txt').write_text('eula=true\n')
(fixture/'server.properties').write_text('server-ip=127.0.0.1\nserver-port=25587\nonline-mode=false\nlevel-name=verify-world\nlevel-seed=424242\nview-distance=2\nsimulation-distance=2\nmax-tick-time=180000\nspawn-protection=0\n')
(fixture/'config/voxy-lod-only.json').write_text(json.dumps({'enabled':True,'automatic':False,'radiusChunks':32,'cooldownTicks':1,'commitChunksPerTick':1,'pauseAboveTickMillis':1000,'structures':True,'dimensions':['minecraft:overworld']}))
(fixture/'config/voxyserver.json').write_text(json.dumps({'lodStreamRadius':32,'maxSectionsPerTickPerPlayer':100,'sectionsPerPacket':50,'generateOnChunkLoad':True,'generateOnChunkUnload':True,'tickInterval':5,'workerThreads':2,'dirtyTrackingEnabled':True,'dirtyTrackingInterval':20,'hashSyncEnabled':True}))
(root/'build/verification-fixture.txt').write_text(str(fixture))
java = r'C:\Program Files\Eclipse Adoptium\jdk-17.0.18.8-hotspot\bin\java.exe'

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
    def command(self, command):
        self.process.stdin.write(command+'\n');self.process.stdin.flush()
    def wait(self, pattern, timeout=180):
        deadline=time.monotonic()+timeout
        while time.monotonic()<deadline:
            if self.process.poll() is not None: raise RuntimeError(f'server exited {self.process.returncode}')
            try: line=self.queue.get(timeout=.5)
            except queue.Empty: continue
            if any(x in line for x in ['ERROR','Exception','LOD Only','VERIFY_','Done (']): print(line.rstrip(),flush=True)
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
    server.command('lodonlytest guard');server.wait('VERIFY_GUARD PASS')
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
