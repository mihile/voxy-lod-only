"""Separate runtime comparison; never mixes it with the scheduler A/B results."""
from pathlib import Path
import csv, hashlib, json, statistics, sys
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt

root=Path(__file__).resolve().parents[1];session=Path(sys.argv[1]).resolve()
meta=json.loads((session/'metadata.json').read_text());runs=json.loads((session/'results.json').read_text())
order=['new-java17','new-java25','new-graal-c2','new-graal']
labels={'new-java17':'Temurin 17','new-java25':'Temurin 25','new-graal-c2':'GraalVM 25 / C2','new-graal':'GraalVM 25 / Graal'}
fields=['durable_seconds','generation_seconds','cpu_seconds','cpu_budget_percent','peak_rss_gib','mean_mspt']
current_hash=hashlib.sha256((root/'build/libs/VoxyLodOnly-0.2.0+1.20.1.jar').read_bytes()).hexdigest()
summary=[]
assert len(runs)==12 and meta['repeats']==3
for case in order:
    rows=[r for r in runs if r['case']==case];assert len(rows)==3
    info=meta['cases'][case];assert info['jar_sha256']==current_hash
    assert info['cpus']==[0,2,4,6] and '-XX:+UseG1GC' in info['flags'] and '-Xmx4G' in info['flags']
    assert not any('StartFlightRecording' in f for f in info['flags'])
    assert all(r['region_stats']['target_saved_chunks']==0 and r['lod_positions']==2515 for r in rows)
    s={'case':case,'label':labels[case],'runs':3,'java_version':info['java_version'],'flags':info['flags'],
       'compiler_gc_flags':info['compiler_gc_flags']}
    s.update({field:statistics.median(r[field] for r in rows) for field in fields})
    s['seconds_range']=[min(r['durable_seconds'] for r in rows),max(r['durable_seconds'] for r in rows)]
    summary.append(s)
out=root/'benchmark/results'
(out/'jvm-summary.json').write_text(json.dumps({'artifact_sha256':current_hash,'summary':summary},indent=2))
with (out/'jvm-runs.csv').open('w',encoding='utf-8',newline='') as file:
    writer=csv.DictWriter(file,fieldnames=['case','trial']+fields+['lod_positions'])
    writer.writeheader();writer.writerows({field:r[field] for field in writer.fieldnames} for r in runs)
fig,axes=plt.subplots(1,3,figsize=(13,4.5),layout='constrained')
xticks=['Temurin\n17','Temurin\n25','GraalVM 25\nC2 control','GraalVM 25\nGraal JIT']
for ax,field,title in zip(axes,['durable_seconds','cpu_seconds','peak_rss_gib'],['Generation + durable save (seconds)','Total process CPU time (seconds)','Peak process RAM (GiB)']):
    values=[s[field] for s in summary]
    ax.bar(range(4),values,color=['#ac8a60','#6d91ac','#90839f','#448c74'])
    for i,s in enumerate(summary):
        ax.scatter([i]*3,[r[field] for r in runs if r['case']==s['case']],s=14,color='black',zorder=3)
        ax.text(i,s[field],f'{s[field]:.2f}',ha='center',va='bottom')
    ax.set_xticks(range(4),xticks);ax.set_title(title);ax.grid(axis='y',alpha=.2);ax.set_axisbelow(True)
    ax.set_ylim(0,max(ax.get_ylim()[1],max(values)*1.18))
