# 소스 빌드

JDK 17 이상과 프로젝트의 Gradle Wrapper를 사용합니다. 결과물은 Java 17 대상입니다.
GraalVM은 필수 빌드 조건이 아닙니다.

## 의존 모드 준비

다음 두 릴리스의 **설치용 JAR**을 받아 아래 이름으로 저장하세요.
컴파일용으로만 사용하며 배포 JAR에는 포함하지 않습니다.

| 릴리스 | 저장 위치 | SHA-256 |
| --- | --- | --- |
| [Voxy port.8](https://github.com/mihile/voxy/releases/tag/v0.2.20-1.20.1-port.8) | `libs/voxy.jar` | `a23d4a09525e578c90a41741cb2a68c75b63162eeb905947f06447b08f4db57f` |
| [VoxyServer port.1](https://github.com/mihile/VoxyServer/releases/tag/v1.2.4-1.20.1-port.1) | `libs/voxyserver.jar` | `1bd0a5ceb5fd5ec8ad944b57966662c4f6c0cd74c7121da8d6211584240b3dc5` |

`libs` 폴더는 직접 만들면 됩니다. Minecraft, Fabric API, Fabric Loader 등 나머지
빌드 의존성은 Gradle이 내려받습니다. `JAVA_HOME`은 사용할 JDK를 가리켜야 합니다.

## 실행

Windows PowerShell:

```powershell
.\gradlew.bat build
```

Linux/macOS:

```sh
sh ./gradlew build
```

결과물은 `build/libs/`에 생성됩니다. `VoxyLodOnly-…jar`가 서버 설치용이며,
`-sources.jar`는 소스 열람용입니다. `build`는 JUnit 검사도 실행합니다.

런타임 검증 도구는 [verification/README.md](../verification/README.md)에 있습니다.
도구의 로컬 Fabric 실행 환경과 모드팩 경로는 테스트할 환경에 맞게 준비해야 합니다.
검증 모드는 제품 JAR과 별도로 빌드되며 서버 배포물에 포함하지 않습니다.
