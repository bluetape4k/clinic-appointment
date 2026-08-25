# Issue #413 workforce host bootstrap·브라우저 E2E inline review

## 검토 범위와 기준

검토 대상은 현재 worktree의 `frontend/appointment-frontend` 변경과 Issue #413 완료 조건이다.

- `src/app/app.ts`
- `src/app/core/services/workforce-auth-bootstrap.service.ts`
- workforce bootstrap unit test와 `app.spec.ts`
- `e2e/workforce-auth.spec.ts`
- Issue #295의 tenant/auth/session 계약 및 PR #411의 선행 구현

판정은 기존 테스트의 재사용이 아니라 fresh unit, TypeScript compile, production build, Chromium E2E와 diff read-back을 근거로 한다.

## 심각도별 결과

| 심각도 | 건수 | 근거 및 처분                                                                                                   |
| ------ | ---: | -------------------------------------------------------------------------------------------------------------- |
| P0     |    0 | 인증 우회·데이터 손실·앱 부팅 불가를 확인하지 못했다.                                                          |
| P1     |    0 | tenant path, Bearer scope, role redirect, 401/403 경계에 blocker가 없다.                                       |
| P2     |    0 | host handoff 계약은 전역 키와 payload shape로 고정했고, production host 설정은 외부 Gateway 책임으로 명시했다. |
| P3     |    0 | 이번 범위에서 별도 정리 항목을 추가하지 않았다.                                                                |

## 관점별 확인

| 관점        | 확인 내용                                                                                                                                                                 | 판정 |
| ----------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---- |
| API/호환성  | `WorkforceAuthBootstrapService`가 기존 `AuthService.bootstrap(token, tenantCode?)`만 호출하고 backend endpoint·patient cookie 계약은 변경하지 않는다.                     | 통과 |
| 보안        | handoff를 소비한 직후 전역 참조를 제거하고 JWT를 browser storage에 쓰지 않는다. invalid token/tenant는 `unauthorized`로 fail-closed한다.                                  | 통과 |
| 안정성      | handoff 부재·형식 오류·bootstrap 예외가 앱 부팅 예외가 되지 않는다. `401`은 token 제거·`unauthorized`, `403`은 token 유지·`forbidden` 계약을 기존 interceptor가 처리한다. | 통과 |
| 사용자/화면 | staff 예약 생성, admin 예약 상태 변경, management role redirect와 401/403 메시지를 Chromium에서 확인했다.                                                                 | 통과 |
| 테스트/운영 | 45개 unit test file/327개 test, TypeScript compile, production build, Chromium 10개가 fresh 실행에서 통과했다.                                                            | 통과 |
| 범위/언어   | 변경 파일에 Kotlin이 없고, Korean-only 문서·GitHub artifact 정책을 유지했다.                                                                                              | 통과 |

## 검증 증거

- `npx ng test --watch=false --progress=false`: **45개 파일 / 327개 테스트 통과**
- `npx tsc --noEmit -p tsconfig.app.json`: **통과**
- `npm run build`: **성공**
- `npm run test:e2e`: **10개 Chromium 시나리오 통과**
- `git diff --check`: **통과**
- `git diff --name-only | rg '\\.kt$'`: **결과 없음**

## 잔여 위험과 경계

실제 Gateway가 page load 전에 전역 handoff를 설정하는 배포 코드는 이 저장소에 없다. 이는 backend login endpoint를 새로 만들지 않는 기존 설계의 외부 host 책임이며, E2E가 동일한 browser injection 경계를 재현한다. host가 handoff를 설정하지 않으면 앱은 인증되지 않은 상태로 시작하고 management route는 `/calendar`로 이동한다.

기존 Gradle frontend task의 Node archive dependency verification metadata 누락은 이 TypeScript 변경으로 해결하지 않는다. npm 기반 Angular test/build/E2E는 통과했으며 dependency를 추가하거나 `npm audit fix`를 실행하지 않았다.

## 결론

현재 구현과 검증 범위에서 P0/P1 blocker는 없다. Issue #413은 PR/CI와 exact-head 확인 후 merge-ready이며, merge 뒤 child issue를 닫고 parent #295의 완료 근거를 갱신할 수 있다.

**판정: MERGE-READY (최신 head 승인 대기)**

## 문서 게이트

- SPW-01: review 범위·독자·근거 경로·Issue/PR 계약을 고정했다. **PASS**
- SPW-02: 범위, 심각도, 위치/근거, 처분, 잔여 위험, verdict를 포함했다. **PASS**
- SPW-03: Korean technical register와 exact identifier 보존을 확인했다. **PASS**
- SPW-04: 코드·테스트·build·E2E·diff 결과를 현재 worktree와 대조했다. **PASS**
- SPW-05: Markdown read-back 및 `git diff --check`를 완료했다. **PASS**
- KO-01~07: 사실·용어·명령·식별자·reader-facing 표면을 검토하고 terminology audit 대상 충돌이 없음을 확인했다. **PASS**
