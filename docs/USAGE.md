# Voxy LOD Only 전체 사용법

Minecraft **1.20.1 / Fabric / Java 17**용 원거리 LOD 생성 모드입니다.
서버의 청크 로더에 원거리 청크를 등록하지 않고, 메모리 안의 임시 `ProtoChunk` 묶음으로
지형을 생성합니다. 변환된 LOD는 **VoxyServer의 기존 저장소와 전송 경로**를 사용합니다.
실제로 플레이어가 방문하는 청크의 일반 월드 저장 동작은 유지합니다.

현재 버전: `0.8.0+1.20.1` — 구조물 데이터의 알려진 잘못된 참조와 불필요한 LOD 엔티티 처리를 개선했습니다.

## 설치

전용 서버 종료 후 기존 LOD Only JAR을 빼고 `mods`에 `VoxyLodOnly-0.8.0+1.20.1.jar`를 넣습니다.
함께 필요한 모드:

- [Voxy 1.20.1 port.8](https://github.com/mihile/voxy/releases/tag/v0.2.20-1.20.1-port.8).
- [VoxyServer 1.2.4+1.20.1-port.1](https://github.com/mihile/VoxyServer/releases/tag/v1.2.4-1.20.1-port.1)
- Fabric API 0.92.6+1.20.1, Fabric Loader 0.17.2 이상

클라이언트는 기존 Voxy + VoxyServer + Sodium 조합이면 됩니다.
Voxy LOD Only JAR은 클라이언트에 필요하지 않습니다. 모드 메타데이터도 전용 서버 환경으로 지정했습니다. 통합 서버/싱글플레이는 현재 비활성화됩니다.
VoxyServer의 port.2 우회 패치를 이 프로젝트로 복사하지 않았고, 의존 모드 JAR도 배포물에 포함하지 않습니다.

## 동작

1. 플레이어 주변의 작업 영역을 가까운 순서로 선택합니다. 기본 반경은 32청크입니다.
2. 이미 저장된 일반 청크와 현재 로딩된 청크는 건너뜁니다. 기존 월드의 LOD 변환은 아래 import 명령을 사용합니다.
3. 출력 영역을 최대 32×32청크로 묶되, 요청 시작점을 기준으로 나누고 실제 요청한 청크들의 경계에 맞춰 계산 범위를 줄입니다. 주변 생성용 이웃은 유지합니다. 계산 범위 축소는 오버월드에 적용하며 다른 차원은 기존 월드 격자 기준 최대 8×8 묶음으로 전체 문맥을 유지합니다. 독립적인 청크의 생성 단계는 병렬 처리하고, 이웃을 수정하는 장식과 조명은 순서를 유지합니다.
4. 독립된 조명 엔진으로 하늘빛/블록빛을 계산합니다. 엔티티 생성과 일반 청크의 시뮬레이션은 실행하지 않습니다.
5. 같은 작업자 풀에서 Voxy section 변환과 mip 계산을 병렬로 준비합니다. 준비 결과는 최대 16청크이며, 서버 틱이 실제 청크 로딩 여부를 다시 확인하고 시간 예산 안에서 반영합니다. 기본은 플레이 중 3ms, 플레이어가 없을 때 10ms입니다. VoxyServer가 저장과 전송을 담당합니다.
6. 임시 청크 묶음을 해제합니다. 생성 도중 실제 청크가 로딩되었다면 그 위치의 LOD 적용을 중단합니다.

현재 묶음을 LOD에 적용하는 동안 다음 묶음을 생성합니다. 적용 중인 것을 포함해 최대 두 묶음만 보관합니다.
서버 평균 틱 시간이 설정값보다 길거나 Voxy 저장 큐가 밀리면 다음 작업/저장을 기다립니다.

0.8은 LOD 생성 중 구조물의 엔티티 배치 단계를 건너뜁니다. 블록·바이옴·조명은 계속 생성하며,
일반 월드에서 템플릿의 정상 엔티티 배치는 유지합니다. 해당 구조물 템플릿을 메모리 캐시에 처음 읽을 때
다음 두 가지 알려진 데이터 문제도 보정합니다:

- BetterArcheology의 `catacombs/`·`light_temple/` 템플릿에서, `pool`·`target`이 모두 `minecraft:`인 jigsaw 종료 연결의 `pool`만 정상 `minecraft:empty`로 바꿉니다. 공기·철창·계단 등 최종 블록 지정은 그대로 유지합니다.
- Integrated Villages 템플릿의 `quark:glass_frame`·`guardvillagers:guard`가 실제 엔티티 레지스트리에 없으면 해당 선택적 참조만 제외합니다. 등록되어 있거나 다른 종류인 엔티티는 유지합니다.

보정은 일반 구조물 생성에도 적용되며, 원본 JAR·데이터팩·월드 파일은 수정하지 않습니다.
로그 필터로 WARN/ERROR를 숨기지 않습니다. 다른 잘못된 참조와 블록 엔티티 경고는 계속 표시합니다.
`status`의 `terminalPoolsRepaired`, `optionalEntitiesRemoved`, `lodEntitiesSkipped`로 누적 처리량을 확인할 수 있습니다.
자동 작업은 기본적으로 요청 플레이어의 접속 해제, 차원 변경, 작업 범위 이탈 시 취소됩니다.
`continueAutomaticWhenEmpty: true`이면 마지막 플레이어가 나간 뒤 현재 요청 영역을 이어서 처리할 수 있습니다.
완료 후 빈 서버에서 새 중심 좌표를 임의로 선택하지 않습니다. 같은 UUID의 새 접속도 새 플레이어 객체로 구분하며 이전 자동 작업은 취소합니다.
이미 완료한 자동 영역은 최소 100틱 뒤 다시 확인하여 매 틱 같은 영역을 검사하고 완료 로그를 반복하지 않습니다.
수동 작업은 플레이어 없이도 실행할 수 있습니다.

## 명령

OP 레벨 2 이상이 필요합니다. 새 `/voxylod`는 블록 단위이고, 기존 `/voxylodonly generate`는 청크 단위입니다.

### 블록 좌표로 영역 생성 (`/voxylod`)

새 명령은 **X/Z 중심 좌표와 반경 모두 블록 단위**입니다. Y 좌표는 필요 없습니다.
`circle`은 원형, `square`는 정사각형입니다. 숫자는 지름이 아니라 중심에서의 반경입니다.
반경은 0–8192블록, 중심 좌표는 ±29,999,999 범위이며 월드 경계 밖은 건너뜁니다.

```text
/voxylod 0 0 circle 4096 start
/voxylod 1000 -500 square 2048 start
/voxylod pause
/voxylod resume
/voxylod status
/voxylod stop
/voxylod radius 64
/voxylod reload
```

위 첫 명령은 현재 차원에서 (X=0, Z=0)을 중심으로 반경 4096블록의 LOD를 생성합니다.
두 번째 명령은 반경 2048블록, 한 변 약 4096블록의 정사각형입니다.
한 번에 수동 영역 하나를 처리합니다. 다른 수동 작업이 있으면 거부하며 `stop` 후 다시 시작할 수 있습니다.
`start`는 기존 플레이어 주변 자동 작업을 취소하고 안전하게 정리한 뒤 요청 영역을 시작하며,
이번 실행의 자동 생성을 끕니다. 자동 생성을 다시 켜려면 `/voxylod automatic on`을 사용하세요.

- `pause`: 영역 순서와 부분 반영 위치를 보존합니다. 새 생성 제출/LOD 반영은 멈추고 이미 실행한 임시 계산은 안전하게 끝납니다.
- `resume`: 같은 영역의 남은 부분을 이어갑니다. 접속 인원별 프로필이 비활성화되어 있으면 그 조건이 풀릴 때까지 기다립니다.
- `stop`: 작업과 대기 요청을 취소하고 자동 생성도 끕니다. 이미 저장한 LOD는 지우지 않습니다. 남은 계산/참조는 정리 후 해제합니다.
- `status`: 차원, 중심, 모양, 반경, 현재 작업의 완료/건너뛰기 수와 전체 후보 수, 접속 프로필과 정리 상태를 표시합니다.

### 재시작 없이 설정 적용 (0.7)

```text
/voxylod radius 64
/voxylod reload
```

`radius 64`는 플레이어 주변 자동 생성 반경을 **64청크**로 즉시 바꾸고 설정 파일의 `radiusChunks`에도 저장합니다.
진행 중인 자동 작업은 정리 후 새 반경으로 선택합니다. 지정한 수동 영역과 자동 생성 on/off 상태는 유지합니다.
수치는 1–128청크입니다. 이 명령은 다른 파일 설정을 다시 읽지 않으며, 그 항목과 알려지지 않은 JSON 속성은 유지합니다.

`reload`는 편집한 `config/voxy-lod-only.json` 전체를 검사하고 적용합니다. 잘못된 JSON/값이면 오류를 알려주고 실행 중 설정은 유지합니다.
배치 크기·작업자 수·구조물 설정 등이 바뀔 수 있어 현재 계산을 취소하고 참조가 정리된 뒤 적용하며, 서버 틱을 막아서 기다리지 않습니다.
수동 작업은 원래 영역을 보존하여 새 설정으로 다시 선택하고, 완료 체크포인트를 건너뛰면서 이어갑니다.
수동 `pause` 상태도 유지합니다. 반영 중이었지만 완료 기록이 없는 청크는 다시 생성할 수 있습니다.
비활성화된 설정에서도 `reload`로 다시 활성화할 수 있습니다. 상태의 `configReloadPending=false`와 로그의 `LOD config applied`로 적용을 확인합니다.
프로필과 새 스레드 수는 접속 인원이 그대로여도 갱신됩니다. 자동 작업은 현재 플레이어/차원을 다시 확인하여 생성하므로 이전 연결의 중심을 이어받지 않습니다.
파일의 `automatic`도 반영하지만, 유지 중인 수동 영역이 있으면 현재 자동 on/off 상태를 보존합니다.
구조물 포함 여부를 바꾸어 같은 위치의 데이터를 다시 만들면 반대 모드의 오래된 완료 기록을 무효화하며, 두 모드의 체크포인트는 정상 종료 때 저장합니다.
설정 변경과 반경 축소는 저장된 LOD를 삭제하지 않습니다. 이 모드 설정만 대상으로 하며 Voxy/VoxyServer 설정 파일은 별도입니다.

0.6 이하에는 이 명령이 없으므로 새 JAR로 업그레이드할 때 한 번은 서버를 재시작해야 합니다. 이후 설정 변경에는 재시작이 필요 없습니다.

차원은 명령 실행 위치를 따릅니다. 콘솔에서는 기본 오버월드이며 다른 차원은 다음처럼 지정합니다:

```text
/execute in minecraft:the_nether run voxylod 0 0 circle 2048 start
```

형태 내부의 블록 열이 하나라도 포함된 청크를 처리하므로 경계에는 청크의 나머지 부분이 걸칠 수 있습니다.
반경 0은 중심 블록이 들어 있는 청크 하나입니다. 주변 계산용 지형은 필요하지만 LOD 저장은 선택한 청크에만 적용합니다.
실제로 로딩되거나 기존 일반 파일에 있는 청크는 건너뛰고, 기존 FULL 데이터 변환은 VoxyServer import를 사용합니다.
수동 영역은 접속 해제나 차원 이동으로 중심이 바뀌지 않으며 기존 인원별 처리량 제한이 적용됩니다.
서버 재시작 후 남은 영역을 계속하려면 같은 `start` 명령을 다시 실행하세요. 정상 종료 때 기록된 완료 위치는 건너뜁니다.

### 기존 청크 좌표 명령 (`/voxylodonly`)

아래 기존 명령은 계속 **청크 좌표/청크 반경**을 받습니다.
반경은 0–128이며, 0은 지정한 청크 하나입니다.

```text
/voxylodonly status
/voxylodonly automatic off
/voxylodonly automatic on
/voxylodonly generate minecraft:overworld 128 128 16
/voxylodonly generate minecraft:the_nether 128 128 8
/voxylodonly cancel
```

기존 일반 월드 데이터를 가져올 때는 VoxyServer의 명령을 사용합니다:

```text
/voxyserver import existing all
/voxyserver import existing status
```

이 import 명령은 기존 `.mca`를 읽어서 LOD로 변환합니다. 미생성 영역은 Voxy LOD Only가 담당합니다.
`automatic on/off`는 실행 중 상태를 바꿉니다. 영구 설정은 아래 파일을 수정하고 `/voxylod reload`로 적용합니다.

## 설정

첫 실행에 생성되는 `config/voxy-lod-only.json`:

```json
{
  "enabled": true,
  "automatic": true,
  "radiusChunks": 32,
  "cooldownTicks": 1,
  "commitChunksPerTick": 64,
  "generationThreads": 0,
  "generationTileSize": 32,
  "maxInFlightBatches": 2,
  "commitBudgetMillis": 3,
  "idleCommitBudgetMillis": 10,
  "pauseAboveTickMillis": 45.0,
  "structures": true,
  "continueAutomaticWhenEmpty": false,
  "withPlayers": {
    "enabled": true,
    "generationThreads": 0,
    "cooldownTicks": 1,
    "commitChunksPerTick": 64,
    "commitBudgetMillis": 3,
    "pauseAboveTickMillis": 45,
    "maxChunksPerSecond": 0
  },
  "withoutPlayers": {
    "enabled": true,
    "generationThreads": 0,
    "cooldownTicks": 1,
    "commitChunksPerTick": 64,
    "commitBudgetMillis": 10,
    "pauseAboveTickMillis": 45,
    "maxChunksPerSecond": 0
  },
  "dimensions": ["minecraft:overworld"]
}
```

`dimensions`는 자동 생성 대상입니다. 수동 명령으로 다른 차원도 지정할 수 있습니다.
`generationThreads: 0`은 JVM이 인식하는 CPU 수에 맞춰 최대 8개 작업자를 사용합니다.
플레이어가 있으면 동시 작업자 상한은 CPU 수에서 하나를 뺀 값입니다.
접속 상태가 바뀌면 생성과 변환의 공유 작업자 풀 상한도 바꿉니다. 이미 실행 중인 작업은 안전하게 끝나므로 감소가 즉시 모든 작업을 멈추지는 않습니다. VoxyServer의 저장/전송과 Minecraft 본체까지 포함한 전체 CPU 사용률 제한은 아닙니다.
`generationTileSize`는 4, 8, 16 또는 32, `maxInFlightBatches`는 1 또는 2입니다.
기존 설정에 명시된 8은 유지됩니다. 기본값은 32이며 설정을 바꾼 뒤 `/voxylod reload`로 적용할 수 있습니다.
`commitChunksPerTick`은 1–64이며 기본 64입니다. 기존 값은 유지됩니다.
플레이 중 `commitBudgetMillis: 3`은 유지하고, 별도 `idleCommitBudgetMillis: 10`은 플레이어가 없을 때만 사용합니다.
idle 값도 3으로 지정하면 기존 시간 제한을 유지합니다. 각 section 뒤 시간 예산을 검사하므로 청크 수보다 시간 제한이 우선합니다.
큰 묶음은 이웃 지형의 중복 계산을 줄이지만 묶음당 최대 메모리와 첫 결과 대기 시간이 늘 수 있습니다.
이미 저장된 청크 때문에 중간에 생긴 빈 칸은 이웃 생성에 필요할 수 있어 계산하고, LOD 적용에서는 제외합니다.
두 시간 예산은 서버 틱에서 LOD를 적용하는 목표 시간이며 1–10ms입니다. 단일 section 처리는 이 시간을 넘길 수 있습니다.
기존 설정 파일의 대기/적용량 값은 유지됩니다. 기존 파일에서 위 값을 바꿔야 새 기본 처리량이 적용됩니다.
`structures`는 구조물 시작 데이터와 배치를 포함할지 정합니다. 나무 등 바이옴 장식은 계속 생성됩니다.
클라이언트에 도달하는 범위는 VoxyServer의 `lodStreamRadius`와 클라이언트의 수신/렌더 거리에도 제한됩니다.
반경을 늘려도 지형 생성 비용은 남습니다. 메모리/CPU 사용량과 처리 속도는 월드 생성 설정에 따라 달라집니다.

### 접속 인원에 따른 설정

`withPlayers`는 한 명 이상 접속했을 때, `withoutPlayers`는 아무도 접속하지 않았을 때 적용합니다.
다음 값들을 각 객체에 넣어 독립적으로 조절할 수 있습니다:

| 설정 | 의미 |
|---|---|
| `enabled` | `false`이면 새 배치 생성과 LOD 반영을 일시 중지합니다. 기존 작업 위치를 보존하고 상태가 바뀌면 이어갑니다. 취소/종료의 정리는 계속 수행합니다. |
| `generationThreads` | 이 모드의 생성·변환 동시 작업자 1–8. 0은 CPU 기준 자동 선택. |
| `maxChunksPerSecond` | 새 청크의 LOD 반영을 시작하는 간격을 제한합니다. 0은 별도 속도 제한 없음, 1–10000은 초당 목표 상한. 예: 10이면 최소 100ms 간격. |
| `commitChunksPerTick` | 한 틱에 완료/건너뛰기 처리할 청크 수 1–64. |
| `commitBudgetMillis` | 서버 틱에서 LOD 반영에 쓸 목표 시간 1–10ms. |
| `cooldownTicks` | 배치 제출 간격. 최소 1틱. |
| `pauseAboveTickMillis` | 서버 평균 틱 시간이 이 값보다 크면 생성 제출과 반영을 기다립니다. |

예를 들어 기존 JSON에 아래 세 항목을 추가하면 플레이 중에는 작업자 1개·초당 10청크·1ms 예산,
빈 서버에서는 작업자 4개·속도 제한 없이·10ms 예산으로 진행합니다:

```json
"continueAutomaticWhenEmpty": true,
"withPlayers": {
  "enabled": true,
  "generationThreads": 1,
  "maxChunksPerSecond": 10,
  "commitBudgetMillis": 1
},
"withoutPlayers": {
  "enabled": true,
  "generationThreads": 4,
  "maxChunksPerSecond": 0,
  "commitBudgetMillis": 10
}
```

이는 기존 JSON 객체 안에 추가할 항목이며 전체 파일은 아닙니다. 플레이 중 완전히 멈추려면 `withPlayers.enabled`를 `false`로 바꾸면 됩니다.
바로 사용할 전체 파일 예시는 [config-examples/voxy-lod-only.json](../config-examples/voxy-lod-only.json)에 있습니다.
설정 파일 변경은 `/voxylod reload` 또는 서버 재시작으로 적용하며, 접속 인원에 따른 전환은 매 틱 자동으로 확인합니다.
`/voxylodonly status`에서 `mode`, `profileEnabled`, `terrainThreads`, `maxChunksPerSecond`, `state=paused`를 확인할 수 있습니다.
기존 파일에 객체/필드가 없으면 기존 상위 설정값을 이어받고, 새 파일은 모든 프로필 설정을 출력합니다.
명시한 프로필 값이 상위 설정보다 우선합니다. 기존 파일은 자동으로 덮어쓰지 않습니다.

속도 설정은 LOD Only가 만드는 미리보기에 적용하며, 일반 방문 청크의 VoxyServer 수집·전송은 제한하지 않습니다.
청크마다 생성 비용이 달라 정확히 해당 속도를 보장하지 않습니다. 지형 생성은 최대 두 배치를 미리 준비하므로 순간 CPU 부하가 일정하지 않을 수 있습니다.
일시 중지 후 작업을 몰아서 따라잡지 않습니다. 진행 중인 임시 생성/변환은 끝까지 정리하고 제한된 결과만 보관하며 새 작업을 제출하지 않습니다.
빈 서버에서 새 영역을 만들려면 콘솔의 `generate`로 영역을 지정하거나, 접속 중 시작한 자동 영역을 `continueAutomaticWhenEmpty`로 이어가세요.

## 저장과 재시작

- LOD: 기존 `world/voxyserver/<world-id>/storage`.
- 완료 위치 체크포인트: `world/voxy_lod_only/*.bin`.
- 일반 청크, 엔티티, POI 파일은 LOD 미리보기 때문에 저장하지 않습니다.
- 체크포인트는 VoxyServer가 종료하며 LOD를 저장한 뒤, 정상 서버 종료 시 원자적으로 기록합니다.
- 비정상 종료로 체크포인트가 남지 않으면 일부 미리보기는 다시 생성될 수 있습니다.
- 기존 청크와 플레이어 수정은 일반 월드 데이터를 기준으로 유지합니다. 전역 저장 차단 패치는 없습니다.
- 체크포인트에는 시드와 차원별 구분이 있습니다. 구조물 설정을 바꾸면 별도 체크포인트를 사용합니다.
- LOD 저장소를 수동으로 초기화하거나 생성 데이터팩을 교체했다면, 서버를 종료한 상태에서 해당 완료 체크포인트도 함께 초기화해야 재생성됩니다.
- 완료 체크포인트는 차원당 최대 4백만 위치로 제한합니다. 용량 초과 시 오류를 알리고 자동 생성을 중단합니다.

## 지원 범위

기본 `NoiseBasedChunkGenerator`와 `FlatLevelSource`를 대상으로 구현했습니다.
현재 데이터팩의 바이옴/구조물/장식 설정을 사용하지만, 다른 종류의 커스텀 ChunkGenerator는 거부합니다.
실제 서버 월드를 직접 수정하거나 로딩하려는 장식은 보호 검사에서 실패하고 자동 생성이 일시 중단됩니다.

미리보기는 새로운 임시 청크를 사용하므로 구버전 월드와의 blending, 플레이어 건축,
실행 시점에 의존하는 모드 지형 생성, 장식의 주변 처리 순서까지 일반 생성과 완전히 같다고 보장하지 않습니다.
실제 방문 시 일반 Minecraft 생성/로딩과 VoxyServer의 정상 수집 경로가 우선합니다.
Prominence 모드팩의 서버 생성과 일부 클라이언트 연동을 검증했습니다. 모든 모드·셰이더 조합의 동작을 보장하지 않습니다. [최신 검증과 제한](VALIDATION.md)을 참고하세요.

## 빌드

Gradle 실행은 JDK 17 이상, 결과물은 Java 17입니다.
위 Voxy와 VoxyServer JAR을 각각 `libs/voxy.jar`, `libs/voxyserver.jar`에 둡니다.

```powershell
.\gradlew.bat build
```

결과: `build/libs/VoxyLodOnly-0.8.0+1.20.1.jar`.
`-sources.jar`와 `build/verification`의 검증 모드는 설치용 모드가 아닙니다.

설계는 [DESIGN.md](../DESIGN.md), [검증 결과](../VALIDATION.md), 재현 가능한 검증 절차는 [verification/README.md](../verification/README.md)를 참고하세요.
Chunky와 같은 CPU/힙 제한으로 비교한 용량·생성/읽기 속도 실측은 [벤치마크 보고서](../benchmark/REPORT.md)에 있습니다.
0.2.0의 병렬화·GraalVM 비교는 [스케줄링 개선 보고서](../benchmark/SCHEDULER_REPORT.md)를 참고하세요.
일반 Temurin 17/25와 GraalVM의 동일 조건 실측은 [JVM 비교](../benchmark/JVM_REPORT.md)에 있습니다.
0.4의 단계 측정과 Chunky 재비교는 [단계별 개선 보고서](../benchmark/PHASE_OPTIMIZATION.md)를 참고하세요.
0.5의 접속 상태별 설정과 실제 재접속 검증은 [작업량 설정 검증 기록](../verification/ACTIVITY_PROFILES.md)에 있습니다.
0.6의 영역 명령과 모양·취소·재시작 검증은 [영역 생성 검증 기록](../verification/AREA_COMMANDS.md)에 있습니다.
0.7의 재시작 없는 설정 적용은 [reload 검증 기록](../verification/CONFIG_RELOAD.md)에 있습니다.

이 프로젝트는 새로 작성한 구현이며 DH나 Voxy WorldGen의 코드를 복사하지 않았습니다.
MIT 라이선스. Minecraft, Fabric, Voxy, VoxyServer는 각자의 별도 라이선스를 따릅니다.
