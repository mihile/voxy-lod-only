"""Audit and report the fixed-Graal 0.2/0.3/Chunky comparison across sessions."""
from pathlib import Path
import csv, hashlib, json, statistics, sys
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt

root=Path(__file__).resolve().parents[1]
sessions=[Path(arg).resolve() for arg in sys.argv[1:]]
assert sessions
order=['baseline02-graal','v03-graal','chunky-graal']
labels=['LOD Only 0.2 / tile 8','LOD Only 0.3 / tile 32','Chunky + VoxyServer']
fields=['durable_seconds','generation_seconds','cpu_seconds','cpu_budget_percent','peak_rss_gib','mean_mspt']
expected={case:hashlib.sha256((root/path).read_bytes()).hexdigest() for case,path in [
    ('baseline02-graal','build/optimization/baseline-0.2.0.jar'),
    ('v03-graal','build/libs/VoxyLodOnly-0.3.0+1.20.1.jar')]}
runs=[]
for session in sessions:
    meta=json.loads((session/'metadata.json').read_text())
    assert meta['target_chunks']==289 and meta['radius']==8 and meta['seed']==424242
    for row in json.loads((session/'results.json').read_text()):
        case=row['case']; assert case in order
        info=meta['cases'][case]
        assert info['cpus']==[0,2,4,6]
        assert info['flags']==['-Xms1G','-Xmx4G','-XX:+UseG1GC','-XX:ActiveProcessorCount=4']
        assert 'graalvm-25.4.4.1.1+1.1' in info['java']
        assert row['lod_positions']==2515
        assert '/ERROR]' not in (Path(row['fixture'])/'server.log').read_text(encoding='utf-8')
        if case in expected:
            assert info['jar_sha256']==expected[case]
            assert row['region_stats']['target_saved_chunks']==0
        else:
            assert row['region_stats']['target_full_chunks']==289
        runs.append({**row,'session':str(session)})
summary=[]
for case,label in zip(order,labels):
    rows=[r for r in runs if r['case']==case]; assert len(rows)==3
    summary.append({'case':case,'label':label,**{f:statistics.median(r[f] for r in rows) for f in fields},
                    'range':[min(r['durable_seconds'] for r in rows),max(r['durable_seconds'] for r in rows)]})
out=root/'benchmark/results';out.mkdir(exist_ok=True)
(out/'chunky-optimization-summary.json').write_text(json.dumps({'sha256':expected,'sessions':[str(p) for p in sessions],'summary':summary},indent=2))
with (out/'chunky-optimization-runs.csv').open('w',encoding='utf-8',newline='') as file:
    writer=csv.DictWriter(file,fieldnames=['session','case','trial']+fields+['lod_positions'])
    writer.writeheader();writer.writerows({f:r[f] for f in writer.fieldnames} for r in runs)
fig,axes=plt.subplots(1,3,figsize=(12,4.4),layout='constrained')
for ax,field,title in zip(axes,['durable_seconds','cpu_seconds','peak_rss_gib'],['Generation + durable save (s)','Total process CPU time (s)','Peak process RAM (GiB)']):
    values=[s[field] for s in summary]
    ax.bar(range(3),values,color=['#9f8d79','#478b74','#6d91ac'])
    for i,s in enumerate(summary):
        ax.scatter([i]*3,[r[field] for r in runs if r['case']==s['case']],s=16,color='black',zorder=3)
        highest=max(r[field] for r in runs if r['case']==s['case'])
        ax.text(i,highest+max(values)*.025,f'{s[field]:.2f}',ha='center',va='bottom')
    ax.set_xticks(range(3),['LOD Only\n0.2 / tile 8','LOD Only\n0.3 / tile 32','Chunky +\nVoxyServer'])
    ax.set_title(title);ax.grid(axis='y',alpha=.2);ax.set_axisbelow(True)
    ax.set_ylim(0,max(values)*1.18)
