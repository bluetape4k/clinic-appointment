# Issue #170 대기열 핵심 기능 최종 사전 PR 코드 리뷰

## 결론

- 판정: `PASS`
- P0: `0`
- P1: `0`
- P2: `0`
- P3: `0`
- 리뷰 기준점: `435e968126c3090ba3c6e8f129ae04d6575136a1`
- 작업 경계: `feat/issue-170-waitlist-core` 격리 worktree
- 이번 작업의 종료점: lesson/체크리스트를 포함한 로컬 커밋과 workflow receipt 복구 완료. PR 생성·push·merge는 범위에 포함하지 않는다.

이번 리뷰는 이전 설계/구현 리뷰에서 지적된 대기열 점유·재시작·만료·스키마 제약 문제를 현재 코드와 테스트에 다시 대조한 결과다. 이전 지적을 그대로 반복하지 않고, 수정된 코드와 fresh 검증 결과를 기준으로 판정했다.

## 이전 지적과 해소 증거

| 이전 지적 | 현재 해소 증거 | 판정 |
|---|---|---|
| OFFERED 상태에서 누락된 capacity hold를 복구하지 못할 수 있음 | `WaitlistOfferClaimService`가 기존 hold를 잠그고, 없으면 offer에 저장된 resource/capacity snapshot으로 `repairMissingOfferedHold`를 수행한다. 복구 경합은 `SlotOccupied`로 변환한다. | 해소 |
| ACCEPTED 재시도가 시작 시각 이후 stale hold를 되살릴 수 있음 | ACCEPTED replay 전에 시작 시각·hold deadline을 재검증하고, 시작 이후에는 만료 결과를 반환한다. | 해소 |
| OFFERED hold 만료 시각이 예약 시작 시각을 넘을 수 있음 | `WaitlistOfferService`가 TTL을 `startsAt`, `endsAt`, `decisionExpiresAt`과 함께 `min`으로 제한하고, recovery가 hold expiry와 startsAt을 함께 검사한다. | 해소 |
| Exposed 모델과 Flyway 스키마의 제약이 어긋날 수 있음 | 네 개 waitlist table에 capacity/시간/상태 제약과 인덱스를 선언하고, `TableSchemaTest` 및 H2/PostgreSQL/MySQL migration 검증으로 교차 확인했다. | 해소 |
| Kotlin/Exposed 패턴 위반 가능성 | 생산 코드 정적 검사에서 `!!`, 운영 `print`, suspend `runCatching`, deprecated Exposed 비교식, raw JDBC를 찾지 못했다. 트랜잭션 경계는 caller-owned로 유지했다. | 해소 |

## Fresh 검증

| 검증 | 결과 |
|---|---|
| `:appointment-core:test` waitlist/state/reliability 대상 테스트 | `PASS` |
| `:appointment-api:test` H2/PostgreSQL/MySQL migration 대상 테스트 | `PASS` |
| `:appointment-core:test :appointment-api:test` 전체 테스트 | `PASS`; core `594/0/0/0`, API `593/0/0/2` (tests/failures/errors/skipped) |
| `:appointment-core:build :appointment-api:compileKotlin :appointment-api:compileTestKotlin` | `PASS` |
| Exposed schema 및 migration matrix | `PASS` |
| contention/restart/recovery 검증 | `PASS`; duplicate terminal transition 없음 |
| Kotlin/Exposed 안전성 정적 검사 | `PASS` (`STATIC_SCAN_FINAL`) |
| `git diff --check` 및 새 파일 trailing whitespace 검사 | `PASS` |

API의 두 skip은 H2에서 실행할 수 없는 PostgreSQL/MySQL explain 또는 SLO 가정 테스트이며, 실패가 아니다. HTTP, notification, outbox, scheduler adapter는 승인된 phase-one 범위 밖이므로 이번 코드 리뷰의 결함으로 세지 않았다.

## 운영·보안 경계

- 대기열 식별자는 opaque reference를 사용하며, 이름·전화번호 같은 개인정보를 waitlist core/outbox payload에 복제하지 않는다.
- Exposed 쿼리는 호출자가 소유한 `transaction {}` 안에서 실행되고, 잠금 순서와 CAS 재시도 경계를 서비스에 명시했다.
- receipt 복구는 기존 failed main lane을 삭제하거나 잘라내지 않고, owner transfer 후 `main-recovery` lane으로 증거를 이어 붙이는 방식으로 수행한다.

## 남은 위험과 범위

- GitHub PR, remote CI, human review thread, merge는 이번 요청의 권한·범위가 아니므로 검증하지 않았다.
- 프로세스 수명이 workflow lease보다 긴 경우를 자동으로 완전히 예방할 수는 없다. 대신 `receipt-diagnose` → `resume-check` → owner transfer → failed-lane resolution 절차를 lesson과 checklist에 고정했다.

## DoD

`P0=0`, `P1=0`, fresh 테스트·정적 검사·diff 검증 완료. 로컬 사전 PR 증거와 durable lesson을 커밋한 뒤 receipt completion proof를 기록한다. PR 생성은 의도적으로 보류한다.
