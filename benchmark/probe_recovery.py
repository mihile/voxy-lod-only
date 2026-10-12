from pathlib import Path
import argparse,json,shutil,time
from run import ROOT,Server,footprint
parser=argparse.ArgumentParser();parser.add_argument('session');args=parser.parse_args()
session=Path(args.session).resolve()
results=[];server=None
try:
    for mode in ['chunky_voxy','lod_default']:
        source=session/f'1-{mode}';folder=session/'recovery-probe'/mode
        shutil.copytree(source,folder);shutil.copy2(ROOT/'build/benchmark/lod-benchmark.jar',folder/'mods/lod-benchmark.jar')
        row={'mode':mode,'raw_closed_lod_bytes':footprint(folder/'bench-world')['lod']}
        server=Server(folder);server.fence('reopened');time.sleep(1)
        row['reopened_before_manual_compact_lod_bytes']=footprint(folder/'bench-world')['lod']
        server.command('lodbench compact');server.wait('BENCH_COMPACT');time.sleep(1)
        row['after_manual_compact_lod_bytes']=footprint(folder/'bench-world')['lod']
        server.stop();server=None
        results.append(row);print(json.dumps(row),flush=True)
    (session/'recovery-probe.json').write_text(json.dumps(results,indent=2))
finally:
    if server is not None:server.stop()
