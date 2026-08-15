# Issue #305 — 환자 취소 이력 조회와 포털 타임라인 설계

## 결정 상태

Issue #305의 구현 사양이다. 대상 브랜치는 `feat/issue-305-cancellation-history`이며
`feat/issue-34-patient-commitment`의 `444e5cfa23634352093a2eff8e1b2d2da85c5163`를
기준으로 한다. 기존 `frontend/appointment-frontend/angular.json` 변경은 이 작업 범위에
없으며, 현재 worktree에도 변경을 만들지 않는다.

이 문서는 #34가 저장한 terminal 취소 snapshot을 환자 본인에게만 제한해서 읽는 API와
Angular 포털 타임라인을 추가한다. 관리자 raw audit 조회, 환자 검색, 취소 mutation은
구현하지 않는다.

## 문제와 목표

현재 #34는 취소 시 `scheduling_appointment_cancellation_details`에 reason code,
서버 소유 detail, actor role, hash, 발생 시각을 저장하지만 환자가 자신의 과거 취소를
확인할 읽기 계약은 없다. 현재 commitment API는 현재 상태와 proposal만 반환하므로
새 세션이나 새로고침 뒤 취소된 방문을 확인할 수 없다.

이번 변경의 목표는 다음과 같다.

- 인증된 PATIENT subject가 같은 tenant의 자기 취소 이력만 조회한다.
- 결과에는 내부 account ID, 전화번호, 이메일, 원문 subject, token, actor ID,
  `actorScopeHash`, `detailHash`, raw audit payload를 포함하지 않는다.
- 취소 전 commitment 상태, 취소 후 상태, reason code와 서버 소유 안내 문구,
  방문 시각, 상품명, 회차를 안정적인 환자용 표현으로 반환한다.
- keyset pagination과 강한 ETag를 사용해 최근 이력 목록을 bounded하게 읽는다.
- cursor와 appointment reference는 위변조·재사용을 방지하는 versioned key ring에
  결속하고, 환자 범위 조회는 snapshot fingerprint 인덱스로 시작해 큰 tenant에서
  전체 취소 이력을 스캔하지 않는다.
- 로그아웃·환자 전환·느린 응답 race에서 이전 환자 이력이 화면에 남지 않는다.
- 320px 폭과 키보드·스크린리더 사용을 포함한 포털 타임라인을 제공한다.

## 현재 코드 근거와 제약

- `PatientJwtIssuer`는 `allowedTenants`만 부여하고 `allowedClinicIds`는 빈 목록으로
  발급한다. 따라서 환자 이력 endpoint에 clinic path selector를 넣으면 정상적인
  patient cookie가 스스로 차단된다.
- `ActorContextResolver.resolve(authentication, tenantCode, null, correlationId)`는
  tenant membership와 인증 증거를 확인하면서 clinic을 선택하지 않는 actor context를
  만들 수 있다.
- `AppointmentCommitmentAccessResolver`의 patient ownership 규칙은 tenant별
  `PatientSubjectFingerprintResolver`를 사용한다. 새 읽기 서비스도 같은 resolver를
  사용하고, raw subject나 fingerprint를 응답·로그에 기록하지 않는다.
- `AppointmentCancellationDetails`에는 취소 전 상태와 환자 범위 snapshot이 아직 없다.
  V28에서 nullable `from_commitment_status`와
  `patient_scope_fingerprint`를 추가한다. V27 기존 row는 migration에서
  `Appointments.patient_reference_fingerprint`를 backfill하고, backfill이 불가능한
  row는 활성화 readiness가 실패하도록 한다. 저장 값은 이미 비가역적인 tenant-scoped
  fingerprint이며 raw subject/전화번호/email이 아니다.
- #34의 cancellation detail은 appointment/commitment/proposal FK cascade를 따른다.
  이력 API는 삭제된 aggregate를 복원하거나 별도 audit 보존을 약속하지 않는다.
- 상품명과 회차는 `AppointmentCancellationDetails`에서
  `AppointmentItems → AppointmentPlanRevisions → AppointmentPlans`를 따라 조회한다.
  상품명이 여러 값으로 모호하면 `null`, 동일 revision의 단일 sequence가 확인될 때만
  `sessionNumber`를 반환한다. `totalSessions`는 해당 revision의 최대 sequence다.
- 방문 시간은 취소 detail이 가리키는 proposal의 `proposedStartAt`과 `proposedEndAt`을
  사용한다. 값이 없으면 `null`이며 appointment legacy projection을 추측해 채우지 않는다.

## 선택한 API 경계

### 경로와 인증

```http
GET /api/{tenantCode}/patient/appointments/cancellation-history
```

clinic path parameter는 사용하지 않는다. Spring Security에서 이 경로를 generic GET보다
먼저 `PATIENT + tenantAuthorizationManager`로 보호한다. controller는 다시
`ActorContextResolver`로 tenant를 확인하고 `requirePatientActor()`를 호출한다.

`tenantCode`는 요청 경로에서만 받고, filter가 조회한 immutable `TenantGroup.id`를 이
API의 canonical tenant identity로 사용한다. cursor·appointmentRef HMAC payload와 모든
SQL predicate는 `tenant_group_id`의 signed 64-bit big-endian 값만 사용한다. code는
token/ref에 넣지 않으며, code→ID 해석이 실패하거나 active 상태가 아니면
`404 PATIENT_HISTORY_TENANT_NOT_FOUND`를 반환한다.
다른 환자의 행은 존재 여부를 노출하지 않고 빈 목록으로 처리한다. workforce actor가
이 경로에 접근하면 `403`이며, 인증 cookie가 없거나 검증에 실패하면 기존 security
writer가 기존 호환 `401`을 반환한다. 이때 response는
`Content-Type: application/json`, `X-Correlation-Id`와 함께
`{"success":false,"data":null,"error":"Authentication is required","errorCode":"UNAUTHORIZED","correlationId":"<same>"}`
형식을 사용하고 `Retry-After`를 보내지 않는다. 이 legacy security envelope는 이 endpoint의
application error schema와 구분되는 유일한 호환 예외이며 OpenAPI와 TypeScript adapter가
명시적으로 변환한다.

### Query와 페이지 계약

```http
GET /api/acme/patient/appointments/cancellation-history?limit=20&cursor={opaque}
If-None-Match: "sha256:..."
```

- `limit` 기본값은 `20`, 허용 범위는 `1..50`이다. 범위를 벗어나면 `400`이다.
- 정렬은 `occurredAt DESC, id DESC`이며 `limit + 1`개를 읽어 `nextCursor`를 만든다.
- cursor는 `v1.<keyId>.<nonce>.<ciphertext>.<tag>` 형식의 AES-GCM token이다. outer
  `keyId`와 authenticated payload의 `issuedKeyId`는 같은 issuance key ID이며, 암호화
  payload에는 version, `issuedKeyId`, `issuedAt`, `issuedAtBucket`, tenant group, patient scope fingerprint,
  `occurredAt`, detail ID를 고정 순서로 넣고,
  AAD에는 `patient-history-cursor-v1`을 넣는다. 원문 subject는 넣지 않으며 active
  key로만 발급하고 active/previous key ring으로만 읽는다. keyId·nonce·ciphertext의
  길이, timestamp 범위, ID 범위, base64url padding/trailing byte를 엄격히 검증하고
  tag 검증은 constant-time primitive에 위임한다. key가 없거나 검증에 실패하면
  `400 PATIENT_HISTORY_PAYLOAD_INVALID`로 fail-closed 한다.
- cursor 전체 길이는 512 ASCII byte 이하, segment는 정확히 5개, keyId는
  `[A-Za-z0-9_-]{1,32}`, nonce는 12 byte, ciphertext는 256 byte 이하, tag는 16 byte로
  고정한다. base64url에는 padding과 whitespace를 허용하지 않으며, payload의
  `detailId`는 양의 long, `occurredAt`은 현재 시각 기준 ±10년 범위의 UTC instant로
  제한한다. `issuedAt`은 현재 시각 기준 미래 60초 이내이고 발급 후 30분 이내여야
  하며, clock skew 검사는 monotonic deadline과 UTC wall clock을 함께 사용한다. 이
  bounds를 넘는 입력은 decode 전에 400으로 거부한다.
