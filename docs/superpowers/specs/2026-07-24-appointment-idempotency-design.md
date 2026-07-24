# 예약 생성 Idempotency 설계

## 목적

`POST /api/{tenantCode}/appointments`의 네트워크 재시도와 모바일 중복 제출이 같은 예약을 여러 번 생성하지 않도록 한다. 호출자는 선택적으로 `Idempotency-Key` 헤더를 보내며, 같은 유효 키로 같은 요청을 재시도하면 최초 생성 결과를 안전하게 받는다.

## 범위와 비범위

### 범위

- 예약 생성 API의 선택적 `Idempotency-Key` 헤더
- 테넌트와 병원 단위의 키 범위, 요청 fingerprint, 만료 시각을 갖는 영속 idempotency 레코드
- 최초 생성, 안전한 재생, 같은 키의 요청 불일치, 만료 후 재사용, 동시 재시도 처리
- H2, PostgreSQL, MySQL Flyway 마이그레이션과 Exposed 모델/Repository
- OpenAPI 설명과 API·서비스·마이그레이션 회귀 테스트

### 비범위

- 기존 예약 수정·취소·상태 변경 API의 idempotency
- 클라이언트 SDK의 자동 키 생성·재시도 정책
- 전역 비동기 정리 작업 또는 별도 캐시/메시지 브로커 도입
- 예약 시간 자체의 업무 중복 판정 변경

## 호환성 계약

- 헤더가 없으면 기존 API와 완전히 같은 생성 경로를 사용하고 `201 Created`를 반환한다.
- 헤더가 있으면 길이 1~255자의 비공백 문자열만 허용한다. 잘못된 값은 기존 validation 규약에 따라 `400 Bad Request`로 응답한다.
- 모든 키 재생 요청도 먼저 현재 인증된 tenant의 clinic 소유권을 검증한다. 재생 결과가 다른 tenant 또는 clinic으로 넘어가지 않는다.
- 새 유효 키는 예약을 한 번 생성하고 `201 Created`를 반환한다.
- 유효 기간 안에 같은 키와 같은 fingerprint를 재전송하면 기존 예약을 `200 OK`로 반환하며, 새 예약이나 `AppointmentDomainEvent.Created`를 만들지 않는다.
- 유효 기간 안에 같은 키와 다른 fingerprint를 전송하면 `409 Conflict`와 안전한 오류 메시지를 반환한다.
- 키가 만료되면 기존 idempotency 레코드를 제거하고 동일 키를 새 생성 요청으로 취급한다. 기존 예약 자체는 삭제하지 않는다.

## 대안과 선택

| 대안 | 장점 | 단점 | 결정 |
|---|---|---|---|
| `appointments`에 key/fingerprint/expiry 컬럼 추가 | 조인 없음 | 생성 도메인 테이블에 transport 재시도 수명주기를 섞고, 만료 키 재사용 모델이 불명확 | 기각 |
| Redis 기반 키 잠금/응답 캐시 | 낮은 조회 지연 | 영속성·재시작·TTL·다중 DB 일관성이 약하고 새 인프라가 필요 | 기각 |
| 별도 DB idempotency 테이블과 유니크 제약 | DB 트랜잭션으로 예약 생성과 원자화, TTL·감사·재사용 경계 명확 | 한 번의 추가 조회/삽입 필요 | 채택 |

## 데이터 모델

새 `scheduling_appointment_idempotency` 테이블을 각 지원 DB에 추가한다.

| 컬럼 | 의미 |
|---|---|
| `id` | Long PK |
| `tenant_group_id` | 키의 테넌트 범위 |
| `clinic_id` | 키의 병원 범위 |
| `idempotency_key` | 호출자가 전달한 원본 키 |
| `request_fingerprint` | 정규화된 생성 요청의 SHA-256 hex 값 |
| `appointment_id` | 최초 생성된 예약 FK |
| `expires_at` | 키 재생을 허용하는 마지막 시각 |
| `created_at` | 최초 수락 시각 |

유니크 제약은 `(tenant_group_id, clinic_id, idempotency_key)`이며, 만료 정리와 조회를 위한 `expires_at` 인덱스를 둔다. 기본 TTL은 24시간이고 immutable constructor-bound `scheduling.appointment.idempotency.ttl` 설정으로 조정한다. TTL은 0보다 커야 하며, 현재 시각은 기본 UTC `Clock`을 주입해 테스트에서 고정한다.

## 처리 흐름

