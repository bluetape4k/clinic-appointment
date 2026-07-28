# Scheduling Policy API

이 문서는 병원 예약 정책 foundation의 HTTP 계약을 정리한다. 정책 API는 상품,
구매, 시술 완료, 환불을 직접 처리하지 않는다. 예약 결정에 필요한 tenant baseline
정책과 clinic override 정책을 버전 관리하고, 영향도 미리보기와 활성화 증거를 남긴다.

## 신뢰 경계

모든 경로는 `/api/{tenantCode}/...` 아래에 있다. `tenantCode`와 `clinicId`는
request body가 아니라 path와 권위 데이터베이스에서 확인한다.

actor 정보도 request body에서 받지 않는다. API Gateway가 검증한 JWT principal을
`ActorContextResolver`가 `ActorContext`로 바꾼다. 정책 명령은 다음 값을 신뢰된
principal에서만 사용한다.

| 필드 | 출처 | 용도 |
|---|---|---|
| `actorId` | Gateway subject | 감사 subject |
| `actorType` | Gateway `actorType` claim | `ADMIN`, `STAFF`, `PATIENT`, `SYSTEM` 분리 |
| `roles` | Gateway role claim | 승인자 분리와 role 검증 |
| `scopes` | Gateway capability claim | 사람 운영자의 HTTP 관리 경로는 `policy:write` 권한 |
| `allowedTenantCodes` | Gateway tenant allow-list | path tenant membership |
| `allowedClinicIds` | Gateway clinic allow-list | clinic override membership |
| `patientSubjectId` | patient actor claim | 고객 actor 식별 |
| `assurance` | Gateway auth evidence | MFA 승인, service actor 검증 |
| `issuer`, `tokenId`, `authenticatedAt` | JWT evidence | 감사와 불완전 인증 거절 |
| `correlationId` | correlation filter | 응답 추적 ID |

정책 request DTO는 선언되지 않은 JSON 필드를 fail-closed로 거절한다. `actor`,
`tenant`, `clinic`, `assurance`, token evidence를 body에 넣어도 권한 상승으로
사용하지 않는다.

`policy:scheduled-activation`은 Gateway를 통과하는 사람 운영자 권한이 아니다.
내부 activation worker가 `ActorType.SYSTEM`, `ActorRole.SYSTEM`,
service assurance와 함께 사용하는 전용 capability다. 외부 JWT에 이 scope를
부여해도 관리 API 호출 권한을 대신하지 않는다.

## Feature Flags

모든 scheduling-policy 기능은 기본값이 `false`다. 운영자는 아래 순서를 지켜야 한다.
후행 flag가 선행 flag 없이 켜지면 애플리케이션 시작이 실패한다.

| 순서 | Property | 노출되는 기능 |
|---:|---|---|
| 1 | `scheduling.policy.shadow-compile-enabled` | 활성 예약에는 적용하지 않고 컴파일만 검증 |
| 2 | `scheduling.policy.effective-read-enabled` | tenant/clinic effective snapshot 조회 |
| 3 | `scheduling.policy.admin-write-enabled` | draft, validate, approve, activate 관리 명령 |
| 4 | `scheduling.policy.preview-worker-enabled` | durable 영향도 preview worker |
| 5 | `scheduling.policy.scheduled-activation-enabled` | due activation command worker |

이 foundation에는 booking consumer flag가 없다. 예약 생성 경로가 새 정책을 소비하는
단계는 별도 변경으로 추가해야 한다.

## Policy Scopes

| Scope | Base path | 의미 |
|---|---|---|
| Tenant baseline | `/api/{tenantCode}/admin/scheduling-policies` | tenant group 전체가 공유하는 baseline |
| Clinic override | `/api/{tenantCode}/admin/clinics/{clinicId}/scheduling-policies` | 특정 clinic의 partial override |

clinic route는 path의 `clinicId`가 tenant에 속하는지 데이터베이스로 검증하고, 같은
clinic이 Gateway principal allow-list에 있는지도 다시 확인한다.

## Lifecycle

정책 definition payload는 immutable이다. 변경은 기존 row를 수정하지 않고 새 draft
revision과 activation으로 표현한다. 허용 전이는 단일 선형 경로가 아니라 다음 집합이다.

```text
DRAFT -> SCHEDULED | ACTIVE | RETIRED
SCHEDULED -> ACTIVE | RETIRED
ACTIVE -> RETIRED
```

관리 흐름은 다음 순서로 호출한다.

