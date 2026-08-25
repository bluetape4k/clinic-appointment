# Issue #393 설계 명세 7-Tier 검토

## 검토 범위

- 대상 명세: `docs/superpowers/specs/2026-08-25-issue-393-outbox-ownership-design.ko.md`
- 검토 기준 commit: `05c18d4191fbf47062a4171e136ea802444703f6`
- 기준 develop: `28e38915cc153fc01275a2c6acad632d99340b93`
- 대상 이슈: [#393](https://github.com/bluetape4k/clinic-appointment/issues/393)
- 대상 Epic: [#407](https://github.com/bluetape4k/clinic-appointment/issues/407)
- 검토 방식: 현재 source/Gradle/fixture/migration을 대조한 6개 독립 관점과
  통합 검토. 구현 코드는 아직 변경하지 않았다.

## 7-Tier 판정

| Tier | 판정 | 검토 근거 |
|---|---|---|
| 성능 | CLEAR | V19 readiness 추가는 고정된 table/index preflight만 늘린다. 기존 dispatcher/worker 호출 위치를 유지하며 dispatch당 추가 table probe는 `1 + globalConcurrency` 이하로 제한된다. `globalConcurrency`는 DB·resolver·provider capacity 이하라는 기존 계약을 따른다. |
| 안정성 | CLEAR | `clinic_waitlist_notification_outbox`와 idempotency/ready/lease index 3종의 missing/UP/오진 방지 시나리오를 명세와 회귀 테스트 계약에 고정했다. transaction·lease·retry·migration SQL은 보존한다. |
| 보안/데이터 경계 | CLEAR | messaging은 event dependency를 `implementation`으로 낮추고 core를 직접 `api`로 선언한다. notification의 event `api`는 public event repository/DTO를 실제 사용하는 명시적 transitional exception으로 문서화한다. |
| 운영 | CLEAR | ADR-15 matrix가 table declaration, repository/write, claim·relay·worker, readiness, migration을 row family별로 분리한다. rollback과 #400 진단 범위도 분리했다. |
| 개발자/API | CLEAR | public reason-code import를 core source로 정렬하고 messaging consumer fixture에 `AppointmentOutboxWriter`, core record/scope/reason anchor를 추가한다. fixture는 API leakage만 증명한다는 한계도 traceability에 기록했다. |
| 사용자/호출자 | CLEAR | 두 README locale의 설치 dependency, direct event dependency migration note, event/notification transitional 책임과 실제 Gradle 계약 정렬을 명세에 포함했다. 기존 terminology findings도 implementation gate로 고정했다. |
| 통합/테스트 | CLEAR | event·messaging·notification 단독 테스트, V19 readiness 테스트, API Flyway migration, API consumer fixture와 `--no-configuration-cache` 검증 명령을 현재 SHA 기준으로 정의했다. |

## 결론

- P0: 0
- P1: 0
- P2: 0
- P3: 0
- 설계 gate: `CLEAR`

### 구현 전 필수 인계 조건

1. 사용자가 이 commit의 설계 명세를 검토한다.
2. 승인 후 `writing-plans`로 구현 계획과 테스트 계획을 작성하고 Step 3-R을
   통과한다.
3. 구현 시 notification readiness에 V19 table과 index 3종을 추가하고 기존 모든
   readiness fixture에 waitlist table을 포함한다.
4. 구현 완료 전 README/architecture의 현재 Korean terminology 9건을 수정해
   audit `findings=0`을 증명한다.
5. Issue #393 본문의 stale 기준 SHA와 transitional exception/V19 범위를 live
   read-back으로 정렬한다.

이 문서는 설계 gate 결과이며, 코드 구현·모듈 테스트·PR 생성·merge 승인을 의미하지
않는다. 전체 stacked PR train의 merge 승인은 모든 child issue가 완료된 뒤 한 번만
수행한다.