1. Controller는 tenant와 clinic 소유권을 확인하고, 새 생성 경로에서는 scheduling resource 접근을 검증한 뒤 선택적 헤더를 Service에 전달한다. 모든 재생 경로에도 tenant와 clinic 소유권 검증은 남긴다.
2. Service는 비공백 키를 정규화하고, API 요청의 도메인 필드를 고정 순서로 직렬화해 SHA-256 fingerprint를 만든다. 키와 민감 환자 정보는 로그에 기록하지 않는다.
3. 키가 없으면 기존 예약 생성과 이벤트 발행 흐름을 유지한다.
4. 키가 있으면 하나의 Exposed `transaction {}` 안에서 같은 범위의 만료 레코드를 제거하고 기존 레코드를 조회한다.
5. 기존 레코드의 fingerprint가 같으면 연결된 예약을 재생 결과로 반환한다. 다르면 전용 conflict 예외를 반환한다.
6. 레코드가 없으면 예약과 idempotency 레코드를 같은 트랜잭션에서 저장한다. DB 유니크 경합이 발생하면 트랜잭션을 롤백하고, 중복 키 제약 위반으로 확인된 경우에만 레코드를 다시 조회해 5단계의 재생/불일치 판정으로 수렴한다. 일반 DB 예외를 재생으로 숨기지 않는다.
7. `AppointmentDomainEvent.Created`는 실제 새 예약을 저장한 호출에서만 트랜잭션 성공 후 한 번 발행한다.

Service 결과는 `Created(appointment)`와 `Replayed(appointment)`를 구분하는 내부 결과 타입으로 표현한다. Controller는 `Created`에 201, `Replayed`에 200을 매핑한다.

## 오류·보안·운영

- fingerprint에는 tenant/clinic scope 외의 모든 생성 요청 필드를 포함하고 nullable 값은 명시적인 marker로 구분한다. 원시 JSON이나 정렬되지 않은 Map은 사용하지 않는다.
- 키는 DB와 오류 메시지에만 필요한 값으로 다루며 로그와 응답 본문에 노출하지 않는다.
- `IdempotencyKeyConflictException`은 `GlobalExceptionHandler`에서 409로 변환한다. 기존 `IllegalStateException` 기반 상태 전이 충돌 계약은 변경하지 않는다.
- Controller는 OpenAPI에 선택적 `Idempotency-Key` 헤더와 200 재생/409 불일치 응답을 명시한다.
- 마이그레이션은 additive다. 이전 애플리케이션 버전은 새 테이블을 무시할 수 있고, 롤백 시 테이블은 TTL이 지난 뒤 별도 운영 변경으로 제거한다.
- 동시 재시도 실패 신호는 유니크 충돌 재조회가 아닌 예상 밖 DB 오류일 때만 오류로 남기며, 그 경우 예약 생성과 key 저장은 같은 트랜잭션으로 함께 롤백된다.

## 검증 기준

1. 헤더 없이 생성하면 기존과 동일하게 201과 한 개의 예약을 반환한다.
2. 같은 키·같은 요청의 두 호출은 하나의 예약 ID를 반환하며 상태는 각각 201, 200이다.
3. 같은 키·다른 요청은 409이고 추가 예약을 만들지 않는다.
4. 만료된 키는 새 예약을 만들고 201을 반환한다.
5. 동시 같은 키 요청은 하나의 예약과 하나의 `Created` 도메인 이벤트만 만든다.
6. tenant 또는 clinic 범위가 다른 동일 키는 서로 영향을 주지 않으며, 인증되지 않은 tenant는 재생 결과를 얻지 못한다.
7. H2/PostgreSQL/MySQL Flyway 검증이 새 테이블과 제약을 통과한다.
8. `appointment-api` 모듈 테스트·build, 정적 검사, `git diff --check`가 통과한다.

## 위험과 완화

| 위험 | 감지 | 완화 |
|---|---|---|
| 유니크 경합으로 두 번째 요청이 500이 됨 | 동시성 테스트와 DB별 통합 테스트 | 유니크 예외 후 재조회·fingerprint 판정 |
| 이벤트 중복 발행 | 이벤트 publisher 검증 | 새 생성 결과에서만 트랜잭션 뒤 발행 |
| 만료 키가 재사용되지 않음 | 고정 Clock 기반 expiry 테스트 | 조회 전 범위·키의 만료 레코드 삭제 |
| 테넌트 간 키 충돌 | 다중 tenant 테스트 | tenantGroupId와 clinicId를 유니크 범위에 포함 |
