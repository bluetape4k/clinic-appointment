# Issue #312 keyset pagination inline review

## 검토 범위와 근거

Issue #312 변경 범위의 Kotlin production/test, API README, PostgreSQL 실행계획
lesson을 `origin/develop...HEAD` diff로 다시 읽었다. 확인한 최신 근거는 다음과 같다.

- `./gradlew :appointment-core:test --tests "io.bluetape4k.clinic.appointment.model.dto.KeysetPaginationTest" --tests "io.bluetape4k.clinic.appointment.repository.ClinicKeysetPaginationRepositoryTest" --no-daemon`
  — H2/PostgreSQL 포함 10개 통과.
- 세 controller의 H2·PostgreSQL 프로파일 targeted test — cursor와 기존 offset 계약 통과.
- `./gradlew :appointment-api:test --no-daemon` — 848 passing, 3 pending, `BUILD SUCCESSFUL`.
- `./gradlew :appointment-core:build :appointment-api:build --no-daemon -x test` — `BUILD SUCCESSFUL`.
- `ClinicKeysetPaginationQueryPlanTest` — 세 PostgreSQL catalog query가 50행을 반환하고
  keyset SQL/plan에 `OFFSET`이 없음을 확인.
- `git diff --check` — whitespace 오류 없음.

## 관점별 판정

| 관점 | 확인 내용 | 판정 |
|---|---|---|
| 보안 | 세 controller 모두 `verifyClinic`을 cursor decode보다 먼저 호출한다. codec은 URL-safe no-padding token, 버전, segment, 양수 ID, canonical encoding을 검증하며 raw cursor를 응답에 반사하지 않는다. repository는 tenant membership와 clinic equality를 함께 적용한다. | 통과 |
| 정확성 | 세 repository가 `(clinic_id ASC, id ASC)`와 exclusive `id > anchor`를 사용한다. first/next/last/empty, tenant/clinic 격리, wrong-clinic, sparse ID, anchor 삭제와 이후 삽입 회귀가 H2/PostgreSQL에서 통과했다. | 통과 |
| 성능 | 각 query가 `limit + 1`만 materialize하고 `count`/`OFFSET`을 호출하지 않는다. PostgreSQL 실행계획은 keyset에 `OFFSET`이 없음을 확인했다. 복합 인덱스는 단일 측정으로 확정하지 않고 후속 이슈 대상으로 남겼다. | 통과 |
| 호환성 | 기존 `findPage`와 page/size route/response는 diff에서 변경되지 않았다. 새 `/cursor` route와 `{items,nextCursor}` 응답만 additive로 추가했으며 migration/dependency/frontend 변경이 없다. | 통과 |
| Kotlin/Exposed | repository DB 호출은 controller의 `transaction {}` 또는 기존 caller transaction 안에서 실행된다. 정렬은 두 key 모두 `SortOrder.ASC`로 명시하고 production 변경에 `!!`가 없다. | 통과 |
| 운영성 | malformed/wrong-clinic cursor는 기존 전역 400 경계로 전달된다. PostgreSQL 테스트는 singleton launcher, Flyway migration, Docker health 확인 뒤 재현할 수 있고 원문 plan은 build report에 남는다. | 통과 |

## 발견사항

| 등급 | 내용 | 조치/근거 |
|---|---|---|
| P0 | 없음 | — |
| P1 | 없음 | delivery 조건 충족 |
| P2 | 현재 PostgreSQL 수치는 `EXPLAIN (ANALYZE, BUFFERS)` 단일 대표 실행이며 p95/처리량 benchmark가 아니다. 또한 composite `(clinic_id, id)` index는 아직 적용하지 않았다. | lesson에 측정 한계를 명시했고, [Issue #386](https://github.com/bluetape4k/clinic-appointment/issues/386)에서 운영 cardinality·쓰기 비용을 비교한다. 이번 API 계약과 SQL shape의 blocker는 아니다. |
| P3 | README Korean audit에서 기존 `snapshot`/`스냅숏` 라인만 남아 있다. 이번 cursor 문서 추가분에는 해당 용어가 없다. | 기존 문서의 unrelated finding으로 보존하고 이번 diff에서 임의 정리하지 않는다. |

## 결론

P0=0, P1=0으로 구현·검증·delivery 단계로 이동할 수 있다. P2는 승인된 범위
밖의 운영 인덱스/반복 benchmark 판단이며 lesson과 후속 Issue에서 추적한다. chart는
단일 대표 측정값을 안정적인 benchmark처럼 오해하게 만들 수 있어 이번 delivery에는
생성하지 않는다.
