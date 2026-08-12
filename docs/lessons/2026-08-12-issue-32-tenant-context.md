# Issue #32 환자 포털 tenant 컨텍스트 lesson

## 발견

환자 로그인·회원가입 페이지가 `TenantContextService`에 저장된 tenant가 없을 때
`tenant-default`를 런타임 기본값으로 주입하고 있었다. 이 값은 Playwright E2E fixture에
필요한 테스트 좌표였지만, 실제 화면의 인증 요청에도 전파되어 tenant 선택 누락을
성공한 것처럼 보이게 만들 수 있었다.

## 결정

- 런타임 login/register form은 저장된 tenant가 없으면 빈 값을 유지한다.
- tenant가 없는 상태는 기존 required validation이 사용자 입력을 요구하도록 둔다.
- `tenant-default`는 명시적인 E2E fixture에서만 사용하며 production source에는 두지 않는다.
- sessionStorage 복원 값은 `TenantContextService`의 검증된 scope만 사용하고, 페이지가
  자체적인 tenant 기본값을 만들지 않는다.

## 검증

- RED: 기존 구현에서 두 회귀 테스트가 빈 문자열 대신 `tenant-default`를 받아 실패했다.
- GREEN: `patient-auth-pages.component.spec.ts` 4건 통과.
- 전체 frontend unit: 37 files, 227 tests 통과.
- Angular build 및 `:frontend:appointment-frontend:build` 통과.
- `:frontend:appointment-frontend:test` 통과.
- Playwright E2E: 3개 통과.
- `tenant-default` 정적 검색 결과는 E2E fixture에만 남았다.

## 재발 방지

새 portal 인증 화면은 테스트 tenant나 운영 tenant를 코드에 하드코딩하지 말고,
검증된 tenant context 또는 명시적인 사용자 입력을 사용해야 한다. E2E에서 기본 tenant가
필요하면 fixture 경계에서만 값을 채우고, 화면 컴포넌트의 초기 상태에는 주입하지 않는다.