- decode한 tenant와 fingerprint가 현재 actor와 다르거나 anchor가 삭제된 경우는
  모두 같은 `409 PATIENT_HISTORY_SNAPSHOT_CONFLICT`로 처리한다. anchor 소유 확인과
  page 조회는 동일한 read-only consistent snapshot transaction에서 수행해 타 환자의
  anchor 존재 여부를 oracle로 만들지 않는다.
- `appointmentRef`는 `v1.<keyId>.<base64url(HMAC-SHA-256)>` 형태의 domain-separated
  digest(`patient-history-appointment-ref-v1` + length-prefixed canonical
  `tenantGroupId|patientScopeFingerprint|appointmentId|detailId`)만
  반환한다. payload를 복호화할 수 없고, key rotation 중에도 active/previous key
  검증만 허용한다.
- `If-None-Match`는 단일 strong tag(`\"sha256:[0-9a-f]{64}\"`)만 허용하고 header 전체
  128 ASCII byte를 넘지 않는다. weak tag, wildcard, comma-separated list, trailing
  byte는 400으로 거부한다. canonical response codec은 entry 50개와 각 문자열 길이
  bounds를 적용해 256 KiB를 넘지 않으며, 초과하면 payload/config 오류로 fail-closed
  한다.
- 응답은 raw JSON typed body이며 legacy `ApiResponse` envelope를 사용하지 않는다.

```json
{
  "limit": 20,
  "entries": [
    {
      "appointmentRef": "v1....",
      "productName": "프리미엄 검진",
      "sessionNumber": 3,
      "totalSessions": 10,
      "visitStartAt": "2026-08-12T01:00:00Z",
      "visitEndAt": "2026-08-12T02:00:00Z",
      "fromStatus": "CONFIRMED",
      "fromStatusLabel": "확정",
      "toStatus": "CANCELLED",
      "toStatusLabel": "취소",
      "reasonCode": "CUSTOMER_REQUEST",
      "reasonLabel": "고객 요청",
      "reasonDetail": null,
      "actorRole": "PATIENT",
      "actorLabel": "환자",
      "occurredAt": "2026-08-10T03:00:00Z"
    }
  ],
  "nextCursor": null
}
```

`fromStatus`는 V28 이후 기록에서만 채워지며, 기존 V27 row는 `null`이다. `toStatus`는
항상 `CANCELLED`다. `actorRole`은 저장된 닫힌 `ADMIN`, `STAFF`, `PATIENT`, `SYSTEM`
집합만 그대로 반환하며 알 수 없는 legacy 값은 응답 전용 `UNKNOWN`으로 반환한다.
`UNKNOWN`은 SYSTEM으로 해석하지 않으며 UI는 “확인 불가”로 표시한다. reason code는
core의 `CancellationReasonRegistry` allow-list를 통과한 값만 반환하고, 알 수 없는
legacy 값은 응답에서 제외하지 않고 `UNKNOWN`으로 축약한다. 원문 detail은 #34가
서버 소유 registry detail만 저장하므로 그 snapshot만 반환한다. `reasonLabel`은
서버가 동일 registry의 한국어 label을 반환하며, frontend는 registry를 복제하지 않는다.
`fromStatusLabel`, `toStatusLabel`, `actorLabel`도 서버의 환자용 label registry가
생성한다. `fromStatus`가 null이면 `fromStatusLabel`도 null이고, `toStatus`는 항상
`CANCELLED`와 “취소”를 함께 반환한다. `actorRole=UNKNOWN`과 미래에 추가된 알 수 없는
actor 값은 `actorLabel="확인 불가"`로 반환한다. frontend는 enum token을 직접 번역하지
않으며, raw `CONFIRMED`, `CANCELLED`, `ADMIN` 등의 token이 DOM에 나오지 않는
shared fixture/DOM assertion을 둔다.
모든 날짜는 UTC ISO-8601 string 또는 `null`이며, `productName`, `sessionNumber`,
`totalSessions`, `visitStartAt`, `visitEndAt`, `fromStatus`, `reasonDetail`은 nullable
계약을 따른다. `fromStatusLabel`도 nullable이고 `toStatusLabel`, `actorLabel`은
항상 비어 있지 않은 환자용 문자열이다.

### ETag와 캐시

- 서버는 `limit`, 요청 cursor, 응답 entries, `nextCursor`를 length-prefixed UTF-8
  canonical codec으로 직렬화해 SHA-256 strong ETag를 만든다. entry는 null 여부와
  무관하게 다음 고정 순서의 모든 필드를 포함한다: `appointmentRef`, `productName`,
  `sessionNumber`, `totalSessions`, `visitStartAt`, `visitEndAt`, `fromStatus`,
  `fromStatusLabel`, `toStatus`, `toStatusLabel`, `reasonCode`, `reasonLabel`,
  `reasonDetail`, `actorRole`, `actorLabel`, `occurredAt`. 각 필드는 1바이트 null marker
  (`0x00=null`, `0x01=값`)와 타입별 canonical bytes를 사용하며, non-null 값 하나만
  바뀌어도 ETag가 바뀐다.
- `nextCursor`는 같은 page boundary에 대해 token을 재암호화하지 않고 canonical token을
  재사용한다. deterministic 재사용과 실제 발급 시각 기준 TTL을 동시에 보장하기 위해
  서버는 결과나 환자 식별자를 저장하지 않는 별도의 bounded opaque token registry를 둔다.
  registry는 모든 API replica가 공유하는 linearizable 저장소를 사용하며, 프로세스 로컬
  map이나 재시작 시 사라지는 near-cache를 원본으로 사용하지 않는다. replica 재시작 뒤에도
  만료 전 entry를 읽을 수 있어야 한다. 공유 registry가 timeout·unavailable 상태이거나
  linearizable read/write를 완료하지 못하면 cursor 발급과 검증은
  `503 PATIENT_HISTORY_UNAVAILABLE`로 fail-closed한다.
  registry key는 issuance 당시의 `issuedKeyId`와 canonical `(tenantGroupId, patientScopeFingerprint,
  occurredAt, detailId, issuedAtBucket)`의 HMAC digest이며, value는 token bytes와 최초
  `issuedAt`뿐이다. token 발급은 반드시 registry lookup부터 수행한다. 동일 digest가
  bucket 안에서 다시 요청되면 저장된 최초 `issuedAt`과 token bytes를 그대로 반환하며,
  저장된 payload를 다시 암호화하거나 현재 시각으로 덮어쓰지 않는다. registry miss일 때만
  현재 시각을 `issuedAt`으로 기록하고 `issuedAtBucket = floorUtc30(issuedAt)`를 확인한
  뒤 payload를 생성·암호화해 `putIfAbsent`로 원자적으로 저장한다. 동시 miss 중 하나만
  승자가 되며 패자는 승자의 token/issuedAt을 읽어 반환한다. registry entry는
  `issuedAt + 30분`에 만료하며 TTL 전에 eviction·capacity 축출·LRU 제거를 허용하지
  않는다. capacity가 가득 찼거나 `putIfAbsent`/read가 linearizable하게 완료되지 않으면
  `503 PATIENT_HISTORY_UNAVAILABLE`로 발급·검증을 fail-closed한다. registry에는 raw
  subject, tenantCode, 환자 식별자, response body를 저장하지 않고, registry 장애나
  digest 충돌은 fail-closed하여 token을 임의로 새로 만들지 않는다.
  `issuedAtBucket = floorUtc30(issuedAt)` invariant와 payload field order를 고정하고,
  canonical payload의 SHA-256 앞 12바이트를 active key와 domain separator에 HMAC한
  deterministic nonce를 사용한다. registry hit의 canonical payload는 저장된
  `issuedAt`을 포함한 최초 bytes를 그대로 사용하므로 같은 digest가 같은 ciphertext를
  재현한다. 서로 다른 payload가 같은 nonce를 얻는 충돌은
  fail-closed하고, 같은 digest는 동일한 ciphertext/token을 반환한다. 따라서 동일
  `(session, tenant, cursor, limit)` 요청을 같은 bucket에서 반복하면 page representation과
  ETag가 안정적이며 304가 가능하다. bucket이 바뀌거나 key rotation이 일어나면
  representation이 바뀌므로 200을 반환한다. 발급 시 registry key와 payload의
  `issuedKeyId`는 당시 active key에 고정한다. 발급 경로는 이미 조회한 현재 page boundary로
  registry key를 계산할 수 있으므로 lookup-first, miss 시 payload 생성·암호화·`putIfAbsent`
  순서를 따른다. 검증 경로는 암호문 내부 값을 신뢰하기 전에 registry를 조회하지 않는다.
  즉 outer grammar와 outer `keyId` bounds를 먼저 확인하고, active/previous key ring으로
  AES-GCM을 복호화해 tag/AAD를 검증한 뒤 authenticated payload bounds와
  `issuedAtBucket` invariant를 확인한다. outer `keyId`와 authenticated payload의
  `issuedKeyId`가 다르면 tag가 유효해도 `400 PATIENT_HISTORY_PAYLOAD_INVALID`로
  종료한다. 먼저 authenticated payload의 tenant group과
  patient scope를 현재 actor의 canonical tenant/fingerprint와 비교한다. 불일치하면
  registry에 접근하지 않고 항상 `409 PATIENT_HISTORY_SNAPSHOT_CONFLICT`로 종료해 registry
  상태 timing oracle을 차단한다. scope가 일치할 때만
  `(issuedKeyId, tenantGroupId, patientScopeFingerprint, occurredAt, detailId, issuedAtBucket)`로
  registry key를 재계산하고 registry entry와 token bytes/최초 `issuedAt`을
  constant-time으로 일치하는지 확인한다. malformed·expired·unknown/retired key·tag 실패는 `400
  PATIENT_HISTORY_PAYLOAD_INVALID`로 fail-closed한다. 단, AES-GCM 인증과 TTL 검증을
  통과한 미만료 token의 issued entry가 없으면 client 입력 오류가 아니라 registry
  data-loss/early-eviction invariant 위반이므로 `503 PATIENT_HISTORY_UNAVAILABLE`로
  분리한다. 이 경우 `patient_history_registry_failure_total{reason="missing_entry"}`를
  기록하고 registry readiness를 false로 전환해 해당 endpoint flag를 즉시 off한다.
  registry timeout·unavailable·capacity-full·linearizability 실패도 같은 503으로
  분리한다. key rotation 뒤에도 registry validation은 현재 active key가 아니라
  authenticated token의 `issuedKeyId`로 수행하며 previous-key entry를 TTL overlap 동안
  유지한다. 서로 다른 authenticated payload가 같은 digest/nonce를 만들면
  `patient_history_registry_failure_total{reason="collision"}`을 기록하고 readiness=false,
  endpoint flag-off 및 sanitized 503으로 처리한다. 400 cursor 폐기나 first-page recovery는
  수행하지 않는다.
  key rotation 후에는 새 keyId가 달라져 200을 반환하고 previous key는 검증만 한다.
