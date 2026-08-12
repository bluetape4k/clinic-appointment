# Issue #32 환자 포털 tenant 컨텍스트 7-tier code review

검토일: 2026-08-12
검토 브랜치: `fix/issue-32-tenant-context`
검토 범위: `frontend/appointment-frontend`의 환자 로그인·회원가입 화면과 회귀 테스트

## 결론

**코드 결함 기준 PASS — P0 0건, P1 0건, P2 0건, P3 0건.**

테스트 fixture의 `tenant-default`가 런타임 환자 인증 화면의 기본값으로 새던 경로를
제거했다. 저장된 tenant scope가 없으면 두 화면 모두 빈 값을 유지하므로, 기존 required
검증이 명시적인 tenant 입력을 요구한다.

## Seven-tier 결과

| tier | 검토 관점 | 결과 | P0/P1/P2/P3 | 근거 |
|---|---|---|---:|---|
| 1 | 요구사항·계약 | tenant context를 코드에 하드코딩하지 않고 저장된 scope 또는 사용자 입력을 사용한다. | 0/0/0/0 | Issue #32, 두 patient auth page |
| 2 | 구조·데이터 | 변경은 두 화면의 초기 signal과 해당 회귀 테스트에 한정되며 API/저장소 계약은 건드리지 않는다. | 0/0/0/0 | `patient-login-page.component.ts`, `patient-register-page.component.ts` |
| 3 | 보안·프라이버시 | 누락 tenant를 다른 tenant로 대체하지 않으므로 잘못된 scope로 인증 요청을 보내는 위험을 줄인다. | 0/0/0/0 | tenant context 경계, required form validation |
| 4 | 정확성·동시성 | 저장된 tenant가 있으면 기존 흐름을 유지하고, 없을 때만 빈 상태를 명시한다. | 0/0/0/0 | patient auth component specs |
| 5 | API·frontend UX | tenant 입력 필드가 빈 상태로 표시되어 사용자가 실제 scope를 선택·입력할 수 있다. | 0/0/0/0 | Angular component tests, Playwright portal flow |
| 6 | 운영·관측성 | 운영 tenant 기본값을 새로 만들지 않고 E2E fixture 경계만 유지한다. 추가 운영 설정 변경은 없다. | 0/0/0/0 | `tenant-default` 정적 검색, E2E fixture |
| 7 | 테스트·빌드·전달 | RED 회귀 재현 후 targeted/full unit, Angular build, Gradle module build/test, E2E를 통과했다. | 0/0/0/0 | 아래 검증 기록 |

## `bluetape-kotlin-patterns` 대조

이번 변경은 Kotlin 소스를 수정하지 않았으므로 Exposed transaction·Kotlin data class·KDoc
규칙은 적용 대상이 아니다. TypeScript 변경은 기존 Angular signal과 테스트 구조를
재사용하고, 새 의존성이나 새 추상화를 추가하지 않았다.

## 검증 기록

```text
RED: login/register tenant 기본값 회귀 2건 실패
GREEN: patient-auth-pages.component.spec.ts — 4 tests passed
전체 frontend unit — 37 files, 227 tests passed
npm run build — Angular bundle generation complete
npm run test:e2e -- --reporter=line — 3 passed
./gradlew --no-daemon :frontend:appointment-frontend:build — BUILD SUCCESSFUL
./gradlew --no-daemon :frontend:appointment-frontend:test — BUILD SUCCESSFUL
git diff --check — 통과
```

## 후속 delivery gate

- [ ] PR exact head에서 GitHub 필수 검사가 통과했는지 확인한다.
- [ ] PR body의 issue/milestone/assignee/labels와 마지막 `## DoD Status`를 live GitHub에서 확인한다.
- [ ] CI와 mergeability를 확인한 뒤에만 별도의 fresh merge approval을 요청한다.
