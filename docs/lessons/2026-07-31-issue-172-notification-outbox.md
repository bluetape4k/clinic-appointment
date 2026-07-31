# 알림 신뢰성은 발송 재시도보다 경계와 상태를 먼저 고정해야 한다

## 배경

기존 알림 경로는 예약 이벤트를 받은 listener가 provider를 직접 호출하고, 실패를
로그로만 남겼다. 이 구조에서는 예약 변경과 알림 요청이 원자적으로 기록되지 않으며,
재시도 여부와 중복 발송 여부도 프로세스 상태에 의존한다. 발송 본문에 필요한 이름과
전화번호까지 예약 서비스가 소유하면 실패 이력과 운영 지표에 개인정보가 남을 위험도
커진다.

Issue #172의 Task 1~12에서는 예약 트랜잭션 안에 개인정보가 없는 알림 의도를 기록하고,
별도 worker가 발송 시점의 회원 정보와 동의를 조회하는 durable outbox 경계를 만들었다.
실제 병원 canary와 legacy listener 제거는 Task 13 이후의 운영 gate로 남아 있다.

## 1. 원자성은 이벤트 발행이 아니라 caller transaction에서 시작한다

예약 저장 뒤 Spring event로 알림을 전달하면 listener가 실패했을 때 예약만 확정될 수
있다. 이번 구현은 예약 command가 사용하는 Exposed `transaction {}` 안에서 예약 변경과
`NotificationOutboxEvent`를 함께 기록한다.

- appointment와 outbox 중 하나라도 실패하면 둘 다 rollback한다.
- 같은 논리 알림은 HMAC 기반 idempotency digest로 한 행에 수렴한다.
- 기존 예약에 `memberId`가 없으면 발송 가능한 행을 만들지 않고
  `LEGACY_SUPPRESSION`으로 종료한다.

아웃박스 repository가 자체 transaction을 열지 않고 caller transaction을 그대로
사용하는지가 핵심이다. API 테스트가 성공 응답만 확인해서는 이 계약을 증명할 수 없으므로
outbox insert 실패 시 예약과 idempotency 행이 함께 rollback되는지 검증해야 한다.

## 2. 수신자 정보는 발송 시점에 회원 경계에서 보강한다

아웃박스에는 `memberId`, template 식별자와 닫힌 parameter만 저장한다. 이름, 전화번호,
이메일과 렌더링된 본문은 저장하지 않는다. worker는 claim 이후 회원 서비스에서 최신
profile과 수신 동의를 조회하고, versioned parameterized template을 렌더링한 뒤 provider를
호출한다.

이 경계는 두 가지 문제를 함께 줄인다.

- 예약 이후 변경된 연락처와 수신 동의를 발송 시점에 반영한다.
- 예약 DB, outbox, attempt, metric과 운영 API에 개인정보가 확산되지 않는다.

회원 조회 실패와 provider 실패는 같은 retry가 아니다. 회원 없음, 동의 거부, scope
불일치는 닫힌 suppression reason으로 종료하고, 일시적인 directory/provider 장애만 제한된
재시도 대상으로 분류한다.

## 3. 재시도 계층의 곱과 stale worker를 동시에 제한한다

durable attempt와 provider 내부 retry를 독립적으로 늘리면 실제 provider 호출 수가 두
설정의 곱으로 증가한다. 설정 검증에서 durable attempt, lease별 provider attempt와 최대
경과 시간을 함께 제한하고, 기본 provider attempt는 한 번으로 유지했다.

다중 worker에서는 attempt 수보다 lease fencing이 더 중요하다. 결과 반영 조건에
`leaseOwner`, `leaseToken`, `attemptNumber`, `leaseUntil`을 포함해 lease를 잃은 worker가
새 worker의 상태를 덮어쓰지 못하게 했다. DB의 `CURRENT_TIMESTAMP`를 사용하므로 인스턴스
시계가 달라도 만료 판단이 일치한다.

## 4. 종료 상태 전환과 개인정보 제거는 같은 update여야 한다