- `200 OK`와 `304 Not Modified` 모두 ETag를 반환한다.
- `Cache-Control: no-store`, `Vary: Cookie`를 사용한다. 브라우저·중간 shared cache가
  환자 이력을 보존하지 않으며, Angular session memory만 ETag/body를 유지한다.
- `If-None-Match`가 현재 표현과 일치하면 body 없이 `304`를 반환한다. 인증 주체가
  바뀌면 같은 tenant라도 포털 facade가 캐시를 버리고 새 요청을 보낸다.
- API 서버는 환자별 결과를 shared cache에 저장하지 않는다. client cache key는
  `(sessionIdentity, tenantIdentityGeneration, tenantCode, cursor, limit)`이고 page
  body와 ETag를 함께 저장한다. `tenantIdentityGeneration`은 route의 tenantCode와
  immutable TenantGroup.id 해석 결과를 결합한 session-memory opaque generation이며
  raw tenant ID를 저장하거나 노출하지 않는다. tenant resolver는 각 요청 전에
  `tenantCode`의 canonical `TenantGroup.id` 해석에 대응하는 서버 발급 opaque
  `tenantIdentityGeneration`을 `X-Tenant-Identity-Generation` response header로 반환한다.
  header grammar는 `^v1\.[A-Za-z0-9_-]{1,32}$`이고 ASCII 128 byte 이하이며, `v1.` 뒤
  segment는 opaque server-generated token으로만 취급한다. 이 grammar·길이 검증은
  backend와 frontend가 같은 shared contract fixture로 수행한다.
  첫 요청의 generation은 `null` bootstrap으로 시작하며 cache와 `If-None-Match`를 사용하지
  않는다. 유효한 response header를 현재 `RequestEpoch`에 채택한 뒤 body를 적용한다.
  이미 non-null generation을 캡처한 요청의 response header가 다르면 body/ETag를 적용하지
  않고 purge한 뒤 epoch당 한 번만 새 generation으로 첫 페이지를 다시 요청한다. 두 번째
  mismatch, header 누락 또는 grammar 위반은 cache 없는 stable error UI로 중단한다.
  로그아웃·환자 교체·component destroy 때 모든 page entry를 제거한다. cursor, ref,
  ETag, query string은 access log·trace·metric label에서 redaction한다.
- cursor TTL은 registry가 기록한 실제 최초 발급 시각 기준 30분이며 payload의 `issuedAt`과
  `issuedAtBucket`을 함께 인증하고 registry 값과 일치하는지 확인한다. active
  key와 previous key의 overlap은 최소 2시간(최대 TTL보다 길게) 유지한다. rotation 시
  active key를 교체한 뒤 2시간 동안 previous key를 읽기 전용으로 유지하고, rollback은
  직전 key를 active로 복원한 뒤 동일 overlap을 보장한다. 만료·unknown-key·tag 실패는
  각각 bounded `patient_history_cursor_invalid_total{reason}` metric으로 집계하되 raw token은 label로
  사용하지 않는다.

### 오류 계약

| HTTP | code | 상황 | 환자 동작 |
|---:|---|---|---|
| 400 | `PATIENT_HISTORY_PAYLOAD_INVALID` | cursor·`If-None-Match` 문법/길이·변조·만료·unknown/retired key·tag | cursor/ETag를 폐기하고 generation당 한 번만 첫 페이지를 재요청 |
| 400 | `PATIENT_HISTORY_LIMIT_INVALID` | limit가 `1..50` 범위를 벗어남 | 서버는 DB 조회 없이 거부하고, client만 generation당 한 번 `limit=20`으로 정규화해 재요청 |
| 401 | 기존 security 401 | cookie 없음·만료·검증 실패 | 이력·page cache·cursor·ETag·pending state를 먼저 동기 purge한 뒤 로그인 화면으로 이동 |
| 403 | `PATIENT_HISTORY_SCOPE_FORBIDDEN` | PATIENT가 아니거나 tenant 증거 부족 | 데이터를 비우고 재시도하지 않음 |
| 404 | `PATIENT_HISTORY_TENANT_NOT_FOUND` | active tenant 없음 | tenant 선택 오류를 표시 |
| 409 | `PATIENT_HISTORY_SNAPSHOT_CONFLICT` | cursor tenant/scope 불일치, 삭제·불일치 anchor | page cache/cursor를 폐기하고 generation당 한 번만 첫 페이지 재조회 |
| 503 | `PATIENT_HISTORY_UNAVAILABLE` | fingerprint/DB/opaque registry timeout·unavailable·capacity·linearizability·authenticated unexpired missing-entry·digest/nonce collision 장애 | `Retry-After: 1`, correlation ID를 표시하고 자동 재시도하지 않음 |
| 500 | `PATIENT_HISTORY_RESPONSE_TOO_LARGE` | canonical response가 256 KiB를 초과하는 결정적 payload/config 오류 | 자동 재시도하지 않고 운영 alert와 correlation ID만 표시 |

read-only endpoint에는 mutation 충돌이 없으므로 409는 cursor scope/anchor snapshot
충돌에만 사용한다. 오류 body는 401 legacy security envelope를 제외한 application 오류에서
동일한 schema를 사용한다. 401은 별도 union으로 OpenAPI/client에 고정한다.

