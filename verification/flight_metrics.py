"""Common postprocessing for all variants, including early instrumentation trials."""
import re

def percentile(values,p):
    values=sorted(values)
    return values[min(len(values)-1,int((len(values)-1)*p))] if values else None

def summarize(result):
    # The observer reads the previous completed tick. The first two observations
    # include the pre-start/partial command tick, not two complete measured ticks.
    raw=result['ticksMillis'];ticks=raw[2:];samples=result['samples'];waits=result['readyWaitMillis']
    resources=result.get('resources',[])
    network={'lod_bytes':0,'lod_sections':0,'chunks':0};previous={}
    frames=[r['clients'] for r in resources]+[result.get('clients',[])]
    for frame_index,clients in enumerate(frames):
        for client in clients:
            key=(client['name'],client.get('session','first'))
            old=previous.get(key)
            for metric in network:
                value=client[metric]
                if old is not None:network[metric]+=value-old[metric] if value>=old[metric] else value
                elif frame_index:network[metric]+=value
            previous[key]=client
    # One requested status per host resource sample. Later entries belong to the
    # stationary resume check and shutdown; never count them as moving throughput.
    moving_statuses=result.get('statuses',[])[:len(resources)]
    def values(field,lines):
        values=[]
        for line in lines:
            match=re.search(r'(?:^|[, ])'+field+r'=(\d+)',line)
            if match:values.append(int(match.group(1)))
        return values
    def maximum(field):return max(values(field,moving_statuses),default=None)
    generated=values('generated',moving_statuses)
    valid=bool(samples) and all(p['dimension']=='minecraft:overworld' for s in samples for p in s.get('positions',[]))
    valid_probes=result.get('scenario')!='reconnect' or result.get('probeVersion',1)>=2
    if not valid_probes: waits=[]  # Early observer retained elapsed offline time across reconnect.
    return dict(tick_p50=percentile(ticks,.5),tick_p95=percentile(ticks,.95),tick_p99=percentile(ticks,.99),
        tick_max=max(ticks,default=0),tick_over_1s=sum(v>1000 for v in ticks),
        leading_partial_tick_max=max(raw[:2],default=0),excluded_leading_observations=2,
        real_ready_p95=percentile(waits,.95),real_ready_max=max(waits,default=0),unresolved_observer_probes=result['unresolvedRealProbes'],
        real_ready_observer_valid=valid_probes,
        heap_peak_gib=max((s['heapBytes'] for s in samples),default=0)/2**30,
        heap_first_min_gib=min((s['heapBytes'] for s in samples[:max(1,len(samples)//4)]),default=0)/2**30,
        heap_last_min_gib=min((s['heapBytes'] for s in samples[-max(1,len(samples)//4):]),default=0)/2**30,
        full_gc_delta=samples[-1]['fullGc']-samples[0]['fullGc'] if samples else None,
        full_gc_before_measurement=samples[0]['fullGc'] if samples else None,
        gc_ms_delta=samples[-1]['gcMillis']-samples[0]['gcMillis'] if samples else None,
        measured_seconds=samples[-1]['seconds'] if samples else 0,
        lod_cpu_seconds=result['cpuNanos'].get('lod',0)/1e9,
        vanilla_worker_cpu_seconds=result['cpuNanos'].get('vanillaWorkers',0)/1e9,
        streaming_cpu_seconds=result['cpuNanos'].get('voxyserver',0)/1e9,
        lod_transport_bytes=network['lod_bytes'],lod_sections=network['lod_sections'],vanilla_chunk_packets=network['chunks'],
        maximum_in_flight=maximum('inFlight'),maximum_grid=maximum('peakGridChunks'),
        maximum_reserved=maximum('reservedChunks'),maximum_stage_queue=maximum('queuedStageTasks'),
        maximum_generated=maximum('generated'),maximum_cancellations=maximum('cancellations'),
        observed_moving_lod_output=generated[-1]-generated[0] if generated else None,
        maximum_ready_output=maximum('readyOutputChunks'),maximum_conversion_tasks=maximum('conversionTasks'),
        maximum_discarded=maximum('discardedChunks'),
        all_observed_players_in_overworld=valid)
