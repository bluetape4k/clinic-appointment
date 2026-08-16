# Issue #336 Step 2-R 설계 검토

## 1. 검토 범위

- 대상 설계: `docs/superpowers/specs/2026-08-16-issue-336-api-elements-boundary-design.md`
- 기준 ref: `d1718331f1d418baf455d8046ad6cfc2e1567460`
- 검토 단계: Type A Step 2-R
- 검토 방식: 성능, 안정성, 보안, 운영, 개발자·공개 API, 사용자·호출자 관점의 독립 읽기 전용 검토와 주 세션 통합

설계가 보장하려는 범위는 같은 Gradle build 안에서 `appointment-core`, `appointment-messaging`, `appointment-notification`을 각각 단독 project dependency로 소비할 때 선택되는 `apiElements` compile 계약이다. Maven publication은 현재 저장소에 구성이 없으므로 검증 범위에 포함하지 않는다.

## 2. 1차 검토 결과

| 관점 | P0 | P1 | P2 | 주요 지적 |
|---|---:|---:|---:|---|
| 성능 | 0 | 0 | 2 | fixture 시간 예산과 downstream compile·classpath delta를 수치로 고정해야 한다. |
| 안정성 | 0 | 2 | 2 | 선택 variant assertion, toolchain·output 격리, 전수 symbol matrix, mutation 원복 증거가 필요하다. |
| 보안 | 0 | 0 | 1 | Redis·Lettuce 전이 graph를 최소화하거나 승인 목록으로 고정해야 한다. |
| 운영 | 0 | 1 | 2 | consumable `apiElements`를 직접 resolve하지 말고 실패 시에도 구조화 report를 보존해야 한다. |
| 개발자·공개 API | 0 | 1 | 1 | auto-configuration 전체와 compile-only 공개 타입을 surface·scope 결정에 포함해야 한다. |
| 사용자·호출자 | 0 | 3 | 2 | 공개 surface 누락, 잘못된 `Database` anchor, `compileOnlyApi`, task mapping, publication 보장 범위를 명확히 해야 한다. |

1차 합계는 `P0=0`, `P1=7`, `P2=10`이다. P1을 모두 설계 문서에 반영한 뒤 영향받은 관점을 다시 검토했다.

## 3. 통합 수정

| 지적 | 설계 반영 |
|---|---|
| variant 선택의 거짓 양성 | `Usage.JAVA_API`를 요청하는 세 root resolvable configuration과 `variants.json` 기반 assertion task를 정의했다. |
| report 유실 | resolution 예외를 module별 실패 상태로 기록하는 report task와 `if: always()` CI artifact upload를 정의했다. |
| fixture 재현성과 격리 | module별 source·configuration·compile task·output을 1:1 표로 고정하고 JVM 21 toolchain, clean, warm `UP-TO-DATE` 조건을 명시했다. |
| 공개 surface 누락 | 외부 package를 import하는 public production declaration을 전수 inventory로 만들고 auto-configuration의 모든 public bean method를 callable reference로 검사하도록 확장했다. |
| 잘못된 Exposed anchor | `JdbcAppointmentOutboxStore(Database)` 주장을 제거하고 `AppointmentConsumerRetentionService`, `JdbcAppointmentConsumerInboxStore`, `AppointmentReplayService`의 실제 공개 `Database` 경계로 교체했다. |
| compile-only 공개 타입 | runtime 필수 공개 의존성은 `api`, optional Spring 공개 선언 전용 의존성은 `compileOnlyApi`로 전달하도록 결정했다. |
| Redis·Lettuce 전이 확대 | notification RED가 요구할 때만 명시적 예외로 허용하고 resolved artifact 승인 목록과 `dependencyInsight`를 증거로 남기도록 제한했다. |
| 성능·classpath 영향 | 기준 SHA와 후보 SHA, JDK·wrapper·cache·daemon 조건, 3회 중앙값, 회귀 예산, artifact 수·크기 delta를 고정했다. |
| mutation 오염 | 변경 전 blob hash와 diff scope를 기록하고 clean `--rerun-tasks` 뒤 원복 hash·diff 일치를 요구했다. |
| 보장 범위 과장 | publication을 제외하고 같은 build의 project `apiElements` 소비만 보장한다고 명시했다. |

