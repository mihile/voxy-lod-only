from pathlib import Path
import argparse,csv,json,statistics
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from run import ROOT,MODES

parser=argparse.ArgumentParser();parser.add_argument('session');args=parser.parse_args()
session=Path(args.session).resolve();meta=json.loads((session/'metadata.json').read_text())
runs=json.loads((session/'results.json').read_text());post=json.loads((session/'post-analysis.json').read_text())
recovery=json.loads((session/'recovery-probe.json').read_text())
assert len(runs)==12 and len(post)==12
out=ROOT/'benchmark/results';out.mkdir(parents=True,exist_ok=True)
MIB=2**20;GIB=2**30
labels={'chunky':'Chunky','chunky_voxy':'Chunky + VoxyServer','lod_default':'LOD Only default','lod_throughput':'LOD Only throughput'}
names={'chunky':'Chunky 일반 월드','chunky_voxy':'Chunky + VoxyServer','lod_default':'LOD Only 기본','lod_throughput':'LOD Only 대기 최소'}
def rows(mode):return [run for run in runs if run['mode']==mode]
def med(mode,field):return statistics.median(run[field] for run in rows(mode))
def disk(mode):return statistics.median(run['disk_delta']['total'] for run in rows(mode))
summary=[]
for mode in MODES:
    records=[p for p in post if p['mode']==mode]
    summary.append({'mode':mode,'durable_seconds_median':med(mode,'durable_seconds'),
        'generation_seconds_median':med(mode,'generation_seconds'),'cpu_seconds_median':med(mode,'cpu_seconds'),
        'cpu_budget_percent_median':med(mode,'average_cpu_budget_percent'),'peak_rss_gib_median':med(mode,'peak_rss_bytes')/GIB,
        'peak_heap_gib_median':med(mode,'peak_heap_used_bytes')/GIB,'disk_growth_mib_median':disk(mode)/MIB,
        'chunks_per_second_median':med(mode,'chunks_per_second'),
        'compacted_whole_lod_db_mib_median':statistics.median(p['post_compaction_footprint'].get('lod',0) for p in records)/MIB,
        'target_all_lod_levels_payload_mib_median':statistics.median(p.get('compressed_live_payload',{}).get('target_all_levels_bytes',0) for p in records)/MIB})
flat=[]
for run in runs:
    p=next(p for p in post if p['trial']==run['trial'] and p['mode']==run['mode'])
    flat.append({'trial':run['trial'],'mode':run['mode'],'target_chunks':run['target_chunks'],
        'generation_seconds':run['generation_seconds'],'durable_seconds':run['durable_seconds'],'cpu_seconds':run['cpu_seconds'],
        'cpu_budget_percent':run['average_cpu_budget_percent'],'peak_rss_mib':run['peak_rss_bytes']/MIB,
        'peak_heap_mib':run['peak_heap_used_bytes']/MIB,'disk_growth_mib':run['disk_delta']['total']/MIB,
        'terrain_growth_mib':run['disk_delta'].get('terrain',0)/MIB,'lod_growth_mib':run['disk_delta'].get('lod',0)/MIB,
        'target_full_chunk_payload_mib':run['region_stats'].get('target_compressed_payload_bytes',0)/MIB,
        'target_lod_payload_mib':p.get('compressed_live_payload',{}).get('target_all_levels_bytes',0)/MIB,
        'compacted_whole_lod_db_mib':p['post_compaction_footprint'].get('lod',0)/MIB,
        'cached_read_seconds':p.get('cached_read',{}).get('operation_seconds','')})
with (out/'runs.csv').open('w',newline='',encoding='utf-8') as file:
    writer=csv.DictWriter(file,fieldnames=list(flat[0]));writer.writeheader();writer.writerows(flat)
(out/'summary.json').write_text(json.dumps({'metadata':meta,'summary':summary},indent=2),encoding='utf-8')

colors=['#64748b','#3b82f6','#f97316','#16a34a']
fig,axes=plt.subplots(2,2,figsize=(12,8),layout='constrained')
spec=[('durable_seconds','Generation + durable write','Seconds',1),('cpu_seconds','Total process CPU time','CPU seconds',1),
      ('peak_rss_bytes','Peak working set','GiB',GIB),('disk_growth','Actual world disk growth (WAL included)','MiB',MIB)]
for ax,(field,title,unit,scale) in zip(axes.flat,spec):
    values=[]
    for i,mode in enumerate(MODES):
        samples=[(run['disk_delta']['total'] if field=='disk_growth' else run[field])/scale for run in rows(mode)]
        median=statistics.median(samples);values.append(median)
        ax.bar(i,median,color=colors[i],alpha=.85,width=.65)
        ax.scatter([i-.13,i,i+.13],samples,color='#111827',s=25,zorder=3)
        ax.text(i,median+.035*max(values+[max(samples)]),f'{median:.2f}',ha='center',fontsize=10)
    ax.set_title(title,fontsize=11);ax.set_ylabel(unit)
    ax.set_xticks(range(4),['Chunky','Chunky\n+ Voxy','LOD\ndefault','LOD\nthroughput']);ax.grid(axis='y',alpha=.2);ax.set_axisbelow(True)
    ax.set_ylim(0,max(ax.get_ylim()[1],max(values)*1.18))
