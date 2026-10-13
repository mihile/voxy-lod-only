# Voxy LOD Only

[English](README.md) | **한국어**

**일반 청크 파일을 쌓지 않고, 서버에서 원거리 지형의 LOD만 미리 만드는 모드입니다.**

Minecraft **1.20.1 · Fabric · 전용 서버**용입니다. 생성한 지형은 VoxyServer가
클라이언트에 보내고, Voxy가 멀리 있는 풍경으로 렌더링합니다.
플레이어가 실제로 방문한 곳은 평소처럼 일반 청크로 생성·저장됩니다.

## 설치

서버를 종료하고 [릴리스](https://github.com/mihile/voxy-lod-only/releases)의
`VoxyLodOnly-…jar`를 서버의 `mods` 폴더에 넣으세요. 함께 필요한 항목:

- [Voxy 1.20.1 port.8](https://github.com/mihile/voxy/releases/tag/v0.2.20-1.20.1-port.8)
- [VoxyServer 1.2.4+1.20.1-port.1](https://github.com/mihile/VoxyServer/releases/tag/v1.2.4-1.20.1-port.1)
- Fabric API **0.92.6+1.20.1**, Fabric Loader **0.17.2 이상**, Java **17 이상**

클라이언트에는 **Voxy + VoxyServer + Sodium**이 필요합니다.
**Voxy LOD Only는 서버에만 설치**합니다. 싱글플레이는 지원하지 않습니다.

## 사용

설치하면 오버월드에서 플레이어 주변 **반경 32청크**를 자동으로 처리합니다.
원하는 영역을 직접 지정하거나, 재시작 없이 반경을 바꿀 수도 있습니다. 명령은 OP 권한이 필요합니다.

| 명령 | 동작 |
| --- | --- |
| `/voxylod radius 64` | 플레이어 주변 자동 생성 반경을 64청크로 변경·저장 |
| `/voxylod 0 0 circle 2048 start` | 현재 차원에서 X=0, Z=0을 중심으로 반경 2,048블록 생성 |
| `/voxylod pause` / `/voxylod resume` | 수동 작업 일시 정지 / 이어서 실행 |
| `/voxylod stop` | 작업 중단 및 자동 생성 끄기 |
| `/voxylod automatic on` | 플레이어 주변 자동 생성 다시 켜기 |
| `/voxylod status` | 진행 상황 확인 |
| `/voxylod reload` | 수정한 설정 파일 적용 |

영역 모양은 `circle` 또는 `square`입니다. 자동 반경은 **청크 단위, 최대 128**,
직접 지정하는 반경은 **블록 단위, 최대 8,192**입니다. 모두 지름이 아닌 반경입니다.

설정 파일은 `config/voxy-lod-only.json`입니다. 플레이어 접속 여부에 따라
작업자 수·처리량·일시 정지를 따로 설정할 수 있습니다.

이미 저장된 일반 청크는 건너뜁니다. 기존 월드를 LOD로 변환하려면
VoxyServer의 `/voxyserver import existing all`을 사용하세요.
보이는 범위는 생성된 LOD와 VoxyServer의 전송 범위, 클라이언트 렌더 거리에 따라 달라집니다.

## 더 알아보기

- [설정과 전체 사용법](docs/USAGE.md)
- [소스 빌드](docs/BUILD.md)
- [검증 결과와 알려진 제한](docs/VALIDATION.md)

MIT License · Minecraft, Fabric, Voxy, VoxyServer는 각자의 별도 라이선스를 따릅니다.