## 4. 재검토 결과

| 관점 | P0 | P1 | P2 | 판정과 남은 조치 |
|---|---:|---:|---:|---|
| 안정성 | 0 | 0 | 2 | PASS. 실제 CI upload와 고정된 성능 측정 절차는 구현 계획·검증 의무로 유지한다. |
| 운영 | 0 | 0 | 2 | PASS. 실제 upload step과 실패 시 report 보존 wiring은 구현 단계에서 검증한다. |
| 개발자·공개 API | 0 | 0 | 3 | PASS. 공개 Spring supertype, Redis annotation type-use, 정확한 version catalog alias를 설계와 구현 의무에 반영했다. |
| 사용자·호출자 | 0 | 0 | 3 | PASS. inventory type-use 범위, notification의 JDBC·Redis anchor, task ordering, fixture 신규 비용 명칭을 설계에 반영했다. |

최종 차단 합계는 `P0=0`, `P1=0`이다. 재검토의 P2는 모두 설계에 직접 반영하거나 구현 계획·검증에서 산출물을 확인할 항목으로 배정했다. 소비자 관점의 P3 1건은 fixture task가 없는 기준 ref와 신규 fixture 비용을 before/after로 오인할 수 있다는 표현 문제였으며, 신규 검증 비용과 공통 task 회귀 비교를 분리해 교정했다.

## 5. 문서 검증

- 저장소 source symbol과 module dependency 선언을 다시 대조했다.
- `:appointment-notification:outgoingVariants --variant apiElements --offline --no-daemon`에서 `apiElements`, `Usage=java-api`, JVM 21을 확인했다.
- producer의 consumable `apiElements`를 resolved dependency graph로 직접 사용하지 않고 root consumer configuration에서 graph를 수집하도록 교정했다.
- `git diff --check`와 Markdown code fence 짝 검사를 통과했다.

### Writer 게이트

| 항목 | 결과 | 근거 |
|---|---|---|
| SPW-01 | PASS | 한국어 설계·검토 문서, Issue #336의 같은-build `apiElements` 계약, 기준 SHA와 현재 source를 독자·목적·근거로 고정했다. |
| SPW-02 | PASS | 문제, 제약, 대안, 선택, task·fixture 계약, 실패 모드, 호환성, 검증, 인수 기준, DoD를 포함한다. |
| SPW-03 | PASS | 식별자와 명령을 보존하고 한국어 기술 문장과 용어를 일관되게 사용했다. |
| SPW-04 | PASS | 공개 symbol, Gradle scope, variant 명령, 검토 finding과 수정 위치를 다시 대조했다. |
| SPW-05 | PASS | Markdown 전체를 다시 읽고 표, 목록, code fence, 링크, 최종 `P0=0/P1=0`을 확인했다. |

한국어 자연스러움 검사는 `KO-01`부터 `KO-06`까지 모두 PASS다. 근거가 없는 효율·중요성 표현은 없고, 영문 번역투나 과장된 표현을 제거했으며, body·표·링크·명령의 기술 토큰과 의미를 보존했다.

## 6. 판정

Step 2-R은 `P0=0`, `P1=0`으로 통과한다. 설계의 의도와 public ABI 유지 범위는 바뀌지 않았고, P2 항목은 설계에 이미 반영했거나 구현 계획·검증의 명시적 의무로 배정했다. 다음 단계는 이 설계에서 exact file·task·RED/GREEN·rollback 순서를 도출하는 구현 계획이며, 계획 승인 전에는 production 또는 build logic 구현을 시작하지 않는다.
