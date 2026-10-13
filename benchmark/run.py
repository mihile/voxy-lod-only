"""Fresh-world, sequential, equal-budget generation/storage benchmark on Windows."""
from pathlib import Path
import argparse, collections, csv, gzip, hashlib, json, os, queue, re, shutil, statistics, struct, subprocess, threading, time, zlib
from datetime import datetime
import psutil

ROOT = Path(__file__).resolve().parents[1]
JAVA = Path(r'C:\Program Files\Eclipse Adoptium\jdk-17.0.18.8-hotspot\bin\java.exe')
TEMPLATE = Path.home()/'.codex/worktrees/voxyserver-port1-release/voxyserver/build/smoke-server'
CPUS = [0, 2, 4, 6]
FLAGS = ['-Xms1G', '-Xmx4G', '-XX:+UseG1GC', '-XX:ActiveProcessorCount=4']
MODES = ['chunky', 'chunky_voxy', 'lod_default', 'lod_throughput']

def prepare(folder, mode, base=None):
    folder.mkdir(parents=True)
    for name in ['libraries', 'fabric-server-launch.jar', 'fabric-server-launcher.properties', 'server.jar']:
        src = TEMPLATE/name
        if src.is_dir(): shutil.copytree(src, folder/name)
        else: shutil.copy2(src, folder/name)
    (folder/'mods').mkdir(); (folder/'config').mkdir()
    jars = [next((TEMPLATE/'mods').glob('fabric-api-*.jar')), ROOT/'build/benchmark/lod-benchmark.jar']
    if mode.startswith('chunky'): jars.append(ROOT/'build/benchmark/deps/Chunky.jar')
    if mode != 'chunky' and mode != 'base': jars += [Path(os.environ.get('VOXY_TEST_JAR', str(ROOT/'libs/voxy.jar'))), ROOT/'libs/voxyserver.jar']
    if mode.startswith('lod'): jars.append(ROOT/'build/libs/VoxyLodOnly-0.1.0+1.20.1.jar')
    for jar in jars: shutil.copy2(jar, folder/'mods'/jar.name)
    if base: shutil.copytree(base/'bench-world', folder/'bench-world')
    (folder/'eula.txt').write_text('eula=true\n')
    (folder/'server.properties').write_text('server-ip=127.0.0.1\nserver-port=25588\nonline-mode=false\nlevel-name=bench-world\nlevel-seed=424242\nview-distance=2\nsimulation-distance=2\nmax-tick-time=180000\nspawn-protection=0\n')
    (folder/'config/voxyserver.json').write_text(json.dumps({'lodStreamRadius':32,'maxSectionsPerTickPerPlayer':100,'sectionsPerPacket':50,'generateOnChunkLoad':True,'generateOnChunkUnload':True,'tickInterval':5,'workerThreads':2,'dirtyTrackingEnabled':True,'dirtyTrackingInterval':20,'hashSyncEnabled':True}))
    config = {'enabled':True,'automatic':False,'radiusChunks':32,'cooldownTicks':20,'commitChunksPerTick':1,'pauseAboveTickMillis':45,'structures':True,'dimensions':['minecraft:overworld']}
    if mode == 'lod_throughput': config.update(cooldownTicks=1,commitChunksPerTick=4,pauseAboveTickMillis=1000)
    (folder/'config/voxy-lod-only.json').write_text(json.dumps(config))