fig.suptitle('Voxy LOD Only 0.1.0 vs Chunky 1.3.146\n289 chunks · 4 logical CPUs · 4 GiB max heap · 3 runs (median bars, individual dots)',fontsize=14)
fig.savefig(out/'comparison.png',dpi=170);fig.savefig(out/'comparison.svg');plt.close(fig)

plain=next(s for s in summary if s['mode']=='chunky');fast=next(s for s in summary if s['mode']=='lod_throughput');default=next(s for s in summary if s['mode']=='lod_default')
terrain_payload=statistics.median(run['region_stats']['target_compressed_payload_bytes'] for run in rows('chunky'))/MIB
reads={}
for kind in ['world','lod']:
    samples=[p['cached_read'] for p in post if p.get('cached_read',{}).get('type')==kind]
    reads[kind]={'seconds':statistics.median(r['operation_seconds'] for r in samples),
        'cpu':statistics.median(r['cpu_seconds'] for r in samples),'rss':statistics.median(r['peak_rss_bytes'] for r in samples)/GIB,
        'range_seconds':[min(r['operation_seconds'] for r in samples),max(r['operation_seconds'] for r in samples)]}

text=f'''# Voxy LOD Only 용량·CPU·RAM·속도 비교

2026-10-13 KST. **현재 0.1.0은 Chunky보다 신규 지형 생성·저장이 느립니다.**
기본 설정은 약 {default['durable_seconds_median']/plain['durable_seconds_median']:.1f}배,
대기를 최소화한 설정도 약 {fast['durable_seconds_median']/plain['durable_seconds_median']:.1f}배 오래 걸렸습니다.
현재 JAR이 남기는 디스크 사용량은 RocksDB 변경 로그 때문에 일반 월드 단독보다 큽니다.
아래 결과는 기능 구현과 성능 최적화를 구분해서 평가한 것입니다.

## 조건

- AMD Ryzen 7 7800X3D, Windows, 물리 RAM 31.58 GiB.
- 동일 논리 CPU [0,2,4,6]에 프로세스 affinity를 설정. Java에도 `ActiveProcessorCount=4`.
- Java 17.0.18, G1GC, `-Xms1G -Xmx4G`. 최대 Java 힙을 맞췄고, 실제 전체 프로세스 RAM은 따로 측정했습니다.
- Minecraft 1.20.1, Fabric Loader 0.17.2, Fabric API 0.92.6+1.20.1.
- 시드 424242, 오버월드, 구조물 포함. 청크 X/Z 120..136의 **17×17 = 289청크**.
- 동일 초기 월드를 복제하고 매번 새 JVM 사용. 다른 먼 영역 25청크를 예열한 뒤 JVM 나이 최소 35초와 저장 안정화를 기다림.
- 각 방식 3회, 실행 순서를 회전. 플레이어/네트워크/렌더링 작업 없음. 실제 운영 월드나 설치된 JAR은 변경하지 않음.
- Voxy port.8 + VoxyServer port.1. LOD Only JAR SHA-256: `{meta['voxy_lod_only_sha256']}`.
- [Chunky 1.3.146 Fabric 다운로드](https://modrinth.com/plugin/chunky/version/NHWYq9at): Modrinth API의 SHA-512로 검증.
  [공식 FAQ](https://github.com/pop4959/Chunky/wiki/FAQ)는 기본 설정이 최대 속도로 동작하도록 설계되었다고 설명합니다.
  이번에는 다른 버전/로더나 C2ME를 추가하지 않았습니다.

## 신규 생성과 저장: 3회 중앙값

| 방식 | 저장까지 시간 | 청크/초 | CPU 누적 시간 | CPU 할당 사용률 | 최대 RSS | 실제 디스크 증가 |
|---|---:|---:|---:|---:|---:|---:|
'''
for s in summary:
    text+=f"| {names[s['mode']]} | {s['durable_seconds_median']:.2f}s | {s['chunks_per_second_median']:.1f} | {s['cpu_seconds_median']:.2f}s | {s['cpu_budget_percent_median']:.1f}% | {s['peak_rss_gib_median']:.2f} GiB | {s['disk_growth_mib_median']:.2f} MiB |\n"
