# Issue #251 frontend JWT 세션 경계와 claim 검증

## 발견

`AuthService`가 JWT를 `localStorage`의 `auth_token` 키에 보관해 브라우저 영속
저장소가 인증 세션의 권위 있는 원천이 되었다. 토큰 payload를 JSON으로 읽기만 하고
`exp`와 `nbf`를 검증하지 않아 만료 토큰과 아직 유효하지 않은 토큰을 그대로
인증 상태로 만들 수 있었다. HTTP interceptor 외부에서 사용하는 SSE `fetch` 경로도
401 응답 뒤 세션을 정리하지 않았다.

## 결정

활성 bearer JWT는 `AuthService` 인스턴스의 메모리에만 보관한다. `setToken`은
base64url payload를 해석한 뒤 유한한 숫자형 `exp`를 요구하고, 현재 시각이 `exp`에
도달했거나 선택적 `nbf`보다 이르면 토큰을 거부한다. 잘못된 토큰은 즉시 제거해
인증 signal을 비운다.

기존 버전이 남긴 `localStorage`와 `sessionStorage`의 `auth_token`은 서비스 생성 시
삭제한다. 새 토큰은 두 저장소에 다시 기록하지 않는다. 일반 HTTP 401과 SSE 401은
같은 `removeToken()` 경계를 사용해 인증 상태를 함께 정리한다.

이번 변경은 기존 Gateway bearer JWT 소비 계약을 유지하는 frontend 범위의
최소 수정이다. 브라우저에서 읽을 수 있는 메모리 토큰 자체를 제거하려면 #33에서
HttpOnly cookie/BFF 발급과 backend cookie 계약을 별도로 설계해야 한다.

## 검증

- RED: 변경 전 targeted 회귀 세트에서 storage·`exp`·`nbf`·401 경계 5건 실패
- GREEN: `auth.service.spec.ts`, `error.interceptor.spec.ts`,
  `reschedule.service.spec.ts` targeted 38 tests 통과
- legacy storage cleanup: `auth.service.spec.ts` 22 tests 통과
- 최종 전체 테스트: 33 files, 214 tests 통과
- `npm run build`: Angular bundle generation 통과
- `npm run test:e2e -- --reporter=line`: 3 tests 통과
- `actionlint .github/workflows/frontend-ci.yml` 및 `git diff --check` 통과

## 후속 규칙

새 인증 소비 경로는 토큰을 영속 저장소에 기록하지 말고 `AuthService`의 단일
메모리 경계와 `removeToken()`을 재사용한다. 401을 직접 처리하는 `fetch`·SSE·WebSocket
경로를 추가할 때는 interceptor와 동일한 세션 무효화 회귀 테스트를 함께 작성한다.
