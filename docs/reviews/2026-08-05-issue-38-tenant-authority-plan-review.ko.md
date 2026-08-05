# Issue #38 tenant authority plan review

## 검토 범위

- Spec: `docs/superpowers/specs/2026-08-05-issue-38-tenant-authority-design.md`
- Plan: `docs/superpowers/plans/2026-08-05-issue-38-tenant-authority-plan.md`
- 대상 변경: commitment HTTP path를 `/api/{tenantCode}/...`로 통일하고,
  path tenant와 검증된 JWT membership을 유일한 외부 tenant authority로 고정
- 제품 코드와 테스트 구현은 이 plan gate 동안 변경하지 않음

## 독립 관점 결과

| 관점 | 1차 결과 | 수정 후 판정 | 반영 위치 |
|---|---|---|---|
| Security | REQUEST CHANGES: multi-tenant JWT가 `singleOrNull()`에 남음, raw matcher variable 신뢰, spoof header/body와 pre-auth path 부족 | PASS 조건 충족: selected path tenant를 `ActorContext`와 access resolver까지 전달하고, shared canonical rule로 matcher를 재검증 | Spec 권한 흐름/실패 계약, Plan Tasks 1–3, 위험표 |
| Stability | P1 후보: malformed path가 JWT보다 늦음, filter DB failure가 generic 5xx, stale ThreadLocal, fixture의 process-global Exposed DB, filter 중복 등록 | PASS 조건 충족: pre-auth filter 순서·chain-only registration, 명시적 internal error, request 경계 cleanup, default DB 복구와 resource lock을 계획 | Spec 권한 흐름, Plan Tasks 1/3/5/8 |
| Performance | P0/P1 없음. route/JWT/context hot path와 duplicate tenant lookup 증거가 부족 | PASS 조건 충족: token/claim/code 상한, 두 번의 의도된 lookup count, no-cache 결정과 bounded focused evidence를 명시 | Plan Tasks 1/2/8, 위험표 |
| API contract | P0/P1 없음. 10개 route inventory, error envelope, OpenAPI operation uniqueness가 더 필요 | PASS 조건 충족: exact 10 operations, foundation/commitment envelope 구분, fail-closed 403 scope 계약으로 고정 | Spec 외부 경로/실패 계약, Plan Tasks 3/4 |
| Kotlin/Spring/testing | REQUEST CHANGES: filter bean registration, ActorContext 생성부 호환성, DB fixture와 실제 security-chain 증거 부족 | PASS 조건 충족: disabled servlet registration, nullable default field, 전체 생성부 inventory, MockMvc/security-chain probe와 순차 실행을 명시 | Plan Tasks 1/2/3/5/8 |
| Documentation/DoD | P0/P1 없음. active/historical `v2` 분류, bilingual README, rollout, live PR readback 필요 | PASS 조건 충족: active route/example scan, residual allowlist, atomic rollout/rollback, Korean review와 PR head/CI readback을 명시 | Plan Tasks 6/9, 위험표 |

## 보안·권한 결정

1. `tenantCode` path는 routing 입력이며 단독 권한이 아니다. 검증된 JWT의
   `allowedTenants` membership과 활성 `TenantGroup` 조회를 모두 통과해야 한다.
2. multi-tenant JWT에서 path 값을 선택한다. `ActorContext.selectedTenantCode`
   를 추가하고, commitment access resolver가 이를 재검증한다. authority 경로의
   `allowedTenants.singleOrNull()`은 제거한다. 기존 policy/background actor는
   nullable default를 통해 source compatibility를 유지하되, commitment boundary는
   selected value가 없으면 fail-closed한다.
3. `X-Tenant-Code`, `X-Clinic-Id`, `tenantGroupId` header/body는 authority가 아니다.
   unknown DTO field는 400, 알려진 consent `evidenceAuthority` namespace 충돌은
   403 `SCOPE_FORBIDDEN`으로 구분한다.
4. `TenantAuthorizationManager`는 Spring matcher가 제공한 raw variable도
   `TenantCodeRules`로 canonical/reserved 검사를 다시 한다.

