# Issue #314 `jdbc-lettuce` 구현 계획 inline review

## 판정

- **계획:** PASS
- **구현 전 P0/P1:** 0건
- **운영 전환:** HOLD — scope-list/per-ID 계약과 운영 지표는 파일럿 결과로만 판단
- **검토 방식:** 독립 리뷰어 없이 이 세션에서 6관점과 통합 검토를 순차 수행
- **대상:** `docs/superpowers/plans/2026-08-24-issue-314-jdbc-lettuce-plan.md`

## 1. 성능

legacy 두 번째 `findByScope`와 candidate `findAll`/per-ID `get`을 같은
fixture에서 SQL count로 분리한다. TTL과 key 수는 raw Redis로 확인하지만
latency·운영 SLO를 추정하지 않는다. 별도 benchmark module이나 chart를
추가하지 않는 범위가 Issue 목적과 맞다.

**판정:** PASS. scope SQL 반복은 R1로 측정 후 운영 채택을 보류한다.

## 2. 안정성

singleton Redis launcher, `ResourceLock`, `SAME_THREAD`, 자식 우선 cleanup,
짧은 timeout의 실패 client, close idempotence를 계획에 포함했다. Testcontainers
직접 생성이나 shared Redis shutdown은 없다.

**판정:** PASS. R2/R5/R7 rerun 절차가 명시되어 있다.

## 3. 보안

dependency는 test-only이고 namespace는 `issue314:jdbc-lettuce:*`로 격리한다.
codec은 타입을 명시한 Jackson3이며 raw payload·credential·endpoint를
출력하지 않는다. 기존 Fory/LZ4 v3 namespace는 읽지 않는다.

**판정:** PASS. 운영 공용 codec 채택은 별도 승인 범위로 남긴다.

## 4. 운영·SRE

runtime bean graph, feature flag, metrics, schema, API를 변경하지 않아
rollback은 dependency와 test probe 제거다. Redis failure는 DB fallback을
확인하고, stale 값의 외부 writer/invalidation 책임을 lesson에 기록한다.

**판정:** PASS. 운영 SLO/alert/dashboard를 만들지 않는 이유가 명확하다.

## 5. 개발자/API

실제 `AbstractJdbcLettuceRepository`, `LettuceCacheConfig.READ_ONLY`,
`ExposedLettuceCodecs.jackson3`, 기존 ResultRow mapper와 Exposed transaction
패턴을 재사용한다. 새로운 production abstraction은 없다. version catalog는
BOM alias만 추가하고 `testImplementation`과 `appointment-api/gradle.lockfile`
test configuration으로 경계를 보장한다.

**판정:** PASS. test-only probe의 write mapping은 추상 계약을 만족시키되
READ_ONLY에서는 호출하지 않는다는 설계와 일치한다.

## 6. 사용자·호출자

production 호출자에게 보이는 `findByScope`와 API 응답은 그대로다. 결과,
tenant, 빈 목록, invalidation, 장애 fallback, close를 Korean lesson과
targeted output으로 설명하며, 운영 채택을 자동으로 주장하지 않는다.

**판정:** PASS. 저장소 문서·리뷰는 repository-local Korean policy를 따른다.

## 통합 traceability

| 명세 DoD | 계획 task | 검토 결과 |
|---|---:|---|
| 실제 API + test-only dependency | 1~3 | compile/resolution 명령 있음 |
| 결과/tenant/empty/codec/TTL | 4 | 세 타입과 raw key/TTL assertion 있음 |
| SQL/cache hit·miss | 5 | StatementInterceptor 경로 있음 |
| stale/invalidate/failure/close | 5~6 | DB update/delete, failed client, lifecycle 있음 |
| runtime/bootJar leakage | 6 | 두 독립 검사가 있음 |
| lesson/보류/rollback | 7, risk doc | acceptance table과 stop condition 있음 |

## 결론

계획은 승인된 test-only 경계를 벗어나지 않고, 명세의 모든 acceptance
criterion과 실패 모드를 순서대로 검증한다. 구현 전 P0/P1 미해결 사항은
없다. plan 승인 후 plan/review/risk 문서를 커밋하고 TDD 구현으로 이동한다.

## Writer gate

| 항목 | 결과 | 근거 |
|---|---|---|
| SPW-01 | PASS | 승인된 명세, 실제 source/API, dependency와 test fixture 근거를 고정함 |
| SPW-02 | PASS | 파일·순서·명령·기대 결과·rollback·approval gate를 포함함 |
| SPW-03 | PASS | Korean technical register와 repository-local 언어 정책을 적용함 |
| SPW-04 | PASS | 명세 DoD를 Task 1~7 traceability로 대조함 |
| SPW-05 | PASS | Markdown read-back, `git diff --check`, terminology audit 통과 |
