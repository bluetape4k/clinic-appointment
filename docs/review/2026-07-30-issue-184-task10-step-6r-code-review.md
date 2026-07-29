# Issue #184 Task 10 Step 6-R 코드 리뷰

## 판정

방문 예약 commitment 구현은 merge gate 기준 `P0=0`, `P1=0`이다. 정책 snapshot
고정, HELD 자원 선점, 만료·취소 시 자원 해제, 현재 Plan revision 사용, 안정적인
proposal hash, 세 dialect migration과 성능 예산을 최종 source와 자동화 검증으로
확인했다.

production `WRITE` 활성화는 merge와 별도 운영 gate이다. 외부 event transport와
metric adapter, retention 단일 실행자, 환불 fact에서 예약 취소 command로 이어지는
자동 handoff가 배포 증거로 확인될 때까지 기본 `OFF`와 빈 allowlist를 유지한다.

## 검토 범위

- `appointment-core`: commitment/proposal/allocation/policy snapshot repository와
  proposal hash
- `appointment-event`: 실행 BOM, 완료·환불 fact, quarantine와 보존 조회
- `appointment-api`: 고객·관리자 API, Gateway actor, consent, 상태 전이,
  idempotency, OpenAPI, rollout/retention
- H2, PostgreSQL, MySQL V10~V12 migration
- README 양 언어, API 문서, 운영 runbook, 성능 증거

## 7-Tier 결과

| Tier | 검토 내용 | 결과 |
|---|---|---|
| 1. 업무 계약 | 한 예약-다중 진료, `PROPOSED/HELD/CONFIRMED`, 변경 중 기존 확정 보호 | 통과 |
| 2. 아키텍처·MSA | 상품·구매·환불·시술 판정은 외부 소유, 예약은 불변 Plan/BOM과 검증된 fact만 소비 | 통과 |
| 3. 데이터·트랜잭션 | caller-owned Exposed transaction, CAS, canonical resource lock, outbox·audit·idempotency 원자성 | 통과 |
| 4. API·보안 | Gateway actor만 신뢰, body identity 위조 차단, `ETag`/멱등성/OpenAPI 계약 | 통과 |
| 5. 테스트·호환성 | core 452, event 116, API 409 통과·2 pending, 세 dialect와 동시성 검증 | 통과 |
| 6. 성능·운영 | 100 caller 중복 점유 0, p95 709 ms, bounded index, rollout/retention runbook | merge 통과, production `WRITE` 보류 |
| 7. 사용자·문서 | 정책 snapshot과 상태 전이 응답, README locale parity, 한국어 KDoc·API 문서 | 통과 |

## 리뷰에서 발견해 수정한 항목

### P1: 기존 proposal 직접 확정의 정책 drift

관리자 직접 확정이 현재 활성 정책을 다시 읽어 proposal 생성 당시 정책과 다르면
정상 proposal을 거절할 수 있었다. proposal에 저장된 policy snapshot ID로 payload를
복원하고 동의 유형·최대 유효시간·약관 hash·직접 확정 허용 여부를 당시 정책으로
검증하도록 수정했다. proposal 생성 후 정책을 변경해도 원래 snapshot으로 직접
확정되는 회귀 테스트를 추가했다.

### P1: migration test와 실제 보존 index 순서 불일치

quarantine 보존 query는 `resolved_at, id` 정렬을 사용하지만 test support가 과거
index 열 순서를 기대했다. H2/PostgreSQL/MySQL V11과 Exposed 선언, migration
assertion을 실제 query 순서인
`tenant_id, clinic_id, legal_hold, resolved_at, id, status`로 맞췄다.

### P2/P3: proposal 의미 hash와 현재 Plan revision

DB 생성 appointment ID가 proposal hash에 포함되어 외부 동의 검증 hash와 영속
hash가 달라질 수 있는 경로를 제거했다. 변경 proposal은 과거 proposal item의
revision이 아니라 현재 활성 Plan revision의 미완료 미래 항목으로 계산하도록
고정했다.

### P2/P3: HELD·만료·취소와 caller 계약

선점형 고객 요청은 생성 transaction에서 자원을 점유하고, 승인 시 기존 HELD
allocation을 재사용한다. proposal 만료와 관리자 취소는 allocation, commitment,
legacy projection, audit, outbox, idempotency 결과를 한 transaction에서 갱신한다.
OpenAPI 회귀 테스트는 query `ETag`, response schema, 필수 request field와 중첩
policy snapshot을 검증한다.

### P1: 생성 proposal의 capacity bucket 상한 유실

가용성 계산기가 `capacityUnits=1`, `maximumCapacity=3`인 방문 bucket을 반환해도
초기 구현은 proposal allocation에 소비량만 남겨 확정 단계에서 상한을 1로 축소했다.
`ResourceAllocationDraft`와 canonical proposal hash에 상한을 포함하고,
`ProposalCandidateSlot.visitCapacityBuckets`가 방문 전체 점유 bucket을 명시하도록
수정했다. 생성 경로로 상한 3까지 서로 다른 예약을 확정하고 네 번째를
`RESOURCE_CONFLICT`로 거부하는 회귀 테스트가 실제 영속 상한까지 확인한다.

### P2: 약관 hash 기대값의 자기참조 검증

외부 동의 검증기가 반환한 `termsHash`를 다시 command의 기대값으로 사용하던
자기참조 비교를 제거했다. 구매 당시 Plan에 보관한 불변 상품
`catalogPayloadHash`를 서버 소유 기대값으로 사용하고 외부 검증 결과를 constant-time으로
대조한다. 현재 카탈로그 버전이나 값이 존재하지만 다른 약관 hash인 증빙도 command
실행 전에 `CONSENT_REQUIRED`로 거부한다.

## 검증 증거

- `:appointment-core:build :appointment-event:build :appointment-api:build`
  성공
- core 452, event 116, API 409 테스트에서 실패·오류 0, API pending 2
- 동시성·성능·retention·dialect 대상 19개 테스트 통과
- PostgreSQL 100 caller: 성공 1, 안정 충돌 99, active allocation 1,
  미복구 deadlock 0, p95 709 ms
- PostgreSQL allocation 10만 건과 retention 각 2만 건 fixture에서 핵심 7개
  query 모두 의도한 index 사용, `Seq Scan on` 0
- Gatling 264/264 성공, 전체 p95 38 ms, p99 68 ms, max 325 ms
- Kover XML report 생성 확인
- `git diff --check` 통과

상세 수치는
`docs/review/2026-07-29-issue-184-performance-evidence.md`를 권위 문서로 한다.

## 잔여 운영 gate

- 실제 broker consumer/publisher와 CRM adapter의 metric·alert 배포 증거
- retention scheduler 또는 CronJob의 cluster-wide 단일 owner 증거
- 환불 fact → 예약 취소 command 자동 handoff의 replay, stale version, DLQ
  통합 검증
- staging `OFF/SHADOW/WRITE`, allowlist, backup/restore, redrive reconciliation
  drill

이 항목은 기본 `OFF` 상태의 merge를 차단하지 않지만 production `WRITE` 활성화를
차단한다.
