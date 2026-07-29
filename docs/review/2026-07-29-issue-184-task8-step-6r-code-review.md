# Issue #184 Task 8 Step 6-R 코드 리뷰

## 검토 범위

- 상품서비스가 승인한 상품 version 전환, mapping, 고객 동의 증거
- 임상·환불 소유 서비스가 발행한 완료·부분 이행·자원 장애·환불 fact
- 완료 provenance를 보존하는 동일 Plan의 immutable revision append·activate
- `BLOCKING/NON_BLOCKING` 의존성에 따른 미래 dirty-set과 환불 취소 폐포
- 확정 예약 불변과 고객 일정 변경 거부 운영 예외·CRM handoff
- raw bytes 보호, strict concrete DTO decode, type/schema/trust/signature 검증
- source aggregate version replay·hash conflict·gap proof·bounded retry
- FK 없는 terminal rejection, 유효 scope의 encrypted quarantine·감사·release 통제
- tenant·clinic·source purchase 범위의 Plan lock과 status CAS
- 기준: `bluetape-kotlin-patterns`, Exposed caller-owned transaction,
  한국어 업무 KDoc, privacy-safe outbox

## 최종 판정

| Tier | 관점 | P0 | P1 | P2 | P3 | 판정 |
|---|---|---:|---:|---:|---:|---|
| 1 | 도메인·업무규칙 | 0 | 0 | 0 | 0 | PASS |
| 2 | 아키텍처·API 경계 | 0 | 0 | 0 | 0 | PASS |
| 3 | 데이터·transaction·동시성 | 0 | 0 | 0 | 0 | PASS |
| 4 | 보안·개인정보 | 0 | 0 | 0 | 0 | PASS |
| 5 | 테스트·예외 경로 | 0 | 0 | 0 | 0 | PASS |
| 6 | 성능·운영 안정성 | 0 | 0 | 0 | 0 | PASS |
| 7 | 문서·KDoc·유지보수성 | 0 | 0 | 0 | 0 | PASS |

최종 차단 집계는 `P0=0`, `P1=0`이다. architecture와 security 독립 재검토는
최종 코드에서 `P0=0/P1=0/P2=0/P3=0`으로 통과했다. comprehensive 7-Tier
재검토의 유일한 P3였던 검증 수치와 본 review artifact 누락도 이 문서와 실행
기록 갱신으로 닫았다.

## 업무 경계 판정

### 예약서비스는 외부 업무 결정을 재판정하지 않는다

상품 version 전환은 상품·구매 측에서 승인하고 고객 동의 증거와 완전한 목표 실행
BOM을 제공한다. 완료·부분 이행·환불 여부도 임상·환불 소유 서비스가 확정한다.
예약서비스는 authority, aggregate version, canonical hash, signature와 업무 계약을
검증한 뒤 앞으로 예약할 의무만 새 revision에 투영한다.

새 상품 구매는 기존 Plan의 migration이 아니다. Task 8은 같은 구매의 version 전환과
이미 구매한 의무의 실행 사실만 다룬다. handler는 appointment나 commitment를 직접
변경하지 않으므로 확정 일정은 별도 proposal과 고객 동의 없이는 바뀌지 않는다.

### 완료 이력과 미래 의무는 분리한다

- 완료된 항목은 과거 revision에 그대로 남아 product version과 실행 provenance가
  바뀌지 않는다.
- 유효한 상품 전환은 동일 Plan에 미래 의무만 포함한 새 revision을 append하고
  compare-and-set으로 활성화한다.
- 부분 이행은 실제 완료된 세부 진료를 원 항목에 남기고, producer가 제공한 잔여
  실행 항목을 새 treatment key로 추가한다.
- 환불은 직접 대상과 전이적 `BLOCKING` 후속만 취소한다.
  `NON_BLOCKING` 독립 항목은 계속 예약할 수 있다.
- 실제 완료·장애·환불 시각과 dirty-set은 privacy-safe outbox에 기록해 후속
  interval과 proposal 계산의 입력으로 사용한다.

### 고객의 일정 거부는 기존 확정 예약을 보존한다

`CUSTOMER_DECLINED_RESCHEDULE`만 허용한다. 거부 fact는 기존 확정 예약이나 활성
revision을 바꾸지 않고 운영 예외와 CRM outbox를 만든다. 같은 source version의
replay는 중복 예외를 만들지 않으며, 같은 version의 다른 hash는 격리한다.

## 외부 fact production 경계

```text
raw envelope + raw JSON + observed routing
→ bounded metadata evidence + exact raw JSON AES-GCM 보호
→ fixed event type/schema 선택
→ strict concrete DTO decode
→ producer/key/algorithm/issuer/audience/replay/hash/signature 검증
→ observed routing과 trusted payload scope 대조
→ source version/proof 판정
→ internal handler transaction
```