fig.suptitle('Voxy LOD Only 0.2.0: JVM comparison\n289 chunks, 4 logical CPUs, 4 GiB max heap, 3 runs (median bars, individual dots)')
fig.savefig(out/'jvm-comparison.png',dpi=170);plt.close(fig)
table='\n'.join(f"| {s['label']} | {s['durable_seconds']:.2f}s | {s['cpu_seconds']:.2f}s | {s['cpu_budget_percent']:.1f}% | {s['peak_rss_gib']:.2f} | {s['seconds_range'][0]:.2f}–{s['seconds_range'][1]:.2f}s |" for s in summary)
native17,native25,c2,graal=summary
def diff(a,b):return (1-b['durable_seconds']/a['durable_seconds'])*100
report=f'''# 일반 JVM과 GraalVM 비교

Voxy LOD Only **0.2.0 최종 JAR**을 바꾸지 않고 JVM만 비교했습니다.
운영 서버·게임·설정과 저장소는 변경하지 않았습니다.

## 결과: 각 3회 중앙값

| 런타임 | 생성·저장 | CPU 누적 시간 | CPU 할당 사용률 | 최대 실제 RAM GiB | 시간 범위 |
|---|---:|---:|---:|---:|---:|
{table}

![JVM 비교](results/jvm-comparison.png)

Graal 기본의 경과 시간 변화는 Temurin 17 대비 {diff(native17,graal):+.1f}%,
Temurin 25 대비 {diff(native25,graal):+.1f}%, 같은 GraalVM의 C2 대비 {diff(c2,graal):+.1f}% 단축입니다.
양수는 Graal이 빠름, 음수는 Graal이 느림을 뜻합니다.
CPU 누적 시간은 모든 프로세스 스레드의 user+system 합이므로 빠른 경과 시간이
더 적은 CPU 연산 시간을 의미하지는 않습니다.

## 비교가 의미하는 것

- Temurin 17.0.18+8과 Oracle GraalVM의 비교에는 **Java 17 대 Java 25** 차이가 섞입니다.
- Temurin 25.0.3+9와 Oracle GraalVM Java 25.0.4.1.1은 메이저 버전이 같지만 패치와 배포판이 다릅니다.
- 같은 GraalVM에서 `-XX:-UseJVMCICompiler`만 바꾼 C2 대조군은 JDK 버전·VM 바이너리를 고정합니다.
  [Graal 공식 문서](https://www.graalvm.org/jdk25/reference-manual/java/options/)의 컴파일러 비교 방법을 사용했습니다.
  PrintFlagsFinal에서 해당 옵션이 false인 것을 확인했습니다.
- 추가 inliner/compact-header 옵션은 사용하지 않았습니다. 모두 G1GC이고 compact headers는 비활성 상태입니다.
- 25청크 요청 예열과 최소 JVM 나이 45초를 사용했습니다. 장시간 정상 상태의 최적 처리량이나
  초기 서버 부팅 속도를 측정한 것은 아닙니다. 표본 3개와 한 시드만으로 작은 차이를 확정할 수 없습니다.
- 이 결과는 전용 서버의 지형 생성·LOD 저장 작업입니다. 전체 Prominence 모드팩/TPS/클라이언트 FPS 결과가 아닙니다.

## 고정 조건과 검증

AMD Ryzen 7 7800X3D, Windows. 동일 논리 CPU [0,2,4,6], `ActiveProcessorCount=4`.
모두 `-Xms1G -Xmx4G -XX:+UseG1GC`. 실제 전체 RAM은 Windows working set으로 따로 측정했습니다.
동일 초기 월드 복사본과 시드 424242, 구조물 포함, 오버월드 X/Z 120..136의 289청크.
매회 새 JVM, 실행 순서 회전, JFR 기록 없음, 플레이어/클라이언트 작업 없음.
시간은 명령부터 생성과 저장 큐 안정화, 일반 월드 flush, Voxy WAL sync까지입니다.
설정: generationThreads=0 (CPU 4개에서 전용 작업자 3개), tile=8, inFlight=2,
cooldown=1, commitChunks=4, commitBudget=3ms, pauseAboveTickMillis=1000.
실제 제품의 기본 pauseAboveTickMillis는 45ms입니다.

12회 모두 목표 영역에 일반 청크 파일 0개를 확인했습니다.
스폰·예열 영역을 포함한 오버월드 전체의 level-0 LOD 위치 수는 모두 2,515개였습니다.
섹션 수만 같다는 것이 지형 내용 전체의 동일성 검증을 뜻하지는 않습니다.
JAR SHA-256: `{current_hash}`.

## 자료와 재현

[실행별 CSV](results/jvm-runs.csv), [버전·옵션·중앙값 JSON](results/jvm-summary.json).
원시 로그/JSON: `{session.relative_to(root).as_posix()}`.
`benchmark/optimize.py --cases new-java17 new-java25 new-graal-c2 new-graal --repeats 3`
이 PC에 이미 설치된 JVM을 사용했으며 시스템 기본 JAVA나 서버 실행 스크립트를 바꾸지 않았습니다.
'''
(root/'benchmark/JVM_REPORT.md').write_text(report,encoding='utf-8')
print(json.dumps({s['label']:{f:round(s[f],3) for f in fields} for s in summary},indent=2))