fig.suptitle('GraalVM 25.4: 289 chunks, 4 logical CPUs, 4 GiB max heap\nMedian bars and 3 individual runs; fresh worlds, same seed')
fig.savefig(out/'chunky-optimization.png',dpi=170);plt.close(fig)
old,new,chunky=summary
gap=(new["durable_seconds"]/chunky["durable_seconds"]-1)*100
comparison=f"측정 중앙값 기준 Chunky보다 {abs(gap):.1f}% "+("더 걸립니다." if gap>=0 else "덜 걸립니다.")
table='\n'.join(f"| {s['label']} | {s['durable_seconds']:.2f}s | {s['cpu_seconds']:.2f}s | {s['cpu_budget_percent']:.1f}% | {s['peak_rss_gib']:.2f} | {s['range'][0]:.2f}–{s['range'][1]:.2f}s |" for s in summary)
java_display=meta['cases']['v03-graal']['java']
report=f'''# Chunky 분석과 Voxy LOD Only 0.3 최적화

GraalVM 25.4 고정 조건에서 **경과 시간 {(1-new['durable_seconds']/old['durable_seconds'])*100:.1f}% 단축**,
처리량 {old['durable_seconds']/new['durable_seconds']:.2f}배입니다.
Chunky 대비 경과 시간은 기존 {old['durable_seconds']/chunky['durable_seconds']:.2f}배에서
{new['durable_seconds']/chunky['durable_seconds']:.2f}배로 줄었습니다. {comparison}

## 반복 측정 결과

| 생성기 | 생성·저장 완료 | CPU 누적 시간 | CPU 할당 사용률 | 최대 실제 RAM GiB | 시간 범위 |
|---|---:|---:|---:|---:|---:|
{table}

![동일 GraalVM 비교](results/chunky-optimization.png)

각 3회 중앙값이며 실행 순서를 회전했습니다.
0.2는 tile=8/commitChunksPerTick=4, 0.3은 새 기본값 tile=32/commitChunksPerTick=16이며
두 버전 모두 generationThreads=0 (3 workers), maxInFlightBatches=2, commitBudgetMillis=3입니다. 25청크 예열과 최소 JVM 나이 45초 후 측정했습니다.
CPU는 논리 CPU [0,2,4,6]에 고정하고 ActiveProcessorCount=4, 힙은 Xms1G/Xmx4G,
G1GC를 사용했습니다. 동일한 초기 월드와 시드 424242, 오버월드 X/Z 120..136의 289청크,
구조물 활성화, 플레이어 없는 전용 서버입니다. 모든 런타임은
`{java_display}`입니다.
추가 Graal 플래그/JFR/동시 검증 작업은 측정 구간에 없습니다.
시간은 명령부터 생성 완료, 저장 큐 안정화, 일반 월드 flush와 Voxy WAL sync까지입니다.
CPU 누적 시간은 전체 프로세스 user+system 합이며, RAM은 전체 프로세스 working set입니다.
Voxy 저장소 전체 압축/compaction 시간을 측정한 것은 아닙니다.

## Chunky 1.3.146의 실제 동작

공식 소스 커밋 `d3956d688b70c10ae598861dfbf901946f7cfabb`은
`git describe --tags`가 `1.3.0-146-gd3956d6`이며 gradle version=1.3입니다.
실험 JAR의 FabricWorld/GenerationTask 바이트코드에서도 같은 호출과 기본값을 확인했습니다.

- [GenerationTask](https://github.com/pop4959/Chunky/blob/d3956d688b70c10ae598861dfbf901946f7cfabb/common/src/main/java/org/popcraft/chunky/GenerationTask.java#L123-L160)는
  세마포어로 기본 최대 50개의 요청을 유지하고, 완료될 때 다음 요청을 넣습니다. 50개 스레드라는 뜻이 아닙니다.
- [FabricWorld](https://github.com/pop4959/Chunky/blob/d3956d688b70c10ae598861dfbf901946f7cfabb/fabric/src/main/java/org/popcraft/chunky/platform/FabricWorld.java#L99-L115)는
  서버 스레드에서 청크 ticket을 추가하고 기존 ChunkHolder의 FULL future를 요청한 뒤 ticket을 해제합니다.
  지형 생성 자체는 Minecraft가 수행합니다. 읽기 검사도 전체 NBT 대신 Status를 스캔합니다.
- Minecraft 1.20.1의 로컬 바이트코드에서는 ChunkHolder.getOrScheduleFuture가 상태별 future를 저장하고
  이미 진행 중이거나 완료된 생성 결과를 재사용하는 것을 확인했습니다. 따라서 이웃 요청들이 동일한
  청크 생성 의존성을 공유할 수 있습니다. Chunky는 LOD 생성 이후의 전체 청크 저장 비용도 부담합니다.
- LOD Only는 FULL/ticket/ChunkHolder를 만들지 않는 독립 메모리 생성기입니다. 이전 구현은 타일마다
  이웃의 생성 단계들을 재계산했고, 부분 요청도 전체 타일 크기로 계산했습니다. 비슷한 CPU 사용률에도
  더 많은 총 CPU 시간을 사용한 이유와 맞습니다. 구체적인 함수별 CPU 기여율은 이 측정으로 확정하지 않습니다.

GPL Chunky 소스를 제품에 복사하지 않았습니다. 기존 독립 생성기의 계산 범위와 작업 배분을 수정했습니다.

## 변경과 계산량

- 실제 eligible 위치의 경계 사각형으로 출력 범위를 줄이고 구조물/카버/장식/조명의 이웃 여유는 유지합니다.
  인접 청크의 광원을 위해 장식 여유는 2청크, terrain 여유는 3청크입니다.
  저장된 청크 등으로 생긴 내부 빈 칸은 이웃에 필요할 수 있어 생성하되 적용하지 않습니다.
- 기본 최대 타일 8→32로 묶고 요청 영역의 시작점을 기준으로 나누어 타일 사이 중복을 줄였습니다. 4/8/16 설정도 지원합니다.
- 원시 long 키 맵으로 좌표 boxing을 제거했습니다. 같은 17×17 이웃 목록을 단계마다 만들지 않고 한 번 만들며,
  WorldGenRegion은 단계별로 별도 생성합니다. 병렬 작업 중 맵과 목록은 변경하지 않습니다.
- 고정 worker lane 대신 먼저 끝나는 작업자가 다음 청크를 가져갑니다. 단계 종료/취소 시 전체 worker drain은 유지합니다.
  이웃을 쓰는 장식과 조명은 기존 순서를 유지합니다.
- 공유 StructureTemplate.Palette의 lazy cache를 ConcurrentHashMap으로 바꿨습니다.
  구조물 병렬 생성 중 HashMap.computeIfAbsent에서 실제 ConcurrentModificationException이 발생했습니다.
  템플릿은 일반 생성과도 공유되므로 모든 palette에 같은 동시 접근 가능한 캐시를 적용합니다.
  블록 목록과 필터 결과는 바꾸지 않습니다.
- VoxyServer의 기존 ingest는 Voxy 작업자에서 변환/밉/적용을 수행합니다. LOD Only의
  변환과 mip 계산도 같은 제한된 작업자 풀로 옮겼으며, 결과는 최대 16청크만 준비합니다.
  실제 청크가 우선하도록 마지막 확인과 WorldUpdater 반영은 서버 스레드에 남겼습니다.
  저장소 참조는 취소/종료 때 작업자가 끝난 뒤 한 번만 해제됩니다.
- 틱당 완료 청크 상한은 4→16으로 올렸지만 section마다 검사하는 3ms 적용 예산은 유지했습니다.
  가벼운 청크가 많을 때 개수 상한 때문에 다음 틱까지 기다리는 시간을 줄입니다.

이번 289청크 요청의 실제 호출 범위로 계산한 수치입니다. 유일한 좌표 수가 아니라 반복 계산을 포함한 단계 청크 수입니다.

| 단계 | 0.2 / 전체 타일 8 | 0.3 / 요청 기준 타일 32 |
|---|---:|---:|
| 출력 타일 개수 | 9 | 1 |
| noise/surface/carvers 대상 | 1,296 | 529 |
| structure starts 대상 | 7,056 | 1,521 |
| decoration 대상 | 900 | 441 |

실제 Chunky 저장 결과도 읽었습니다. 같은 목표 영역과 이웃에는 총 1,521개의 고유 청크가 있으며,
최종 상태는 structure_starts 992, biomes 88, carvers 80, initialize_light 72, full 289입니다.
이 최종 상태로 확인할 수 있는 최소 누적 고유 청크 수는 structure starts 1,521,
noise/carvers 이상 441, features 이상 361입니다. 동일 청크를 단계별로 공유하는 이유를
보여줍니다. 실제 생성 호출 횟수/취소된 계산까지 센 프로파일은 아닙니다.
[Chunky 최종 상태 스냅샷](results/chunky-stage-snapshot.json).

이는 단계 호출 수 감소이며 같은 비율로 CPU 시간이 줄어든다고 가정하지 않습니다.
타일이 32로 가득 차면 임시 terrain은 38×38, structure halo는 54×54입니다.
계산 범위 축소는 오버월드에만 적용합니다. 네더의 큰 타일에서 전체/잘린 장식 결과가 달라지는
회귀 검사가 발견되어 다른 차원은 기존 월드 격자의 최대 8타일에서 전체 문맥을 유지하도록 제한했습니다. 오버월드에서도
모든 시드와 모드의 장식 동등성을 보장하는 것은 아닙니다.
큰 묶음은 최대 메모리와 첫 결과 대기 시간이 늘 수 있으며 이웃 재계산을 완전히 없애지는 않습니다.
아직 직렬 장식/조명과 서버 틱의 LOD 반영은 남아 있습니다. 변환과 mip 계산은 병렬입니다.
남은 경과 시간 차이에서 각 단계의 기여율은 별도 단계 프로파일 없이는 단정하지 않습니다.

## 검증과 적용

모든 비교 실행에서 LOD 위치 수는 2,515로 일치했습니다(스폰/예열 포함).
LOD Only 목표 일반 청크 저장은 0, Chunky 목표 FULL 저장은 289개입니다.
전체 월드의 파일 증가량에는 스폰 영역의 정상 저장과 RocksDB WAL도 포함되므로
그 증가량을 목표 청크 payload 크기로 해석하지 않습니다. LOD 전용 저장 방식을 유지했습니다.
서버 기능/픽셀 이전 렌더 경로 검증은 [VALIDATION.md](../VALIDATION.md)의 0.3 항목을 참고하세요.
서버 전체 모드팩, 모든 시드/구조물, 장시간 TPS나 클라이언트 FPS의 개선을 증명한 시험은 아닙니다.
기본 구조물/장식의 결과를 일반 Minecraft FULL 생성과 모든 위치에서 완전히 같다고 보장하지 않습니다.

기존 설정의 generationTileSize=8은 자동으로 바뀌지 않습니다. 이 표의 0.3 조건을 적용하려면
서버 종료 후 generationTileSize=32와 commitChunksPerTick=16으로 바꾸고
commitBudgetMillis=3은 유지합니다. 기존 캐시/체크포인트와 Voxy/VoxyServer JAR은 변경하지 않습니다.
운영 서버에는 적용하지 않았습니다.

검증 중 한 번 발생한 Voxy 본체 저장소 열기 예외/락 해제 누락은
[별도 원인 기록과 프롬프트](VOXY_STORAGE_LOCK_NOTE.md)에 남겼습니다. 최초 파일 잠금 원인은
확정하지 못했고, 닫힌 fixture를 새 경로로 복사한 최종 그래픽 시험은 통과했습니다.
이 실패 실행은 성능 결과에 포함하지 않습니다.

## 재현 자료

원시 실행: {', '.join('`'+str(p.relative_to(root))+'`' for p in sessions)}.
각 metadata.json에 JVM, affinity, 설정, JAR hash가 기록돼 있습니다.
[전체 CSV](results/chunky-optimization-runs.csv), [요약 JSON](results/chunky-optimization-summary.json).

```text
build/benchmark-venv/Scripts/python.exe benchmark/optimize.py --cases baseline02-graal v03-graal chunky-graal --repeats 3
```

0.2 SHA-256: `{expected['baseline02-graal']}`

0.3 SHA-256: `{expected['v03-graal']}`
'''
(root/'benchmark/CHUNKY_OPTIMIZATION.md').write_text(report,encoding='utf-8')
print(json.dumps(summary,indent=2))