모든 decode·trust·routing 실패는 FK가 없는
`scheduling_untrusted_event_rejections`에 관측 tenant·clinic, source scope,
reason code와 evidence hash를 terminal record로 남긴다. 존재가 확인된 scope 또는
trusted payload scope가 있으면 암호화 원문을 `scheduling_quarantine_events`에
추가하고 append-only 감사 row도 같은 transaction에 기록한다. 존재하지 않는
tenant·clinic header는 FK 때문에 증거 저장을 실패시키지 않는다.

1 MiB를 한 byte 초과한 raw JSON도 암호화한 뒤 `PAYLOAD_TOO_LARGE`로 격리할 수
있다. 과도한 `eventId`, `eventType`, `signature`, `sourceAuthority`,
`sourceAggregateId`는 전체 길이와 최대 256자 표본 hash만 evidence/AAD/index에
사용하므로 pre-trust 메모리 복제를 제한한다.

외부 fact의 구조·mapping·routing 실패 quarantine은 일반 업무 예외처럼 단일
승인으로 release할 수 없다. source correction reference와 trust 재검증 증거가
모두 있어야 release 승인된다.

## 리뷰 중 발견하고 닫은 결함

### trust 검증 없는 DTO와 source version gap

최초 구현은 handler 단위 trusted envelope만 전제로 했고 raw decode·signature
경계와 authority gap proof가 완결되지 않았다. strict concrete decoder와 공통
trust verifier를 추가하고, 연속 version 또는 유효한 authority proof만 처리하도록
고쳤다. 복구되지 않은 gap은 bounded backoff 뒤 암호화 격리한다.

### 완료 fact의 시각·dirty-set·격리 누락

완료 fact의 `occurredAt`이 envelope와 replay window를 벗어날 수 있었고, 후속
`BLOCKING` 폐포와 실제 시각이 outbox에 충분히 남지 않았다. fact 시각 경계,
실제 canonical byte 크기, dirty-set과 effective time map을 검증·기록하도록
보정했다.

### raw consumer와 암호화 격리의 단절

verify-only ingress와 handler 사이에 raw 원문 보호와 durable rejection 수렴이
없었다. `ExternalFactEventConsumer`를 유일한 public mutation 경계로 추가하고
Task 8 handler mutation 메서드를 module-internal로 제한했다.

### 잘못된 routing과 거대 header

존재하지 않는 tenant·clinic routing은 FK-bound quarantine insert를 실패시킬 수
있었다. 기존 구매 ingress의 FK 없는 terminal rejection 저장소를 재사용하고,
유효 scope에서만 encrypted quarantine을 추가하도록 분리했다.

거대 metadata는 ciphertext와 AES-GCM AAD를 과도하게 만들 수 있었다. metadata,
AAD, index 대체 식별자 모두 길이와 bounded sample hash를 사용하도록 고쳤고,
200,000자 header 회귀 테스트로 상한을 고정했다.

## 검증 증거

- Task 8 handler·strict ingress·production consumer 대상: 29개, 실패 0
- quarantine release 정책 포함 event 표적 검증: 38개, 실패 0
- `PlanDirtySetResolverTest`: 3개, 실패 0
- `:appointment-core:build --no-daemon --rerun-tasks`: 452개 통과,
  skipped 0, 실패 0, 오류 0
- `:appointment-event:build --no-daemon --rerun-tasks`: 107개 통과,
  skipped 0, 실패 0, 오류 0
- `:appointment-api:compileKotlin :appointment-api:compileTestKotlin`: 성공
- `git diff --check`: 위반 0
- 신규 production 금지 패턴 `!!`, `println`, broad `runCatching`,
  `synchronized`, `GlobalScope`, `Thread.sleep`: 추가 0

모듈별 `ktlint`·`detekt` task는 존재하지 않아 정적 분석 통과로 과장하지 않는다.
Kotlin compiler와 전체 module build, 금지 패턴 scan, 독립 7-Tier 검토를 품질
증거로 사용했다. Dokka는 root `README.md`를 module/package 문서로 읽으면서
기존 `#` classifier를 거부해 생성되지 않았다. 신규 KDoc의 잘못된 link는 제거했지만,
repo 공통 Dokka include 설정 정리는 Task 8 업무 로직과 분리해 후속 운영·문서
작업에서 다룬다.

## 후속 Task 경계

Task 9는 실제 Spring/pub-sub adapter가 `ExternalFactEventConsumer`만 호출하는지,
AES-GCM key rotation, proof provider timeout/circuit breaker, feature flag,
metrics·alert·retention cleanup과 운영 runbook을 검증한다.

Task 10은 H2·PostgreSQL·MySQL migration 동등성, 대규모 package fact,
replay/gap contention, invalid routing과 poison event 보존의 장기 성능 회귀를
검증한다.