text+='''
CPU 누적 시간은 모든 프로세스 스레드의 user+system 합입니다. CPU 4개를 모두
1초간 사용하면 4 CPU초입니다. 사용률은 이 합을 실제 경과 시간×4로 나눈 값입니다.
RAM은 200ms 간격 Windows working set 측정입니다. Java 힙 4GiB가 전체 native 메모리의
하드 제한이라는 뜻은 아닙니다. 각 실행의 RSS 변동은 CSV/그림의 개별 점을 확인하세요.

시간은 요청 명령부터 생성 완료, 수집/저장 큐가 5틱 연속 비어 있음, 일반 월드 flush와
Voxy WAL 동기화가 끝날 때까지입니다. DB compaction은 이 시간에 포함하지 않았습니다.
현재 JAR의 기본 저장 동작을 그대로 측정했기 때문입니다.

LOD 기본: cooldown 20틱, commit 1청크/틱, 평균 틱 45ms 초과 시 대기.
LOD 대기 최소: cooldown 1틱, commit 4청크/틱, 평균 틱 제한 1000ms.
하드코딩된 section 사이 3ms yield는 양쪽 모두 유지했습니다. 제품 코드를 바꾸지 않고 설정만 비교했습니다.

Chunky는 289개 요청 청크가 모두 FULL로 저장되었는지 NBT를 확인했습니다.
LOD Only는 해당 영역의 일반 청크 저장 수가 0임을 확인했습니다.
일반 생성은 필요한 주변 의존 청크도 `.mca`에 저장하므로 전체 증가량은 289개 NBT payload보다 큽니다.
작은 스폰 영역의 일반 틱/메타데이터 변화도 전체 증가량에 포함됩니다.

![비교 그래프](results/comparison.png)

## 데이터 크기와 DB 변경 로그

현재 Voxy의 `RocksDBStorageBackend.flush()`는 `flushWal(true)`를 호출합니다.
이 호출은 WAL을 동기화하며, memtable을 SST로 flush하거나 compaction하는 것과 다릅니다.
LOD section과 부모 mip을 반복 갱신한 변경 이력이 로그에 남아 파일 용량을 늘립니다.
[RocksDB 공식 WAL 설명](https://github.com/facebook/rocksdb/wiki/Write-Ahead-Log-%28WAL%29),
[저장 구조 설명](https://github.com/facebook/rocksdb/wiki/RocksDB-Overview).

종료된 테스트 월드를 다시 복사해서 살아 있는 키/값을 집계하고, 모든 관련 column family를
SST flush/compact했습니다. compaction 전후 목표 영역 데이터 크기가 같음을 확인했습니다.
원래 측정 월드와 제품 JAR은 그대로 남겼습니다.

| 데이터 | 크기 중앙값 |
|---|---:|
'''
text+=f'| 요청한 289개 일반 청크의 압축 NBT payload | {terrain_payload:.3f} MiB |\n'
for s in summary:
    if s['mode']=='chunky':continue
    text+=f"| {names[s['mode']]}: 해당 영역 LOD 압축 키/값, 모든 mip 레벨 | {s['target_all_lod_levels_payload_mib_median']:.3f} MiB |\n"
text+=f'''
대기 최소 LOD payload는 일반 NBT payload보다 약
{(1-fast['target_all_lod_levels_payload_mib_median']/terrain_payload)*100:.1f}% 작았습니다.
이 수치는 `.mca` padding/헤더, 엔티티/POI, 공통 블록·바이옴 사전, DB 인덱스·로그를 제외한
데이터 비교입니다. LOD 레코드 경계 때문에 일부 비어 있는 인접 청크 공간도 포함합니다.
Voxy의 3D 데이터와 모든 mip을 저장하므로 일반 청크 대비 수십 배 압축된다고 볼 수 없습니다.

| 방식 | compaction 후 오버월드 LOD DB 전체 |
|---|---:|
'''
for s in summary:
    if s['mode']!='chunky':text+=f"| {names[s['mode']]} | {s['compacted_whole_lod_db_mib_median']:.2f} MiB |\n"
text+='''
이 DB 전체 숫자에는 스폰·예열 LOD도 포함되어 있으므로, 위의 목표 영역 증가량과 직접 빼거나 비교하면 안 됩니다.
실제 배포 JAR이 자동으로 이 compaction을 수행한다는 뜻도 아닙니다.

### 재시작만으로 줄어드는 부분

원래 측정 DB를 추가로 복사해서 **수동 compact를 호출하기 전** 크기도 확인했습니다.
RocksDB를 다시 열며 WAL 복구 데이터가 SST로 정리되므로, 이번 작은 DB에서는
큰 감소의 대부분이 수동 compact 이전에 이미 일어났습니다.

| 1회차 DB 전체 | 종료 직후 | 다시 열기/정상 flush 후, 수동 compact 전 | 수동 compact 후 |
|---|---:|---:|---:|
'''
for probe in recovery:
    text+=f"| {names[probe['mode']]} | {probe['raw_closed_lod_bytes']/MIB:.2f} MiB | {probe['reopened_before_manual_compact_lod_bytes']/MIB:.2f} MiB | {probe['after_manual_compact_lod_bytes']/MIB:.2f} MiB |\n"