1. draft 생성
2. draft 검증
3. 영향도 preview 제출
4. preview 완료 polling
5. preview evidence로 승인
6. 즉시 활성화 또는 미래 활성화 예약
7. 필요하면 retire 또는 missed command replay

## Tenant Endpoints

| Method | Path | 정상 응답 | 설명 |
|---|---|---|---|
| `POST` | `/drafts` | `201` | tenant baseline draft 생성 |
| `POST` | `/{id}/validate` | `200` | 현재 draft revision 검증 |
| `POST` | `/{id}/preview` | `200` 또는 `202` | bounded 영향도 preview 제출 |
| `POST` | `/{id}/approve` | `200` | 완료 preview evidence로 승인 |
| `POST` | `/{id}/schedule` | `202` | future activation command 생성 |
| `POST` | `/{id}/activate` | `200` | 즉시 activation 실행 |
| `POST` | `/{id}/retire` | `200` | definition 이력 보존 retire |
| `POST` | `/activation-commands/{commandId}/replay` | `200` | `MISSED` command 수동 replay |
| `GET` | `/effective?decisionAt=...&serviceAt=...` | `200` | tenant baseline effective snapshot 조회 |
| `GET` | `/preview-jobs/{jobId}` | `200` | tenant preview job polling |

## Clinic Endpoints

clinic endpoint는 tenant endpoint와 같은 동사를 사용하지만 base path가 다음과 같다.

```text
/api/{tenantCode}/admin/clinics/{clinicId}/scheduling-policies
```

preview job polling도 clinic scope를 path에 포함한다.

```text
GET /api/{tenantCode}/admin/clinics/{clinicId}/scheduling-policies/preview-jobs/{jobId}
```

## Request Shapes

### Draft 생성

`kind`는 닫힌 `SchedulingPolicyKind` 이름 중 하나다. `schemaVersion`은 현재 `1`만
허용한다. `payload`는 `kind`와 scope에 맞는 strict JSON이어야 한다.

<!-- booking-draft-example:start -->
```json
{
  "kind": "BOOKING_COMMITMENT",
  "schemaVersion": 1,
  "effectiveFrom": "2026-08-01T00:00:00Z",
  "effectiveUntil": null,
  "payload": {
    "adminBookingMode": "DIRECT_CONFIRM_WITH_CONSENT_EVIDENCE",
    "patientBookingMode": "PROVISIONAL_APPROVAL_REQUIRED",
    "provisionalCapacityMode": "SOFT_HOLD",
    "provisionalRequestTtlSeconds": 7200,
    "resourceHoldTtlSeconds": null,
    "approvalRoles": ["ADMIN", "STAFF"],
    "adminConsentEvidence": {
      "allowedEvidenceTypes": ["SIGNED_FORM", "VERBAL_RECORDING"],
      "maximumAgeSeconds": 2592000,
      "termsHashRequired": true
    },
    "confirmedChangeMode": "NEW_PROPOSAL_AND_CUSTOMER_CONSENT"
  },
  "expectedScopeRevision": 0,
  "changeReason": "Initial tenant booking policy"
}
```
<!-- booking-draft-example:end -->

### Admin 예약 정책 예시

관리자가 고객 대신 예약을 바로 확정하려면 정책상
`DIRECT_CONFIRM_WITH_CONSENT_EVIDENCE`와 신선한 외부 동의 증빙이 필요하다. 이 API는
동의 원문을 저장하지 않고, 정책에는 허용 증빙 유형과 최대 연령만 저장한다.

<!-- booking-draft-example:start -->
```json
{
  "kind": "BOOKING_COMMITMENT",
  "schemaVersion": 1,
  "effectiveFrom": "2026-08-01T00:00:00Z",
  "effectiveUntil": null,
  "payload": {
    "adminBookingMode": "DIRECT_CONFIRM_WITH_CONSENT_EVIDENCE",
    "patientBookingMode": "PROVISIONAL_APPROVAL_REQUIRED",
    "provisionalCapacityMode": "NO_HOLD",
    "provisionalRequestTtlSeconds": 3600,
    "resourceHoldTtlSeconds": null,
    "approvalRoles": ["ADMIN"],
    "adminConsentEvidence": {
      "allowedEvidenceTypes": ["SIGNED_FORM"],
      "maximumAgeSeconds": 1209600,
      "termsHashRequired": true
    },
    "confirmedChangeMode": "NEW_PROPOSAL_AND_CUSTOMER_CONSENT"
  },
  "expectedScopeRevision": 3,
  "changeReason": "Require signed consent before admin direct confirmation"
}
```
<!-- booking-draft-example:end -->

