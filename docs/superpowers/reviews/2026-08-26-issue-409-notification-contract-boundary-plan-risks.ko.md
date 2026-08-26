# Issue #409 알림 contract 분리 구현 계획 3-P 위험 예측

## 목적과 기준

이 문서는 구현 전 3-P gate로서 승인 명세, 3-R 계획 검토, 현재 develop를 기준으로
구조 변경이 만들 수 있는 성능·안정성·운영 위험을 선제적으로 고정한다.

- 기준 develop: 8d68b1e3bc8c944bc1ba1f9e6e8233417d23cff8
- 승인 명세: bda78b01639017cf3b732a0a289cc103c475dccc
- 계획: 7303ed4f
- 3-R: docs/superpowers/reviews/2026-08-26-issue-409-notification-contract-boundary-plan-review.ko.md
- 변경 범위: 새 module·dependency·Flyway SQL 없이 event/persistence package와 wiring을 분리

## 위험 예측과 완화 gate

| 영역 | 예측 위험 | 조기 탐지 | 완화/중단 기준 | 담당 task |
|---|---|---|---|---|
| 구조/계약 | event jar에 table·claim DTO가 이름 변경으로 재유입 | forbidden jar entry와 source scan | 하나라도 발견하면 P1, Task 7을 통과할 때까지 중단 | 2·3·7 |
| ABI/source | API public constructor가 concrete repository를 계속 참조하거나 source migration이 누락 | API source fixture와 consumer compile | public persistence reference는 P1; port-only compile 전진 금지 | 5·7 |
| Spring wiring | port alias와 concrete bean의 ConditionalOnMissingBean 충돌, fallback이 성공을 오보고 | context test 4경로와 bean graph 로그 | missing writer에서 fail-closed가 아니면 P1 | 4·5 |
| DB/schema | package 이동으로 table discovery 또는 SchemaUtils 순서가 바뀌고 V14/V19와 불일치 | generateMigrations 출력, schema bootstrap, Flyway H2/MySQL/PostgreSQL | SQL/checksum/column/index drift는 P1; migration commit 금지 | 0·3·8 |
| transaction | caller transaction guard가 사라져 autocommit 또는 잘못된 Exposed receiver가 실행 | negative transaction test, source inspection | transaction 밖 성공 또는 receiver shadowing이면 P1 | 3·6 |
| lease/concurrency | repository 이동 중 claim lock, lease token fence, retry transition이 달라짐 | 20 worker MultithreadingTester와 Redis integration | duplicate claim·stale completion·lease loss가 하나라도 있으면 P1 | 4·6·9 |
| coroutine/lifecycle | claim 뒤 provider 호출의 IO dispatcher, cancellation cleanup, Redis/Lettuce 종료 누락 | worker/dispatcher source와 cancellation test | leaked job/connection 또는 cancellation 후 write면 P1 | 4·6·9 |
| hot path | package move 과정에서 새 DB round trip, allocation, lock 범위, polling/backpressure가 추가 | SQL/query diff, 4-P 표, codec backlog benchmark | 신규 round trip/lock widening은 P1; 성능 향상 주장은 금지 | 3·6·9 |
| retry/retention | bounded retry/backoff, retention, readiness fail-closed semantics가 누락 | targeted worker/retention/readiness test | 무한 polling 또는 fail-open이면 P1 | 4·9 |
| security/data | opaque receipt가 status·lease token을 노출하거나 event가 persistence table을 역참조 | reflection contract test, jar/source guard | persistence metadata 노출은 P1 | 1·2·7 |
| rollback/운영 | package rollback은 가능하지만 schema rollback으로 오해하거나 Issue evidence가 끊김 | ADR/README/lesson와 checksum readback | 문서·Issue·PR에 rollback 경계가 없으면 delivery 중단 | 8·10 |

## 성능·안정성 불변 조건

다음은 package move에도 변하지 않아야 하는 불변 조건이다.

1. 기존 SQL predicate, index order, claim lease duration, retry/terminal transition을
   유지한다.
2. 새 polling loop나 DB round trip을 만들지 않는다.
3. Exposed 호출은 caller transaction 안에서만 실행한다.
4. worker의 IO dispatcher, coroutine cancellation, provider cleanup과 Redis/Lettuce
   lifecycle을 보존한다.
5. H2 test isolation은 Base58 suffix와 before-each schema/delete 규칙으로 유지하고,
   durable/security UUID는 임의로 바꾸지 않는다.
6. `@Testcontainers`를 도입하지 않고 bluetape4k singleton launcher와 concurrency
   helper를 재사용한다.

## 검증 순서

```text
compile/source RED
  -> moved persistence compile
  -> worker/API context and targeted tests
  -> consumer fixture + jar/source guard
  -> migration/Flyway + checksum
  -> full build + 4-P + 7-Tier
  -> Issue/PR exact-head CI
```

각 단계에서 P1 위험이 관찰되면 다음 단계와 PR publish를 중단하고 원인·재현 명령·
rollback 범위를 review artifact에 남긴다. P2인 transitional public worker API는
중복 확인 Issue를 생성해 delivery record에 링크한다.

## 3-P 판정

현재 계획은 모든 예측 위험에 조기 탐지 명령과 stop criterion을 배정했다. 새 dependency,
schema migration, polling path를 추가하지 않으며, 성능 향상을 주장하지 않고 회귀 부재만
증명한다. 구현 전 P0=0, P1=0이며, 위 gate를 구현 중 다시 실행한다.

**판정: PASS (P0=0, P1=0, P2=1 / transitional public API follow-up)**
