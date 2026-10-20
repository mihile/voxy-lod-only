"""Isolated paired horse-speed flight runs. Source server is read-only; worlds are always disposable."""
from pathlib import Path
from datetime import datetime
import argparse,collections,gzip,hashlib,json,queue,re,shutil,socket,struct,subprocess,threading,time,traceback
import psutil
from flight_client import FlightClient
from flight_metrics import summarize

ROOT=Path(__file__).resolve().parents[1]
JAVA=Path(r'C:\Java\graalvm-25.4.4.1.1+1.1\bin\java.exe')
SOURCE=Path(r'C:\Prominence_2_4.1.3')
OUTPUT=ROOT/'build/performance-20261016'

def nbt(stream,kind):
    def number(fmt):return struct.unpack('>'+fmt,stream.read(struct.calcsize(fmt)))[0]
    def text():return stream.read(number('H')).decode('utf-8',errors='replace')
    if kind in range(1,7):return number({1:'b',2:'h',3:'i',4:'q',5:'f',6:'d'}[kind])
    if kind==7:return stream.read(number('i'))
    if kind==8:return text()
    if kind==9:
        typ=number('B');return [nbt(stream,typ) for _ in range(number('i'))]
    if kind==10:
        result={}
        while True:
            typ=number('B')
            if not typ:return result
            name=text();result[name]=nbt(stream,typ)
    if kind in (11,12):return [number('i' if kind==11 else 'q') for _ in range(number('i'))]
    raise ValueError(kind)

def seed():
    with gzip.open(SOURCE/'world/level.dat','rb') as f:
        typ=f.read(1)[0];name=struct.unpack('>H',f.read(2))[0];f.read(name);root=nbt(f,typ)
    return root['Data']['WorldGenSettings']['seed']

def digest(path):return hashlib.sha256(path.read_bytes()).hexdigest()

def snapshot():
    base=OUTPUT/'pack-snapshot'
    if (base/'inventory.json').exists():
        # Every variant in a new comparison must use the currently built observer.
        helper=ROOT/'build/verification/lod-flight-verification.jar'
        if digest(helper)!=digest(base/'mods/lod-flight-verification.jar'):
            shutil.copy2(helper,base/'mods/lod-flight-verification.jar')
        return base
    base.mkdir(parents=True,exist_ok=True);inventory=[]
    for name in ['libraries','versions','config','defaultconfigs','datapacks','moonlight-global-datapacks']:
        if (SOURCE/name).exists():shutil.copytree(SOURCE/name,base/name)
    shutil.copy2(SOURCE/'fabric-server-launcher.jar',base/'fabric-server-launch.jar')
    (base/'mods').mkdir();excluded=[]
    for jar in sorted((SOURCE/'mods').glob('*.jar')):
        inventory.append(dict(name=jar.name,sha256=digest(jar)))
        if jar.name.lower().startswith(('crashassistant','crash_assistant')):excluded.append(jar.name);continue
        if jar.name.lower().startswith('voxylodonly-'):continue
        shutil.copy2(jar,base/'mods'/jar.name)
    shutil.copy2(ROOT/'build/verification/lod-flight-verification.jar',base/'mods/lod-flight-verification.jar')
    (base/'inventory.json').write_text(json.dumps(dict(mods=inventory,excluded=excluded,seed=seed()),indent=2))
    return base

class Server:
    def __init__(self,folder,heap,scenario=''):
        self.folder=folder;self.history=collections.deque(maxlen=5000);self.q=queue.Queue();self.statuses=[]
        self.log=(folder/'console.log').open('w',encoding='utf8');self.startup_error=None
        self.process=subprocess.Popen([str(JAVA),'-Xms'+heap,'-Xmx'+heap,'-XX:+UseG1GC','-Dflight.scenario='+scenario,
            '-Xlog:gc*:file=flight-gc.log:time,uptime,level,tags','-jar','fabric-server-launch.jar','nogui'],cwd=folder,
            stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,encoding='utf8',errors='replace',bufsize=1)
        self.os=psutil.Process(self.process.pid)
        def read():
            for line in self.process.stdout:
                self.log.write(line);self.log.flush();self.history.append(line);self.q.put(line)
                if 'Could not execute entrypoint stage' in line or 'Exception in thread "main"' in line:self.startup_error=line.strip()
                if 'Voxy LOD Only: automatic=' in line:self.statuses.append(line.strip())
        threading.Thread(target=read,daemon=True).start()
        try:
            self.wait('Done (',600)
            deadline=time.monotonic()+30
            while not (folder/'flight-protocol.json').exists():
                if time.monotonic()>deadline:raise TimeoutError('SERVER_STARTED instrumentation')
                time.sleep(.1)
        except BaseException:
            self.stop();raise
    def wait(self,marker,timeout=120):
        end=time.monotonic()+timeout
        while time.monotonic()<end:
            if self.startup_error:
                self.process.terminate();raise RuntimeError(self.startup_error)
            if self.process.poll() is not None:raise RuntimeError('server exit '+str(self.process.returncode))
            try:line=self.q.get(timeout=.25)
            except queue.Empty:continue
            if 'FLIGHT_VALIDATION_FAILURE' in line:raise RuntimeError(line.strip())
            if marker in line:return line
        raise TimeoutError(marker)
    def command(self,line):self.process.stdin.write(line+'\n');self.process.stdin.flush()
    def stop(self):
        if self.process.poll() is None:
            self.command('stop')
            try:self.process.wait(timeout=180)
            except subprocess.TimeoutExpired:self.process.terminate();raise RuntimeError('test shutdown timeout')
        self.log.close()

