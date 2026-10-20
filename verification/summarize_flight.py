"""Produce a compact, seed-free report from explicitly selected flight trials."""
import argparse
import hashlib
import json
import re
from pathlib import Path
from flight_metrics import summarize


def fields(line):
    return dict(re.findall(r'(\w+)=([^, ]+)', line))


def gc_log(folder):
    pauses=[]
    full=[]
    for line in (folder/'flight-gc.log').read_text(encoding='utf-8').splitlines():
        match=re.search(r'Pause .* ([0-9.]+)ms$',line)
        if not match:continue
        duration=float(match[1]);pauses.append(duration)
        if 'Pause Full' in line:
            full.append(dict(timestamp=line.split(']')[0].lstrip('['),pause_ms=duration))
    return dict(full_count=len(full),max_pause_ms=max(pauses,default=0),full_pauses=full)


def describe(result):
    assert not result.get('failure'), result.get('failure')
    summary = summarize(result)
    assert summary['all_observed_players_in_overworld'], result['fixture']
    assert result['exit'] == 0, result['fixture']
    moving = result.get('statuses', [])[:len(result['resources'])]
    owners = sorted({fields(line)['owner'] for line in moving
                     if fields(line).get('owner', 'manual/none') != 'manual/none'})
    pressure = sorted({fields(line)['pressure'] for line in moving if 'pressure=' in line})
    service = {}
    for line in moving:
        for player, count in re.findall(r'([0-9a-f-]{36}):(\d+):\d+ms', line):
            service[player] = max(service.get(player, 0), int(count))
    cleanup = fields(result.get('cleanup_statuses', [''])[-1])
    stationary = result.get('stationary_statuses', [])
    stationary_progress = None
    if stationary:
        stationary_progress = int(fields(stationary[-1])['generated']) - int(fields(stationary[0])['generated'])
    return dict(
        variant=result['variant'], scenario=result['scenario'], seconds=result['seconds'], heap=result['heap'],
        fixture=Path(result['fixture']).name, addon_sha256=result['addon_sha256'], metrics=summary,
        observer_sha256=result.get('observer_sha256') or hashlib.sha256(
            (Path(result['fixture'])/'mods/lod-flight-verification.jar').read_bytes()).hexdigest(),
        gc_entire_run=gc_log(Path(result['fixture'])),
        observed_owner_count=len(owners), max_selected_tiles_per_session_cursor=service,
        observed_pressure_reasons=pressure, reconnected=result.get('reconnected', False),
        stationary_generated_delta=stationary_progress,
        cleanup={key:cleanup.get(key) for key in ('state', 'inFlight', 'conversionTasks', 'generationGridChunks',
                  'queuedStageTasks', 'activeStageTasks', 'reservedChunks', 'readyOutputChunks', 'error')},
        host_available_min_gib=min(r['host_free'] for r in result['resources']) / 2**30,
        rss_peak_gib=max(r['rss'] for r in result['resources']) / 2**30,
        warm_world=Path(result['warm_source']).name if result.get('warm_source') else None,
    )


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('inputs', nargs='+', type=Path, help='comparison JSON array or individual result JSON')
    parser.add_argument('--output', type=Path, required=True)
    args=parser.parse_args()
    runs=[]
    for path in args.inputs:
        raw=json.loads(path.read_text(encoding='utf-8'))
        runs.extend(raw if isinstance(raw,list) else [raw])
    unique={run['fixture']:run for run in runs}
    excluded=[run['fixture'] for run in unique.values()
              if run['scenario']=='reconnect' and run.get('probeVersion',1)<2]
    rows=[describe(run) for run in unique.values() if run['fixture'] not in excluded]
    args.output.write_text(json.dumps(dict(note='Single exploratory runs; milliseconds, GiB and summed thread CPU seconds. '
        'Read FLIGHT_PERFORMANCE.md for setup exclusions and limits.',
        excluded_obsolete_reconnect_observer=[Path(path).name for path in excluded],runs=rows),indent=2)+'\n',encoding='utf-8')
    for row in rows:
        m=row['metrics']
        ready='none' if m['real_ready_p95'] is None else f"{m['real_ready_p95']:.1f}"
        print(f"{row['scenario']:10} {row['seconds']:3}s {row['variant']:3} "
              f"tick95={m['tick_p95']:.1f} max={m['tick_max']:.1f} ready95={ready} "
              f"FullGC={m['full_gc_delta']} LODcpu={m['lod_cpu_seconds']:.1f} "
              f"output={m['observed_moving_lod_output']} slots={m['maximum_in_flight']}")


if __name__ == '__main__':
    main()
