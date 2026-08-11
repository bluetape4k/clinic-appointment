# 환자 포털 설계 명세

## 목표

승인된 Codex visualize C 방향을 기존 Angular 22 workspace의 `/portal` route로
구현하고, backend가 이미 제공하는 tenant-scoped commitment contract를 환자용
예약·proposal·알림 UI에서 동일하게 사용한다.

## 근거와 권위

- GitHub issue #295–#299의 1.4.0 acceptance criteria
- `CustomerAppointmentController`, `AppointmentCommitmentQueryController`,
  `SlotController`의 현재 소스
- Angular 22 `frontend/appointment-frontend`의 standalone route/config 구조

백엔드 endpoint나 상품/회차 persistence는 수정하지 않는다. 상품/회차가 응답에
없을 때 UI는 빈 공간 대신 기존 예약 제목과 상태를 사용한다.

## 컴포넌트 경계

```text
App (/ staff shell)
└── /portal (PatientPortalShellComponent)
    ├── 예약 현황 (PatientAppointmentsPage)
    ├── 알림 (PatientNotificationsPage)
    └── 내 정보 (PatientProfilePage)

core/api
├── TenantContextService
├── PortalApiClient
├── PortalApiErrorMapper
└── PortalEventStreamAdapter (SSE -> polling fallback)

features/patient-portal
├── appointment-commitment.facade.ts
├── patient-portal-state.ts
├── appointment-card.component.*
└── patient-portal.routes.ts
```

`PatientPortalShellComponent`은 nav와 outlet만 소유한다. API URL, header, retry,
ETag를 shell이나 component에 두지 않는다. `AppointmentCommitmentFacade`는
요청→proposal→accept/decline의 명령과 상태를 signal로 제공한다.

## API 타입

```ts
export type CommitmentStatus = 'PROPOSED' | 'HELD' | 'CONFIRMED' | 'EXPIRED' | 'CANCELLED';

export interface AppointmentProposalResponse {
  appointmentId: number;
  commitmentId: number;
  proposalId: number;
  status: CommitmentStatus;
  version: number;
  expiresAt: string;
  policySnapshot: AppointmentPolicySnapshot;
}

export interface AppointmentCommitmentResponse {
  appointmentId: number;
  commitmentId: number;
  status: CommitmentStatus;
  version: number;
  currentProposal: AppointmentProposalSummary;
  confirmedProposalId: number | null;
  effectivePolicySnapshotId: number | null;
}

export interface PortalApiErrorState {
  kind: 'unauthorized' | 'forbidden' | 'conflict' | 'expired' | 'precondition' | 'retryable' | 'unknown';
  status: number;
  code: string;
  retryAfterSeconds: number | null;
  correlationId: string | null;
}
```

`CreateAppointmentRequestV2`는 `appointmentPlanId`, UTC `preferredStartAt`,
`preferredEndAt`, opaque `evidence`만 보낸다. actor/patient/tenant/clinic은 body에
넣지 않는다.

## 시각 계약

예약 카드의 DOM 순서는 `상품명(선택) → 날짜/시간 → 상태·회차 → 다음 행동`이다.
상품명이 없으면 첫 요소가 날짜/시간이며 `aria-label`도 같은 순서를 따른다.
회차 값은 `sessionNumber`가 있을 때만 렌더링하며, `totalSessions`가 null이면
`N회차`로 끝낸다. 이 규칙은 예약 목록·상세·알림의 공용 `AppointmentSummary`
formatter 하나로 공유한다.

## 실패·재동기화 계약

- create는 같은 `Idempotency-Key`와 `If-None-Match: *`를 재사용한다.
- accept/decline은 facade가 마지막 `ETag`를 `If-Match`로 보낸다.
- 412 또는 순서가 뒤바뀐 SSE event가 오면 event를 적용하지 않고 commitment를
  다시 읽는다.
- SSE 연결 실패는 지수 backoff 후 polling으로 전환하고, 탭 재진입 시 즉시 read를
  수행한다.
- 410은 `만료됨`과 새 슬롯 탐색 행동을 함께 표시한다. 503은 `Retry-After`를
  초 단위로 읽어 동일 intent retry를 예약한다.

## 품질 기준

API client 단위 테스트, facade/component 테스트, jsdom 브라우저 시나리오,
Playwright smoke(가능한 CI 환경)를 분리한다. `frontend-ci.yml`은 production
build만으로 성공하지 않도록 unit/contract job을 required check 대상으로 내보낸다.
