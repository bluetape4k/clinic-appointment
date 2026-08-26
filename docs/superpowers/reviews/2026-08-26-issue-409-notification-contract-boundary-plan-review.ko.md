# Issue #409 알림 contract 분리 구현 계획 3-R 검토

## 검토 범위와 기준

- 대상 Issue: #409 아키텍처(notification): event contract와 persistence contract를 분리한다
- 기준 develop: 8d68b1e3bc8c944bc1ba1f9e6e8233417d23cff8
- 승인 명세: bda78b01639017cf3b732a0a289cc103c475dccc
- 명세 2-R: 85fea437
- 검토 대상 계획: 7303ed4f
- 검토 기준: bluetape-workflow Type A, bluetape-kotlin-patterns, writing-plans,
  full-feature Step 3-R plan review와 SPW-01..05/KO-01..07

이번 검토는 구현 전 계획 gate다. production source, Gradle wiring, GitHub Issue/PR,
schema와 migration에는 변경을 가하지 않았다.

## 계획 요구사항 추적

| 검토 항목 | 계획 위치 | 결과 |
|---|---|---|
| event 순수 write port와 opaque receipt | Task 1·2 | 통과 |
| table/repository/claim lifecycle의 notification persistence 소유 | Task 3·4 | 통과 |
| API public constructor의 port 전환과 ABI migration | Task 5·7 | 통과 |
| waitlist payload와 persistence row 분리 | Task 2·3·6·7 | 통과 |
| transaction·lease·retry·retention semantics | Task 3·4·6·9 | 통과 |
| schema·index·migration 불변성 | Task 0·8·9 | 통과 |
| bluetape4k assertions/Base58/singleton 재활용 | Task 1·6·9 | 통과 |
| consumer fixture와 jar/source forbidden guard | Task 1·7 | 통과 |
| README·ADR·KDoc·lesson·Issue·PR Korean 산출물 | Task 2·8·9·10 | 통과 |
| 7-Tier와 최종 DoD | Task 9·10 | 통과 |

## 3-R 독립 관점 검토

| 관점 | 계획에서 확인한 경로 | P0 | P1 | 판정 |
|---|---|---:|---:|---|
| Performance | Task 3 query/index 보존, Task 6 concurrency/benchmark, Task 9 DB hot path와 round trip 표 | 0 | 0 | 통과 |
| Stability | Task 4 Spring condition/worker lifecycle, Task 5 fail-closed fallback, Task 6 cancellation/cleanup | 0 | 0 | 통과 |
| Security/Data boundary | Task 1 opaque receipt, Task 7 full forbidden inventory, Task 5 concrete repository 비노출 | 0 | 0 | 통과 |
| Operator/Ops | Task 4 readiness/retention/polling, Task 8 migration scanner·checksum, Task 9 운영 관점 | 0 | 0 | 통과 |
| Developer/API | Task 5 constructor/port, Task 7 live API fixture/source guard, Task 8 ADR/README | 0 | 0 | 통과 |
| User/caller | Task 5 writer/recovery semantics, Task 6 idempotency/legacy suppression 회귀, Task 9 caller 관점 | 0 | 0 | 통과 |

### 순서와 의존성

1. Task 0이 baseline과 migration checksum을 먼저 고정한다.
2. Task 1 RED는 새 port/receipt의 부재만 확인한다.
3. Task 2는 event port와 waitlist 순수 contract skeleton을 추가하고 임시 persistence
   선언을 유지해 compile 가능한 중간 commit을 만든다.
4. Task 3이 table/repository/DTO/enum/helper의 물리 이동과 event 최종 제거를 같은
   작업에서 수행한다.
5. Task 4·5·6이 worker, API, 테스트 호출자를 순차적으로 갱신한다.
6. Task 7·8이 live Gradle/API boundary와 migration/docs를 검증한다.
7. Task 9가 full build와 4-P/7-Tier를 수행하고, Task 10이 delivery artifact를 만든다.

계획의 화살표 순서는 각 앞 단계의 compile/targeted test gate를 통과한 뒤에만 다음
단계를 시작하도록 명시되어 있다. Task 2의 임시 선언은 최종 공개 surface가 아니라
Task 3에서 제거되는 원자적 migration 범위다.

## 필수 세부 gate 점검

| 필수 점검 | 계획의 증적 | 결과 |
|---|---|---|
| 모든 명세/DoD 항목이 concrete task에 매핑됨 | 계획 말미 self-review 추적표 | 통과 |
| 각 task가 파일·책임·명령·기대 결과를 가짐 | Task 0–10 책임 지도와 단계별 명령 | 통과 |
| 성공·실패·edge·concurrency·coroutine·lifecycle 경로 | Task 3–6·9 | 통과 |
| Spring auto-configuration conditional/registration | Task 4·5 context test | 통과 |
| Exposed transaction과 receiver shadowing 위험 | Task 3 negative test, Task 6 transaction gate | 통과 |
| coroutine cancellation/dispatcher | Task 6·9 cleanup/IO 검증 | 통과 |
| 성능·안정성: allocation, blocking, cleanup, polling/backpressure | Task 4·6·9 4-P 표 | 통과 |
| Testcontainers 금지와 singleton launcher 재활용 | Task 6 명시 | 통과 |
| migration scanner 재귀와 rollback | Task 8 scanner/checksum/ADR | 통과 |
| README 변형과 Korean artifact | Task 8·9·10 | 통과 |
| 새 module/dependency/schema migration 방지 | 승인 기준과 Task 8 gate | 통과 |

## 발견사항과 조치

| 등급 | 발견 | 조치 | 구현 blocker |
|---|---|---|---|
| P2 | notification module worker 일부 생성자가 persistence 타입을 transitional public surface로 계속 노출 | Task 10에서 duplicate-check 후 좁은 후속 Issue를 생성하거나 기존 Issue를 링크하고, #409 DoD에 남김 | 아니오 |
| P2 | event fixture의 expected API scope는 live resolution 결과를 실행 시 pin해야 함 | Task 7 Step 1에서 실제 report 좌표를 기록하고 fixture assertion으로 고정 | 아니오 |
| P2 | migration scanner가 package 재귀를 지원하지 않을 가능성 | Task 8 Step 1에서 generateMigrations 로그로 판정하고 필요할 때만 최소 package 설정 변경 | 아니오 |

P0=0, P1=0이다. P2 항목은 계획 안에 owner·검증·delivery 기록이 있어 구현을
차단하지 않는다. 새로운 P0/P1이 구현 중 발생하면 Task 9 merge-ready gate를 통과할
때까지 진행하지 않는다.

## 재사용·삭제 우선 판단

- 기존 NotificationOutboxCodec, hasher, draft/value object, singleton launcher,
  MultithreadingTester, io.bluetape4k.assertions, Base58을 재사용한다.
- 새 abstraction/module/dependency를 추가하지 않고, concrete persistence만
  notification module의 기존 경계로 이동한다.
- event facade alias를 남기지 않아 jar/source boundary를 다시 오염시키지 않는다.
- 새 DB round trip이나 polling path를 만들지 않고 기존 SQL/query/index를 보존한다.

## 3-R 결론

계획은 승인 명세와 명세 2-R의 P2 follow-up을 모두 concrete task와 검증 명령으로
분해했다. 순서 보정 후 중간 compile gate도 설명 가능하며, P0/P1 blocker가 없다.
Step 3-P 위험 예측과 구현 승인 게이트로 진행할 수 있다.

**판정: PASS (P0=0, P1=0, P2=3 / 구현 전 해결 불필요)**
