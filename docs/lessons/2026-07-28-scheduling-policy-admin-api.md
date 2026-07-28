# 예약 정책 관리 API에서 배운 점

## 맥락

정책 aggregate, approval, preview, activation worker, effective snapshot이 이미 있어도
HTTP API를 얇게 붙이는 작업은 단순한 controller 추가가 아니었다. tenant 기본값과 clinic
override가 같은 lifecycle을 가지면서도 scope identity, Gateway 인증정보, revision,
generation, preview evidence, idempotency가 한 요청에서 동시에 맞아야 했다.

## URL과 Gateway claim만으로 scope를 만든다

request body에 tenant, clinic, actor, role을 넣지 않는 것만으로는 충분하지 않았다. 전역 ID나
opaque token으로 행을 먼저 조회한 뒤 애플리케이션에서 tenant를 비교해도 외부 응답은 안전할
수 있지만, 멀티테넌트 저장소 경계는 약해진다. preview job과 완료 evidence token은 처음부터
`tenant_group_id`, `scope`, `clinic_scope_key`를 SQL predicate에 넣어야 했다.

이 원칙을 지키면 다른 tenant나 clinic의 ID·token을 넣은 요청은 “행이 있지만 권한이 없음”과
“행이 없음”을 구분하지 않고 같은 `POLICY_RESOURCE_NOT_FOUND` 또는 stale 결과로 닫힌다.

## 단계적 flag는 처리 가능한 결과까지 함께 봐야 한다

`adminWriteEnabled=true`, `previewWorkerEnabled=false`는 설정 의존성상 유효했다. draft를
관리하는 단계로는 맞지만, preview endpoint가 동기 한도를 넘겨 `202` job을 만들면 그 job을
처리할 worker가 없었다. 각각의 bean과 flag만 보면 정상인데, HTTP 결과와 후속 consumer를
함께 보면 영구 `PENDING`이라는 장애가 생긴다.

수정은 job을 만든 뒤 상태를 바꾸는 것이 아니라 submit 이전에 preview route를 숨기는
fail-closed guard였다. 단계적 rollout 테스트는 bean 존재뿐 아니라 “이 단계에서 생성한
durable work를 누가 끝내는가”를 검증해야 한다.

## OpenAPI는 런타임보다 느슨해도 결함이다

안정 오류를 직접 만들기 위해 `@RequestHeader(required=false)`로 받은
`Idempotency-Key`가 Swagger에서는 선택값으로 보였다. schedule, activate, replay가 같은
응답 annotation을 사용하면서 실제로 불가능한 `200|202` 성공 status도 함께 공개됐다.

서버가 런타임에 올바르게 거부해도 caller가 잘못된 SDK와 재시도 코드를 만들게 하면 API
계약은 실패한 것이다. binding 편의와 공개 계약이 다를 때는 OpenAPI `@Parameter`를
명시하고 generated document를 테스트해야 한다. 성공 status도 operation별로 분리해야 한다.

## 프로세스 로컬 제한은 분산 제한이 아니다

preview polling limiter의 `ConcurrentHashMap`은 한 process의 반복 polling과 메모리를
보호한다. hard cap과 expiry를 넣어도 여러 instance를 오가는 요청의 SaaS 전체 rate limit은
보장하지 못한다. 그래서 코드 KDoc에 API Gateway 또는 분산 rate limiter 책임을 명시했다.

로컬 limiter를 권위성이나 전역 quota로 설명하지 않는 것이 중요하다. 권위 scope fence는
항상 데이터베이스 predicate가 담당한다.

## 데이터베이스 검증의 기준

H2 RED→GREEN은 빠른 구조 피드백에는 유용하지만 clinic-appointment의 운영 데이터베이스
의미를 대표하지 않는다. 이번 작업은 PostgreSQL datasource와 Flyway migration을 사용하는
HTTP security test, 그리고 H2/PostgreSQL/MySQL 8의 exact-scope repository test를
분리해서 실행했다.

앞으로도 결과를 보고할 때 “H2 통과”를 완료 근거로 쓰지 않는다. PostgreSQL+Flyway를 운영
의미의 권위 증거로 두고 H2는 피드백 속도, MySQL은 지원 dialect 동등성 증거로 표현한다.

## 검증 프로세스도 생명주기를 격리한다

여러 Spring test context를 한 Gradle test worker에 묶었을 때 각 context가 공유하던 Redis
test container와 near-cache 종료 순서가 충돌했다. 테스트 본문은 통과했지만 종료 단계의
1분 timeout이 누적되어 한 번의 큰 명령은 전체 실행 제한을 넘겼다.

이 경우 성공한 test method 수만 보고 묶음 실행을 통과했다고 표현하면 안 된다. 이번 작업은
OpenAPI, PostgreSQL security, 기존 appointment API를 독립 프로세스로 나누어 종료 상태까지
확인했다. 후속 작업은 Redis container와 cache bean의 소유권·종료 순서를 고쳐 다시 하나의
모듈 검증으로 합쳐야 한다.

## 다음 작업을 위한 guard

- durable async 응답을 추가하면 같은 flag 단계에서 consumer가 실제로 실행되는지 테스트한다.
- 전역 ID·opaque token 조회는 scope predicate가 SQL에 포함됐는지 먼저 검토한다.
- runtime-required header와 operation별 성공 status는 generated OpenAPI로 검증한다.
- controller 테스트는 route 존재뿐 아니라 scope, actor, CAS, evidence, header 전달을 확인한다.
- 공개 DTO는 업무 의미와 null 가능성을 `@property` KDoc으로 남긴다.
- 관리 API metric, Redis test context 종료 정리, README·runbook parity는 Task 10에서
  완료하고, 그전에는 완료로 주장하지 않는다.