```json
{
  "code": "PATIENT_HISTORY_UNAVAILABLE",
  "message": "취소 이력을 잠시 불러올 수 없습니다.",
  "correlationId": "01J..."
}
```

`Content-Type`은 `application/json`, `correlationId`는 `X-Correlation-Id` header와
body에 같은 opaque request ID로 제공한다. `Retry-After`는 503에서만 정수 초(여기서는
`1`)로 제공한다. 200/304의 `ETag`는 정확히 `"sha256:[0-9a-f]{64}"`인 strong quoted
tag이고, 304는 body와 `Content-Length` 없이 `ETag`, `X-Tenant-Identity-Generation`,
`Cache-Control: no-store`, `Vary: Cookie`, `X-Correlation-Id`를 반환한다. 이 generation
header가 누락·malformed이면 304 body를 적용하지 않고 fail-closed 한다. malformed ETag는 위 400 schema로
응답한다. 오류 body에는 SQL, tenant ID, fingerprint, token, 원문 exception을 넣지 않는다.
500 `PATIENT_HISTORY_RESPONSE_TOO_LARGE`에는 `Retry-After`를 보내지 않으며, 이 known
deterministic error는 unexpected 5xx alert 분모에서 제외한다.

## 데이터 모델과 트랜잭션

### V28/V29/V30 migration

운영 migration은 한 번의 장시간 backfill로 묶지 않고 세 단계로 분리한다.

```sql
-- V28: expand only
ALTER TABLE scheduling_appointment_cancellation_details
    ADD COLUMN from_commitment_status VARCHAR(32),
    ADD COLUMN patient_scope_fingerprint VARCHAR(128);

-- V29: dialect별 index
-- PostgreSQL: CREATE INDEX CONCURRENTLY ... (executeInTransaction=false)
-- MySQL: ALTER TABLE ... ADD INDEX ..., ALGORITHM=INPLACE, LOCK=NONE
-- H2: 일반 CREATE INDEX (개발/테스트 전용)

-- V30: 비식별 재개 checkpoint
CREATE TABLE scheduling_patient_history_backfill_checkpoint (
    scope VARCHAR(64) PRIMARY KEY,
    migration_version INT NOT NULL DEFAULT 30,
    dialect VARCHAR(16) NOT NULL,
    last_detail_id BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP NOT NULL,
    CHECK (migration_version = 30),
    CHECK (last_detail_id >= 0)
);
```

V28은 nullable column만 추가하며 기존 writer가 null을 기록해도 동작해야 한다. V29는
PostgreSQL `CREATE INDEX CONCURRENTLY`, MySQL online DDL, H2 개발용 DDL로 분리한다.
PostgreSQL profile은 `spring.flyway.postgresql.transactional-lock=false`와
script-level `executeInTransaction=false`를 함께 사용한다. 세 dialect의 index DDL은
30초 lock/statement timeout을 넘으면 endpoint를 활성화하지 않고 재시도한다.

각 방언의 운영 preflight는 별도 실행 증거로 남긴다. `appointment_id` 누락, appointment
lookup 불일치, appointment clinic과 detail clinic 불일치, clinic tenant와 detail tenant
불일치, duplicate scope를 PII 없이 count하고 하나라도 0이 아니면 중단한다. PostgreSQL은
`COUNT(*) FILTER (WHERE ...)`, MySQL/H2는 동등한 `SUM(CASE WHEN ... THEN 1 ELSE 0 END)`를
사용한다. preflight 실패 뒤에는 column/index를 되돌리지 않고 endpoint flag를 off로 둔 채
forward-fix한다.

V30 backfill runner는 `appointment.patient-history.backfill-enabled=true`일 때만 실행한다.
detail `id` 오름차순 keyset으로 한 transaction당 500행 이하를 처리하고, 동일 transaction에서
`id > last_detail_id` checkpoint를 원자적으로 갱신한다. checkpoint에는 migration version,
dialect, 마지막 detail PK와 시각만 저장하며 patient/tenant 식별자는 저장하지 않는다. detail과
appointment의 clinic/tenant가 일치하고 appointment fingerprint가 있을 때만 업데이트한다.
그 외 residual row는 추정하거나 삭제하지 않고 readiness를 계속 false로 둔다.

V28은 다음 단계로 운영한다.

1. **Expand** — nullable column을 추가하고 기존 writer가 이를 무시해도 동작하도록 한다.
2. **Index** — PostgreSQL은 별도 `executeInTransaction=false` migration에서
   `CREATE INDEX CONCURRENTLY`, MySQL은 online DDL 옵션, H2는 단일 migration DDL을
   사용한다. lock/statement timeout은 30초이며 초과하면 endpoint flag를 켜지 않고
   migration을 재실행한다. PostgreSQL index migration과 column/backfill migration의
   실행 순서와 Flyway history row를 별도 smoke test로 확인한다.
3. **Backfill** — V30 runner가 detail `id` 오름차순 keyset으로 500행 이하씩 별도
   transaction에서 업데이트한다. batch 중단·재개는 writer를 중지하지 않고, residual count와
   lastId를 metric과 smoke artifact에 남긴다.
4. **Preflight/readiness** — cross-scope/missing/duplicate count와 null residual을
   조회하고, residual이 0이며 모든 cancellation writer가 `from_status`와
   `patient_scope_fingerprint`를 기록하는 version fence를 통과할 때만 producer flag를
   활성화한다.
5. **Steady probe** — endpoint readiness bean이 60초마다 residual count와 최근 5분
   신규 row의 두 column non-null 비율을 검사한다. residual 또는 non-null 비율 저하가
   있으면 flag를 자동으로 끄고 `patient_history_readiness` alert를 발생시킨다.

기존 row를 삭제하거나 상태를 추정하지 않는다. mixed-version 동안에는 old writer가
nullable column을 null로 기록하는 것을 허용하지만 endpoint producer flag는 꺼져 있다.
writer version fence가 모든 replica에서 `historyWriterVersion >= 2`임을 확인한 뒤에만
새 writer가 null insert를 거부하며 endpoint를 켠다. old writer가 drain되지 않았거나
신규 row null 비율이 0이 아니면 producer flag는 자동으로 꺼진다. 따라서 nullable
schema compatibility와 non-null application enforcement는 서로 다른 rollout 단계에서
적용된다. Exposed `AppointmentCancellationDetails`도 두 nullable column과
`(tenantGroupId, patientScopeFingerprint, occurredAt, id)` index를 선언하고
`SchemaInitConfig` 목록을 유지한다.

### Repository 경계

`appointment-core`에 `PatientCancellationHistoryRepository`와
`PatientCancellationHistoryRecord`를 추가한다. repository는 transaction을 소유하지
않고 호출자가 연 transaction 안에서 실행한다. 한 요청의 DB read는 반드시 하나의
read-only `REPEATABLE_READ` snapshot transaction 안에서 수행하며, 최대 세 개의
고정 select만 허용한다(목록 anchor 확인 1회, page 1회, page ID metadata batch 1회).
page 조회는 `tenant_group_id + patient_scope_fingerprint + (occurredAt,id)` 복합
인덱스를 타야 하며, EXPLAIN 회귀 gate는 `limit+1`을 넘어 tenant 전체를 순회하는
계획을 거부한다.

조회 순서는 다음과 같다.

1. cursor anchor가 있으면 detail ID와 keyset을 같은 tenant/scope 조건으로 확인한다.
2. `AppointmentCancellationDetails`를 tenant + patient scope fingerprint로 제한하고
   keyset predicate를 적용해 `limit+1` page를 읽는다. appointment/proposal 시간은 이
   page select의 join으로 가져온다.
3. page에 포함된 detail ID만 `AppointmentItems`, `AppointmentPlanRevisions`,
   `AppointmentPlans`, `PlanRevisionTreatments`에서 한 번에 metadata batch 조회한다. SQL은
   detail별 `ROW_NUMBER() OVER (PARTITION BY detail_id ORDER BY item_id)`가 8개 결과를
   넘기지 않도록 dialect별 `LIMIT 8` bounded subquery를 사용한다. 9번째 존재 여부는
   결과 행을 추가로 반환하지 않는 `EXISTS` overflow marker로 계산하므로 metadata 결과
   row는 page당 400행 상한을 넘지 않는다.
