# 환자 임상 포털 디자인 계약

## 결정 상태

- 상태: 승인된 C 방향을 구현 계약으로 고정
- 범위: `frontend/appointment-frontend` 안의 `/portal` lazy route와 공유 API 계층
- 대상: 환자 예약 현황, 알림, 내 정보
- 언어: 저장소 규칙에 따라 화면 문구와 문서는 한국어

## 구조 경계

기존 staff 화면의 `/calendar`, `/appointments`, `/management` route와 환자 포털의
`/portal` route를 분리한다. 하나의 Angular 22 standalone workspace를 재사용하되,
환자 포털 feature가 staff service의 raw URL이나 권한 가정을 재사용하지 않도록
`features/patient-portal` 아래에 shell·상태 reducer·화면을 둔다.

모든 환자 요청은 `core/api/portal-api-client.ts`를 통과한다. 이 client만
`/api/{tenantCode}` 경로와 precondition header를 조립하며, component와 facade는
typed command/query만 호출한다.

## 시각 언어

- 저채도 surface와 얇은 구조선을 기본으로 하고, 컨테이너가 장식보다 정보 순서를
  드러내도록 한다.
- 상태 색은 `제안`, `선점`, `확정`, `만료`, `재배정 필요`에만 제한적으로 사용한다.
  색상 외에 상태명, 시간, 다음 행동을 함께 렌더링한다.
- native `button`, `a`, `input`, `select`를 우선 사용하고, 아이콘만 있는 조작은
  `aria-label`을 제공한다.
- 예약 카드의 첫 줄은 상품명이 있을 때 상품명, 없을 때 예약 상태/일정이다.
  회차가 있으면 상태 옆에 `3회차 / 10회`를 표시하고 전체 회차가 없으면 `3회차`만
  표시한다. 상품이 없을 때 빈 제목 슬롯을 만들지 않는다.

## 토큰

```scss
:root {
  --portal-surface: light-dark(#f5f6f8, #17191d);
  --portal-surface-raised: light-dark(#ffffff, #202329);
  --portal-ink: light-dark(#20242a, #f0f2f4);
  --portal-muted: light-dark(#69717d, #a7afbb);
  --portal-line: light-dark(#d9dde3, #3a404a);
  --portal-focus: #4f7cff;
  --portal-status-proposed: #6b7ca8;
  --portal-status-held: #8b6f47;
  --portal-status-confirmed: #3e7b63;
  --portal-status-expired: #8a5960;
  --portal-status-reschedule: #765fa3;
}
```

`light-dark()`를 지원하지 않는 환경에서는 light 값을 fallback으로 두고
`prefers-color-scheme: dark`에서 같은 의미의 dark surface를 덮어쓴다. 전역
Angular Material 색상 토큰과 이름을 섞지 않고 `portal-` 접두사로 격리한다.

## 상태와 데이터 계약

`AppointmentCommitmentStatus`는 `PROPOSED`, `HELD`, `CONFIRMED`, `EXPIRED`,
`CANCELLED`를 그대로 typed union으로 보존한다. `PortalApiClient`는 다음 헤더를
매번 명시적으로 다룬다.

| 동작 | 요청 헤더 | 응답 헤더/의미 |
| --- | --- | --- |
| 최초 요청 | `Idempotency-Key`, `If-None-Match: *` | `ETag`가 있으면 다음 read의 기준 |
| commitment 조회 | 없음 | `ETag`를 facade가 저장 |
| proposal 결정 | `Idempotency-Key`, `If-Match` | 새 `ETag`와 commitment projection |
| 일시 장애 | 기존 idempotency key 재사용 | `Retry-After`를 backoff 힌트로 노출 |

HTTP 상태와 `SchedulingApiErrorResponse.errorCode`는 `PortalApiErrorState`로
정규화한다. 401/403은 세션 경계, 409/412는 최신 상태 재조회, 410은 만료,
422/428은 사용자가 보완할 동의/조건, 503은 재시도 가능한 일시 상태로 표현한다.

## 반응형·접근성 계약

- 320px, 736px, 1024px 세 폭에서 가로 overflow가 없어야 한다.
- shell nav는 키보드 순서가 DOM 순서와 일치하고, active route는 텍스트와
  `aria-current="page"`로 표현한다.
- 상태 변경은 `aria-live="polite"` 영역에 요약을 쓰고 색상만으로 알리지 않는다.
- `:focus-visible` outline은 `--portal-focus`를 사용한다.
- `@media (prefers-reduced-motion: reduce)`에서 transition/animation을 제거한다.

## 제외와 대안

- 별도 `frontend/appointment-patient-portal` Angular 앱은 이번 단계에서 만들지
  않는다. 현재 workspace가 단일 application이고 route lazy-loading으로 배포 경계와
  권한 경계를 충분히 분리할 수 있어 중복 toolchain을 피한다.
- React/Vue로 교체하지 않는다. 기존 Angular 22 workspace, CI, Material 의존성,
  route guard를 유지하는 것이 변경 위험이 가장 낮다.
- 실제 운영 push provider와 native Capacitor shell은 이번 포털 web 범위 밖이다.
