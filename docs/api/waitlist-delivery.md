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

현재 일반 waitlist audit 경계의 caller correlation·actor 규칙은 이 scheduler metric 및
fenced lease 경계와 별도 계약이다. 이 변경은 해당 일반 audit payload를 확장하지 않으며,
fenced scheduler는 자체 metric·handle 경계에서 서버 생성 opaque owner와
allowlisted outcome만 사용한다. 일반 audit 경계를 변경할 때는 별도 설계와 회귀 검증을
추가해야 한다.

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

Issue #311의 fenced scheduler 경로는 기존 Boolean scheduler와 분리된 typed
application port다. `appointment.waitlist.delivery.enabled=true`이고
`RedisClient`, `DataSource`, `WaitlistFencedVacancyDispatcher`, expiry·suppression·
hold-reconcile port와 `MeterRegistry`가 모두 존재할 때만
`WaitlistFencedSchedulingConfiguration`이 bean을 조립한다. dispatcher가 제공되지
않는 기본 예제 실행에서는 scheduler를 만들지 않으며 no-op dispatcher도 등록하지
않는다.

V31 migration은 `scheduling_waitlist_vacancy_jobs`에
`fence_epoch BIGINT NOT NULL DEFAULT 0`, `fence_sequence BIGINT NOT NULL DEFAULT 0`를
추가한다. Redis lock은 bluetape4k `LettuceFencedLock`과
`LockConfig(namespace = "bt4k:coord:v1")`를 재사용하고, resource 구성요소 검증을
통과하는 `waitlist-delivery` 이름을 사용한다. 라이브러리가 생성하는 파생 key는
`bt4k:coord:v1:{waitlist-delivery}:lock:waitlist-delivery`와 관련 state,
generation, holds, terminal, fence-counter key다. 이 key는 logical namespace의
버전 계약이며 resource 문자열에 콜론을 직접 넣지 않는다.

acquire는 `LeasePolicy.Fixed(jobLease)`로 제한하고 `jobLease`는 5분을 넘을 수 없다.
traffic 전에 `bootstrapFencing()`을 한 번 실행하며, `Base58.randomString(8)`으로 만든
opaque owner와 매 acquire의 native request를 adapter 내부에만 보관한다. 명확한
`Acquired` 또는 `Reentered` handle을 얻은 경우에만 expiry → suppression → hold
reconcile → dispatch 순서를 시작한다. `Contended`, `TimedOut`, `Failed`는 해당 tick을
종료한다. `Ambiguous`는 동일 owner/request pair로 한 번 reconcile하고 recovered
handle이 확인될 때만 business work와 release를 수행한다.

DB claim은 Redis lease의 보조 권위를 신뢰하지 않고 `(epoch, sequence)`가 현재 저장
값보다 strictly greater한 경우만 수락한다. `completeOffer`, `completeNoCandidate`,
`markExpired`, `markFailed` 같은 terminal update는 claim의 동일 token과 기존
`leaseOwner`, `version`, `leaseVersion`, `leaseExpiresAt`를 exact-match로 확인한다.
따라서 lease expiry 뒤 재개한 stale worker의 write는 affected row 0으로 거부된다.
Redis는 scheduler 실행 중복을 줄이는 권위이고, DB는 business state의 최종 권위다.

`close()`는 새 tick과 local lock task를 중지하고 shared `RedisClient`의 소유권을
가져오거나 자동 unlock하지 않는다. readiness probe가 V31 column을 확인하지 못하면
`WaitlistFencingReadinessException`으로 startup을 실패시킨다. rollback은
`appointment.waitlist.delivery.enabled=false`로 dispatch를 내리고 Redis counter와
DB fence column은 되돌리지 않는 방식으로 수행한다.

운영 metric은 다음 이름과 닫힌 enum tag만 사용한다.

- `appointment_waitlist_lease_acquire_total{outcome=acquired|contended|timeout|ambiguous|failed}`
- `appointment_waitlist_lease_acquire_seconds{outcome=...}`
- `appointment_waitlist_scheduler_tick_seconds{mode=active|clinic_disabled|global_off}`
- `appointment_waitlist_ownership_loss_total{source=redis|db}`

owner, request, token, key, tenant/member/entry/offer ID와 비키드·truncated hash는
log와 metric에 기록하지 않는다. metric에는 outcome·latency·bounded count만 남긴다.

운영 명령과 증거는 [대기 목록 전달 런북](../runbooks/waitlist-delivery.md)에서 확인한다.
