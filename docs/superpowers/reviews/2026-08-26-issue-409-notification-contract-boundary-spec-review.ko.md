# Issue #409 알림 contract 분리 명세 2-R 검토

> 검토 상태: PASS (P0=0, P1=0)
>
> 검토일: 2026-08-26
>
> 검토 명세: `docs/superpowers/specs/2026-08-26-issue-409-notification-contract-boundary-design.ko.md`
>
> 검토 대상 HEAD: `bda78b01639017cf3b732a0a289cc103c475dccc`
>
> 기준 branch: `develop` (`8d68b1e3bc8c944bc1ba1f9e6e8233417d23cff8`)
>
> 대상 이슈: [#409](https://github.com/bluetape4k/clinic-appointment/issues/409)

## 1. 검토 범위와 근거

이 문서는 `$bluetape-full-feature` Step 2-R의 6개 독립 관점으로 승인된 설계를
검토한 결과다. 검토 범위는 event contract와 notification persistence contract의
경계, API 조립과 ABI migration, waitlist 분리, Gradle API/source fixture, Flyway와
transaction 불변 조건, bluetape4k 재사용 및 7-Tier 수용 기준이다.

검토 근거는 다음과 같다.

| 근거 | 확인 내용 |
|---|---|
| Issue #409 live read-back | 제목·본문·assignee `debop`·`OPEN` 상태와 #393 후속 범위를 확인했다. |
| 기준 develop | 현재 기준 SHA가 `8d68b1e3bc8c944bc1ba1f9e6e8233417d23cff8`임을 확인했다. |
| 명세 HEAD | 승인된 선택 1과 명세 수정 후 HEAD가 `bda78b01639017cf3b732a0a289cc103c475dccc`임을 확인했다. |
| 현재 source/build | `appointment-event`, `appointment-notification`, `appointment-api`의 notification source, `ServiceConfig`, auto-configuration, consumer fixture, migration bootstrap 경계를 대조했다. |
| baseline 검증 | 구현 전 `./gradlew :appointment-event:test :appointment-notification:test --no-daemon --console=plain`이 성공했다. |
| 문서 게이트 | SPW-01..05 및 KO-01..07을 명세에 기록했고 Korean terminology audit 결과 `findings=0`을 확인했다. |

이번 검토는 production source를 변경하지 않는다. 명세의 주장과 구현 시 검증 가능한
증적이 일치하는지 확인하고, 구현 계획 단계로 넘길 수 있는지를 판정한다.

## 2. 6개 독립 관점 검토

각 관점은 다른 관점의 결론을 전제로 하지 않고 명세 자체와 위 근거를 기준으로
검토했다. 모든 관점에서 P0/P1 결함은 발견하지 않았다.

| 관점 | 검토 초점 | 증거와 판단 | 결과 |
|---|---|---|---|
| Performance | enqueue/claim hot path, DB round trip, lock/lease, allocation, benchmark 영향 | 명세 7절이 SQL/query plan, round trip, lock 범위, lease duration, retry delay 불변을 명시한다. repository 이동만 수행하고 성능 변경을 목표로 하지 않으며 기존 benchmark/worker test를 구현 검증으로 남겼다. | P0=0, P1=0 |
| Stability | transaction 경계, retry/recovery, disabled context, race/deadlock, rollback | 명세 3·6·7절이 caller-owned transaction, claim 후 provider I/O, fail-closed context, lease fencing, retry/retention semantics를 보존한다. negative transaction test와 auto-configuration/API wiring test가 수용 기준에 있다. | P0=0, P1=0 |
| Security | event/persistence trust boundary, secret·lease token, deserialization, safe default | opaque `NotificationOutboxWriteReceipt`로 ResultRow·lease·attempt를 숨기고, payload는 기존 codec/registry를 재사용한다. 보안 lease token은 의미가 달라 UUID를 기계적으로 바꾸지 않으며, disabled writer는 fail-closed다. | P0=0, P1=0 |
| Operator/Ops | schema/migration, readiness, observability, rollout/rollback, ownership | table·column·index와 V14/V19/V21/V22 SQL을 고정하고, SchemaInitConfig/readiness import만 이동한다. migration scanner 재귀 여부를 구현 단계의 명시적 gate로 두었고 rollback은 코드 commit 되돌리기로 한정했다. | P0=0, P1=0 |
| Developer/API | public API shape, ABI/source migration, module fit, Kotlin/test conventions | event에는 최소 `NotificationOutboxWriter`만 남기고 concrete JDBC repository는 notification persistence가 소유한다. API 생성자와 event/notification consumer fixture의 새 타입, 의도적 ABI 단절, import migration, bluetape4k assertion 재사용을 구체적으로 적었다. | P0=0, P1=0 |
| User/caller | 호출자 ergonomics, misuse resistance, examples, unsupported behavior, migration | caller가 event draft와 opaque receipt만 사용하도록 하고 persistence projection은 notification API로 이동한다. README/KDoc/migration note와 fixture를 수용 기준에 넣었으며, worker 전체 API 은닉은 명시적 후속 범위로 분리했다. | P0=0, P1=0 |

## 3. 통합 finding 표

| 우선순위 | 관점 | 증거 | 필요한 조치 | 재검토 |
|---|---|---|---|---|
| P0 | 해당 없음 | 경계·보안·운영을 무력화하는 차단 결함이 없다. | 없음 | 해당 없음 |
| P1 | 해당 없음 | 구현 불가능하거나 기존 runtime/schema 계약을 깨뜨리는 고위험 결함이 없다. | 없음 | 해당 없음 |
| P2 | Developer/API, User/caller | 명세 3.2·9절이 `appointment-notification`의 일부 persistence public constructor/API 표면과 worker 전체 API 은닉을 이번 최소 범위 밖으로 남긴다. 현재 fixture와 worker가 이를 사용한다는 source 근거가 있다. | 이번 이슈에서는 경계를 event artifact와 API public writer까지로 고정한다. 구현 완료 시 해당 후속 범위를 duplicate-check한 GitHub Issue로 등록하거나 기존 Issue에 연결하고, README에 후속 링크를 남긴다. | 구현 전 issue read-back 및 최종 7-Tier |
| P3 | 해당 없음 | 문서 표현·용어·placeholder·unsupported claim을 명세 작성 단계에서 정리했다. | 없음 | 해당 없음 |

P2 항목은 현재 명세의 누락이 아니라 의도적으로 제한한 범위다. 이를 구현 범위에
몰래 포함하면 새 module·API migration·worker compatibility 판단이 섞여 #409의
최소 경계가 넓어진다. 따라서 구현 완료 전에 후속 Issue 등록 여부를 확인하는 것을
필수 closeout 증적으로 남긴다.

## 4. 통합 검토

### 4.1 중복·모순 제거

- event contract는 순수 envelope/codec/hasher/value object와 write port만 소유하고,
  table·row·claim·retry·retention은 notification persistence가 소유한다는 문장이
  3.1과 3.2에서 일관된다.
- `NotificationOutboxWriteReceipt`를 opaque `id`로 정의하면서 concrete repository의
  subtype 반환을 허용한 부분은 source/ABI migration 표와 모순되지 않는다. event
  caller는 subtype을 요구하지 않는다는 조건이 명시되어 있다.
- `appointment-notification` artifact가 일부 persistence API를 노출할 수 있다는
  예외를 명세 3.2에 명시해 “notification artifact 전체가 persistence를 숨긴다”는
  과장된 해석을 제거했다.
- security lease token과 durable recovery checkpoint UUID는 기존 의미를 유지하고,
  새 fixture suffix만 `Base58.randomString(8)`을 사용한다는 조건을 6·7절에 맞췄다.
- migration scanner의 재귀 동작은 확인 전 사실로 단정하지 않고 구현 단계 확인 gate로
  남겼다. SQL source와 checksum은 불변이다.

### 4.2 증적·문서 완결성

- event jar entry 금지 목록, event source import guard, 두 consumer fixture, module
  test, migration test, diff check가 각각 compile surface·artifact·동작·schema를
  분리해 증명한다.
- ADR-15, README, KDoc, Korean migration note를 같은 source path/API matrix로
  갱신하도록 수용 기준에 넣었다. 구현 계획에서 각 파일과 검증 명령을 1:1로
  연결해야 한다.
- 새 external dependency·module·schema migration을 금지하고 기존
  `bluetape4k-assertions`, singleton launcher, Exposed helper, `Base58`, leader/
  resilience4j/Kafka4/Redis 8.8 표면 재사용을 우선하도록 명시했다.
- 명세에 `TODO`, `TBD`, `FIXME`, `미정`, 구현 전 placeholder를 남기지 않았고, 문서
  terminology audit 결과가 `findings=0`이다.

### 4.3 구현 전 결정 상태

사용자 선택 1이 이미 반영되어 새 contract module을 추가하거나 concrete repository를
event에 facade로 남기는 선택은 닫혔다. 구현 계획에서 추가로 사용자에게 물어야 할
미결정 사항은 없다. 다만 다음 두 항목은 구현 중 증적으로 판정해야 한다.

1. Exposed migration scanner가 `notification.persistence` 하위 table을 재귀적으로
   포함하는지 확인한다. 미포함이면 tables package 설정만 수정한다.
2. 기존 notification persistence public 표면을 완전히 숨기는 후속 Issue를 중복
   확인하고 등록/연결한다.

## 5. 검토 게이트와 판정

| 게이트 | 결과 | 근거 |
|---|---|---|
| 관점 독립성 | PASS | Performance, Stability, Security, Operator/Ops, Developer/API, User/caller를 별도 근거로 검토했다. |
| P0/P1 차단 | PASS | P0=0, P1=0; 구현을 막는 미해결 finding이 없다. |
| P2/P3 처리 | PASS | P2는 범위 밖 후속 Issue closeout 조건으로 defer했고, P3는 문서 게이트에서 제거했다. |
| 경계·호환성 | PASS | event → persistence 단방향, API port 주입, 의도적 ABI migration과 rollback이 명시됐다. |
| 운영·schema | PASS | SQL/checksum/table/index/transaction/lease 불변과 scanner 확인 gate가 명시됐다. |
| ecosystem 재사용 | PASS | 기존 bluetape4k assertions, Base58, singleton launcher, Exposed와 resilience/leader/Kafka4/Redis 표면 재사용을 수용 기준에 넣었다. |
| 문서 품질 | PASS | SPW-01..05, KO-01..07 및 terminology audit `findings=0`. |

### 최종 판정

`PASS — 구현 계획 단계로 진행 가능`.

명세는 승인된 설계 1을 현재 source와 build evidence에 연결하고, 7-Tier 관점에서
P0/P1 차단 결함 없이 구현 가능한 수준이다. 다음 단계는 `$writing-plans`를 사용해
파일별 TDD 순서, source/ABI guard, migration scanner 확인, 테스트와 rollback 증적을
작성하는 것이다. 구현은 계획 승인 후에 시작한다.
