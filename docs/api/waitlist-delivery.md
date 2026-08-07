# 대기 목록 전달 API 계약

이 문서는 이슈 #170의 2단계 직원용 경계를 설명한다. 환자 셀프서비스, 공개
magic link, 결제, CRM attribution, 외부 메시징 broker는 이 계약에 포함하지 않는다.

## 범위와 헤더

기본 경로는 `/api/{tenantCode}/clinics/{clinicId}/waitlist`다. JWT principal,
tenant membership, clinic membership, capability가 권위 있는 값이며 요청 본문으로
이 값을 덮어쓸 수 없다. 모든 mutation에는 16–128자의 출력 가능한 ASCII 문자를 담은
`Idempotency-Key`가 필요하다. 기존 row를 변경하는 command는 JSON 본문에
`expectedVersion`도 담아야 한다.

entry, offer, policy, adjustment, appointment reference는 opaque 문자열이다. API는
내부 `Long` ID port를 호출하기 전에 reference를 디코딩하고 scope를 검증한다.
형식이 잘못되었거나 종류가 다르거나 다른 병원에 속한 reference는 의도적으로
`404 WAITLIST_REFERENCE_NOT_FOUND`로 반환한다.

## 라우트

| 메서드 | 경로 | capability | 결과 |
|---|---|---|---|
| `POST` | `/entries` | `waitlist:write` | 범위가 지정된 waiting entry 생성 (`201`). |
| `GET` | `/entries`, `/entries/{entryRef}` | `waitlist:read` | Keyset 페이지 또는 단일 entry 조회 (`200`). |
| `POST` | `/entries/{entryRef}/withdraw` | `waitlist:write` | 버전 조건부 철회 (`200`). |
| `GET` | `/offers`, `/offers/{offerRef}`, `/offers/{offerRef}/decision` | `waitlist:read` | Offer 또는 decision 뷰 조회 (`200`). |
| `POST` | `/offers/{offerRef}/confirm` | `waitlist:write` | 대체 appointment 하나 생성 (`201`). |
| `POST` | `/offers/{offerRef}/decline` | `waitlist:write` | 거절하고 자원 해제 (`200`). |
| `GET` | `/policies/active`, `/policies/{policyRef}` | `waitlist:read` | 유효 policy 뷰 조회 (`200`). |
| `POST` | `/policies`, `/policies/{policyRef}/activate` | `waitlist:policy` | 버전이 있는 policy 저장/활성화 (`201`/`200`). |
| `POST` | `/restrictions`, `/restrictions/{restrictionRef}/release` | `waitlist:adjustment` | 제한을 정해진 범위에서 변경. |
| `POST` | `/recovery-credits`, `/recovery-credits/{recoveryCreditRef}/revoke` | `waitlist:adjustment` | recovery credit을 정해진 범위에서 변경. |
| `POST` | `/benefit-grants`, `/benefit-grants/{benefitGrantRef}/revoke` | `waitlist:adjustment` | 승인되고 상한이 적용된 benefit 변경. |

목록 응답은 `{ "items": [...], "nextCursor": "..." }` 형식이다. 기본 페이지 크기는
50이고 최대값은 100이다. cursor는 filter, scope, ordering에 묶인다. 변조되었거나
오래된 cursor는 `400 INVALID_CURSOR`를 반환한다.

## Confirm과 replay 계약

confirm 경로는 짧은 transaction에서 idempotency command를 먼저 예약한 뒤, 별도의
업무 transaction에서 offer, entry, hold, policy decision, appointment capacity를
lock하고 다시 검증한다. 결과 기록은 세 번째 transaction에서 완료한다. appointment
생성 직후 프로세스가 중단되면 command는 `PROCESSING` 상태로 남는다. 재시도는 새
appointment를 만들지 않고 command scope와 request digest를 기준으로 기존 대체
appointment를 대조·복구한다.

| 상황 | 상태 | 사유 |
|---|---:|---|
| 최초 confirm | `201` | opaque `appointmentRef`를 담은 `ACCEPTED`. |
| 성공 후 같은 key와 같은 요청 | `201` | `Idempotent-Replay: true`를 담은 원래 결과. |
| 처리 중 같은 key | `202` | `IDEMPOTENCY_IN_PROGRESS`, `Retry-After: 1`. |
| 만료·stale·점유된 offer | `409` | `OFFER_EXPIRED`, `DECISION_STALE`, `SLOT_OCCUPIED` 중 하나. |
| 다른 request digest와 같은 key 사용 | `409` | 안정적인 idempotency 충돌. |

notification delivery는 acceptance가 아니다. provider 실패 또는 알 수 없는 결과는
delivery 상태로 기록할 뿐이며 offer를 되살리거나 수락할 수 없다.

## 오류와 비식별화 계약

오류에는 안전한 메시지, `reasonCode`, `correlationId`, `retryable`, 선택적인
`retryAfterSeconds`(호환성을 위한 `errorCode` alias 포함)만 담는다. 원본 member ID,
연락처, clinical note, policy score vector, JWT claim, SQL, provider exception text는
절대 포함하지 않는다.

| 상태 | 사유 계열 |
|---:|---|
| `400` | `INVALID_IDEMPOTENCY_KEY`, `PAYLOAD_INVALID`, `INVALID_CURSOR` |
| `401` | 공통 security envelope의 `AUTH_UNAUTHENTICATED` |
| `403` | `WAITLIST_FORBIDDEN` / `AUTH_SCOPE_DENIED` |
| `404` | `WAITLIST_REFERENCE_NOT_FOUND` |
| `409` | stale version, terminal state, expiry, capacity 또는 idempotency 충돌 |
| `503` | `WAITLIST_UNAVAILABLE`; 재시도 가능하면 `Retry-After` 포함 |

## 롤아웃

`appointment.waitlist.delivery.enabled=false`가 안전한 기본값이다. 선택적인
`clinic-allowlist`를 사용하면 지정한 병원에만 dispatch를 활성화한다. flag를 끄거나
병원을 제거하면 새로운 vacancy dispatch와 notification delivery를 중지하지만, expiry,
suppression, stuck-hold reconciliation은 계속 수행한다. terminal write를 승인하는
기준은 Redis leader lease가 아니라 database fencing이다.

운영 명령과 증거는 [대기 목록 전달 런북](../runbooks/waitlist-delivery.md)에서 확인한다.