class Server:
    def __init__(self, folder, boot_timeout=180, allowed_error=None):
        self.folder=folder;self.events=queue.Queue();self.lines=[];self.samples=[];self.stop_monitor=threading.Event()
        self.allowed_error=allowed_error
        self.log=(folder/'server.log').open('w',encoding='utf-8')
        self.created=time.perf_counter()
        self.process=subprocess.Popen([str(JAVA)]+FLAGS+['-jar','fabric-server-launch.jar','nogui'],cwd=folder,
            stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,encoding='utf-8',errors='replace',bufsize=1)
        self.osprocess=psutil.Process(self.process.pid);self.osprocess.cpu_affinity(CPUS)
        assert self.osprocess.cpu_affinity()==CPUS
        def reader():
            for line in self.process.stdout:
                now=time.perf_counter();self.lines.append((now,line));self.log.write(line);self.log.flush();self.events.put((now,line))
        def monitor():
            while not self.stop_monitor.is_set():
                try:
                    memory=self.osprocess.memory_info();cpu=self.osprocess.cpu_times()
                    self.samples.append({'t':time.perf_counter(),'rss':memory.rss,'private':getattr(memory,'private',memory.vms),'cpu':cpu.user+cpu.system,'host_cpu':psutil.cpu_percent(None)})
                except psutil.NoSuchProcess:break
                self.stop_monitor.wait(.2)
        self.reader=threading.Thread(target=reader,daemon=True);self.reader.start()
        self.monitor=threading.Thread(target=monitor,daemon=True);self.monitor.start()
        try:
            self.wait('Done (',boot_timeout);self.wait('BENCH_SAMPLE',30)
        except BaseException:
            try: self.stop()
            except Exception: pass
            raise
    def command(self,command):
        self.process.stdin.write(command+'\n');self.process.stdin.flush()
    def wait(self,pattern,timeout=600):
        end=time.perf_counter()+timeout
        while time.perf_counter()<end:
            if self.process.poll() is not None:raise RuntimeError(f'early server exit {self.folder}')
            try:timestamp,line=self.events.get(timeout=.5)
            except queue.Empty:continue
            if 'BENCH_FAILURE' in line or '/ERROR]' in line or 'Encountered an unexpected exception' in line:
                if self.allowed_error is None or not self.allowed_error(line):raise RuntimeError(line)
            if pattern in line:return timestamp,line
        raise TimeoutError(pattern+' '+str(self.folder))
    def fence(self,label):
        self.command('lodbench fence '+label)
        return self.wait('BENCH_FENCE label='+label)
    def generate(self,mode,cx,cz,radius):
        n=(2*radius+1)**2
        if mode.startswith('chunky'):
            for command in ['chunky world minecraft:overworld','chunky shape square',
                f'chunky corners {(cx-radius)*16+8} {(cz-radius)*16+8} {(cx+radius)*16+8} {(cz+radius)*16+8}',
                'chunky quiet 1']:
                self.command(command)
            start=time.perf_counter();cpu=self.osprocess.cpu_times();self.command('chunky start')
            finish,line=self.wait('Task finished for')
            actual=int(re.search(r'Processed: ([\d,]+) chunks',line).group(1).replace(',',''))
            assert actual==n,(actual,n,line)
        else:
            start=time.perf_counter();cpu=self.osprocess.cpu_times()
            self.command(f'voxylodonly generate minecraft:overworld {cx} {cz} {radius}')
            self.wait('LOD Only generation started')
            finish,line=self.wait('LOD Only job finished')
        return start,finish,cpu.user+cpu.system,line
    def stop(self):
        if self.process.poll() is None:
            self.command('stop')
            try:self.process.wait(timeout=90)
            except subprocess.TimeoutExpired:self.process.kill();self.process.wait();raise
        self.reader.join(timeout=2);self.stop_monitor.set();self.monitor.join(timeout=2);self.log.close()
        if self.process.returncode!=0:raise RuntimeError('server exit '+str(self.process.returncode))

def footprint(world):
    result=collections.Counter()
    for path in world.rglob('*'):
        if not path.is_file():continue
        rel=path.relative_to(world);parts=rel.parts
        if 'voxyserver' in parts:category='lod'
        elif 'voxy_lod_only' in parts:category='checkpoint'
        elif path.suffix=='.mca' and 'entities' in parts:category='entities'
        elif path.suffix=='.mca' and 'poi' in parts:category='poi'
        elif path.suffix in ['.mca','.mcc'] and 'region' in parts:category='terrain'
        else:category='other'
        result[category]+=path.stat().st_size
    result['total']=sum(result.values());return dict(result)

