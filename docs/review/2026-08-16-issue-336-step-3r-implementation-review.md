# Issue #336 구현 단계 독립 검토

## 검토 범위와 판정

- 검토 대상: `build.gradle.kts`, 세 모듈 dependency scope, 세 consumer fixture, optional Redis/Lettuce 회귀, CI/Nightly artifact 보존, verification·lesson 문서
- 검토 기준: 승인 설계 `7dfb89e1c2f7acd4e7f0bf21413cf7e8f45e4ff3`, 구현 계획 `73dbb87a`, Issue #336
- production Kotlin source diff: 없음
- 최종 심각도: `P0=0`, `P1=0`, `P2=0`, `P3=0`
- 최종 gate: **PASS**

초기 독립 구현 검토에서 확인된 P1 세 건은 다음 구현과 회귀로 해소했다.

1. detached CI checkout에서 `git symbolic-ref`가 실패하지 않도록 `gitSourceRef()`가 `rev-parse --abbrev-ref HEAD`와 `GITHUB_REF_NAME`을 fallback으로 사용한다. detached 실행에서 report가 `sourceRef=HEAD`로 생성됐다.
2. 모듈별 direct `api`·`compileOnlyApi` 집합과 producer direct API root를 exact 비교한다. messaging의 Jackson `implementation`을 임시 `api`로 바꾼 mutation은 `unexpected=[tools.jackson.module:jackson-module-kotlin]`으로 실패했다.
3. resolution fingerprint가 선언 scope, direct API roots, 전체 resolved coordinate, attributes, artifact 이름·크기를 포함하고 assertion에서 현재 configuration을 다시 계산한다. report fingerprint를 임시 값으로 바꾼 mutation은 `resolution fingerprint is stale`로 실패했다.

## 관점별 검토

| 관점 | 근거 | 결과 |
|---|---|---|
| 성능 | fixture compile은 root `check`에 연결되며 collector가 cold/warm series schema를 보존한다. full 3-run baseline/candidate SLO 비교는 외부 CI 비용 범위 밖으로 문서화했다. | `P0=0 P1=0`, 성능 비교는 후속 증거 |
| 보안 | report redaction·repository root/non-symlink 검사, `assertSafeEvidence`, `gitleaks detect --no-git` 통과. CI artifact는 7일 보존이며 새 credential 권한을 추가하지 않는다. | `P0=0 P1=0` |
| SRE/운영 | CI와 Nightly가 실패에서도 고정 report 경로를 artifact로 보존한다. detached ref와 bounded diagnostics로 CI 재현 좌표를 남긴다. 외부 broker/Redis/운영 SLO는 범위 밖이다. | `P0=0 P1=0` |
| 개발자 경험·공개 API | 실제 public symbol/type-use fixture, `apiElements`·`java-api` assertion, exact scope/root allowlist, task graph assertion이 함께 실패한다. 새 public external type-use 갱신 지침을 lesson에 기록했다. | `P0=0 P1=0` |
| 사용자·호출자 | 모듈 하나만 의존하는 Kotlin consumer가 통합 애플리케이션 classpath 없이 컴파일된다. production public ABI와 runtime source는 변경하지 않았다. | `P0=0 P1=0` |
| 아키텍처 | project dependency 하나의 `Usage.JAVA_API` configuration, producer `jar` ordering, report→assertion→fixture compile→check 흐름이 분리되어 있다. runtime-required notification Redis/Lettuce/leader/Resilience4j는 `api`로 유지했다. | `P0=0 P1=0` |

## 실제 검증

- `./gradlew clean compileModuleConsumerFixtures --rerun-tasks --no-daemon --no-configuration-cache --console=plain` — `BUILD SUCCESSFUL in 30s`
- 세 모듈 fresh test — `appointment-core` 702개, `appointment-messaging` 114개, `appointment-notification` 159개 통과
- `./gradlew build -x test -x :frontend:appointment-frontend:build ...` — `BUILD SUCCESSFUL`
- `./gradlew check --dry-run ...` — producer jar, 두 report, 두 assertion, 세 fixture compile, integration, root `check` 순서 확인
- 세 모듈 `outgoingVariants` — `apiElements`, JVM 21, `java-api` 확인
- notification `dependencyInsight` — leader Redis Lettuce와 `io.lettuce:lettuce-core`가 `apiElements` 경로에 존재
- optional classpath 부재 `ApplicationContextRunner` — `SUCCESS: Executed 1 tests`
- `actionlint`, `node --check`, `git diff --check`, `gitleaks` — 모두 통과

## 남은 범위

외부 Maven publication 소비, 실제 broker/database/Redis 운영 환경, CI 원격 실행, 기준 SHA와 후보의 3회 성능 중앙값 비교는 이번 구현 gate의 필수 조건이 아니다. collector helper와 report schema는 준비했으며 별도 성능 증거 작업으로 남긴다.