### Customer 예약 정책 예시

고객이 직접 등록하는 예약은 먼저 가예약 요청으로 들어오고, 권한 있는 병원 담당자가
승인한 뒤 확정된다. 확정 예약 변경도 고객 동의 없이 조용히 이동하지 않는다.

<!-- booking-draft-example:start -->
```json
{
  "kind": "BOOKING_COMMITMENT",
  "schemaVersion": 1,
  "effectiveFrom": "2026-08-01T00:00:00Z",
  "effectiveUntil": null,
  "payload": {
    "adminBookingMode": "DIRECT_CONFIRM_WITH_CONSENT_EVIDENCE",
    "patientBookingMode": "PROVISIONAL_APPROVAL_REQUIRED",
    "provisionalCapacityMode": "HARD_HOLD",
    "provisionalRequestTtlSeconds": 1800,
    "resourceHoldTtlSeconds": 600,
    "approvalRoles": ["ADMIN", "STAFF"],
    "adminConsentEvidence": {
      "allowedEvidenceTypes": ["SIGNED_FORM", "VERBAL_RECORDING"],
      "maximumAgeSeconds": 2592000,
      "termsHashRequired": true
    },
    "confirmedChangeMode": "NEW_PROPOSAL_AND_CUSTOMER_CONSENT"
  },
  "expectedScopeRevision": 3,
  "changeReason": "Use short hard holds for patient-originated requests"
}
```
<!-- booking-draft-example:end -->

### 후속 mutation 요청

아래 예시는 draft ID `41`, draft revision `4`, 현재 active scope revision `3`,
tenant generation `7`을 기준으로 한다. clinic route라면
`clinicGeneration`도 caller가 직전에 읽은 실제 값으로 고정해야 한다.

검증:

```json
{
  "expectedDraftRevision": 4
}
```

영향도 preview:

```json
{
  "expectedDraftRevision": 4,
  "expectedGeneration": {
    "tenantGeneration": 7,
    "clinicGeneration": 0
  }
}
```

완료된 preview evidence로 승인:

```json
{
  "expectedDraftRevision": 4,
  "previewEvidenceToken": "preview-evidence-opaque-token",
  "changeReason": "Reviewed impact and approved rollout"
}
```

미래 활성화 예약:

```json
{
  "expectedDraftRevision": 4,
  "expectedActiveRevision": 3,
  "expectedGeneration": {
    "tenantGeneration": 7,
    "clinicGeneration": 0
  },
  "previewEvidenceToken": "preview-evidence-opaque-token",
  "effectiveFrom": "2026-08-01T00:00:00Z",
  "changeReason": "Activate at the August operating boundary"
}
```

즉시 활성화는 같은 body를 사용하되 `effectiveFrom`을 보내지 않고,
`Idempotency-Key` header를 함께 보낸다.

```json
{
  "expectedDraftRevision": 4,
  "expectedActiveRevision": 3,
  "expectedGeneration": {
    "tenantGeneration": 7,
    "clinicGeneration": 0
  },
  "previewEvidenceToken": "preview-evidence-opaque-token",
  "changeReason": "Activate after final operational approval"
}
```

퇴역:

```json
{
  "expectedActiveRevision": 4,
  "expectedGeneration": {
    "tenantGeneration": 8,
    "clinicGeneration": 0
  },
  "changeReason": "Replace with the next reviewed policy revision"
}
```

`MISSED` activation command replay는 원본 command ID를 path에 넣고 fresh
`Idempotency-Key` header를 사용한다.

```json
{
  "expectedGeneration": {
    "tenantGeneration": 8,
    "clinicGeneration": 0
  },
  "changeReason": "Replay after resolving the operational outage"
}
```

## Preview와 Polling

`POST /{id}/preview`는 같은 API에서 동기 완료와 비동기 접수를 모두 표현한다.

| 응답 | 의미 | Caller 동작 |
|---|---|---|
| `200` | preview가 동기 예산 안에서 완료됨 | `activationEvidenceToken`을 승인/활성화에 사용 |
| `202` | durable preview job으로 접수됨 | `Location`을 polling하고 `Retry-After`를 지킴 |
| `429` | 같은 scope/job polling 또는 queue capacity 제한 | `Retry-After` 이후 같은 의도로 재시도 |

