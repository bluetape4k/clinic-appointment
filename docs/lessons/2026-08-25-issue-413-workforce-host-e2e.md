# Issue #413 workforce host bootstrap·브라우저 E2E lesson

## 문제

PR #411에서 `AuthService.bootstrap(token, tenantCode?)`와 workforce Bearer scope를 제공했지만, Angular 앱 셸이 page load 시 호스트 handoff를 소비하지 않았다. 따라서 production host가 token을 전달해도 초기 `roleGuard`가 인증 상태를 볼 수 없었고, 직원·관리자 브라우저 흐름은 patient portal E2E로 대체 검증할 수 없었다.

## 결정

- Gateway/host는 Angular 스크립트가 실행되기 전에 `globalThis.__CLINIC_WORKFORCE_AUTH__ = { token, tenantCode? }`를 설정한다.
- `WorkforceAuthBootstrapService`가 앱 셸 생성 시 handoff를 한 번 읽고, `AuthService.bootstrap(token, tenantCode?)`를 호출한 뒤 전역 참조를 즉시 제거한다.
- JWT는 `localStorage`·`sessionStorage`에 기록하지 않고 `AuthService` 메모리에만 둔다. tenant persistence는 기존 `TenantContextService` 계약을 그대로 사용한다.
- handoff 형식 오류나 bootstrap 실패는 예외를 앱 부팅까지 전파하지 않고 workforce `unauthorized` 상태로 전환한다.
- backend workforce login endpoint는 추가하지 않는다. 외부 Gateway가 발급한 token을 host handoff로 전달하는 책임 경계를 유지한다.

## 결과

- 직원 `ROLE_STAFF` handoff가 tenant-scoped 예약 목록과 새 예약 생성 `POST` 화면을 연다.
- 관리자 `ROLE_ADMIN` handoff가 관리 대시보드·통계 화면과 예약 상태 변경 `PATCH`를 연다.
- `ROLE_PATIENT` handoff는 workforce management route를 통과하지 못하고 `/calendar`로 이동한다.
- workforce `401`·`403` 응답은 각각 `인증 필요`·`권한 없음`으로 표시되며, 모든 fixture 요청은 `/api/tenant-default/...`와 `Bearer` scope를 사용한다.

## 검증

- `npx ng test --watch=false --progress=false`: **45개 파일 / 327개 테스트 통과**
- `npm run build`: **production bundle 생성 성공**
- `npm run test:e2e`: **10개 Chromium 시나리오 통과** (기존 patient 5개 + workforce 5개)
- `npx tsc --noEmit -p tsconfig.app.json`: **통과**
- `git diff --check`: **통과**
- 변경 파일에 Kotlin 소스가 없으며 backend workforce login endpoint도 변경하지 않았다.

## 놓친 점과 재발 방지

초기 구현은 `AuthService.bootstrap()` seam만 제공하고 실제 앱 셸 호출자를 빠뜨렸다. 인증 API를 추가할 때는 다음 두 경계를 같은 변경에서 확인한다.

1. `AuthService`의 복원 API가 존재하는가.
2. `App` 또는 동등한 초기화 seam이 page load 전에 복원 API를 호출하는가.

브라우저 E2E는 `page.addInitScript`로 host handoff를 주입하고, route guard·tenant path·Authorization header·401/403·대표 POST/PATCH까지 함께 확인한다. 실제 Gateway 배포 설정은 외부 host 책임이므로 이 저장소의 계약과 fixture가 drift하지 않는지만 검증한다.

## 문서 게이트

- SPW-01: Korean lesson의 독자·목적·근거 파일(`app.ts`, `workforce-auth-bootstrap.service.ts`, unit/E2E 결과)을 고정했다. **PASS**
- SPW-02: 문제·결정·결과·검증·놓친 점·재발 방지 구조를 충족했다. **PASS**
- SPW-03: 기술 토큰과 명령은 그대로 보존하고 Korean technical register를 적용했다. **PASS**
- SPW-04: 현재 worktree 소스와 fresh test/build/E2E 결과를 대조했다. **PASS**
- SPW-05: Markdown read-back 및 `git diff --check`를 완료했다. **PASS**
- KO-01~07: 사실·식별자·용어·문서 표면을 검토하고 contextual terminology audit 대상 충돌이 없음을 확인했다. **PASS**
