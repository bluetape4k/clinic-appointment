# 방문 확정 약속 API

## 경계와 인증

예약서비스는 상품·구매·시술 완료·환불 사실의 소유자가 아니다. 상품 BOM과 구매
당시 version을 예약 생성 시 수정불가한 실행 스냅숏으로 보관하고, 이후 변경은
신뢰된 broker event로만 받는다.

Public API는 API Gateway가 검증한 JWT principal을 사용한다. request body에서 actor,
tenant, clinic, patient subject, 정책 mode, 자원 mapping을 받지 않는다. 고객 요청은
항상 가예약에서 시작하고, 관리자 직접 확정도 유효 병원 정책과 동의 규칙을 통과해야
한다.

Gateway claim은 `sub`, `jti`, `auth_time`, `allowedTenants`,
`allowedClinicIds`, `actorType`, `roles`, `assurance`를 포함한다. 단일 병원
identity라면 `clinicId`도 `allowedClinicIds`의 원소로 제공한다. 고객은
`actorType: "PATIENT"`와 `PATIENT` role, 안정적인 `patientSubject`가 모두
필요하고 관리자·직원은 `patientSubject`를 가질 수 없다.

```json
{
  "sub": "patient-01",
  "jti": "token_01J1M6Y6XRK8N0W2M3P4Q5R6S7",
  "auth_time": 1785373200,
  "allowedTenants": ["tenant-default"],
  "allowedClinicIds": [101],
  "clinicId": 101,
  "actorType": "PATIENT",
  "roles": ["PATIENT"],
  "assurance": "MFA",
  "patientSubject": "patient-subject-01"
}
```

이 claim은 body 입력을 대체하지만 저장된 Plan·appointment의 tenant·clinic·patient
범위 검사를 대체하지 않는다. 범위가 다중이거나 모호하면 command를 fail-closed로
거절한다.

## 상태와 변경 규칙

- `PROPOSED`: 자원을 점유하지 않는 가예약 제안이다.
- `HELD`: 정책이 허용한 제한 시간 동안 자원을 선점한 제안이다.
- `CONFIRMED`: 고객 동의와 관리자 승인 등 필요한 조건을 모두 충족한 확정 예약이다.
- 확정 예약 변경은 새 제안과 새 고객 동의가 완료되기 전까지 기존 확정과 점유를
  유지한다.
- 선행 진료 완료 event가 들어오면 완료 항목은 불변으로 두고 `BLOCKING` 후속 항목만
  변경 대상 집합으로 재계산한다.
- 장비 고장이나 하루 내 일부 진료 미이행은 남은 진료를 새 방문으로 분리한다.
- 추가 상품 구매는 기존 Plan에 합치지 않고 새 Plan을 만든다.

## 엔드포인트

| 행위자 | Method / path | 성공 | 업무 결과 |
|---|---|---:|---|
| 고객 | `POST /api/{tenantCode}/appointment-requests` | 202 | 정책에 따라 `PROPOSED` 또는 자원을 선점한 `HELD` 가예약 생성 |
| 관리자 | `POST /api/{tenantCode}/admin/appointments` | 201 | 정책이 허용하면 직접 확정 |
| 관리자 | `POST /api/{tenantCode}/appointments/{id}/approve` | 200 | 고객이 동의한 정확한 제안 승인 |
| 고객 | `POST /api/{tenantCode}/appointments/{id}/proposals/{proposalId}/accept` | 200 | 현재 제안 수락 |
| 고객 | `POST /api/{tenantCode}/appointments/{id}/proposals/{proposalId}/decline` | 200 | 기존 확정을 보존하며 제안 거절 |
| 관리자 | `POST /api/{tenantCode}/appointments/{id}/confirm` | 200 | 정책·동의 확인 후 확정 |
| 관리자 | `POST /api/{tenantCode}/appointments/{id}/change-proposals` | 202 | 기존 확정을 보존한 대체 제안 생성 |
| 관리자 | `POST /api/{tenantCode}/appointments/{id}/proposals/{proposalId}/expire` | 200 | 만료 제안 종결, 최초 `HELD` 자원 점유 해제 |
| 관리자 | `POST /api/{tenantCode}/appointments/{id}/cancel` | 200 | 가예약·확정 예약 취소와 활성 자원 점유 해제 |
| 고객·관리자 | `GET /api/{tenantCode}/appointments/{id}/commitment` | 200 | 행위자 범위 확정 약속 조회 모델 반환 |

