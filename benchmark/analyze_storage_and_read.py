"""Analyze closed copies of benchmark worlds; original measured fixtures remain intact."""
from pathlib import Path
import argparse,json,re,shutil,time
from run import ROOT,Server,footprint

parser=argparse.ArgumentParser();parser.add_argument('session');args=parser.parse_args()
session=Path(args.session).resolve();meta=json.loads((session/'metadata.json').read_text());runs=json.loads((session/'results.json').read_text())
results=[];server=None
try:
    for run in runs:
        source=Path(run['fixture']);folder=session/'post-analysis'/source.name
        shutil.copytree(source,folder)
        shutil.copy2(ROOT/'build/benchmark/lod-benchmark.jar',folder/'mods/lod-benchmark.jar')
        print(f'ANALYSIS_START trial={run["trial"]} mode={run["mode"]}',flush=True)
        before=footprint(folder/'bench-world');server=Server(folder)
        result={'trial':run['trial'],'mode':run['mode'],'raw_closed_footprint':before,'fixture':str(folder)}
        if run['mode']!='chunky':
            server.fence('analysis_ready')
            server.command('lodbench livebytes');_,line=server.wait('BENCH_LIVE')
            result['compressed_live_payload']=dict((key,int(value)) for key,value in re.findall(r'(total_compressed_key_value_bytes|target_level0_bytes|target_all_levels_bytes|records)=(\d+)',line))
            server.command('lodbench compact');_,line=server.wait('BENCH_COMPACT',120)
            result['compact_seconds']=float(re.search(r'elapsed_ms=([\d.]+)',line).group(1))/1000
            server.command('lodbench livebytes');_,line=server.wait('BENCH_LIVE')
            again=dict((key,int(value)) for key,value in re.findall(r'(total_compressed_key_value_bytes|target_level0_bytes|target_all_levels_bytes|records)=(\d+)',line))
            assert again['target_level0_bytes']==result['compressed_live_payload']['target_level0_bytes']
            assert again['target_all_levels_bytes']==result['compressed_live_payload']['target_all_levels_bytes']
        if run['mode']!='chunky':
            server.stop();server=None
            shutil.copy2(folder/'server.log',folder/'storage-analysis.log')
        if run['mode'] in ['chunky','lod_throughput']:
            # LOD DB is compacted and reopened before this test. No position iteration/fence
            # warms all DB value blocks before measurement; OS filesystem caches stay enabled.
            if server is None:server=Server(folder)
            time.sleep(2)
            kind='world' if run['mode']=='chunky' else 'lod'
            cpu=server.osprocess.cpu_times();cpu0=cpu.user+cpu.system;start=time.perf_counter()
            server.command(f'lodbench load{kind} 128 128 {meta["radius_chunks"]}')
            finish,line=server.wait('BENCH_LOAD type='+kind,120)
            cpu=server.osprocess.cpu_times();memory=server.osprocess.memory_info()
            samples=[sample for sample in server.samples if start<=sample['t']<=finish]
            result['cached_read']={'type':kind,'operation_seconds':float(re.search(r'elapsed_ms=([\d.]+)',line).group(1))/1000,
                'units':int(re.search(r'units=(\d+)',line).group(1)),'cpu_seconds':cpu.user+cpu.system-cpu0,
                'peak_rss_bytes':max([memory.rss]+[sample['rss'] for sample in samples]),'os_cache_flushed':False}
            if kind=='world':assert result['cached_read']['units']==meta['target_chunks']
            else:assert result['cached_read']['units']>0
            server.stop();server=None
            shutil.copy2(folder/'server.log',folder/'cached-read.log')
        result['post_compaction_footprint']=footprint(folder/'bench-world')
        results.append(result);(session/'post-analysis.json').write_text(json.dumps(results,indent=2))
        print('ANALYSIS_RESULT '+json.dumps({k:v for k,v in result.items() if k!='fixture'}),flush=True)
    print('ALL_ANALYSES_PASS',flush=True)
finally:
    if server is not None:server.stop()
