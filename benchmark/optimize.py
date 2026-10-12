"""Equal-budget old/new/JVM scheduler trials. Only disposable server fixtures are used."""
from pathlib import Path
import argparse, hashlib, json, re, shutil, statistics, time, subprocess
from datetime import datetime
import run as bench

ROOT = bench.ROOT
GRAAL = Path(r'C:\Java\graalvm-25.4.4.1.1+1.1\bin\java.exe')
JAVA17 = bench.JAVA
JAVA25 = Path(r'C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot\bin\java.exe')
OLD = ROOT/'build/optimization/baseline-0.1.0.jar'
NEW = ROOT/'build/libs/VoxyLodOnly-0.2.0+1.20.1.jar'
CASES = {
    'old-graal': (GRAAL, OLD, {}, []),
    'new-graal': (GRAAL, NEW, {}, []),
    'new-java17': (JAVA17, NEW, {}, []),
    'new-java25': (JAVA25, NEW, {}, []),
    'new-graal-c2': (GRAAL, NEW, {}, ['-XX:-UseJVMCICompiler']),
    'chunky-graal': (GRAAL, None, {}, []),
    'new-graal-flags': (GRAAL, NEW, {}, ['-XX:+UseCompactObjectHeaders', '-Djdk.graal.TuneInlinerExploration=1']),
    'new-graal-serial': (GRAAL, NEW, {'generationThreads':1, 'generationTileSize':4, 'maxInFlightBatches':1}, []),
    'new-graal-tile4': (GRAAL, NEW, {'generationTileSize':4}, []),
    'new-graal-8cpu': (GRAAL, NEW, {}, []),
}

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--cases', nargs='+', choices=CASES, default=['old-graal','new-graal','new-java17','chunky-graal','new-graal-flags'])
    parser.add_argument('--repeats', type=int, default=3)
    parser.add_argument('--radius', type=int, default=8)
    parser.add_argument('--jfr', action='store_true')
    parser.add_argument('--base', type=Path, default=ROOT/'build/benchmark/20261013-020547/base')
    args=parser.parse_args()
    session=ROOT/'build/optimization'/datetime.now().strftime('%Y%m%d-%H%M%S');session.mkdir(parents=True)
    (ROOT/'build/optimization/latest.txt').write_text(str(session))
    base=args.base.resolve()
    assert bench.region_stats(base/'bench-world',128,128,args.radius).get('target_saved_chunks',0)==0
    metadata={'target_chunks':(2*args.radius+1)**2,'seed':424242,'center':[128,128], 'radius':args.radius,
              'warmup_chunks':25,'minimum_jvm_age_seconds':45, 'repeats':args.repeats,'cases':{}}
    results=[];server=None
    diagnostics={}
    try:
        for trial in range(args.repeats):
            order=args.cases[trial%len(args.cases):]+args.cases[:trial%len(args.cases)]
            for case in order:
                java, jar, overrides, extra=CASES[case]
                if case not in diagnostics:
                    diag=subprocess.run([str(java), *extra, '-XX:+PrintFlagsFinal', '-version'],capture_output=True,text=True,check=True)
                    diagnostics[case]={'java_version':diag.stderr.strip(),'compiler_gc_flags':[line.strip() for line in diag.stdout.splitlines()
                        if any(name in line for name in ['UseJVMCICompiler','UseCompactObjectHeaders','TieredCompilation','UseG1GC'])]}
                    if case=='new-graal-c2':assert any('UseJVMCICompiler' in l and '= false' in l for l in diagnostics[case]['compiler_gc_flags'])
                bench.JAVA=java;bench.FLAGS=['-Xms1G','-Xmx4G','-XX:+UseG1GC', '-XX:ActiveProcessorCount='+('8' if case.endswith('8cpu') else '4')]+extra
                bench.CPUS=[0,2,4,6,8,10,12,14] if case.endswith('8cpu') else [0,2,4,6]
                folder=session/f'{trial+1}-{case}'
                # prepare a Voxy-enabled stock Chunky fixture, then select just the generator.
                bench.prepare(folder,'chunky_voxy',base)
                mode='chunky_voxy' if jar is None else 'lod_throughput'
                if jar is not None:
                    (folder/'mods/Chunky.jar').unlink()
                    shutil.copy2(jar,folder/'mods'/jar.name)
                config={'enabled':True,'automatic':False,'radiusChunks':32,'cooldownTicks':1,'commitChunksPerTick':4,
                        'pauseAboveTickMillis':1000,'structures':True,'dimensions':['minecraft:overworld'],
                        'generationThreads':0,'generationTileSize':8,'maxInFlightBatches':2,'commitBudgetMillis':3}
                config.update(overrides);(folder/'config/voxy-lod-only.json').write_text(json.dumps(config))
                if args.jfr: bench.FLAGS += ['-XX:StartFlightRecording=filename=scheduler.jfr,settings=profile,dumponexit=true,maxsize=128M']
                metadata['cases'][case]={'java':str(java),'flags':bench.FLAGS,'cpus':bench.CPUS,'config':config,
                    'jar_sha256':hashlib.sha256(jar.read_bytes()).hexdigest() if jar else None, **diagnostics[case]}
                (session/'metadata.json').write_text(json.dumps(metadata,indent=2))
                print(f'OPT_START trial={trial+1} case={case}',flush=True)
                server=bench.Server(folder)
                server.generate(mode,512,512,2);server.fence('warmup')
                time.sleep(max(2,45-(time.perf_counter()-server.created)));server.fence('settled')
                before=bench.footprint(folder/'bench-world')
                start,end,cpu_start,line=server.generate(mode,128,128,args.radius)
                if jar is not None: assert int(re.search(r'generated=(\d+)',line).group(1))==25+metadata['target_chunks'],line
                durable,fence=server.fence('measured')
                cpu=server.osprocess.cpu_times();cpu_end=cpu.user+cpu.system
                samples=[s for s in server.samples if start<=s['t']<=durable]
                mspt=[float(re.search(r'mspt=([\d.]+)',s).group(1)) for t,s in server.lines if start<=t<=durable and 'BENCH_SAMPLE' in s]
                server.stop();server=None
                after=bench.footprint(folder/'bench-world');stats=bench.region_stats(folder/'bench-world',128,128,args.radius)
                if jar is None: assert stats['target_full_chunks']==metadata['target_chunks'],stats
                else: assert stats.get('target_saved_chunks',0)==0,stats
                result={'trial':trial+1,'case':case,'mode':mode,'fixture':str(folder),'generation_seconds':end-start,
                    'durable_seconds':durable-start,'cpu_seconds':cpu_end-cpu_start,
                    'cpu_budget_percent':(cpu_end-cpu_start)/(durable-start)/len(bench.CPUS)*100,
                    'peak_rss_gib':max(s['rss'] for s in samples)/(1024**3),'mean_mspt':statistics.mean(mspt) if mspt else None,
                    'disk_delta':{k:after.get(k,0)-before.get(k,0) for k in set(before)|set(after)},'region_stats':stats,
                    'lod_positions':int(re.search(r'lod_positions=(\d+)',fence).group(1))}
                (folder/'resource-samples.json').write_text(json.dumps(samples))
                results.append(result);(session/'results.json').write_text(json.dumps(results,indent=2))
                print('OPT_RESULT '+json.dumps(result),flush=True)
        print('OPT_ALL_PASS '+str(session),flush=True)
    finally:
        if server is not None:server.stop()

if __name__=='__main__':main()