text+='''
이 값도 스폰·예열을 포함한 전체 DB입니다. 따라서 종료 직후 WAL 포함 크기만으로
LOD 데이터가 본질적으로 일반 청크보다 크다고 결론 내리면 안 됩니다.
현재 JAR의 단기 디스크 증가량, 압축된 실제 데이터, 다시 열어 정리된 DB를 각각 읽어야 합니다.

## 저장된 영역을 다시 읽기

새 JVM에서 같은 CPU/힙 제한으로 일반 FULL 청크 또는 LOD level-0 section을 읽었습니다.
LOD DB는 정리 후 재시작했고, 목표 영역의 DB 값들을 미리 전체 순회하지 않았습니다.
운영체제 파일 캐시는 비우지 않았습니다. 서버 데이터 읽기이며 클라이언트 렌더링·셰이더·FPS 검사는 아닙니다.

| 방식 | 읽기 시간 중앙값 | CPU 시간 | 최대 RSS |
|---|---:|---:|---:|
'''
for kind,label in [('world','일반 FULL 청크 289개'),('lod','같은 영역의 LOD level-0 section')]:
    r=reads[kind];text+=f"| {label} | {r['seconds']:.3f}s | {r['cpu']:.3f}s | {r['rss']:.2f} GiB |\n"
text+=f'''
이 조건의 LOD 읽기는 약 {reads['world']['seconds']/reads['lod']['seconds']:.1f}배 빨랐습니다.
일반 청크는 엔티티·월드 청크 구조를 복구하고, LOD는 표시용 section을 디코딩합니다.
같은 영역을 읽지만 제공하는 기능과 단위가 다릅니다. 콜드 디스크의 순수 I/O 속도나
화면이 그려질 때까지의 시간을 뜻하지 않습니다.

## 현재 구현의 개선 지점

- 가용 CPU 4개 중 실제 생성 경로가 충분히 병렬화되지 않습니다.
- 4×4 출력 tile마다 새 24×24 ProtoChunk 영역을 만들고 내부 8×8 terrain을 계산합니다.
  인접 tile에서 겹치는 지형·구조물 작업을 다시 수행합니다.
- 틱 단위 section 적용과 대기 설정이 경과 시간을 늘립니다. 대기를 줄여도
  CPU 누적 시간이 줄어들지는 않았습니다.
- 부모 mip의 여러 중간 갱신이 저장 로그에 남습니다. 저장 묶음/coalescing과
  DB flush/compaction 정책을 검토해야 디스크 사용량도 줄일 수 있습니다.

위 항목은 코드 구조와 측정 결과에서 확인한 개선 후보입니다. 개별 함수의 CPU 비중을
JFR 등으로 프로파일링해서 증명한 것은 아닙니다. 기존 캐시를 옮기거나 삭제하지 않았습니다.
현재 결과를 DH 전체 또는 다른 Voxy 버전의 성능으로 일반화하면 안 됩니다.
한 시드·한 영역·한 자원 제한·3회 표본이며 Prominence 전체 모드팩/셰이더는 대상이 아닙니다.

## 자료와 재현

- [원시 실행별 CSV](results/runs.csv)
- [중앙값과 조건 JSON](results/summary.json)
- [벡터 그래프](results/comparison.svg)
- [측정 스크립트](run.py), [종료한 복사본 분석](analyze_storage_and_read.py)
- 로컬 원시 로그/JSON: `build/benchmark/{session.name}`.

```powershell
.\\gradlew.bat remapBenchmarkJar
build/benchmark-venv/Scripts/python.exe benchmark/run.py --radius 8 --repeats 3
build/benchmark-venv/Scripts/python.exe benchmark/analyze_storage_and_read.py build/benchmark/<session>
build/benchmark-venv/Scripts/python.exe benchmark/probe_recovery.py build/benchmark/<session>
build/benchmark-venv/Scripts/python.exe benchmark/report.py build/benchmark/<session>
```

스크립트의 Fabric 서버 템플릿/JDK 경로와 테스트용 Chunky 파일을 준비해야 합니다.
Python 의존성은 psutil과 matplotlib이며 테스트 전용 venv에 설치했습니다.
원래 설치용 모드 JAR은 검증한 0.1.0과 같은 SHA-256을 유지합니다.
'''
(ROOT/'benchmark/REPORT.md').write_text(text,encoding='utf-8')
print(json.dumps({'summary':summary,'reads':reads,'normal_target_payload_mib':terrain_payload},indent=2))
