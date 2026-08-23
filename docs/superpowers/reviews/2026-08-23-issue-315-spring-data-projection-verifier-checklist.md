# Issue #315 구현 verifier 체크리스트

## 판정 기준

승인된 test-only pilot의 결과·격리·transaction·SQL·backend·runtime 경계를
source, test, evidence에 연결하고, 운영 채택과 참고용 측정값을 분리한다.

## 추적 결과

| acceptance | source/test | fresh evidence | 판정 |
|---|---|---|---|
| 기존 결과·정렬 동일성 | `ClinicProjectionAdapter`, 결과 equality와 `id ASC` 테스트 | H2/PostgreSQL targeted class 성공 | PASS |
| tenant 격리·입력 계약 | `Long` adapter, A/B/unknown/0/negative 테스트 | targeted class 성공 | PASS |
| transaction/connection | `SpringTransactionManager`, `DataSourceUtils`·Exposed identity 테스트 | manager 1개, physical connection identity 성공 | PASS |
| repository wiring | `@EnableExposedJdbcRepositories` annotation 및 bean definition property read-back | `springTransactionManager` 일치 assertion 성공 | PASS |
| 단일 SELECT/N+1 | typed PartTree, `StatementInterceptor` | predicate/order 및 대표 SELECT 각 1회 | PASS |
| lifecycle/전역 상태 | sentinel restore, callback/close failure suppressed, schema owner drop | H2/PostgreSQL targeted class 성공 | PASS |
| H2/PostgreSQL capability | profile별 fixture, unique schema, bounded Hikari, `EXPLAIN` | raw evidence와 PostgreSQL index scan | PASS |
| 성능/차트 | symmetric 4/32/128, 5 warm-up/30 samples, chart ledger | raw, SVG/PNG, semantic/visual/asset-pair audit | PASS |
| production artifact 경계 | test-only source와 runtime/bootJar 분리 | runtimeClasspath/bootJar exact check | PASS |
| 운영 채택 | full-row·authz·pool 경계 | `NOT_TESTED`/보류를 summary와 lesson에 명시 | HOLD |

## 미검증 항목

- in-process `Future.cancel/join`과 synthetic 다중 `Database.connect` tracker는
  구현하지 않았다. 현재 helper는 context가 만든 `primaryDatabase` 후보와
  schema owner를 직접 정리하고, 외부 Gradle process deadline을 사용한다.
- pool contention/다중 호출, full-row의 column-level projection, 실제 인증
  route의 tenant 권한 검증은 production adoption 전 별도 이슈가 필요하다.

## verifier 결론

구현·검증 산출물은 test-only feasibility pilot의 acceptance를 충족한다.
production route 또는 repository 교체는 candidate total 비용과 위 미검증
경계 때문에 보류한다.

판정: **DONE (pilot) / PENDING (production adoption)**