4. detail ID별로 중복 item을 묶어 단일 상품명·단일 sequence만 확정한다. metadata join
   fan-out과 EXPLAIN row cap은 고정 fixture로 검증하며 N+1 query는 금지한다.
5. select 목록에는 raw name/phone/external ID, actor hashes, detail hash를 넣지 않는다.

`patient_scope_fingerprint IS NULL`인 legacy row는 readiness가 0을 보장하기 전까지
활성 endpoint에서 읽지 않는다. 따라서 fallback으로 tenant 전체를 스캔하거나
`Appointments`에 대한 unbounded patient filter를 두지 않는다. backfill은 appointment
FK와 동일 tenant/clinic 조건을 함께 검사하고, duplicate/missing appointment row가
있으면 migration을 실패시킨다.

repository의 public record는 patient response에 필요한 값만 가진다. `appointmentId`는
외부 DTO로 직접 내보내지 않고 API 계층의 `PatientCancellationHistoryReferenceCodec`로
`appointmentRef`를 만든다.

모듈 책임은 다음으로 고정한다.

| 모듈 | 소유 계약 | 의존 방향 |
|---|---|---|
| `appointment-core` | Exposed table, nullable V28 columns, repository, 내부 read record | API/HTTP/security를 참조하지 않음 |
| `appointment-api` | patient fingerprint 계산, service/retry/transaction, API DTO, cursor/reference/ETag codec, controller, HTTP/security/error mapping | core를 참조 |
| `frontend/appointment-frontend` | typed client, session-bound page cache, facade state machine, timeline/accessibility | HTTP DTO를 contract test로 일치 |

repository는 `PatientCancellationHistoryPage` 내부 record만 반환하고 API DTO를 만들지
않는다. `PatientCancellationHistoryService`가 `transaction {}`와 JDBC connection의
read-only/`REPEATABLE_READ` 설정을 소유하며, 첫 시도와 재시도는 각각 새 transaction으로
호출한다. retry loop가 repository transaction을 중첩 호출하지 않는다.

canonical codec golden vector는 다음 field order와 binary type을 고정한다.

| 필드 | encoding |
|---|---|
| `version`, `issuedKeyId`, purpose | UTF-8 string: 4-byte unsigned big-endian length + bytes |
| `issuedAt`, `issuedAtBucket`, `occurredAt`, `visitStartAt`, `visitEndAt` | signed epoch milliseconds: 8-byte big-endian |
| `tenantGroupId`, `appointmentId`, `detailId`, `sessionNumber`, `totalSessions`, `limit`, `entry-count` | signed 64-bit long: 8-byte big-endian |
| all status/reason/actor/product/ref/cursor strings | UTF-8 NFC-normalized string: 4-byte unsigned big-endian length + bytes |
| nullable field | 1-byte marker (`0x00=null`, `0x01=value`) before the value |

cursor는 `version,issuedKeyId,issuedAt,issuedAtBucket,tenantGroupId,patientScopeFingerprint,occurredAt,detailId`를
위 encoding으로 암호화하고, reference는
`purpose,tenantGroupId,patientScopeFingerprint,appointmentId,detailId`를 같은 규칙으로
HMAC한다. ETag는
`limit,requested-cursor-present,requested-cursor,next-cursor-present,next-cursor,entry-count`
다음 각 entry의 `appointmentRef,productName,sessionNumber,totalSessions,visitStartAt,visitEndAt,fromStatus,fromStatusLabel,toStatus,toStatusLabel,reasonCode,reasonLabel,reasonDetail,actorRole,actorLabel,occurredAt`를 고정 순서와 null marker로 직렬화한다. empty
page는 entry-count `0`, null cursor는 marker `0`으로만 표현한다. `nextCursor`가 null에서
값으로 바뀌거나 값만 바뀌어도 ETag가 달라져 304가 아닌 200을 반환한다. 각 codec에는 active/previous/retired key,
malformed segment, null/empty response에 대한 고정 golden vector를 둔다.

### 취소 command 연계

`AppointmentCommitmentCommandService`의 기존 취소 transaction에서 CAS 직전 읽은
commitment status를 `fromCommitmentStatus`로 insert한다. 상태 CAS, projection cancel,
cancellation detail, audit, outbox, idempotency snapshot의 transaction 경계는
#34와 동일하게 유지한다. 기존 notification payload와 raw audit 저장 범위는 확장하지
않는다.

## API 구현 경계

`PatientCancellationHistoryService`는 다음을 담당한다.

- canonical tenant 조회와 patient subject fingerprint 계산
- limit/cursor decode와 anchor conflict 검사
- repository page 조회 및 opaque reference 매핑
- canonical response hash와 ETag 비교
- 오류 registry(`PATIENT_HISTORY_*`) 변환
- DB 장애 재시도의 단일 owner. backend만 총 2회(첫 시도 + 1회) 재시도하며,
  `40001`, `40P01`, `08xxx` 계열의 transient connection/serialization/deadlock만
  750ms 총 deadline 안에서 25~75ms jitter 후 새 read-only transaction으로 재실행한다.
  요청 시작 monotonic timestamp에서 남은 budget을 connection acquisition, 각 SQL
  statement timeout, metadata batch와 jitter에 전달하고, 남은 시간이 0이면 DB 호출과
  jitter를 즉시 중단한다. statement/lock timeout은 remaining budget보다 길게 설정하지
  않는다. auth/cursor/설정 오류와 모든 4xx는 재시도하지 않는다. frontend는 자동 재시도하지
  않고 `Retry-After: 1`(초 단위, 1회 수동 시도)와 correlation ID를 표시한다. limit
  오류는 서버가 `400 PATIENT_HISTORY_LIMIT_INVALID`로 거부한다. client만 입력을 버리고
  generation당 한 번 `limit=20`으로 정규화한 첫 페이지를 요청하며, 동일 generation에서
  같은 invalid query를 반복 전송하지 않는다. cursor 오류는 cursor/ETag를 비운 뒤에만
  one-shot first-page recovery를 수행한다.
- opaque registry read/`putIfAbsent`도 같은 750ms monotonic request deadline에 포함한다.
  registry timeout·unavailable·capacity-full·non-linearizable·missing-entry·collision 결과는
  partial page나 새 token을 반환하지 않고 `PATIENT_HISTORY_UNAVAILABLE` 503과
  `Retry-After: 1`로 매핑하며, `patient_history_registry_failure_total{reason}`과
  readiness residual을 기록한다. missing-entry·collision 또는 readiness false가 감지되면 endpoint
  feature flag를 즉시 off하고 운영 alert를 발생시킨다. 이
  경우 frontend는 cursor/ETag를 400처럼 폐기하거나 자동 first-page recovery하지 않고
  동일 page를 수동 재시도하게 한다.

controller는 HTTP query/header와 `ResponseEntity`만 다룬다. API 계층에서 fingerprint,
token, raw audit를 로그에 남기지 않는다. `GlobalExceptionHandler`와
`SecurityConfig`의 path classifier뿐 아니라 `TenantContextFilter`의 early tenant
lookup와 `SecurityErrorResponseWriter`도 새 경로를 명시적으로 인식하게 한다. access
log·trace·metric에는 correlation ID, error code, bounded category만 남기며 cursor,
appointmentRef, ETag, full query string, exception message/stack의 원문을 기록하지
않는다. 이를 검증하는 log-capture negative test를 둔다.
허용 metric은 `patient_history_requests_total{outcome}`,
`patient_history_retry_total{sql_state_class}`, `patient_history_cursor_conflict_total`,
`patient_history_304_total`, `patient_history_readiness{state}`,
`patient_history_cursor_invalid_total{reason}`,
`patient_history_registry_failure_total{reason}`,
`patient_history_metadata_ambiguous_total`로 제한하며 label은
고정 enum만 사용하고 tenant/patient/cursor/ref를 넣지 않는다. `outcome`과 `state`는
각각 6개 이하, `sql_state_class`는 4개 이하, cursor `reason`은
`expired|unknown_key|tag|grammar|scope` 5개 이하, registry `timeout|unavailable|capacity|nonlinearizable|collision|missing_entry` 6개 이하 cardinality를 갖는다. registry failure·missing-entry·readiness residual·flag-off는 503 wire/alert/rollout-gate test로 검증하며, 모든 metric은
14일 보존한다. readiness residual>0,
unexpected 5xx>1%, retry deadline exceeded>0, cursor conflict 급증(5분 100건 초과)은
alert를 발생시키며 보존 기간은 14일이다. 모든 4xx/5xx response에는 correlation ID
header가 있고, retry/503 contract test에서 이를 확인한다.

