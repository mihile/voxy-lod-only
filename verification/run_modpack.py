"""Read a modpack installation; run only a fresh, separate world under this project's build/."""
from pathlib import Path
from datetime import datetime
import argparse, hashlib, json, re, shutil, statistics, sys, time, os

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'benchmark'))
import run as bench

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--source',type=Path,default=Path(r'C:\Prominence_2_4.1.3'))
    parser.add_argument('--soak-seconds',type=int,default=180)
    parser.add_argument('--structure-compat',action='store_true')
    parser.add_argument('--compat-baseline',action='store_true')
    parser.add_argument('--fingerprint-repeats',type=int,default=1,choices=[1,2])
    args=parser.parse_args()
    source=args.source.resolve()
    def error_body(line):
        match=re.search(r'/ERROR\]:\s*(.*)',line)
        if not match:return None
        return re.sub(r'jar:file:/+\S*?/mods/', 'jar:mods/',match.group(1).strip())
    startup_errors=set()
    for line in (source/'logs/latest.log').read_text(encoding='utf-8',errors='replace').splitlines():
        if 'Done (' in line:break
        body=error_body(line)
        if body:startup_errors.add(body)
    folder=ROOT/'build'/('modpack-'+datetime.now().strftime('%Y%m%d-%H%M%S'))
    assert folder.parent.resolve()==(ROOT/'build').resolve() and source!=folder
    folder.mkdir()
    for name in ['libraries','versions','config','defaultconfigs','datapacks','moonlight-global-datapacks']:
        if (source/name).is_dir(): shutil.copytree(source/name,folder/name)
    shutil.copy2(source/'fabric-server-launcher.jar',folder/'fabric-server-launch.jar')
    (folder/'mods').mkdir()
    inventory=[]; excluded=[]
    for jar in sorted((source/'mods').glob('*.jar')):
        inventory.append({'name':jar.name,'sha256':hashlib.sha256(jar.read_bytes()).hexdigest()})
        if jar.name.lower().startswith(('crash_assistant','crashassistant')):
            # This diagnostic helper can close other helper processes or open a crash GUI.
            excluded.append(jar.name);continue
        if jar.name.lower().startswith(('voxy-','voxyserver-','voxylodonly-')):continue
        shutil.copy2(jar,folder/'mods'/jar.name)
    companion=Path(bench.os.environ.get('VOXY_TEST_JAR',str(ROOT/'libs/voxy.jar')))
    for jar in [companion,Path(os.environ.get('VOXY_SERVER_TEST_JAR',str(ROOT/'libs/voxyserver.jar'))),Path(os.environ.get('VOXY_LOD_TEST_JAR',str(ROOT/'build/libs/VoxyLodOnly-0.8.0+1.20.1.jar'))),ROOT/'build/benchmark/lod-benchmark.jar']:
        shutil.copy2(jar,folder/'mods'/jar.name)
    if args.structure_compat:shutil.copy2(ROOT/'build/verification/lod-only-verification.jar',folder/'mods/lod-only-verification.jar')
    (folder/'eula.txt').write_text('eula=true\n')
    (folder/'server.properties').write_text('server-ip=127.0.0.1\nserver-port=25589\nonline-mode=false\nlevel-name=bench-world\nlevel-seed=424242\nview-distance=2\nsimulation-distance=2\nmax-tick-time=180000\nspawn-protection=0\nenable-rcon=false\nenable-query=false\n')
    (folder/'config/voxy-lod-only.json').write_text(json.dumps({'enabled':True,'automatic':False,'radiusChunks':32,
        'cooldownTicks':1,'commitChunksPerTick':64,'commitBudgetMillis':3,'idleCommitBudgetMillis':10,
        'generationTileSize':32,'generationThreads':0,'pauseAboveTickMillis':1000,'structures':True,'dimensions':['minecraft:overworld']}))
    # Avoid binding a cloned voice service to a production UDP port, if it is installed.
    voice=folder/'config/voicechat/voicechat-server.properties'
    if voice.exists():
        text=voice.read_text();text=re.sub(r'^port=.*$', 'port=25590',text,flags=re.M)
        text=re.sub(r'^bind_address=.*$', 'bind_address=127.0.0.1',text,flags=re.M);voice.write_text(text)
    bench.JAVA=Path(r'C:\Java\graalvm-25.4.4.1.1+1.1\bin\java.exe')
    bench.FLAGS=['-Xms1G','-Xmx6G','-XX:+UseG1GC','-XX:ActiveProcessorCount=4']
    metadata={'source_read_only':str(source),'fixture':str(folder),'source_mods':inventory,'java':str(bench.JAVA),
        'flags':bench.FLAGS,'cpus':bench.CPUS,'seed':424242,'production_world_copied':False,'excluded_diagnostic_mods':excluded,
        'installed_jars':[{ 'name':p.name,'sha256':hashlib.sha256(p.read_bytes()).hexdigest()} for p in (folder/'mods').glob('*.jar') if 'voxy' in p.name.lower()],
        'source_startup_error_bodies':sorted(startup_errors)}
    (folder/'metadata.json').write_text(json.dumps(metadata,indent=2))
    (ROOT/'build/modpack-fixture.txt').write_text(str(folder))
    print('MODPACK_START '+str(folder),flush=True)
    server=None
    try:
        # Keep errors in the raw log. Permit only exact bodies already seen before
        # Done in the running source pack; new errors and BENCH_FAILURE remain fatal.
        server=bench.Server(folder,boot_timeout=600,allowed_error=lambda line:error_body(line) in startup_errors)
        fingerprints={}
        if args.structure_compat:
            if not args.compat_baseline:
                server.command('lodonlytest structurecompat');line=server.wait('VERIFY_STRUCTURE_RESOURCES',180)[1]
                fingerprints['resources']=line.strip();print(line.rstrip(),flush=True)
                assert 'terminalRepairs=26' in line and 'missingOptionalRemoved=146' in line,line
                line=server.wait('VERIFY_STRUCTURE_ENTITIES',30)[1]
                fingerprints['entities']=line.strip();print(line.rstrip(),flush=True)
            for command,marker in [('structurefingerprint','VERIFY_STRUCTURE_FINGERPRINT'),('terrainfingerprint','VERIFY_TERRAIN_FINGERPRINT')]:
                server.command('lodonlytest '+command);line=server.wait(marker,300)[1]
                fingerprints[command]=line.strip();print(line.rstrip(),flush=True)
            if args.fingerprint_repeats==2:
                server.command('lodonlytest terrainfingerprint');line=server.wait('VERIFY_TERRAIN_FINGERPRINT',300)[1]
                fingerprints['terrainfingerprint_repeat']=line.strip();print(line.rstrip(),flush=True)
        def job(dimension,x,z,radius):
            server.command(f'voxylodonly generate {dimension} {x} {z} {radius}')
            server.wait('LOD Only generation started',30)
            line=server.wait('LOD Only job finished',300)[1]
            print(line.rstrip(),flush=True)
        job('minecraft:overworld',128,128,1)
        job('minecraft:the_nether',128,128,0)
        job('minecraft:overworld',-128,-128,1)
        server.command('voxylodonly generate minecraft:overworld 256 256 4')
        server.wait('LOD Only generation started',30)
        time.sleep(.5);server.command('voxylodonly cancel');server.wait('job cancelled',30)
        time.sleep(3);server.command('voxylodonly status')
        line=server.wait('Voxy LOD Only: automatic=',30)[1];print(line.rstrip(),flush=True)
        assert 'inFlight=0' in line and 'conversionTasks=0' in line and 'error=none' in line,line
        deadline=time.perf_counter()+args.soak_seconds
        while time.perf_counter()<deadline:
            if server.process.poll() is not None:raise RuntimeError('modpack server exited during soak')
            time.sleep(min(5,max(.01,deadline-time.perf_counter())))
        server.fence('modpack')
        server.stop()
        resource=server.samples;lines=server.lines;server=None
        addon_errors=[line for _,line in lines if ('LOD Only generation failed' in line or 'Network handle null' in line)]
        assert not addon_errors,addon_errors
        new_errors=[line for _,line in lines if '/ERROR]' in line and error_body(line) not in startup_errors]
        assert not new_errors,new_errors
        remote=[];other_dimensions=[]
        for p in (folder/'bench-world').rglob('r.*.*.mca'):
            relative=p.relative_to(folder/'bench-world')
            # This pack supplies its own Vaaz arena worlds. They are not requested
            # by these jobs; record them separately instead of blaming LOD generation.
            if relative.parts[0]=='dimensions':
                other_dimensions.append(str(relative));continue
            parts=p.name.split('.')
            if abs(int(parts[1]))>=3 or abs(int(parts[2]))>=3:remote.append(str(p.relative_to(folder)))
        assert not remote,remote
        samples=[dict(re.findall(r'(heap_used|gc_ms|mspt)=([\d.]+)',line)) for _,line in lines if 'BENCH_SAMPLE' in line]
        summary={'result':'PASS','fixture':str(folder),'source_mod_count':len(inventory),'soak_seconds':args.soak_seconds,
            'fingerprints':fingerprints,
            'peak_rss_gib':max(s['rss'] for s in resource)/(1024**3),'mean_mspt_all_samples':statistics.mean(float(s['mspt']) for s in samples),
            'remote_region_files':remote,'other_dimension_region_files':other_dimensions,'server_exit':0,
            'baseline_startup_errors':[line.strip() for _,line in lines if error_body(line) in startup_errors],
            'excluded_diagnostic_mods':excluded,'note':'Functional modpack smoke/idle soak, not a Chunky comparison or long gameplay/FPS test.'}
        if args.structure_compat:
            marker=next(t for t,line in lines if 'VERIFY_STRUCTURE_FINGERPRINT' in line)
            summary['blank_pool_warnings']=sum('Empty or non-existent pool: minecraft:' in line for _,line in lines)
            summary['missing_entity_warnings']=sum('Skipping Entity with id quark:glass_frame' in line or 'Skipping Entity with id guardvillagers:guard' in line for _,line in lines)
            if not args.compat_baseline:assert summary['blank_pool_warnings']==0 and summary['missing_entity_warnings']==0,summary
        (folder/'result.json').write_text(json.dumps(summary,indent=2))
        print('MODPACK_PASS '+json.dumps({k:v for k,v in summary.items() if k not in ['other_dimension_region_files','baseline_startup_errors']}),flush=True)
    finally:
        if server is not None:server.stop()

if __name__=='__main__':main()
