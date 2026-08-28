# Issue #430 설계·계획 3-R 검토

## 검토 범위와 기준

- 대상: `docs/superpowers/specs/2026-08-27-issue-430-api-auth-contract-design.md`
  및 `docs/superpowers/plans/2026-08-27-issue-430-api-auth-contract-plan.md`
- 기준 코드: #23 head `c2275ff9dc16c6e64829ffb4da9015331a84be0a`
- 검토일: 2026-08-27
- 검토 방식: performance, stability, security, operator, developer/API, user/caller의
  여섯 관점을 현재 main session에서 독립적으로 확인한 뒤 통합

## 여섯 관점 결과

| 관점 | P0 | P1 | P2 | P3 | 결과와 근거 |
|---|---:|---:|---:|---:|---|
| Performance | 0 | 0 | 1 | 0 | origin `URL` 정규화와 XSRF token 조회는 요청 경계의 유한한 작업이다. benchmark는 해당 변경의 수용 기준이 아니므로 N/A다. 반복 측정 주장을 하지 않도록 계획에 명시했다. |
| Stability | 0 | 0 | 1 | 0 | 새 외부 connection·thread·retry가 없고 CORS source는 비활성일 때 빈 mapping만 제공한다. native `SameSite=Strict` cookie 실동작은 이 slice에서 포장하지 않고 #24/#27로 경계를 분리했다. |
| Security | 0 | 0 | 0 | 0 | origin-only/HTTPS/wildcard 거부, patient XSRF scope 제한, Bearer 메모리 보관, patient JWT storage 금지, explicit CORS와 credentials 검증이 모두 계획에 있다. |
| Operator/Ops | 0 | 0 | 1 | 0 | CORS는 disabled-by-default라 기존 배포를 깨지 않지만, native 운영자는 HTTPS origin과 CORS 목록을 함께 넣어야 한다. `application.yml` 설명과 startup validation 테스트를 계획했다. |
| Developer/API | 0 | 0 | 0 | 0 | `TenantApiClient`가 tenant path와 auth context의 단일 책임을 유지하고 Angular의 `HttpXsrfTokenExtractor`와 Spring Security CORS 통합을 재사용한다. 새 dependency나 raw client가 없다. |
| User/Caller | 0 | 0 | 1 | 0 | README에서 browser proxy, native origin, cookie/XSRF와 실기기 한계를 설명한다. 실기기 성공을 browser E2E로 대체하지 않는다고 명시했다. |

## 통합 결과

### 검토한 연결

1. Issue #430의 모든 완료 조건이 spec의 실패/호환성 계약과 plan의 Task 2~5로
   연결된다.
2. Task 0에서 topology에 backend checks를 추가한 뒤 backend RED/GREEN을 실행하므로
   frontend-only CI가 backend 변경을 놓치지 않는다.
3. `TenantApiClient`, `API_AUTH_SCOPE`, `PatientAuthService`, `AuthService`,
   `SessionStateService`의 기존 경계를 보존하고, 새 interceptor는 token extractor만
   재사용한다.
4. Spring CORS source는 항상 존재하되 `enabled=true`일 때만 `/api/**` mapping을
   등록하고 두 security chain에 `.cors {}`를 등록하므로 authentication/CSRF 순서를
   바꾸지 않는다.
5. public behavior 변경에 맞춰 `README.md`, `README.ko.md`, API `application.yml`,
   Korean KDoc/설정 설명을 plan에 포함했다. CHANGELOG/release note는 이 stacked
   feature가 아직 미출시이므로 N/A다.

## P0/P1 수렴 판정

- **P0 = 0**
- **P1 = 0**
- 설계·계획 단계의 P2는 성능 수치 미측정, native cookie 실기기 검증, 운영 CORS
  설정 책임을 명시적으로 기록한 비차단 후속 경계다.
- Task 순서는 spec/plan commit → RED → GREEN → E2E/docs → 7-Tier review로
  의존성이 역전되지 않는다.

## SPW 및 Kotlin 적용 확인

- SPW-01~05를 spec/plan에 기록했고, 현재 두 문서에 대해
  `audit-korean-terms.mjs` 결과 `findings=0`을 확인했다.
- backend Kotlin 구현 시 `bluetape-kotlin-patterns`의 immutable configuration,
  `io.bluetape4k.assertions`, Spring configuration 조건/등록 순서를 적용한다.
- 계획의 Kotlin final checklist와 Step 6-R가 implementation diff를 다시 판정하므로
  이 문서는 구현 완료 판정으로 사용하지 않는다.

## 검토 결론

**PASS — 구현 진행 가능.** P0/P1은 없으며, 위 세 P2 경계는 spec·plan과 후속 lesson에
남긴다. 구현 범위가 native cookie bridge 또는 backend auth redesign으로 확장되면
이 verdict를 폐기하고 spec/plan을 다시 검토한다.