## 포털 UX와 TypeScript 경계

`PatientCancellationHistoryComponent`를 standalone component로 만들고
`PatientAppointmentsPageComponent`의 현재 예약 영역 아래에 배치한다.

- 최초 진입: skeleton(`aria-hidden="true"`)과 list container `aria-busy="true"`, 별도
  `aria-live="polite"` loading announcement
- 결과 없음: “표시할 취소 이력이 없습니다” empty state. aggregate 삭제나 legacy
  snapshot 누락을 “취소한 적 없음”으로 해석하지 않는다.
- 결과 있음: `<ol>` timeline, 날짜·상품명·`3회차 / 10회`, 방문 시각, 상태 전이,
  reason label/detail, 취소 actor category를 표시한다. nullable 값은 각각
  `상품 정보 없음`, `회차 정보 없음`, `방문 시간 확인 불가`, `이전 상태 확인 불가`,
  `사유 확인 불가`, `확인 불가`로 표시하고 raw enum/`undefined`를 렌더링하지 않는다.
- 다음 페이지: `더 보기` button으로 keyset cursor를 이어서 읽는다.
- 관찰된 `tenantCode`가 현재 facade tenant와 달라지는 순간 `RequestEpoch`를 즉시
  증가시키고 서버가 발급한 `tenantIdentityGeneration`을 `null`로 되돌린다. 이어서
  `entries`, `nextCursor`, `etag`, 모든 page cache, pending bookkeeping, 이전 error를
  동기 purge하고 `initialLoading`으로 전환한 뒤 새 tenant의 첫 요청을 시작한다. 첫 요청은
  cache와 `If-None-Match` 없이 수행하고, 응답의 유효한
  `X-Tenant-Identity-Generation`을 새 epoch에 채택한다. 따라서 A tenant에서 B tenant로
  바뀌는 한 frame에도 A 이력이 남지 않는다. B의 403/404도 빈 state에서만 표시하고,
  A의 delayed success/error/304는 `(RequestEpoch, sessionVersion, tenantCode, cursor,
  limit)` tuple 불일치로 폐기한다. 이 경계는 route 재사용과 직접 tenant signal 변경 모두에
  적용한다.
- 401/403/404/409/500/503을 각각 purge 후 로그인·권한·tenant·cursor 폐기 후 첫 페이지
  재조회·retry 안내로 구분한다. `PATIENT_HISTORY_RESPONSE_TOO_LARGE`(500)는 deterministic
  config 오류 문구와 correlation ID만 표시하고 자동 재시도하지 않는다. invalid cursor와 409의 자동 첫 페이지 재조회는
  session generation당 1회만 허용하고, 반복 실패는 자동 loop 없이 안정된 retry UI로
  전환한다. raw error body는 화면에 반사하지 않는다.
- facade 상태는 `initialLoading | ready | loadingMore | initialError | loadMoreError |
  exhausted`로 고정한다. `busy`는 이 상태 union에서 계산하는 read-only computed 값이며
  별도 mutable signal로 저장하지 않는다. 오류 payload도 `initialError` 또는
  `loadMoreError` variant에만 결속하며 `ready + error` 같은 조합을 허용하지 않는다.
  load-more 실패는 기존 entries와 동일 cursor를 보존하고 수동 재시도한다. 성공 append는
  한 번에 적용하고 appointmentRef 중복을 제거한다. `loadMore()` 반환값은
  `{ kind: 'accepted' } | { kind: 'busy' } | { kind: 'exhausted' }`로 고정해 caller가
  boolean을 상태로 오해하지 않게 한다. `더 보기`는 loading 중 `aria-disabled`/disabled
  상태를 제공하고, append 후에도 버튼 focus를 유지한다. exhausted 상태에서도 native
  `disabled`를 사용하지 않고 `aria-disabled="true"`와 activation guard를 사용해 같은
  focusable control을 유지한다. 추가된 항목 수는
  `aria-live="polite"`로 알린다. `initialError`와 `loadMoreError`는 `role="alert"`인
  assertive live region으로 code가 아닌 환자용 오류 문구를 알린다. empty/result summary는
  `role="status"`로 노출하고 skeleton은 `aria-hidden="true"`로 유지한다. 마지막
  append로 `exhausted`가 되면 `더 보기`를 제거하지 않고 `aria-disabled="true"`인 완료 상태와
  “모든 취소 이력을 불러왔습니다”를 같은 focusable control에 표시해 현재 focus를
  잃지 않게 한다. 결과가 처음부터 exhausted인 경우에는 summary status로 focus를
  이동하지 않고 현재 landmark를 유지한다.
- timeline 날짜는 `<time datetime>`로 제공하고, UTC instant를 브라우저의 현재
  timezone으로 `Intl.DateTimeFormat('ko-KR', { year: 'numeric', month: 'short', day: 'numeric',
  hour: '2-digit', minute: '2-digit', timeZoneName: 'short' })`로 표시하며 화면에 timezone
  abbreviation을 함께 보여 준다. 이 명시적 component 조합은 `dateStyle/timeStyle` shortcut과
  `timeZoneName`의 런타임 충돌을 피한다.
  상태는 색상만으로 구분하지 않는다. `320px`에서 가로 스크롤 없이 날짜와 상품/회차가
  줄바꿈된다. 모든 action은 키보드로 접근하고 focus-visible outline을 유지한다.

`PortalApiClient`는 자유로운 `previousBody`, `etag`, cache 객체 인자를 받지 않고
`getCancellationHistory(query: PatientHistoryQuery)`만 public으로 제공한다. public query에는
`cursor`와 `limit`만 있고, facade가 `PatientAuthService`와 tenant resolver에서 얻은
`RequestEpoch`/identity tuple을 private `PatientHistoryPageRequest`로 조립한다. 이 내부
request에만 immutable `(sessionVersion, tenantIdentityGeneration, tenantCode, cursor, limit)`가
들어간다. facade 내부의 private session-bound cache map이 이 tuple을 자체 canonical
key로 계산해 body/ETag를 조회하며 caller가 제공한 key/body/ETag는 존재하지 않는다.
client는 현재 auth/session resolver가 제공한 tuple과 private cache key가 완전히 일치할
때만 `If-None-Match`를 전송하며, 304인데 private body cache가 없거나 tuple이 다르면 기존
body를 사용하지 않고 1회 unconditional fetch를 수행한다. 401 처리의 첫 동작은
`RequestEpoch++`, entries/nextCursor/etag/page cache/pending state purge이며 그 뒤에만
login navigation을 실행한다. 서버 오류는 기존 `PortalApiException` mapping을 사용한다.
반환 타입은 `CancellationHistoryPageResult = { kind: 'body'; body: CancellationHistoryResponse; etag: string } | { kind: 'not-modified'; body: CancellationHistoryResponse; etag: string }`로 고정하고, `PortalApiException`은 `code`, `status`, `correlationId`, `retryAfterSeconds`, `message`를 보유한다. `UNAUTHORIZED`는 기존 security envelope의 `errorCode`를 `code`로 변환하며 raw `error` 문구는 화면에 반사하지 않는다. client는 고정된 한국어 로그인 안내 문구와 `correlationId`만 보유하고 `retryAfterSeconds`는 `null`이다. OpenAPI와 client adapter는 `success/data/error/errorCode/correlationId`의 401 fixture와 sanitized Korean UX message assertion을 공유한다.

TypeScript contract는 다음과 같이 OpenAPI와 1:1로 고정한다.