`GET /preview-jobs/{jobId}`는 저장된 진행률만 읽는다. `PENDING` 또는 `RUNNING`이면
`Retry-After` 헤더를 포함한 `200`을 반환한다. `COMPLETED`에서만
`activationEvidenceToken`과 `resultHash`가 공개된다.

## Idempotency

즉시 activation과 missed command replay는 `Idempotency-Key` header가 필요하다.
raw key는 body에 넣지 않는다.

| Endpoint | Key 의미 | Replay 결과 |
|---|---|---|
| `POST /{id}/activate` | 하나의 즉시 activation 의도 | 같은 key와 같은 fingerprint는 기존 command 재사용 |
| `POST /activation-commands/{commandId}/replay` | `MISSED` command의 새 replay 의도 | fresh key로 새 durable command 생성 |

같은 key가 다른 command fingerprint와 충돌하면 `POLICY_IDEMPOTENCY_CONFLICT`와
HTTP `409`를 반환한다.

## Effective Read

effective read는 두 시간 축을 받는다.

tenant effective snapshot은 다음 8개 tenant baseline kind가 모두 `ACTIVE`이고
요청한 `decisionAt`/`serviceAt`에 유효할 때만 만들어진다.

- `BOOKING_COMMITMENT`
- `HOLD_AND_CONSENT`
- `CAPACITY_AND_OVERBOOKING`
- `PRIORITY_AND_RELIABILITY`
- `RECONFIRMATION`
- `DISRUPTION_RECOVERY`
- `OPERATING_EXTENSION`
- `NOTIFICATION_AND_SLA`

따라서 예시의 booking policy 하나만 활성화한 직후에는 effective read가 성공하지
않는다. 먼저 같은 tenant에 8개 baseline을 모두 활성화해야 한다. clinic override는
partial이어도 되며, 빠진 kind/field는 완전한 tenant baseline에서 상속한다.

```text
GET /api/{tenantCode}/admin/scheduling-policies/effective?decisionAt=2026-08-01T01:00:00Z&serviceAt=2026-08-10T01:00:00Z
GET /api/{tenantCode}/admin/clinics/{clinicId}/scheduling-policies/effective?decisionAt=2026-08-01T01:00:00Z&serviceAt=2026-08-10T01:00:00Z
```

`decisionAt`과 `serviceAt`은 RFC 3339 offset timestamp여야 하며, `serviceAt`은
`decisionAt`보다 앞설 수 없다. 서비스는 generation을 읽고 컴파일한 뒤 다시
generation을 확인한다. 두 번의 권위 read가 달라지면 stale snapshot을 반환하지 않고
`POLICY_EFFECTIVE_READ_CONFLICT`를 반환한다.

## Error Contract

정책 오류는 `SchedulingApiErrorResponse`로 반환된다.

```json
{
  "success": false,
  "data": null,
  "error": "Scheduling policy preview capacity is temporarily limited.",
  "errorCode": "POLICY_PREVIEW_LIMITED",
  "correlationId": "f20d537d-0f1d-49c9-8e80-fd88103ce83d",
  "retryable": true,
  "action": "Retry the same preview request after the server-provided Retry-After interval."
}
```

| Error code | HTTP | Retryable |
|---|---:|---|
| `POLICY_PAYLOAD_INVALID` | `400` | `false` |
| `POLICY_OVERRIDE_FORBIDDEN` | `400` | `false` |
| `POLICY_ACTOR_FORBIDDEN` | `403` | `false` |
| `POLICY_RESOURCE_NOT_FOUND` | `404` | `false` |
| `POLICY_DRAFT_STALE` | `409` | `false` |
| `POLICY_PREVIEW_STALE` | `409` | `false` |
| `POLICY_ACTIVATION_CONFLICT` | `409` | `false` |
| `POLICY_IDEMPOTENCY_CONFLICT` | `409` | `false` |
| `POLICY_ACTIVATION_MISSED` | `409` | `false` |
| `POLICY_APPROVAL_INSUFFICIENT` | `422` | `false` |
| `POLICY_PREVIEW_LIMITED` | `429` | `true` |
| `POLICY_EFFECTIVE_READ_CONFLICT` | `409` | `true` |
| `POLICY_EFFECTIVE_READ_UNAVAILABLE` | `503` | `true` |

재시도 가능한 오류는 `Retry-After` 헤더를 포함한다. 공개 응답은 내부 예외 메시지,
payload, actor claim, SQL, idempotency key를 반사하지 않는다.