## 경로·실패 계약

- canonical slug: Flyway V20과 동일한 lower-case ASCII alphanumeric segment의
  single-hyphen 조합, 최대 64자
- reserved root: `v1`, `v2`
- malformed/encoded-ambiguous/reserved path: JWT parser 전에 404
  `RESOURCE_NOT_FOUND`
- missing/invalid/expired JWT: 401 `UNAUTHORIZED`
- 활성 tenant가 없거나 inactive: 인증 후 404 `RESOURCE_NOT_FOUND`
- JWT membership 또는 clinic/scope mismatch: foundation filter에서는 403
  `FORBIDDEN`, commitment endpoint에서는 403 `SCOPE_FORBIDDEN`
- scoped commitment/proposal 부재도 fail-closed 403 `SCOPE_FORBIDDEN`으로 유지한다.
  존재 여부를 구분하기 위한 별도 query는 이번 이슈에 추가하지 않는다.
- tenant DB lookup exception: 일반 요청 500 `INTERNAL_ERROR`, policy 요청
  `POLICY_INTERNAL_ERROR`; 404/403으로 위장하지 않고 correlation ID와
  sanitized tenant code만 structured log에 남긴다.

## 안정성·운영 결정

- filter 순서: correlation → `TenantPathValidationFilter` → JWT →
  `TenantContextFilter`
- 두 custom filter는 Spring bean으로 등록하되 servlet `FilterRegistrationBean`은
  비활성화해 security chain에서 한 번만 실행한다.
- raw/decoded URI의 `%2f`, `%2e`, `%5c`, semicolon parameter,
  double-encoded separator를 실제 `MockMvc`/security-chain probe로 검증하고
  JWT parser 미호출을 확인한다.
- `TenantContext`는 request entry/finally에서 stale value를 제거하고 success,
  exception, async/error dispatch에서 복구한다. 현재 suspend controller는 명시적
  `TenantClinicScope`를 사용하므로 불필요한 ambient propagation은 추가하지 않는다.
- Exposed fixture는 `SchemaUtils.createMissingTablesAndColumns`와 transaction
  cleanup을 사용하되 `issue38-a`/`issue38-b` 같은 namespaced row만 삭제한다.
  공유 Spring context의 `tenant-default` seed를 지우지 않으며,
  `TransactionManager.defaultDatabase`도 원상복구한다.
- rollout은 mixed old/new pod traffic을 지원하지 않는다. api-enabled/drain,
  atomic deploy, all-new readiness, 10개 신규 route와 10개 legacy negative smoke,
  rollback readiness를 runbook에 포함한다.

## 범위 제외와 이유

- DB schema, `tenantGroupId`, key/FK, state machine, idempotency, ETag 변경 제외:
  tenant authority 경계만 바꾸며 내부 데이터 모델은 이미 tenant-scoped다.
- compatibility alias(`/api/v2`) 제외: 아직 공개 contract가 고정되지 않았고,
  두 authority model을 동시에 유지하면 rollout 중 권한 경계가 흔들린다.
- existence-sensitive 404 query 제외: scope-hidden과 absent aggregate를 구분하려면
  별도 조회가 필요하고 data-leak 위험과 범위가 커진다. 현재 fail-closed 403을
  유지한다.
- JMH와 새 외부 dependency 추가 제외: 이미 version catalog에 있는
  `kotlinx-coroutines-test`만 test scope로 연결하고, bounded claim/path work와
  repository lookup count를 focused test로 검증하며 query plan/index는 변경하지
  않는다.

## 판정

1차 판정은 `REQUEST CHANGES`였으나, 위 P1 수정과 P2 보완을 spec/plan에 반영했다.
현재 plan gate 판정은 `PASS — revised plan approval required`이다. 다음 단계는
사용자 승인 후 TDD RED/GREEN으로 구현을 시작하는 것이며, 그 전에는 제품 코드나
테스트 파일을 변경하지 않는다.
