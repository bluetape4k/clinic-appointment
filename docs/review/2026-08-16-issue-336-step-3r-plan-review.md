# Issue #336 Step 3-R 구현 계획 검토

## 1. 검토 범위와 판정 기준

- 대상 계획: `docs/superpowers/plans/2026-08-16-issue-336-api-elements-boundary-plan.md`
- 승인 설계: `docs/superpowers/specs/2026-08-16-issue-336-api-elements-boundary-design.md`
- 기준 ref: `d1718331f1d418baf455d8046ad6cfc2e1567460`
- 설계 commit: `7dfb89e1c2f7acd4e7f0bf21413cf7e8f45e4ff3`
- 검토 단계: Type-A Step 3-R 계획 검토
- 목표: 같은 Gradle build의 project `apiElements` 소비자 계약을 fixture로 검증하고, production Kotlin ABI·runtime 동작을 유지한다.

계획은 외부 Maven publication 소비를 보장하지 않는다. 이번 범위는 세 project dependency가 `Usage.JAVA_API`로 선택하는 `apiElements`와 그 공개 Kotlin signature에 한정한다.

## 2. 독립 관점 검토

| 관점 | 초기 finding | 계획 반영 | 최종 P0/P1 | 남은 P2/P3와 처리 |
|---|---|---|---:|---|
| 성능 | report 생성·up-to-date 입력·cold/warm 반복 조건이 부족했다. | Task 2의 resolution/classpath fingerprint와 고정 output, Task 6의 JDK·cache·daemon·worker 고정, cold/warm 각 3회와 median/min/max/spread/CV를 추가했다. CI refresh와 Nightly no-refresh 수치를 분리한다. | 0/0 | 새 fixture compile 시간은 baseline에 없는 신규 비용이므로 별도 증거로 남긴다(P2 완화). |
| 보안 | report에 arbitrary diagnostic과 경로가 섞일 수 있고 dependency allowlist와 runtime 분류가 약했다. | bounded cause chain, secret-pattern/gitleaks 검사, URL·credential·절대 경로·stack trace 차단, symlink/root boundary 검사, machine-readable `group:name` allowlist, `ApplicationContextRunner` classpath 존재/부재 검증을 추가했다. | 0/0 | 기존 `actions/upload-artifact@v7`의 SHA pinning은 별도 보안 변경으로 분리한다(P3 수용). |
| SRE·운영 | stale `variants.json`, 수동 task ordering, mutation 중단 복구, untracked 누락, report upload 경고가 차단 위험이었다. | report input fingerprint와 source/ref freshness, `assertModuleConsumerFixtureTaskGraph`, producer `jar` 선행, checkpoint/`trap` 원복·hash/status/clean compile, tracked·untracked fail-closed, `if-no-files-found: error`, 필수 `actionlint`를 고정했다. | 0/0 | CI/Nightly failure artifact 실제 run은 구현·CI 단계에서 증거화한다(P2 소유: Task 5/6). |
| 개발자 경험·공개 API | 실제 선언과 계획 anchor가 어긋날 위험이 있었다. | core의 실제 `LongJdbcRepository` 구현체 10개, messaging의 inbox/table·configuration·lifecycle/health 공개 타입, notification의 두 JDBC store와 네 scheduling runner를 fixture·inventory에 1:1로 고정했다. 로컬 skill 이름도 `subagent-driven-development`/`executing-plans`로 교정했다. | 0/0 | 새 public external type-use가 생기면 fixture·manifest·scope를 함께 갱신한다(P2 예방 규칙). |
| 사용자·제품·호출자 | publication 보장 범위와 consumer fixture의 단독성, 공개 surface 누락이 혼동될 수 있었다. | 목표에 same-build project variant만 명시하고, 각 configuration에 대상 project 하나만 허용하며, `KClass`·constructor/callable reference·supertype·annotation type-use를 직접 컴파일하도록 고정했다. | 0/0 | 외부 publication 검증은 저장소 publication 부재로 N/A이며 별도 이슈로 확장하지 않는다. |
| 아키텍처·Gradle variant | configuration resolution에 의존한 producer task ordering과 compile-only runtime 오판 가능성이 있었다. | 각 compile task에 대응 `:appointment-*:jar`를 명시적으로 연결하고, report→assertion→compile→check graph를 기계 검증한다. runtime bean 생성에 필요한 Redis/Lettuce/leader/Resilience4j는 explicit `api` allowlist 또는 standalone smoke 없이는 `compileOnlyApi`로 낮추지 않는다. | 0/0 | 전체 public inventory manifest assertion은 Task 4의 구현 증거로 닫는다(P2 소유: Task 1/4). |

초기 독립 검토 결과는 관점별로 P1이 존재했으나, 위 수정 후 차단 finding은 모두 해소되었다. 최종 계획 판정은 `P0=0`, `P1=0`이다.

## 3. 통합 계획 계약

### 3.1 변경 경계

