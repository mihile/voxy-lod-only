# Voxy LOD Only

Minecraft **1.20.1 / Fabric / Java 17**용 원거리 LOD 생성 모드입니다.
서버의 청크 로더에 원거리 청크를 등록하지 않고, 메모리 안의 임시 `ProtoChunk` 묶음으로
지형을 생성합니다. 변환된 LOD는 **VoxyServer의 기존 저장소와 전송 경로**를 사용합니다.
실제로 플레이어가 방문하는 청크의 일반 월드 저장 동작은 유지합니다.

현재 버전: `0.2.0+1.20.1` — 병렬 생성·파이프라인 적용판입니다.

## 설치

전용 서버의 `mods`에 `VoxyLodOnly-0.2.0+1.20.1.jar`를 넣습니다.
함께 필요한 모드:

- [Voxy 1.20.1 port.8](https://github.com/mihile/voxy/releases/tag/v0.2.20-1.20.1-port.8)
- [VoxyServer 1.2.4+1.20.1-port.1](https://github.com/mihile/VoxyServer/releases/tag/v1.2.4-1.20.1-port.1)
- Fabric API 0.92.6+1.20.1, Fabric Loader 0.17.2 이상

클라이언트는 기존 Voxy + VoxyServer + Sodium 조합이면 됩니다.
Voxy LOD Only JAR은 클라이언트에 필요하지 않습니다. 통합 서버/싱글플레이는 현재 비활성화됩니다.
VoxyServer의 port.2 우회 패치를 이 프로젝트로 복사하지 않았고, 의존 모드 JAR도 배포물에 포함하지 않습니다.

## 동작

1. 플레이어 주변의 작업 영역을 가까운 순서로 선택합니다. 기본 반경은 32청크입니다.
2. 이미 저장된 일반 청크와 현재 로딩된 청크는 건너뜁니다. 기존 월드의 LOD 변환은 아래 import 명령을 사용합니다.
3. 기본 출력 8×8청크와 주변 생성용 이웃을 메모리에 만듭니다. 독립적인 청크의 생성 단계는 병렬 처리하고, 이웃을 수정하는 장식과 조명은 순서를 유지합니다.
4. 독립된 조명 엔진으로 하늘빛/블록빛을 계산합니다. 엔티티 생성과 일반 청크의 시뮬레이션은 실행하지 않습니다.
5. 서버 틱에서 짧은 단위로 Voxy section을 변환하고 mip을 갱신합니다. VoxyServer가 저장과 전송을 담당합니다.
6. 임시 청크 묶음을 해제합니다. 생성 도중 실제 청크가 로딩되었다면 그 위치의 LOD 적용을 중단합니다.

현재 묶음을 LOD에 적용하는 동안 다음 묶음을 생성합니다. 적용 중인 것을 포함해 최대 두 묶음만 보관합니다.
서버 평균 틱 시간이 설정값보다 길거나 Voxy 저장 큐가 밀리면 다음 작업/저장을 기다립니다.
자동 작업은 요청 플레이어의 접속 해제, 차원 변경, 작업 범위 이탈 시 취소됩니다.
수동 작업은 플레이어 없이도 실행할 수 있습니다.

## 명령

OP 레벨 2 이상이 필요합니다. 좌표는 **블록 좌표가 아니라 청크 좌표**입니다.
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
`automatic on/off`는 이번 실행에만 적용됩니다. 영구 설정은 아래 파일을 수정하고 재시작합니다.

## 설정

첫 실행에 생성되는 `config/voxy-lod-only.json`:

```json
{
  "enabled": true,
  "automatic": true,
  "radiusChunks": 32,
  "cooldownTicks": 1,
  "commitChunksPerTick": 4,
  "generationThreads": 0,
  "generationTileSize": 8,
  "maxInFlightBatches": 2,
  "commitBudgetMillis": 3,
  "pauseAboveTickMillis": 45.0,
  "structures": true,
  "dimensions": ["minecraft:overworld"]
}
```

`dimensions`는 자동 생성 대상입니다. 수동 명령으로 다른 차원도 지정할 수 있습니다.
`generationThreads: 0`은 JVM이 인식하는 CPU 수에서 하나를 남기고 최대 8개 작업자를 사용합니다.
`generationTileSize`는 4 또는 8, `maxInFlightBatches`는 1 또는 2입니다.
`commitBudgetMillis`는 서버 틱에서 LOD를 적용하는 목표 시간이며 1–10ms입니다. 단일 section 처리는 이 시간을 넘길 수 있습니다.
기존 설정 파일의 대기/적용량 값은 유지됩니다. 기존 파일에서 위 값을 바꿔야 새 기본 처리량이 적용됩니다.
`structures`는 구조물 시작 데이터와 배치를 포함할지 정합니다. 나무 등 바이옴 장식은 계속 생성됩니다.
클라이언트에 도달하는 범위는 VoxyServer의 `lodStreamRadius`와 클라이언트의 수신/렌더 거리에도 제한됩니다.
반경을 늘려도 지형 생성 비용은 남습니다. 메모리/CPU 사용량과 처리 속도는 월드 생성 설정에 따라 달라집니다.

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
Prominence 전체 모드팩/셰이더 조합은 이 초기 버전의 검증 범위에 포함하지 않습니다.

## 빌드

Gradle 실행은 JDK 21, 결과물은 Java 17입니다.
위 Voxy와 VoxyServer JAR을 각각 `libs/voxy.jar`, `libs/voxyserver.jar`에 둡니다.

```powershell
.\gradlew.bat build
```

결과: `build/libs/VoxyLodOnly-0.2.0+1.20.1.jar`.
`-sources.jar`와 `build/verification`의 검증 모드는 설치용 모드가 아닙니다.

설계는 [DESIGN.md](DESIGN.md), [검증 결과](VALIDATION.md), 재현 가능한 검증 절차는 [verification/README.md](verification/README.md)를 참고하세요.
Chunky와 같은 CPU/힙 제한으로 비교한 용량·생성/읽기 속도 실측은 [벤치마크 보고서](benchmark/REPORT.md)에 있습니다.
0.2.0의 병렬화·GraalVM 비교는 [스케줄링 개선 보고서](benchmark/SCHEDULER_REPORT.md)를 참고하세요.
일반 Temurin 17/25와 GraalVM의 동일 조건 실측은 [JVM 비교](benchmark/JVM_REPORT.md)에 있습니다.

이 프로젝트는 새로 작성한 구현이며 DH나 Voxy WorldGen의 코드를 복사하지 않았습니다.
MIT 라이선스. Minecraft, Fabric, Voxy, VoxyServer는 각자의 별도 라이선스를 따릅니다.
