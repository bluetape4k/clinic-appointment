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

오류에는 안전한 메시지, `reasonCode`, bounded `correlationId`, `retryable`, 선택적인
`retryAfterSeconds`(호환성을 위한 `errorCode` alias 포함)만 담는다. 원본 member ID,
연락처, clinical note, policy score vector, JWT claim, SQL, provider exception text는
절대 포함하지 않는다. 일반 API의 `correlationId`는 trace correlation 경계이며,
현재 HTTP filter가 caller가 보낸 bounded 값을 보존할 수 있으므로 decision/audit
sample이나 provider evidence의 식별자로 재사용하지 않는다. fenced evidence에는
별도의 서버 생성 random 또는 keyed opaque correlation만 사용하고 domain ID,
caller-provided 값, profile-shaped 원문은 금지한다. actor reference는 `SYSTEM` 또는
`hmac:vN:<64 hex>` 형태의 full keyed HMAC만 허용하며, `staff:<suffix>`,
`recovery:<suffix>`, 임의 suffix, truncated hash, 비키드 hash는 허용하지 않는다.

현재 waitlist 일반 audit 경계에는 caller correlation 보존과 `staff:<sha256...take(24)>`
형태의 비키드·truncated actor가 남아 있으므로 위 fenced evidence 계약을 아직
충족하지 않는다. 이 문서 범위에서는 해당 일반 경계를 조용히 변경하지 않으며,
위 조건이 해결되고 회귀 검증되기 전에는 `LettuceFencedLock` production path를
활성화하지 않는다.

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

## Scheduler fencing 경계

waitlist scheduler의 Redis lease는 실행 중복을 줄이는 advisory gate일 뿐 business
state authority가 아니다. 현재 production 경로는 Redis fencing token을
`scheduling_waitlist_vacancy_jobs`의 terminal mutation까지 전달하지 않으므로,
`LettuceFencedLock`을 Boolean lease 포트 뒤에 형식적으로 추가하지 않는다.

DB terminal write는 `leaseOwner`, `version`, `leaseVersion`, `leaseExpiresAt`를
함께 확인하는 claim fence를 사용한다. stale owner가 만료 뒤 재개하거나 새 worker가
claim을 takeover한 경우, 이 predicate를 통과하지 못한 write는 성공으로 취급하지
않는다. Redis lease 획득 성공만으로 이 DB 조건을 생략하거나 완화하지 않는다.

향후 `LettuceFencedLock`을 활성화하려면 logical owner/request identity, fixed
lease 또는 bounded watchdog, `bootstrapFencing`, ambiguous reconcile, close와
cancellation semantics를 먼저 정하고 Redis token을 모든 보호 대상 terminal
mutation에 전달해야 한다. 각 write는 이전 `(epoch, sequence)`보다 strictly
greater한 token만 반영해야 하며, 일부 caller만 전환하는 부분 migration은 허용하지
않는다. 이 조건이 검증될 때까지는 도입을 보류한다.

운영 metric과 log에는 raw·truncated owner, request id, fencing token, lock key 또는
비키드 해시를 기록하지 않는다. bounded outcome, latency, 결과 category와 bounded
count만 기록한다. 현재 Boolean lease 포트는 tick 시작 시 획득 실패만 관측하며,
획득 뒤 Redis lease expiry/ownership loss를 감지하거나 차단하지 않는다. 획득 뒤
ownership loss가 발생해도 terminal write의 최종 판단은 Redis 상태가 아니라 DB
claim fence가 내린다.

`Ambiguous` 또는 unknown 결과는 같은 owner/request reconcile이나 명확한 lease
expiry로 `NOT_HELD`가 확인될 때까지 quarantine한다. 그 전에는 새 acquire,
dispatch, requeue 또는 business mutation을 시작하지 않는다.

현재 기본 `jobLease`는 30초이고 `pollInterval`은 1초이며, Boolean port에는
renewal과 scheduler tick duration 계측이 없다. 따라서 lease가 전체 tick 예산보다
짧지 않다는 보장은 아직 없다. fenced path를 재개할 때는 expiry·reclaim·dispatch를
포함한 tick p95/p99와 안전 여유를 측정하고 `jobLease >= worst-case tick + safety
margin` invariant를 검증해야 한다. Redis backend error와 ambiguous 결과에는
contention과 구분되는 bounded exponential backoff+jitter 또는 circuit breaker,
retry budget이 필요하다.

향후 metric은 `scheduler_tick_duration`, `lease_acquire_duration`,
`lease_outcome`, `ownership_loss_total`처럼 고정된 이름과 enum category만 사용해야
한다. 현재 facade에는 scheduler/acquire latency와 실행 중 ownership-loss 계측이
없으므로, 이 관측 계약이 추가·검증되기 전에는 fenced path를 활성화하지 않는다.

운영 명령과 증거는 [대기 목록 전달 런북](../runbooks/waitlist-delivery.md)에서 확인한다.