```ts
type PatientHistoryQuery = {
  cursor: string | null;
  limit: number;
};

type PatientHistoryPageRequest = {
  requestEpoch: number;
  sessionVersion: number;
  tenantIdentityGeneration: string | null;
  tenantCode: string;
  cursor: string | null;
  limit: number;
};

type CancellationHistoryEntry = {
  appointmentRef: string;
  productName: string | null;
  sessionNumber: number | null;
  totalSessions: number | null;
  visitStartAt: string | null;
  visitEndAt: string | null;
  fromStatus: string | null;
  fromStatusLabel: string | null;
  toStatus: string;
  toStatusLabel: string;
  reasonCode: string;
  reasonLabel: string;
  reasonDetail: string | null;
  actorRole: string;
  actorLabel: string;
  occurredAt: string;
};

type CancellationHistoryResponse = {
  limit: number;
  entries: CancellationHistoryEntry[];
  nextCursor: string | null;
};

type PortalApiExceptionShape = {
  code: string;
  status: number;
  correlationId: string;
  retryAfterSeconds: number | null;
  message: string;
};
```

`getCancellationHistory`는 `query: PatientHistoryQuery`를 받아 내부
`PatientHistoryPageRequest`를 통해 `Promise<CancellationHistoryPageResult>`를 반환하며, `etag`는
200/304에서 항상 non-null strong tag이다. `nextCursor`는 마지막 page에서만 `null`이고,
generation이 `null`인 bootstrap request는 cache와 `If-None-Match`를 사용하지 않는다. valid
header를 받은 뒤 private cache key에 generation을 포함한다. private cache는 request의
전체 tuple에서만 생성되며 public caller가 공급할 수 없다. shared
JSON fixture가 위 필드 순서·nullability와 401 legacy/application error union을 동시에
검증하고, adversarial caller가 stale body/etag를 주입할 수 없음을 확인한다.

`PatientCancellationHistoryFacade`는 page component scope provider로 생성하고
`status`, `entries`, `nextCursor`, `etag`를 signal로 보유한다. `busy`와 `error`는
각각 status union에서 파생한 computed 값으로만 노출한다.
`PatientAuthService`는 login/session replacement/logout 시작 시 동기적으로 증가하는
  `sessionVersion`을 노출한다. facade의 `RequestEpoch`는 sessionVersion, tenant
  `tenantIdentityGeneration`, logout/401/component lifecycle을 포함한 단조 증가 정수이며,
  각 async 요청은 시작 시 immutable
`(requestEpoch, sessionVersion, tenantIdentityGeneration, tenantCode, cursor, limit)`을
캡처하고 success/error/finally에서 현재 값과 일치할 때만 state를 적용한다. tenant 변경·logout 시작과 component destroy는 `RequestEpoch`를
증가시키고 in-flight request를 논리적으로 취소한다(취소된 Promise의 결과는 절대 적용하지
않음). busy 중 중복 load-more는 `{ kind: 'busy' }`를 반환하며 caller는 intent/cursor를
삭제하지 않는다. exhausted 상태의 호출은 `{ kind: 'exhausted' }`를 반환한다. facade
destroy 시 page cache와 pending request bookkeeping을 함께 clear한다.
304 요청도 동일한 page cache key가 이미 있을 때만 `If-None-Match`를 전송하며, 다른
cursor/limit에 이전 page body를 재사용하지 않는다. component destroy는 pending request의
결과 적용을 금지하고 facade 인스턴스를 폐기한다. rollback으로 history component가
제거되면 기존 appointments page state만 유지하고 history cache는 복원하지 않는다.

## 실패 모드와 방어

1. **clinic 없는 PATIENT JWT**: tenant-only route와 `clinicId=null` actor resolver로
   정상 세션을 허용한다. clinic path를 추가하지 않는다.
2. **다른 환자의 이력 추측**: 모든 SQL에 tenant + fingerprint predicate를 적용하고
   결과가 없으면 빈 목록만 반환한다. appointmentRef와 cursor도 다른 tenant에서
   재사용할 수 없다.
3. **V27 legacy row**: `fromStatus=null`로 표시하고 UI가 임의의 이전 상태를 만들어내지
   않는다.
4. **stale cursor**: anchor가 사라지면 409로 중단하고 첫 페이지 재조회만 허용한다.
5. **304 뒤 환자 전환**: facade generation 검증으로 이전 환자의 response 적용을
   거부하고 logout/session change와 401 처리 시작 시 ETag, entries, page cache를
   삭제한다. 304 body가 없거나 cache key가 불일치하면 body를 재사용하지 않고 한 번만
   unconditional fetch하며, 같은 generation에서 반복 실패하면 retry UI로 멈춘다.
6. **상품/회차 join 중복**: detail ID별 distinct 값이 하나일 때만 product/session을
   채우고 모호하면 null을 반환한다.
7. **DB/fingerprint 일시 장애**: backend만 새 read-only transaction에서 한 번 재시도하고
   750ms deadline을 넘기면 503과 bounded retry를 반환하며 partial page를 성공으로
   저장하지 않는다. frontend는 같은 요청을 자동 재전송하지 않는다.
8. **opaque registry readiness 장애**: endpoint route와 security/path classification은
   유지한다. readiness=false 또는 flag-off 상태의 모든 history 요청은 tenant-not-found
   404로 바꾸지 않고 동일한 sanitized `503 PATIENT_HISTORY_UNAVAILABLE`,
   `Retry-After: 1`, `X-Correlation-Id` contract를 반환한다. 기존 page/cursor를 임의로
   삭제하거나 partial page를 반환하지 않으며 frontend는 수동 재시도 UI를 유지한다.

## 대안과 선택 이유

| 대안 | 결정 | 이유 |
|---|---|---|
| `/{clinicId}/...` patient history | 거부 | 현재 PATIENT JWT의 `allowedClinicIds=[]`와 충돌한다. |
| 기존 `AppointmentStateHistory` 재사용 | 거부 | tenant·patient ownership과 #34의 server-owned detail snapshot을 보장하지 않는다. |
| raw `appointmentId` 반환 | 거부 | 내부 식별자 노출 범위를 넓힌다. opaque reference를 사용한다. |
| offset pagination | 거부 | 취소 삽입·삭제 시 중복/누락이 발생한다. occurredAt/id keyset을 사용한다. |
| shared Redis 환자 캐시 | 거부 | tenant·patient cache key 실수 시 privacy 사고가 발생한다. `no-store`와 session-bound page cache만 사용한다. |
| V28에서 legacy row 상태 추정 | 거부 | 저장되지 않은 과거 상태를 만들어내면 감사 의미가 훼손된다. null을 표시한다. |

## 배포·롤백

- 배포 순서는 V28 expand/index → bounded backfill/readiness → shared opaque registry
  provision 및 linearizable read/write/restart/capacity readiness smoke → writer version
  fence 및 old-writer drain → backend dual-read/write → API/security flag → portal이다.
- `from_commitment_status`가 nullable이므로 이전 worker와의 schema 호환성을 유지한다.
- backend rollback은 새 endpoint/controller flag를 먼저 끄되 route는 유지해 sanitized 503을
  반환하고, residual/non-null probe와 writer version을 확인한 뒤 이전 binary를 허용한다.
  partial backfill은 마지막 `id` marker에서 재개할 수 있으며 V28 column/index를 즉시
  삭제하지 않는다. keyring은 이전 key를 cursor TTL 전체 동안 유지한다. 재활성화는
  preflight=0, residual=0, registry readiness=true, missing-entry/early-eviction 원인
  해소 및 필요 시 기존 cursor TTL drain, writer fence 통과, cursor issue→validate
  protected smoke와 protected HTTP smoke 통과를 다시 확인한 뒤에만 수행한다.
- portal rollback은 기존 appointments page를 배포하고 history API 호출을 숨긴다.
- 운영 전 확인할 항목은 실제 PostgreSQL migration, tenant-only patient JWT, cross-tenant/
  cross-patient negative test, 304 재검증, logout race, 320px accessibility다. 각 gate는
  `artifact`, `owner`, `when`, `threshold`, `rollback trigger`를 갖는다: migration
  artifact/DB owner/expand 직후/preflight·residual=0/lock timeout 또는 residual>0,
  protected HTTP artifact/API owner/flag 전환 직전/401·403·cross-scope 모두 기대값/
  unexpected 5xx 또는 privacy mismatch, SLO artifact/SRE owner/canary 30분/p95≤500ms,
  p99≤750ms·503<1%/초과 시 flag off. ACL·backup/restore·canary·SLO·protected backend
  증거가 생성되기 전에는 production 상태를 `PENDING`으로 유지한다.

