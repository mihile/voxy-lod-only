# 검증 중 발견한 별도 Voxy 본체 예외 처리 문제

LOD Only 최적화와 구분해서 기록합니다. 운영 서버에서 재현했다는 뜻은 아닙니다.

GraalVM 25.4, Voxy port.8 + VoxyServer port.1 + LOD Only 0.3의 별도 테스트에서,
종료한 검증 월드를 그래픽 테스트로 다시 열 때 RocksDB가 `storage/LOCK` 파일을
열지 못하는 오류가 한 번 발생했습니다. 로그의 호출자는 VoxyServer DirtyTracker입니다.
이 첫 파일 잠금의 원인은 확정하지 못했습니다. 당시 해당 저장소를 사용하는 Java는
테스트 서버 하나였고, 닫힌 테스트 월드를 새 경로로 복사한 재시험은 정상 통과했습니다.
운영 저장소를 삭제/이동하거나 JAR을 교체하지 않았습니다.

별도로 확실하게 보이는 코드 문제는 VoxyInstance.getOrCreate입니다. writeLock을 잡은 뒤
createWorld/createStorage에서 예외가 나면 unlockWrite까지 도달하지 않습니다.
테스트의 첫 RocksDB 오류 뒤 서버 스레드는 잠금 대기로 멈췄고 180초 watchdog이 종료했습니다.
동일한 libs/voxy.jar의 javap 바이트코드에서도 getOrCreate의 createWorld 호출 뒤에만
unlockWrite가 있고 예외 해제 경로가 없는 것을 확인했습니다.
이 예외 처리 범위는 Voxy 본체에 있습니다. LOD Only가 VoxyServer의 기존 변환 APIs를
작업자에서 사용할 수 있다는 사실과는 별개의 문제입니다.

증거:

- `build/isolated-20261013-055352/crash-reports/crash-2026-10-13_05.58.47-server.txt`
- 최초 그래픽 로그는 이후 재시험으로 덮어쓰기 됐지만 crash report에 오류/스레드 덤프가 남습니다.
- 로컬 본체 소스: `C:/Users/maste/Desktop/tool/Vibe/voxy 1.20.1/src/main/java/me/cortex/voxy/commonImpl/VoxyInstance.java`

다른 작업에 그대로 전달할 프롬프트:

> Voxy 1.20.1 port.8에서 VoxyServer port.1과 함께 사용할 때 저장소 열기 실패 뒤 서버가
> 멈추는 문제를 수정해줘. GraalVM 25.4 테스트에서 RocksDB LOCK 파일 열기 실패가 났고,
> VoxyInstance.getOrCreate의 writeLock 이후 createWorld가 던진 예외 때문에 unlockWrite가
> 실행되지 않아 다음 조회가 잠금 대기로 멈췄다. 저장소 잠금 최초 원인은 별도로 확인하되,
> getOrCreate의 잠금 해제를 try/finally로 보장하고, WorldEngine 생성 실패 시 이미 만든
> SectionStorage/네이티브 자원을 닫는 범위도 확인해줘. 예외를 주입한 테스트로 이후 요청이
> 멈추지 않고 안전하게 실패 또는 재시도하는지 검증해줘. 실행 중 캐시나 LOCK 파일을
> 지우거나 다른 프로세스의 잠금을 강제로 해제하지 말고, 수정 JAR은 별도로 빌드해줘.

이번 작업에서는 Voxy 본체와 VoxyServer JAR을 변경하지 않았습니다.

## 후속 수정: Voxy port.9 (2026-10-13)

위 문단은 LOD Only 0.3 최적화 당시 기록입니다. 후속 작업에서 Voxy 본체의
잠금 finally 해제, 실패한 엔진 생성의 저장소 정리, RocksDB 초기화/flush 실패의
네이티브 자원 정리를 수정했습니다. VoxyServer port.1 바이너리는 변경하지 않았습니다.

별도 port.9 JAR SHA-256: `12cb5ce5f0ef90d6e8ed095bcbdd7143f7a81286a79f6bfd78d49beffbd7228b`.
GraalVM 25.4 JUnit 9개 PASS. 실제 remapped Fabric 서버 5회 시작마다 실패 처리 7개 PASS,
LOD 저장/변환/취소/종료/재시작/실제 청크 경쟁 처리 PASS. 그래픽 클라이언트도 PASS,
종료 후 inFlight=0, conversionTasks=0, 서버/클라이언트 종료 코드 0.

port.9는 별도 로컬 검증 빌드이며 이 저장소와 릴리스에 포함하지 않습니다.
최초 파일 시스템 잠금의 원인은 여전히 미확정이며, 이번 수정은 저장소 열기 실패가
서버 전체의 영구 잠금으로 번지지 않도록 합니다. 운영 저장소/JAR 적용은 하지 않았습니다.
