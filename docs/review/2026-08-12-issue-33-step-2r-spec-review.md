# Issue #33 환자 인증 명세 2-R 검토

## 검토 범위

- 명세: `docs/superpowers/specs/2026-08-12-issue-33-patient-authentication-design.md`
- 이슈: [#33](https://github.com/bluetape4k/clinic-appointment/issues/33)
- 대상: `appointment-api`, `appointment-core`, `frontend/appointment-frontend`
- 기준: `$bluetape-kotlin-patterns`, Type-A Full Feature, 7-tier 명세 검토
- 검토일: 2026-08-12

명세 승인 후 구현 전에 여섯 관점과 통합 관점으로 다시 읽었다. 이번 기록은
명세의 경계·대안·실패 모드·검증 가능성을 평가하며, 구현 diff의 품질을
소급 증명하지 않는다.

## 여섯 관점

| 관점 | 판정 | P0/P1 | 근거 및 조치 |
| --- | --- | ---: | --- |
| 성능 | 통과 | 0/0 | login은 identity 1회 조회와 password hash 1회 검증으로 bounded된다. 계정 식별자 최대 3개, request/claim collection 상한, 무제한 in-memory rate map 금지가 명시됐다. |
| 안정성 | 통과 | 0/0 | 계정·식별자 생성의 단일 transaction, additive migration, cookie expiry/clear, bearer 우선순위, stale session 401을 명세했다. |
| 보안·개인정보 | 통과 | 0/0 | HttpOnly/Secure/SameSite cookie, `csrf.spa()` 기반 CSRF double-submit과 login/logout 후 token refresh, tenant path authorization, opaque subject, raw credential·PII 비로그, dummy hash timing 완화, PATIENT token의 `nbf` 발급과 미래 시각 거부를 명시했다. |
| 운영 | 통과 | 0/0 | migration V26, rollback 순서, low-cardinality metrics, protected profile의 limiter fail-closed, production OTP/verified-member prerequisite를 명시했다. |
| 개발자/API | 통과 | 0/0 | key/value enum·정규화, endpoint/status/body 계약, Exposed table/repository 소유권, 기존 bearer 호환, Angular mode 전환을 구체화했다. |
| 사용자·호출자 | 통과 | 0/0 | 세 식별자 연결, 로그인 실패 generic error, session expiry/tenant mismatch 안내, returnUrl, keyboard/focus/aria-live 규칙을 명시했다. |

## 통합 판단

명세에는 구현을 막는 P0/P1이 없다. 특히 다음 위험을 설계 단계에서 닫았다.

1. 기존 workforce token의 `nbf` optional 호환성을 유지하면서 PATIENT issuer에는
   `nbf=iat`를 강제하고, 제공된 미래 `nbf`는 계속 거부하도록 issue의 만료/미발효
   token 조건과 연결했다.
2. public auth endpoint에서 기존 cookie principal이 다른 tenant라는 이유로
   회원가입·로그인이 조기 거절되지 않도록 `TenantContextFilter`의 처리 순서를
   명시했다. 활성 tenant 검증은 별도로 유지한다.
3. identity 미존재 login에도 dummy hash 검증을 수행하도록 하여 단순한 user
   enumeration timing 차이를 줄였다.
4. production rate limiting을 무제한 local map으로 가장하지 않고 limiter port와
   protected profile fail-closed 계약으로 분리했다.

## P2 및 후속 처리

| 항목 | 처리 | 근거 |
| --- | --- | --- |
| 전화번호/이메일 소유권 검증 | 명세 제외, production prerequisite로 기록 | OTP·외부 verified-member evidence가 없는 예제 credential은 production assurance가 아니다. |
| password reset/recovery | 별도 이슈로 분리 | 세션 발급과 credential 복구를 한 migration/API로 섞지 않는다. |
| 기존 예약 `MemberId`와 신규 `patientSubject` 자동 연결 | 명세 제외 | 인증 경계와 기존 회원 디렉터리 소유권을 분리하고, 연결은 외부 authority가 제공할 때 별도 설계한다. |
| 정규화 국가 규칙 | 3-R 계획에서 순수 함수와 경계 test로 고정 | 한국 전화번호 및 `+82` canonicalization을 구현 시 실제 fixture로 증명한다. |

## 판정

**Step 2-R: PASS — P0=0, P1=0.**

다음 단계는 이 명세의 모든 수용 기준과 위험 완화를 파일·테스트 단위로
분해한 Step 3 구현 계획이다. 계획에는 PATIENT issuer `nbf` 발급·미래 거부,
public tenant resolver, dummy hash, limiter port, migration, frontend cookie mode 및
각 module-scoped 검증 명령을 반드시 포함해야 한다.
