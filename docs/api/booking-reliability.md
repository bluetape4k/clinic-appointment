# 예약 신뢰성 API 계약

이 문서는 [예약 신뢰성 정책 기준 문서](../booking-reliability-policy.ko.md)의 HTTP 계약이다.
모든 경로는 `/api/{tenantCode}/clinics/{clinicId}/members/{memberId}/booking-reliability` 아래에
있으며 `memberId`는 회원 서비스의 opaque 식별자다.

## 권한과 응답

| 메서드 | 경로 suffix | 권한 | 성공 |
|---|---|---|---:|
| `GET` | `/decision` | role `ADMIN`/`STAFF`/`DOCTOR` + `SCOPE_booking-reliability:read` + 정확한 clinic membership | 200 |
| `GET` | `/audit` | role `ADMIN`/`STAFF` + `SCOPE_booking-reliability:audit` + 정확한 clinic membership | 200 |
| `POST` | `/override` | role `ADMIN`/`STAFF` + `SCOPE_booking-reliability:write` + 정확한 clinic membership | 200 |
| `POST` | `/clear` | role `ADMIN`/`STAFF` + `SCOPE_booking-reliability:write` + 정확한 clinic membership | 200 |

경로별 matcher는 일반 tenant matcher보다 먼저 적용한다. 보안 실패에도 이름·전화번호를
반환하지 않는 공통 오류 registry를 사용한다.

## 결정 조회

```http
GET /api/tenant-default/clinics/20/members/member-opaque-001/booking-reliability/decision?policySnapshotId=77
Authorization: Bearer <clinic-scoped-token>
```

응답의 `data`에는 `decisionId`, `clinicId`, opaque `memberId`, `verdict`, policy version/hash,
상한이 있는 count, allowlist reason code, 최대 32개 `triggeringAppointmentIds`,
`hasAdditionalTriggers`, opaque `auditCursor`, expiry, `evaluationDigest`, 현재 `mode`만
포함한다. `name`, `phone`, `email`, `rawPayload`, `staffNote` 필드는 존재하지 않는다.

## Override와 clear

두 명령 모두 `Idempotency-Key` 헤더가 필요하다. 키는 1~128자의 상한이 있는 ASCII 값이며
원문은 서버에서 hash하여 저장한다. `evaluationDigest`는 현재 decision의 lowercase SHA-256이어야 한다.

```http
POST /api/tenant-default/clinics/20/members/member-opaque-001/booking-reliability/override
Idempotency-Key: override_01J...
Content-Type: application/json

{
  "verdict": "ELIGIBLE",
  "reasonCode": "MANUAL_OVERRIDE",
  "effectiveFrom": "2026-08-01T03:00:00Z",
  "expiresAt": "2026-08-02T03:00:00Z",
  "decisionId": 77,
  "evaluationDigest": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
}
```

`clear`는 `reasonCode`, `decisionId`(선택), `evaluationDigest`를 받고 `MANUAL_CLEAR`를 기본
사유로 사용한다. actor, clinic, member, 이름, 전화번호, 자유 텍스트는 요청 본문에 넣지 않는다.
선언하지 않은 JSON field는 400으로 거절한다. stale decision 또는 CAS 충돌은 409
`BOOKING_DECISION_STALE`, 키 누락은 428 `BOOKING_IDEMPOTENCY_REQUIRED`다.

## 감사 cursor

```http
GET .../booking-reliability/audit?limit=50&cursor=v1.<opaque>
```

`limit`은 1~100, cursor는 서버가 만든 opaque 값만 받는다. 응답은 `entries`와 `nextCursor`로
구성하며 actor는 상한이 있는 `actorType:actorId` 참조로만 남긴다. 현재 페이지는 override/clear
command ledger와 그때 사용한 decision digest 참조를 제공한다. 사건 원문은 이 endpoint에서
반환하지 않으며, 권한 있는 `decision` 응답도 bounded trigger ID와 opaque cursor 참조만
노출하고 회원 profile을 합쳐 반환하지 않는다.

## 오류 registry

| code | HTTP | 재시도 |
|---|---:|---|
| `BOOKING_REVIEW_REQUIRED` | 409 | 직원 검토 |
| `BOOKING_DECISION_UNAVAILABLE` | 503 | 같은 의도로 bounded retry |
| `BOOKING_DECISION_STALE` | 409 | decision 재조회 |
| `BOOKING_RELIABILITY_FORBIDDEN` | 403 | 권한/clinic scope 수정 |
| `BOOKING_IDEMPOTENCY_REQUIRED` | 428 | header 추가 |
| `BOOKING_PAYLOAD_INVALID` | 400 | schema 수정 |

오류 body는 `success`, `data`, `error`, `errorCode`, `correlationId`, `retryable`, `action`을
포함하고 민감정보를 포함하지 않는다.

## 예약 command 소비 규칙

`appointment-api`의 commitment command는 `OFF`에서는 gate를 호출하지 않는다. `SHADOW`에서는
decision을 저장·관측하지만 예약을 막지 않는다. `ENFORCE`에서는 `RESTRICTED`와
`REQUIRES_STAFF_APPROVAL`을 차단하고 `STALE`/`UNAVAILABLE`도 fail-closed로 처리한다.
허용된 신규 proposal/commitment에는 `decisionId`, policy version/hash, `evaluationDigest`,
expiry가 immutable stamp로 함께 저장된다. 이미 `CONFIRMED`인 row의 update/cancel 경로에는
이 gate를 연결하지 않는다. waitlist/offer 후보 생성과 고객 응답 소비는 #170의 후속 범위다.