def chunk_metadata(data):
    """Parse only root coordinates/status, skipping large nested block/light arrays."""
    view=memoryview(data);offset=0
    def take(n):
        nonlocal offset
        result=view[offset:offset+n];offset+=n;return result
    def number(fmt):return struct.unpack(fmt,take(struct.calcsize(fmt)))[0]
    def string():return bytes(take(number('>H'))).decode('utf-8')
    def skip(kind):
        if kind in [1,2,3,4,5,6]:take({1:1,2:2,3:4,4:8,5:4,6:8}[kind])
        elif kind==7:take(number('>i'))
        elif kind==8:string()
        elif kind==9:
            subtype=number('>B');count=number('>i')
            for _ in range(count):skip(subtype)
        elif kind==10:
            while (subtype:=number('>B'))!=0:string();skip(subtype)
        elif kind==11:take(number('>i')*4)
        elif kind==12:take(number('>i')*8)
        else:raise ValueError(kind)
    assert number('>B')==10;string();result={}
    while (kind:=number('>B'))!=0:
        name=string()
        if name in ['xPos','zPos'] and kind==3:result[name]=number('>i')
        elif name=='Status' and kind==8:result[name]=string()
        else:skip(kind)
    return result

def region_stats(world,cx,cz,radius):
    counts=collections.Counter();target={};compressed=0
    for path in (world/'region').glob('r.*.*.mca'):
        parts=path.name.split('.');rx,rz=int(parts[1]),int(parts[2])
        with path.open('rb') as file:
            header=file.read(4096)
            if len(header)<4096:continue
            for i in range(1024):
                loc=int.from_bytes(header[i*4:i*4+4],'big')
                if loc==0:continue
                counts['saved_chunks']+=1
                x,z=rx*32+(i&31),rz*32+(i>>5)
                if abs(x-cx)>radius or abs(z-cz)>radius:continue
                file.seek((loc>>8)*4096)
                length=int.from_bytes(file.read(4),'big');code=file.read(1)[0];payload=file.read(length-1)
                if code==2:data=zlib.decompress(payload)
                elif code==1:data=gzip.decompress(payload)
                elif code==3:data=payload
                else:raise ValueError('unsupported region compression '+str(code))
                meta=chunk_metadata(data)
                assert meta['xPos']==x and meta['zPos']==z,meta
                target[f'{x},{z}']=meta['Status'];compressed+=length+4
    counts['target_saved_chunks']=len(target);counts['target_full_chunks']=sum(status in ['full','minecraft:full'] for status in target.values())
    counts['target_compressed_payload_bytes']=compressed
    return dict(counts)

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--radius',type=int,default=8);parser.add_argument('--repeats',type=int,default=3)
    parser.add_argument('--modes',nargs='+',choices=MODES,default=MODES);parser.add_argument('--base');args=parser.parse_args()
    if not 1<=args.radius<=128 or not 1<=args.repeats<=10:parser.error('radius must be 1..128 and repeats 1..10')
    stamp=datetime.now().strftime('%Y%m%d-%H%M%S');session=ROOT/'build/benchmark'/stamp;session.mkdir(parents=True)
    (ROOT/'build/benchmark/latest.txt').write_text(str(session))
    metadata={'seed':424242,'dimension':'minecraft:overworld','center_chunk':[128,128],'radius_chunks':args.radius,'target_chunks':(args.radius*2+1)**2,
        'cpu_affinity':CPUS,'java_flags':FLAGS,'chunky_version':'1.3.146','modes':args.modes,'repeats':args.repeats,
        'host_ram_bytes':psutil.virtual_memory().total,'voxy_lod_only_sha256':hashlib.sha256((ROOT/'build/libs/VoxyLodOnly-0.1.0+1.20.1.jar').read_bytes()).hexdigest()}
    (session/'metadata.json').write_text(json.dumps(metadata,indent=2))
    base=Path(args.base).resolve() if args.base else session/'base';server=None
    try:
        if not args.base:
            prepare(base,'base');server=Server(base)
            for cmd in ['gamerule doMobSpawning false','gamerule doDaylightCycle false','gamerule doWeatherCycle false','gamerule randomTickSpeed 0','time set 1000']:server.command(cmd)
            server.fence('base');server.stop();server=None
        results=[]
        for trial in range(args.repeats):
            order=args.modes[trial%len(args.modes):]+args.modes[:trial%len(args.modes)]
            for mode in order:
                print(f'BENCH_START trial={trial+1} mode={mode} target={metadata["target_chunks"]}',flush=True)
                folder=session/f'{trial+1}-{mode}';prepare(folder,mode,base);server=Server(folder)
                server.generate(mode,512,512,2);server.fence('warmup')
                # Equal minimum JVM age and enough time for VoxyServer's delayed initial-light retries.
                time.sleep(max(2,35-(time.perf_counter()-server.created)))
                server.fence('settled')
                before=footprint(folder/'bench-world')
                server.command('lodbench sample');_,preline=server.wait('BENCH_SAMPLE')
                start,end,cpu_start,line=server.generate(mode,128,128,args.radius)
                if mode.startswith('lod'):
                    actual=int(re.search(r'generated=(\d+)',line).group(1))
                    assert actual==25+metadata['target_chunks'],line
                durable,fence=server.fence('measured')
                cpu=server.osprocess.cpu_times();cpu_end=cpu.user+cpu.system
                selected=[sample for sample in server.samples if start<=sample['t']<=durable]
                heap=[];mspt=[];gc=[]
                for timestamp,entry in server.lines:
                    if start<=timestamp<=durable and 'BENCH_SAMPLE' in entry:
                        values=dict(re.findall(r'(heap_used|heap_committed|gc_count|gc_ms|mspt|processors)=([\d.]+)',entry))
                        assert int(values['processors'])==4
                        heap.append(int(values['heap_used']));mspt.append(float(values['mspt']));gc.append(int(values['gc_ms']))
                server.stop();server=None
                after=footprint(folder/'bench-world');delta={key:after.get(key,0)-before.get(key,0) for key in set(before)|set(after)}
                stats=region_stats(folder/'bench-world',128,128,args.radius)
                if mode.startswith('chunky'):assert stats['target_full_chunks']==metadata['target_chunks'],stats
                else:assert stats.get('target_saved_chunks',0)==0,stats
                result={'trial':trial+1,'mode':mode,'target_chunks':metadata['target_chunks'],'generation_seconds':end-start,'durable_seconds':durable-start,
                    'chunks_per_second':metadata['target_chunks']/(durable-start),'cpu_seconds':cpu_end-cpu_start,
                    'average_cpu_budget_percent':(cpu_end-cpu_start)/(durable-start)/4*100,
                    'peak_rss_bytes':max(sample['rss'] for sample in selected),'peak_private_bytes':max(sample['private'] for sample in selected),
                    'peak_heap_used_bytes':max(heap) if heap else None,'mean_mspt':statistics.mean(mspt) if mspt else None,
                    'host_cpu_mean_percent':statistics.mean(sample['host_cpu'] for sample in selected),'disk_before':before,'disk_after':after,'disk_delta':delta,
                    'region_stats':stats,'lod_positions':int(re.search(r'lod_positions=(\d+)',fence).group(1)),'fixture':str(folder)}
                (folder/'resource-samples.json').write_text(json.dumps(selected))
                results.append(result);(session/'results.json').write_text(json.dumps(results,indent=2))
                print('BENCH_RESULT '+json.dumps({key:result[key] for key in ['trial','mode','durable_seconds','cpu_seconds','peak_rss_bytes','disk_delta','region_stats']}),flush=True)
        print('BENCH_ALL_PASS '+str(session),flush=True)
    finally:
        if server is not None:server.stop()

if __name__=='__main__':main()
