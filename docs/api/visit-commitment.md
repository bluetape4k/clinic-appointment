# 예약 Commitment v2 API

## 경계와 인증

예약서비스는 상품·구매·시술 완료·환불 사실의 소유자가 아니다. 상품 BOM과 구매
당시 version을 예약 생성 시 immutable 실행 snapshot으로 보관하고, 이후 변경은
신뢰된 broker event로만 받는다.

Public API는 API Gateway가 검증한 JWT principal을 사용한다. request body에서 actor,
tenant, clinic, patient subject, 정책 mode, 자원 mapping을 받지 않는다. 고객 요청은
항상 가예약에서 시작하고, 관리자 직접 확정도 유효 병원 정책과 동의 규칙을 통과해야
한다.

## 상태와 변경 규칙

- `PROPOSED`: 자원을 점유하지 않는 가예약 제안이다.
- `HELD`: 정책이 허용한 제한 시간 동안 자원을 선점한 제안이다.
- `CONFIRMED`: 고객 동의와 관리자 승인 등 필요한 조건을 모두 충족한 확정 예약이다.
- 확정 예약 변경은 새 proposal과 새 고객 동의가 완료되기 전까지 기존 확정과 점유를
  유지한다.
- 선행 진료 완료 event가 들어오면 완료 항목은 불변으로 두고 `BLOCKING` 후속 항목만
  dirty-set으로 재계산한다.
- 장비 고장이나 하루 내 일부 진료 미이행은 남은 진료를 새 방문으로 분리한다.
- 추가 상품 구매는 기존 Plan에 합치지 않고 새 Plan을 만든다.

## Endpoint

| Actor | Method / path | 성공 | 업무 결과 |
|---|---|---:|---|
| 고객 | `POST /api/v2/appointment-requests` | 202 | `PROPOSED` 가예약과 proposal 생성 |
| 관리자 | `POST /api/v2/admin/appointments` | 201 | 정책이 허용하면 직접 확정 |
| 관리자 | `POST /api/v2/appointments/{id}/approve` | 200 | 고객이 동의한 정확한 proposal 승인 |
| 고객 | `POST /api/v2/appointments/{id}/proposals/{proposalId}/accept` | 200 | 현재 proposal 수락 |
| 고객 | `POST /api/v2/appointments/{id}/proposals/{proposalId}/decline` | 200 | 기존 확정을 보존하며 proposal 거절 |
| 관리자 | `POST /api/v2/appointments/{id}/confirm` | 200 | 정책·동의 확인 후 확정 |
| 관리자 | `POST /api/v2/appointments/{id}/change-proposals` | 202 | 기존 확정을 보존한 대체 proposal 생성 |
| 고객·관리자 | `GET /api/v2/appointments/{id}/commitment` | 200 | actor 범위 commitment projection 조회 |

모든 mutation은 `Idempotency-Key`를 요구한다. 신규 생성은
`If-None-Match: *`, 기존 aggregate 변경은 최신 `ETag`를 담은 `If-Match`를
추가로 요구한다. 동의 payload는 원문이 아니라 `evidenceAuthority`와 추측하기
어려운 opaque `evidenceId`만 전달한다.

## 배포 설정

`AppointmentCommitmentProperties`의 기본 mode는 `OFF`다.

| 설정 | 기본값 | 의미 |
|---|---:|---|
| `appointment.commitment.mode` | `OFF` | `OFF`, 계산 비교만 하는 `SHADOW`, allowlist write인 `WRITE` |
| `appointment.commitment.clinic-allowlist` | 비어 있음 | `WRITE`가 실제 적용될 병원 ID |
| `appointment.commitment.proposal-ttl` | `30m` | proposal 승인 대기 만료 |
| `appointment.commitment.retry.max-attempts` | `3` | 최초 시도를 포함한 제한 재시도 |
| `appointment.commitment.retry.initial-backoff` | `25ms` | 첫 충돌 재시도 대기 |

동기 계획 상한은 treatment 500개, 관계 edge 4,000개, 반복 100회, 탐색 365일,
candidate slot 2,000개, 반환 proposal 20개다. 설정으로 승인값보다 낮출 수는 있지만
높일 수 없다.

`api-enabled`는 v2 row가 전혀 없는 bootstrap에서만 전체 route 노출을 제어한다.
v2 row 생성 후 rollback은 신규 유입만 차단하고 기존 v2 query/mutation을 유지한다.
구체적인 절차는 [운영 런북](../runbooks/visit-commitment-operations.md)을 따른다.

## 안정 오류

| HTTP | `errorCode` 예 | Caller 조치 |
|---:|---|---|
| 400 | `PAYLOAD_INVALID` | OpenAPI schema에 맞게 수정 |
| 403 | `SCOPE_MISMATCH`, `SCOPE_FORBIDDEN` | 정확한 clinic-scoped Gateway token 사용 |
| 409 | `RESOURCE_CONFLICT`, `PROPOSAL_NOT_CURRENT` | 최신 commitment를 읽고 새 proposal 요청 |
| 410 | `PROPOSAL_EXPIRED` | 새 proposal 요청 |
| 412 | `VERSION_CONFLICT` | 최신 `ETag`로 재시도 |
| 422 | `PLAN_LIMIT_EXCEEDED`, `CONSENT_REQUIRED` | Plan 또는 현재 동의 증거 수정 |
| 428 | `PRECONDITION_REQUIRED` | 생성은 `*`, 변경은 최신 `ETag` 전달 |
| 503 | `INGRESS_DISABLED` | 기존 예약은 유지하고 신규 요청만 보류 |

생성 OpenAPI 계약은 `AppointmentCommitmentOpenApiTest`가 실제 `/v3/api-docs`에서
경로, 필수 header, 성공·오류 응답을 검증한다.