- 허용 production 변경은 세 모듈의 `build.gradle.kts` dependency scope뿐이다.
- fixture는 `src/consumerFixture/**` 아래에 두고 production source를 import해 compile contract만 검사한다.
- root `build.gradle.kts`에는 세 `Usage.JAVA_API` configuration, 격리 output, report/assertion task, root `check` wiring을 추가한다.
- CI/Nightly에는 기존 compile-only job의 report artifact upload만 추가한다. Docker, Testcontainers, broker, database service는 추가하지 않는다.
- `appointment-*/src/main/**` Kotlin production source와 public ABI는 변경하지 않는다.

### 3.2 실패·복구 계약

1. Task 1에서 fixture 자체 오류를 제거한 뒤 변경 전 scope의 실제 RED compiler error를 모듈별로 보존한다.
2. Task 3에서 한 좌표씩 `api`/`compileOnlyApi`를 판정하고 매번 clean·`--rerun-tasks`로 검증한다.
3. Task 4 mutation은 blob hash, tracked/untracked status, checkpoint와 `trap`을 남긴다.
4. 중단·실패·signal 뒤에는 scope 한 줄, fixture/report output, hash, `git diff --check`, status, clean compile을 원상복구 확인한다.
5. report는 실패에서도 bounded diagnostics와 freshness metadata를 남기고, secret·경로·stack trace를 기록하지 않는다.

### 3.3 증거 산출물

- `variants.json`: 선택 variant, attributes, 허용 좌표, `sourceRef`/`gitSha`, input fingerprint
- `classpath.json`: module별 artifact count/size와 classpath fingerprint
- `diagnostics.json`: 실패 module의 제한된 exception/cause chain과 실행 metadata
- `performance.json`: CI refresh와 Nightly no-refresh를 구분한 fixed-window 3-run values/median/spread/CV
- `docs/verification/2026-08-16-issue-336-api-elements-boundary.md`: source symbol·type-use·fixture line·RED/GREEN/mutation·task graph·CI artifact evidence
- `docs/lessons/2026-08-16-issue-336-api-elements-boundary.md`: 재사용 가능한 public API 경계 교훈

## 4. 설계 factual clarification

승인 설계의 의미와 ABI 보장 범위는 바꾸지 않고, 구현 전에 다음 실제 저장소 사실만 명시적으로 보강했다.

- producer artifact task는 configuration 추론이 아니라 대응 project `jar` task dependency로 고정한다.
- notification scheduling 공개 클래스는 `NotificationOutboxSchedulingRunner`, `NotificationObservationSchedulingRunner`, `NotificationRetentionSchedulingRunner`, `NotificationReminderSchedulingRunner` 네 개로 고정한다.
- `NotificationAutoConfiguration`의 Redis/Lettuce runtime 사용은 compile-only 성공만으로 분류하지 않고 `api` allowlist 또는 runtime smoke evidence를 요구한다.

## 5. 검토 evidence와 문서 품질 gate

| 항목 | 결과 | 근거 |
|---|---|---|
| SPW-01 | PASS | 문제, 독자, same-build `apiElements` 범위, 기준 ref와 승인 설계를 고정했다. |
| SPW-02 | PASS | 실제 파일·task·fixture·RED/GREEN·rollback·CI·성능·완료 게이트를 순서대로 포함했다. |
| SPW-03 | PASS | 프로젝트 문서와 review prose를 한국어로 작성하고 code/API/command token은 보존했다. |
| SPW-04 | PASS | 실제 source symbol, version catalog alias, workflow 경로, `outgoingVariants` evidence와 계획 line을 대조했다. |
| SPW-05 | PASS | 표, 목록, code fence, P0/P1 판정과 승인 전 mutation 금지를 다시 읽어 확인했다. |

기준 단계에서 다음 evidence를 확인했다.

- `./gradlew :appointment-core:test :appointment-messaging:test :appointment-notification:test --no-parallel`가 `BUILD SUCCESSFUL`이었다.
- `./gradlew :appointment-notification:outgoingVariants --variant apiElements --offline --no-daemon`에서 `apiElements`, `Usage=java-api`, JVM 21을 확인했다.
- GNO에서 Issue #336 관련 계획 hit는 없었고, live GitHub issue metadata는 `OPEN`, milestone `1.4.0`, assignee `debop`으로 확인했다.
- 계획 단계에는 source/build/CI mutation이 없으며, 계획·설계·review 문서와 flow transient evidence만 남긴다.

## 6. 최종 판정과 다음 게이트

- P0: `0`
- P1: `0`
- P2: 구현 단계에서 산출물로 닫거나 명시적 N/A로 기록
- P3: 기존 artifact action SHA pinning처럼 범위 밖 보안 변경은 별도 이슈로 분리

계획 단계는 통과한다. 다음 단계는 이 계획 문서와 review 문서를 Lore 형식으로 커밋하는 것이며, 그 커밋 뒤에는 사용자의 별도 승인 전까지 source/build/CI 구현을 시작하지 않는다. PR 생성·CI merge gate·rebase merge는 구현 완료 후 fresh evidence와 별도 승인으로 남긴다.
