"""Summarize only the final, unprofiled, equal-budget 0.3/0.4/Chunky trials."""
from pathlib import Path
import csv,json,statistics,sys
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt

ROOT=Path(__file__).resolve().parents[1]
session=Path(sys.argv[1]) if len(sys.argv)>1 else Path((ROOT/'build/optimization/latest.txt').read_text())
rows=json.loads((session/'results.json').read_text());meta=json.loads((session/'metadata.json').read_text())
cases=['v03-graal','v04-graal','chunky-graal'];summary={}
for case in cases:
    group=[r for r in rows if r['case']==case];assert len(group)==3
    assert not any('FlightRecording' in x or 'voxy.lod.profile' in x for x in meta['cases'][case]['flags'])
    summary[case]={key:statistics.median(r[key] for r in group) for key in ['durable_seconds','generation_seconds','cpu_seconds','cpu_budget_percent','peak_rss_gib','mean_mspt']}
    summary[case]['duration_range']=[min(r['durable_seconds'] for r in group),max(r['durable_seconds'] for r in group)]
    assert {r['lod_positions'] for r in group}=={2515}
result={'session':str(session),'companion_voxy':meta['companion_voxy'],'cases':summary,
    'v04_time_reduction_percent':100*(1-summary['v04-graal']['durable_seconds']/summary['v03-graal']['durable_seconds']),
    'v04_cpu_work_change_percent':100*(summary['v04-graal']['cpu_seconds']/summary['v03-graal']['cpu_seconds']-1),
    'v04_vs_chunky_duration_change_percent':100*(summary['v04-graal']['durable_seconds']/summary['chunky-graal']['durable_seconds']-1)}
out=ROOT/'benchmark/results';out.mkdir(exist_ok=True)
(out/'phase-optimization-summary.json').write_text(json.dumps(result,indent=2))
with (out/'phase-optimization-runs.csv').open('w',newline='') as f:
    writer=csv.DictWriter(f,fieldnames=['trial','case','durable_seconds','generation_seconds','cpu_seconds','cpu_budget_percent','peak_rss_gib','mean_mspt','lod_positions'])
    writer.writeheader();writer.writerows({k:r[k] for k in writer.fieldnames} for r in rows)
labels=['LOD Only 0.3','LOD Only 0.4','Chunky + VoxyServer'];colors=['#7c8fa6','#32a7a0','#e6a65a']
fig,ax=plt.subplots(figsize=(8.5,4.2))
for i,case in enumerate(cases):
    data=summary[case];v=data['durable_seconds'];lo,hi=data['duration_range']
    ax.bar(i,v,color=colors[i],width=.55)
    ax.errorbar(i,v,yerr=[[v-lo],[hi-v]],fmt='none',color='#263445',capsize=6)
    ax.text(i,hi+.14,f'{v:.2f} s',ha='center',fontweight='bold')
ax.set_xticks(range(3),labels);ax.set_ylabel('Generation + durable save (seconds)')
ax.set_ylim(0,10);ax.spines[['top','right']].set_visible(False);ax.grid(axis='y',alpha=.2);ax.set_axisbelow(True)
ax.set_title('GraalVM 25.4 / 4 logical CPUs / 4 GiB max heap\n289 target chunks; 3 rotated trials; median and full range',fontsize=11)
fig.tight_layout();fig.savefig(out/'phase-optimization.png',dpi=180);plt.close(fig)
print(json.dumps(result,indent=2))
