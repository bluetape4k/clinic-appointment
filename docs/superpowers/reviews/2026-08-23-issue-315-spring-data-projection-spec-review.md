# Issue #315 설계 통합 리뷰

## 검토 범위

- 대상: `docs/superpowers/specs/2026-08-23-issue-315-spring-data-projection-design.md`
- 기준: Issue #315의 `Clinics` 조회 전용 Spring Data projection pilot
- 제외: production source, `/api/{tenantCode}/clinics` route, `ClinicRepository`
  교체, schema/dependency 변경
- 리뷰 방식: Type A 여섯 관점 중 동기 JDBC pilot에 적용되는 성능, 안정성,
  보안, 운영, Developer/API, User/Caller lane을 독립 검토한 뒤 이 문서에서
  통합했다.

## 초기 finding과 보정

| 관점 | 초기 finding | 설계 보정 | 최종 상태 |
|---|---|---|---|
| Performance | raw `@Query`의 row별 `findById()` N+1, 표본 수·측정 범위·운영 query plan 부족 | PartTree derived query로 고정, 4·32·128건, warm-up 5회/측정 30회, transaction 포함 symmetric total, component timing, PostgreSQL `EXPLAIN`, pool 동시성 미검증 시 보류 | PASS |
| Stability/Ops | context startup 실패 시 `Database` handle 유실, active transaction 정리 순서·전역 registry lock·PostgreSQL wiring 불명확 | `defaultDatabase`/`primaryDatabase` 보존과 registration diff, startup 실패 cleanup, rollback/unbind → context close → unregister → default 복원, `@ResourceLock`/`SAME_THREAD`, 기존 `Containers.Postgres`와 bounded timeout | PASS |
| Security | 물리 connection 경계, full-row 민감 컬럼 위험, runtime classpath 격리 증거 필요 | 물리 connection identity, synthetic fixture 한정, full-row 경로의 production adoption 보류, `runtimeClasspath`/`bootJar` read-back, tenant predicate와 authorization 경계 분리 | PASS |
| Developer/API | `EntityID` 호출 경계 노출, read-only repository가 CRUD surface를 상속, `extractId`/`table`·scan·transaction manager 계약 추상적 | `Long` 기반 `internal` adapter와 전용 package, adapter 밖 CRUD 호출 금지, `table = Clinics`, `extractId` 계약, scan bean 수 1개, `transactionManagerRef = "springTransactionManager"` 및 factory read-back | PASS |
| User/Caller | 기존 `ClinicRepository`와 candidate 호출 방식·권한·pagination·입력 오류 의미 혼재 | adapter `findByTenant(Long)` 고정, Spring-managed transaction 전용 및 drop-in replacement 아님을 명시, `TenantClinicAccessChecker` 유지, `<= 0` 예외·unknown positive empty 결과, pagination 비대체 | PASS |
| Coroutine/Flow | 동기 JDBC test-only surface라 dispatcher/cancellation 계약 없음 | 적용 대상 아님을 범위에 기록 | N/A |

## 통합 판정

- P0: 0
- P1: 0
- P2: 0
- P3: 0
- 설계 gate: **PASS**

이번 pilot은 production adoption 승인이나 기존 repository 교체 승인이 아니다.
구현 gate에서는 다음 증거를 실제로 수집해야 한다.

1. `ClinicProjectionAdapter.findByTenant(Long)`가 `EntityID<Long>`를 외부에
   노출하지 않고, 한국어 KDoc과 입력·빈 결과 계약을 지키는지 확인한다.
2. narrow `ApplicationContextRunner`가 repository 후보 하나만 scan하고
   `transactionManagerRef`가 `springTransactionManager`인지 bean definition과
   runtime transaction에서 함께 확인한다.
3. refresh 실패·assertion 실패·context close 실패에서 Exposed registration과
   Spring resource가 남지 않는지 확인한다.
4. H2와 `test-postgresql`을 분리 실행하고, PostgreSQL readiness 실패를 H2
   성공으로 위장하지 않는다. `EXPLAIN`, statement count, tenant predicate,
   `ORDER BY id ASC`를 raw Markdown evidence로 보존한다.
5. full-row DAO Entity 한계, 제한된 pool 동시성 미검증, production
   authorization 미검증은 결과 문서와 후속 Issue에서 “보류” 또는 “N/A”로
   명시한다.

## 다음 gate

설계 문서와 본 통합 리뷰를 기준으로 구현 plan을 작성한다. plan에는 전용
test package/source 파일, TDD 순서, lifecycle 실패 경로, H2/PostgreSQL 실행
명령, runtime artifact 경계, evidence 경로와 rollback을 각각 매핑한다.