`SENT`, `SUPPRESSED`, `EXHAUSTED`는 다시 claim하지 않는 종료 상태다. 종료 상태로 바꾼
뒤 별도 정리 작업에서 개인정보 필드를 제거하면 그 사이 장애가 발생했을 때 원본 값이
남는다. 따라서 종료 상태, 감사 fingerprint와 원본 필드 `NULL` 처리를 하나의 fenced
update로 수행한다.

감사 HMAC key를 사용할 수 없는 경우에도 원본 제거가 우선이다. 이때는 원문 예외나
수신자 정보를 저장하지 않고 닫힌 `HMAC_KEY_UNAVAILABLE` 코드만 남긴다.

## 5. 운영 API는 데이터보다 조치 가능성을 제공해야 한다

운영 상태 조회는 tenant·clinic scope 안에서 다음 조치에 필요한 제한된 상태만 반환한다.
알림 본문, 수신자 정보, provider payload와 예외 원문은 반환하지 않는다. 연결되지 않은
운영 port를 `404`로 숨기면 배포 누락과 실제 자원 부재를 구분할 수 없으므로 안정적인
`503 NOTIFICATION_OPERATION_UNAVAILABLE`과 `Retry-After`를 반환하도록 수정했다.

metric도 같은 원칙을 따른다. clinic, member, template을 tag로 넣지 않고 닫힌 category와
outcome만 사용한다. 리뷰 중 동일 meter를 매 호출마다 등록하는 비용이 발견되어, 허용된
조합의 meter handle을 제한된 map에 캐시했다. 낮은 cardinality 계약은 tag 값뿐 아니라
meter 등록 횟수까지 테스트해야 한다.

수동 `re-notify`는 완료 행을 되살리지 않는다. 현재 profile·동의·template으로 새 generation을
만들며, dry-run, 최대 100건, clinic scope, 이중 승인과 rate limit을 적용한다.

## 검증에서 드러난 보완점

- 공통 API 통합 테스트에 HMAC hasher가 없으면 unrelated 예약 API가 `503`으로 실패했다.
  production 우회를 추가하지 않고 테스트 fixture에 결정적인 hasher를 제공했다.
- 운영 service bean이 없을 때 endpoint가 `404`가 되어 wiring 누락을 숨겼다. 명시적인
  fail-closed `503` 계약과 auto-configuration 검증을 추가했다.
- 요청의 appointment ID를 `Set`으로 바로 받으면 중복 입력을 감지할 수 없었다. API에서는
  `List`로 받은 뒤 중복을 거절하고 service 경계에서 `Set`으로 변환했다.
- Spring ASYNC redispatch 허용은 최초 REQUEST 인증을 대신하지 않는다. 실제 서버 기반
  보안 테스트로 인증되지 않은 최초 요청이 거절되는지 확인했다.

## 현재 증거와 남은 gate

- `:appointment-api:test --rerun-tasks`: 528개 테스트 통과
- `:appointment-notification:build :appointment-api:build`: 통과
- 성능, 안정성, 보안, 운영, 개발자/API, 사용자/caller 검토: `P0=0`, `P1=0`
- Task 13의 실제 `24시간 + 1,000건` canary 관측, legacy listener 제거, Task 14의
  PostgreSQL/MySQL 실행계획과 부하 검증, Task 15 문서화, Task 16 최종 수렴은 미완료다.

현재 결과는 PR 검토를 시작할 수 있는 중간 단계이지 merge-ready 상태가 아니다.

## 재사용 지침

- 예약과 알림 의도는 같은 caller transaction에서 기록한다.
- 아웃박스에는 회원 식별자와 닫힌 parameter만 저장하고 연락처와 본문은 발송 시점에
  알림 서비스가 채운다.
- durable retry와 provider retry의 최대 곱을 설정 검증으로 제한한다.
- lease를 잃은 worker의 결과는 fencing token과 attempt 조건으로 거절한다.
- 종료 상태 전환과 원본 필드 제거를 같은 update로 수행한다.
- metric과 운영 API는 개인정보 대신 닫힌 상태와 권장 조치만 제공한다.
- 실제 canary 관측 없이 direct 경로를 제거하거나 전체 rollout 완료를 선언하지 않는다.