## 수용 기준과 DoD

| 기준 | 구현·검증 증거 |
|---|---|
| tenant-only PATIENT history | API controller/security/OpenAPI test |
| cross-tenant·cross-patient 차단 | authenticated cursor가 현재 actor scope와 불일치할 때 registry mock zero interactions와 동일한 409를 검증하는 repository/service negative test 및 protected HTTP test |
| V27/V28 호환과 from-status | 세 방언 migration test, command insert test, legacy null test |
| product/session/visit metadata | repository fixture와 response contract test |
| keyset + 400/409 cursor | codec/service tests, `issuedAt` TTL/clock-skew and invalid-limit normalization tests |
| strong ETag + 304 | controller/client tests, deterministic continuation reuse and 200/304 `X-Tenant-Identity-Generation` header test |
| 400/401/403/404/500/503 contract | security/handler tests, invalid-limit server rejection and client one-shot normalization |
| portal loading/empty/error/load-more | facade/component tests |
| logout/session race 방지 | generation reset delayed-response test |
| 401/cache purge와 304 caller misuse 방지 | session-bound cache key, invalid 304 unconditional fetch, 401 synchronous purge tests |
| portal state/accessibility | initial/load-more state machine, tenant A→B purge, aria/focus/timezone/label/null fallback assertions, exhausted `aria-disabled` focus guard |
| 320px·키보드·스크린리더 | component DOM/accessibility test와 CSS inspection |
| bounded DB access | patient-sparse large-tenant fixture, query-count(최대 3), PostgreSQL `EXPLAIN (ANALYZE, BUFFERS)` page scan ≤ 51 rows, metadata scan ≤ 400 rows |
| metadata fan-out | bounded per-detail subquery와 단일 `IN (:detailIds)` batch, detail당 최대 8 metadata row contract; 초과 시 해당 metadata를 `null`로 만들고 `patient_history_metadata_ambiguous_total`을 증가시키는 N+1 negative test |
| retry/ETag 비용 | transient SQLSTATE 1회 재시도·비transient 0회, 200 p95 ≤ 500ms/p99 ≤ 750ms, 304 p95 ≤ 350ms, canonical codec allocation ≤ 512 KiB evidence |
| migration 운영 gate | dialect별 preflight/backfill/resume/readiness/flag-off 및 online DDL smoke artifact |
| observability | 허용 label/cardinality, readiness·retry·cursor-invalid·registry-missing-entry·metadata-ambiguous·409·503·304 metric과 alert threshold, missing-entry 즉시 endpoint flag-off와 route-preserving sanitized 503, correlation ID header test |
| cursor lifecycle | cursor TTL 30분, active/previous key overlap 2시간, rotation·rollback·invalidation metric test, K1 발급→K2 rotation→K1 previous-key 검증 성공·outer/inner key ID mismatch 거부 golden vector, issuance key ID별 shared registry 동시 발급·다중 replica·restart·capacity-full·조기 eviction·payload keyId 조회 negative test, cross-scope 409/registry zero-interaction test, registry 장애/503·missing-entry/early-eviction readiness·endpoint flag-off·bounded failure metric·partial-page 금지 test |
| registry collision invariant | digest collision과 nonce collision 각각 metric `collision`, readiness=false, route-preserving sanitized 503, endpoint flag-off, partial page/token 금지, cursor/ETag 보존 및 수동 retry test |
| HTTP wire contract | OpenAPI JSON error/success/legacy-401 schema, sanitized Korean 401 message, quoted ETag/304 headers including required `X-Tenant-Identity-Generation` (`^v1\.[A-Za-z0-9_-]{1,32}$`, ≤128 ASCII bytes), `Retry-After: 1`, `X-Correlation-Id` contract test; absent/malformed generation is fail-closed |
| canonical/golden vectors | cursor/reference/ETag field별 binary type/null marker/length/byte order와 non-null field mutation, key rotation golden vector |
| caller misuse resistance | public query에 identity/cache가 없고 private `RequestEpoch`·tuple-keyed cache만 사용, adversarial injection test |
| previous finding disposition | security/performance/stability/user/ops finding별 섹션·테스트·`CLOSED`/`PENDING` 추적표 |
| Kotlin/TypeScript 패턴과 문서 | `bluetape-kotlin-patterns`, targeted TypeScript review, Korean KDoc/README 검토 |

실제 production ACL, backup, canary, SLO, protected backend은 이 브랜치의 로컬 테스트로
증명하지 않으며 최종 보고에서 `PENDING`으로 남긴다.

## 작성 게이트

### 이전 검토 finding disposition

| finding | 현재 섹션 | 상태 | 검증 gate |
|---|---|---|---|
| SEC-01 cursor/ref 환자·tenant 결속 | Query와 페이지 계약, canonical tenant identity | CLOSED (설계) | alias/id-change negative vector, cursor tamper/scope/key rotation golden tests |
| SEC-02 민감 응답 browser cache | ETag와 캐시 `no-store` | CLOSED (설계) | header contract + logout/401 purge test |
| PERF-01 patient sparse unbounded scan | V28 patient scope index, Repository 경계 | CLOSED (설계) | EXPLAIN row cap/query-count gate |
| PERF-02 metadata N+1 | Repository 경계 fixed batch | CLOSED (설계) | batch/fan-out test |
| STAB-01 logout/401 async race | TypeScript sessionVersion/RequestEpoch/tenantIdentityGeneration/401 purge | CLOSED (설계) | delayed success/401/tenant-generation/304 equality test |
| STAB-02 retry owner/deadline | API 구현 경계 backend-only retry | CLOSED (설계) | SQLSTATE/deadline/Retry-After test |
| OPS-01 migration/backfill/readiness | V28 운영 단계와 steady probe | CLOSED (설계) | dialect migration/readiness/flag-off artifact |
| OPS-02 production ACL/backup/canary/SLO | 배포·롤백 운영 gate | PENDING (외부) | protected backend/production evidence |

API/TypeScript wire contract의 `reasonLabel`, nullable fields, error schema, 304 union은
OpenAPI와 client contract test가 같은 JSON fixture를 읽도록 하며, 같은 canonical
`TenantGroup.id`에서 tenant alias만 바뀌면 cursor/reference bytes가 동일하고,
`TenantGroup.id`가 바뀌면 bytes가 달라지며 이전 cursor/reference가 거부되는 별도
negative vector를 둔다. `sessionIdentity`는 `sessionVersion`에서만 파생하고,
`tenantIdentityGeneration`은 `X-Tenant-Identity-Generation` response header에서만
갱신한다. logout/401/`tenantIdentityGeneration` 변경은 `RequestEpoch`를 증가시켜 cache와
in-flight equality를 무효화한다. 400/409 조건은 표에 적은 모든 경우를 포함하고,
scope mismatch와 deleted anchor의 client action은 동일한 one-shot first-page recovery로
고정한다.

위 표의 `CLOSED`는 설계 문서 수준이며 구현 테스트가 통과했다는 뜻이 아니다. `PENDING`
항목은 실제 운영 증거가 생성될 때까지 production-ready로 승격하지 않는다.

- **SPW-01 PASS** — Issue #305, `444e5cfa...`, 현재 Kotlin/Angular/security/schema
  근거와 미확정 legacy 상태를 고정했다.
- **SPW-02 PASS** — 문제, 경계, API·DB·UI 계약, 오류, 대안, migration, acceptance와
  DoD를 포함했다.
- **SPW-03 PASS** — 한국어 기술 문체와 `tenant`, `fingerprint`, `cursor`, `ETag`,
  `strong` 등 일관된 용어를 사용하고 identifier/command를 보존했다.
- **SPW-04 PASS** — `PatientJwtIssuer`, `ActorContextResolver`, V27 migration,
  `AppointmentCancellationDetails`, `PortalApiClient`와의 traceability를 확인했다.
- **SPW-05 PASS** — Markdown을 끝까지 read-back했고 표·코드 fence·오류 code를 확인했다.