def run(base,variant,scenario,duration,heap,warm=None,settle_seconds=30):
    folder=OUTPUT/('flight-'+datetime.now().strftime('%Y%m%d-%H%M%S-%f')+'-'+variant+'-'+scenario)
    shutil.copytree(base,folder)
    # All copies and edits are below the project build directory.
    assert folder.resolve().is_relative_to((ROOT/'build').resolve())
    if warm:shutil.copytree(warm/'flight-world',folder/'flight-world',dirs_exist_ok=True)
    addon=OUTPUT/'baseline-0.8.0.jar' if variant=='old' else ROOT/'build/libs/VoxyLodOnly-0.9.0-dev+1.20.1.jar'
    if variant!='off':shutil.copy2(addon,folder/'mods'/addon.name)
    config=json.loads((folder/'config/voxy-lod-only.json').read_text())
    config.update(enabled=variant!='off',automatic=True,radiusChunks=128,generationTileSize=32,
                  generationThreads=0,maxInFlightBatches=2,structures=True)
    config['withPlayers']=dict(enabled=True,generationThreads=0,cooldownTicks=1,commitChunksPerTick=64,
                             commitBudgetMillis=3,pauseAboveTickMillis=45,maxChunksPerSecond=0)
    (folder/'config/voxy-lod-only.json').write_text(json.dumps(config,indent=2))
    voice=folder/'config/voicechat/voicechat-server.properties'
    if voice.exists():
        text=voice.read_text();text=re.sub(r'^port=.*$','port=-1',text,flags=re.M)
        text=re.sub(r'^bind_address=.*$','bind_address=127.0.0.1',text,flags=re.M);voice.write_text(text)
    port=25594
    with socket.socket() as check:check.bind(('127.0.0.1',port))
    world_seed=json.loads((base/'inventory.json').read_text())['seed']
    (folder/'server.properties').write_text(f'server-ip=127.0.0.1\nserver-port={port}\nonline-mode=false\nlevel-name=flight-world\nlevel-seed={world_seed}\nview-distance=10\nsimulation-distance=10\nmax-tick-time=180000\nspawn-protection=0\nallow-flight=true\nenable-rcon=false\nenable-query=false\n')
    (folder/'eula.txt').write_text('eula=true\n')
    server=None;clients=[];result=dict(variant=variant,scenario=scenario,seconds=duration,heap=heap,fixture=str(folder),
        seed=world_seed,speed_blocks_per_second=8,production_world_copied=False,warm_source=str(warm) if warm else None,
        addon_sha256=digest(addon) if variant!='off' else None,client_rendering=False,
        observer_sha256=digest(folder/'mods/lod-flight-verification.jar'))
    print('FLIGHT_RUN '+json.dumps(result),flush=True)
    try:
        server=Server(folder,heap,scenario);ids=json.loads((folder/'flight-protocol.json').read_text())
        login=json.loads((folder/'flight-login.json').read_text())
        count=1 if scenario=='solo' else 2
        for i in range(count):
            client=FlightClient('Flight'+('A' if i==0 else 'B'),port,ids,login);clients.append(client)
        deadline=time.monotonic()+90
        while any(c.position is None or c.chunks==0 for c in clients):
            if any(c.error for c in clients):raise RuntimeError(str([c.error for c in clients]))
            if time.monotonic()>deadline:raise TimeoutError('initial modpack spawn synchronization')
            time.sleep(.1)
        time.sleep(2)
        server.command('flighttest position');server.wait('FLIGHT_POSITION')
        deadline=time.monotonic()+60
        while any(c.position is None or abs(c.position[0])<15000 for c in clients):
            if any(c.error for c in clients):raise RuntimeError(str([c.error for c in clients]))
            if time.monotonic()>deadline:raise TimeoutError('initial teleport')
            time.sleep(.1)
        # Same fixed settling period in all variants. This does not make terrain/cache from another run available.
        time.sleep(5);server.command('flighttest start');server.wait('FLIGHT_START')
        started=time.monotonic();next_status=started;resources=[];disconnected=False;reconnected=False
        reconnecting=False;reconnect_result={}
        def reconnect():
            try:reconnect_result['client']=FlightClient('FlightB',port,ids,login)
            except Exception as exc:reconnect_result['error']=repr(exc)
        while time.monotonic()-started<duration:
            elapsed=time.monotonic()-started
            if server.process.poll() is not None:raise RuntimeError('early server exit')
            for i,client in enumerate(clients):
                if scenario=='reconnect' and i==1:
                    if elapsed>duration/2 and not disconnected:client.close();disconnected=True
                    if disconnected and not reconnected:
                        if elapsed<duration/2+5:continue
                        if not reconnecting:
                            reconnecting=True;threading.Thread(target=reconnect,daemon=True).start()
                        if 'error' in reconnect_result:raise RuntimeError(reconnect_result['error'])
                        if 'client' not in reconnect_result:continue
                        client=reconnect_result['client'];clients[i]=client;reconnected=True
                        server.command(f'execute in minecraft:overworld run tp FlightB {-16384-elapsed*8} 180 16384');time.sleep(.5)
                    if disconnected and not reconnected:continue
                if client.error:raise RuntimeError(client.name+': '+client.error)
                sign=1 if i==0 or scenario=='together' else -1
                x=sign*(16384+elapsed*8);z=16384+(64*i if scenario=='together' else 0)
                client.move(x,180,z)
            if time.monotonic()>=next_status:
                next_status+=5
                if variant!='off':server.command('voxylodonly status')
                resources.append(dict(seconds=elapsed,rss=server.os.memory_info().rss,
                    cpu_seconds=sum(server.os.cpu_times()[:2]),host_free=psutil.virtual_memory().available,
                    clients=[c.result() for c in clients]))
                print(f'FLIGHT_PROGRESS {variant} {scenario} {elapsed:.0f}/{duration}s',flush=True)
            time.sleep(.05)
        server.command('flighttest finish');server.wait('FLIGHT_FINISH')
        result.update(json.loads((folder/'flight-result.json').read_text()))
        result.update(clients=[c.result() for c in clients],resources=resources,statuses=list(server.statuses))
        result['reconnected']=reconnected
        if variant=='new' and settle_seconds:
            # Outside the timing window: prove yielding can resume useful LOD work with players online.
            mark=len(server.statuses);settle_end=time.monotonic()+settle_seconds
            while time.monotonic()<settle_end:
                server.command('voxylodonly status');time.sleep(2)
            result['stationary_statuses']=server.statuses[mark:]
        for client in clients:client.close()
        if variant!='off':
            server.command('voxylod stop');time.sleep(3);server.command('voxylodonly status');time.sleep(1)
            result['cleanup_statuses']=server.statuses[-2:]
        server.stop();result['exit']=server.process.returncode;server=None
        result['summary']=summarize(result)
        print('FLIGHT_RESULT '+json.dumps(result['summary']),flush=True)
    except Exception as exc:
        result['failure']=repr(exc);result['traceback']=traceback.format_exc();print('FLIGHT_FAILURE '+result['traceback'],flush=True)
    finally:
        for client in clients:client.close()
        if server is not None:
            try:server.stop()
            except Exception as exc:result['shutdown_failure']=repr(exc)
        (folder/'comparison-result.json').write_text(json.dumps(result,indent=2))
    return result,folder

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--variants',nargs='+',default=['off','old','new'],choices=['off','old','new'])
    parser.add_argument('--scenarios',nargs='+',default=['solo','together','opposite','reconnect'],choices=['solo','together','opposite','reconnect','existing'])
    parser.add_argument('--seconds',type=int,default=120);parser.add_argument('--heap',default='6G');args=parser.parse_args()
    OUTPUT.mkdir(parents=True,exist_ok=True);base=snapshot();results=[]
    if not (base/'flight-world/level.dat').exists():
        raise RuntimeError('Missing common spawn-only world: prepare with LOD absent and no players, stop it, '
                           'then copy flight-world into pack-snapshot before comparing variants. '
                           'Independent fresh world/cache initialization is not an equivalent paired baseline.')
    session=OUTPUT/('comparison-'+datetime.now().strftime('%Y%m%d-%H%M%S')+'.json')
    for scenario in args.scenarios:
        warm=None
        if scenario=='existing':
            warm_result,warm=run(base,'off','opposite',args.seconds,args.heap)
            if warm_result.get('failure'):raise RuntimeError('warmup failed')
        for variant in args.variants:
            result,_=run(base,variant,scenario,args.seconds,args.heap,warm);results.append(result)
            session.write_text(json.dumps(results,indent=2))
            if result.get('failure') and variant=='new':raise RuntimeError(result['failure'])
    print('FLIGHT_COMPARISON '+str(session),flush=True)

if __name__=='__main__':main()
