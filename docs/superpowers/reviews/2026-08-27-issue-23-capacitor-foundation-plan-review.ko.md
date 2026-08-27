# Issue #23 Capacitor foundation Step 3-R 계획 검토

## 검토 범위

- 검토 대상: `docs/superpowers/specs/2026-08-27-issue-23-capacitor-foundation-design.md`
  및 `docs/superpowers/plans/2026-08-27-issue-23-capacitor-foundation-plan.md`
- 기준 브랜치: `origin/develop`의
  `49a86b1f2bb4ea733795b5ad7c5d92551e510814`
- 작업 브랜치: `feat/issue-23-capacitor-foundation`
- 책임 범위: `frontend/appointment-frontend`의 Capacitor dependency, 설정,
  iOS·Android project, setup 문서와 검증 lesson
- 제외 범위: API origin, CORS, cookie·CSRF, native storage, PWA, UX 재작성,
  typed bridge와 실기기 검증

## 여섯 관점 검토

| 관점 | 근거와 판단 | 결과 |
|---|---|---|
| 성능 | Angular production build를 재사용하고 새 runtime client나 bridge를 추가하지 않는다. 기존 budget이 build에서 계속 적용되며, 별도 benchmark가 필요한 hot path는 이번 foundation에 없다. | P0=0, P1=0; N/A |
| 안정성 | `cap add`·`cap sync` 실패 경로, `webDir` 누락, SDK 부재와 재실행 경계를 계획에 명시했다. sync를 두 번 실행해 불필요한 변경을 확인하도록 보강했다. | P0=0, P1=0 |
| 보안 | `server.url`, cleartext, native storage와 plugin 설정을 넣지 않으며 기존 API·인증 계약을 변경하지 않는다. API origin·cookie·CSRF는 #430의 소유 범위로 고정되어 있다. | P0=0, P1=0 |
| 운영 | Node 22 기준, native toolchain 확인 명령, rollback·후속 이슈 경계와 Lore trailer를 계획했다. browser 검증을 native build 성공으로 과장하지 않는다. | P0=0, P1=0 |
| 개발자/API | dependency → config/platform → README → 회귀 검증 → lesson 순서가 생성물 의존성을 만족한다. `webDir`는 실제 Angular output과 일치하며 기존 route와 client를 재사용한다. | P0=0, P1=0 |
| 사용자/호출자 | 두 Korean README에 sync/open 명령, SDK 요구사항, #24·#430 경계를 기록한다. 지원하지 않는 native 기능은 문서에서 명시적으로 제외한다. | P0=0, P1=0 |

## Step 3-R 필수 점검

| 점검 | 증거 |
|---|---|
| spec → task 매핑 | spec 완료 조건 7개를 Task 1~5와 acceptance traceability 표에 연결했다. |
| 실행 순서 | dependency 설치와 lockfile 갱신 후 config, platform, 문서, 검증 순으로 배치했다. |
| 후속 산출물 의존성 | lesson과 review artifact는 구현·검증 결과 이후에 생성하며, platform asset은 `cap sync` 후 확인한다. |
| 테스트 경로 | build, unit, TypeScript, browser E2E, `git diff --check`, native toolchain과 sync 재실행을 명시했다. |
| 문서·locale | `README.md`, `README.ko.md`, lesson과 Korean review artifact를 포함했다. |
| rollback·호환성 | Angular 22·Node 22·Capacitor 8.5.0 고정과 실패별 재실행/후속 issue 경계를 기록했다. |
| 재사용 판단 | 별도 native UI·API client·인증 저장소를 만들지 않고 Angular route, `TenantApiClient`, 인증 상태와 기존 테스트를 재사용한다. |

## 통합 판정

초기 계획에서 sync 재실행 증거가 약했던 부분을 보강했으며, generated platform
project를 browser E2E로 대체하지 않는 경계도 명시했다. 이 slice는 기존
Angular bundle을 Capacitor가 소비하는 foundation에 한정되고, #430·#431·#26·#25·#27·#24의
후속 계약을 침범하지 않는다.

**PASS — P0=0, P1=0, P2=0, P3=0.**

## SPW-01~05

- **SPW-01 PASS:** spec·plan 경로, 기준 commit, 책임/제외 범위와 검토 근거를 기록했다.
- **SPW-02 PASS:** 여섯 관점, 필수 점검, severity와 통합 판정을 표로 완결했다.
- **SPW-03 PASS:** 저장소의 한국어 문서 정책을 따르고 code·command·identifier는
  원문을 보존했다.
- **SPW-04 PASS:** `webDir`, script, 후속 Issue 링크와 검증 명령을 실제 계획과
  대조했다.
- **SPW-05 PASS:** Markdown read-back, `git diff --check`, Korean terminology
  audit를 계획의 Task 3에 포함했다.