모든 상태 변경 요청은 `Idempotency-Key`를 요구한다. 신규 생성은
`If-None-Match: *`, 기존 aggregate 변경은 최신 `ETag`를 담은 `If-Match`를
추가로 요구한다. 동의 payload는 원문이 아니라 `evidenceAuthority`와 추측하기
어려운 불투명 `evidenceId`만 전달한다.

### 요청 body 예제

Gateway가 인증한 actor·tenant·clinic·patient와 서버가 해석하는 정책·자원은 body에
넣지 않는다. 아래 JSON 외 필드는 strict DTO가 거부한다.

고객 가예약 `POST /api/{tenantCode}/appointment-requests`:

```json
{
  "appointmentPlanId": 101,
  "preferredStartAt": "2026-08-10T01:00:00Z",
  "preferredEndAt": "2026-08-10T02:00:00Z",
  "evidence": {
    "evidenceAuthority": "tenant-default:consent-service",
    "evidenceId": "consent_01J1M6Y6XRK8N0W2M3P4Q5R6S7"
  }
}
```

관리자 직접 생성은 같은 body를
`POST /api/{tenantCode}/admin/appointments`에 보낸다.

관리자 승인 `POST /api/{tenantCode}/appointments/{id}/approve`:

```json
{ "proposalId": 301 }
```

고객 수락 `POST /api/{tenantCode}/appointments/{id}/proposals/{proposalId}/accept`:

```json
{
  "evidence": {
    "evidenceAuthority": "tenant-default:consent-service",
    "evidenceId": "consent_01J1M6Y6XRK8N0W2M3P4Q5R6S8"
  }
}
```

고객 거절 `POST /api/{tenantCode}/appointments/{id}/proposals/{proposalId}/decline`:

```json
{ "reasonCode": "CUSTOMER_DECLINED_SCHEDULE" }
```

관리자 확정 `POST /api/{tenantCode}/appointments/{id}/confirm`:

```json
{
  "proposalId": 302,
  "evidence": {
    "evidenceAuthority": "tenant-default:consent-service",
    "evidenceId": "consent_01J1M6Y6XRK8N0W2M3P4Q5R6S9"
  }
}
```

변경 제안 `POST /api/{tenantCode}/appointments/{id}/change-proposals`:

```json
{
  "preferredStartAt": "2026-08-17T01:00:00Z",
  "preferredEndAt": "2026-08-17T02:00:00Z"
}
```

관리자 취소 `POST /api/{tenantCode}/appointments/{id}/cancel`:

```json
{ "reasonCode": "REFUND" }
```

환불 금액·승인·정산은 결제서비스가 소유한다. 예약서비스는 `REFUND`,
`CUSTOMER_REQUEST`, `EQUIPMENT_FAILURE` 같은 등록 code를 취소 event에 남기고
현재 활성 allocation만 해제한다.

## 배포 설정

`AppointmentCommitmentProperties`의 기본 mode는 `OFF`다.

| 설정 | 기본값 | 의미 |
|---|---:|---|
| `appointment.commitment.api-enabled` | `false` | commitment 경로의 부트스트랩 노출 게이트 |
| `appointment.commitment.ingress-enabled` | `true` | 신규 고객 요청·관리자 직접 생성만 허용 |
| `appointment.commitment.mode` | `OFF` | `OFF`, 계산 비교만 하는 `SHADOW`, 허용목록 쓰기인 `WRITE` |
| `appointment.commitment.clinic-allowlist` | 비어 있음 | `WRITE`가 실제 적용될 병원 ID |
| `appointment.commitment.proposal-ttl` | `30m` | 제안 승인 대기 만료 |
| `appointment.commitment.retry.max-attempts` | `3` | 최초 시도를 포함한 제한 재시도 |
| `appointment.commitment.retry.initial-backoff` | `25ms` | 첫 충돌 재시도 대기 |
| `appointment.commitment.ceiling.resources-per-slot` | `200` | 한 후보 slot의 의료진·장비·공간 자원 항목 상한 |
| `appointment.commitment.ceiling.candidate-resource-entries` | `10,000` | 한 요청의 모든 후보 slot 자원 항목 합계 상한 |
| `appointment.commitment.idempotency-hash-secret` | 없음 | commitment API 활성화 시 필수인 Base64 HMAC 비밀값. 디코딩 후 32바이트 이상이며 JWT·정책 command secret과 분리 |

동기 계획 상한은 treatment 500개, 관계 edge 4,000개, 반복 100회, 탐색 365일,
candidate slot 2,000개, slot당 자원 200개, 요청당 자원 항목 10,000개, 반환
제안 20개다. 설정으로 승인값보다 낮출 수는 있지만 높일 수 없다.

제안과 확정 약속 응답의 `policySnapshot`은 스냅숏 ID/hash, tenant·clinic
세대, 정책 종류별 원본 version을 함께 제공한다. 고객 수락·관리자 승인·조회는
현재 상품 카탈로그나 현재 정책을 다시 해석하지 않고 이 영속 스냅숏을 사용한다.

`api-enabled`는 commitment row가 전혀 없는 부트스트랩에서만 전체 경로 노출을 제어한다.
활성화 시 전용 idempotency secret이 없거나 짧거나 Base64가 아니면 startup을
실패시킨다. raw `Idempotency-Key`는 이 secret과 고정 domain으로
HMAC-SHA-256 처리한 뒤에만 저장한다.

운영 `api-enabled`에는 `AppointmentCommitmentPlanningResolver` 운영
어댑터가 추가로 필요하다. 이 어댑터는 상품 상세를 재해석하지 않고 구매 스냅숏과
외부 기준 시스템에서 환자 identity, 후보 inventory slot, 저장 제안의 자원 mapping,
확정 조회 모델 대상을 해석한다. 기본 구현은 모든 계획 호출을 fail-closed로
거절한다.
Gateway patient subject를 구매 Plan의 보호된 환자 fingerprint와 비교하려면 구매
ingress와 같은 HMAC key·algorithm·domain separation을 구현한
`PatientSubjectFingerprintResolver` bean도 필요하다. 기본 구현은 일반 SHA-256으로
추정하지 않고 patient 접근을 fail-closed로 거절한다.
commitment row 생성 후 롤백은 신규 유입만 차단하고 기존 조회/상태 변경 요청을 유지한다.
구체적인 절차는 [운영 런북](../runbooks/visit-commitment-operations.md)을 따른다.

## 안정 오류

| HTTP | `errorCode` 예 | 호출자 조치 |
|---:|---|---|
| 400 | `PAYLOAD_INVALID` | OpenAPI schema에 맞게 수정 |
| 401 | `UNAUTHORIZED` | Gateway가 발급한 유효 JWT로 다시 인증 |
| 403 | `SCOPE_MISMATCH`, `SCOPE_FORBIDDEN` | 정확한 clinic-scoped Gateway token 사용 |
| 409 | `RESOURCE_CONFLICT`, `PROPOSAL_NOT_CURRENT` | 최신 확정 약속을 읽고 새 제안 요청 |
| 410 | `PROPOSAL_EXPIRED` | 새 제안 요청 |
| 412 | `VERSION_CONFLICT` | 최신 `ETag`로 재시도 |
| 422 | `PLAN_LIMIT_EXCEEDED`, `CONSENT_REQUIRED` | Plan 또는 현재 동의 증거 수정 |
| 428 | `PRECONDITION_REQUIRED` | 생성은 `*`, 변경은 최신 `ETag` 전달 |
| 503 | `INGRESS_DISABLED` | 기존 예약은 유지하고 신규 요청만 보류 |

생성 OpenAPI 계약은 `AppointmentCommitmentOpenApiTest`가 실제 `/v3/api-docs`에서
경로, 필수 header, 성공·오류 응답을 검증한다.
