# 알림 신뢰성은 발송 재시도보다 경계와 상태를 먼저 고정해야 한다

## 배경

기존 알림 경로는 예약 이벤트를 받은 listener가 provider를 직접 호출하고, 실패를
로그로만 남겼다. 이 구조에서는 예약 변경과 알림 요청이 원자적으로 기록되지 않으며,
재시도 여부와 중복 발송 여부도 프로세스 상태에 의존한다. 발송 본문에 필요한 이름과
전화번호까지 예약 서비스가 소유하면 실패 이력과 운영 지표에 개인정보가 남을 위험도
커진다.

Issue #172에서는 예약 트랜잭션 안에 개인정보가 없는 알림 의도를 기록하고, 별도
worker가 발송 시점의 회원 정보와 동의를 조회하는 durable outbox 경계를 만들었다.
`SHADOW`, 병원 allowlist `CANARY`, `ACTIVE`, `PAUSED` 전환 경로와 대규모 backlog
검증까지 코드로 고정했다. 실제 병원 canary와 전환기 listener 제거는 배포 뒤 후속
운영 gate로 남아 있다.

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
- 알림 모듈 단위 테스트는 통과했지만 API 통합 classpath에서는 coroutine builder의
  바이너리 이름이 달라 `NoSuchMethodError`가 발생했다. 동기 Spring event 경계는
  특정 coroutine builder ABI에 기대지 않고 표준 `Continuation` 완료를 기다리도록
  바꿨다. 라이브러리 단위 테스트뿐 아니라 실제 소비 모듈 테스트가 필요한 이유다.
- event route가 실제 worker를 호출하면서 delivery-attempt 자식 행이 생겼다. 기존 API
  테스트 fixture가 부모 outbox를 먼저 삭제해 다음 실행부터 FK 오류가 연쇄 발생했다.
  테스트 정리도 운영 retention과 같은 자식-부모 순서를 지켜야 한다.
- worker 1초 poll에서 매번 100,001개 backlog를 객체로 만들면 관측 기능이 발송 성능을
  잠식한다. ready backlog snapshot은 worker와 분리된 기본 10초 주기로 최대 10,001개만
  읽고, `capped` 상태로 10,000건 경보 임계값 초과를 표현하도록 바꿨다.
- 전환기 direct executor의 `CallerRunsPolicy`는 queue 포화 시 예약 event thread가
  provider I/O를 수행하게 만든다. 포화 작업은 거절하되 이미 커밋된 outbox 행을 pending으로
  남겨 예약 경로의 지연과 알림 복구 가능성을 분리해야 한다.
- future timeout과 interrupt만으로 모든 provider SDK 호출을 강제 종료할 수는 없다.
  adapter의 connect/read/request timeout을 worker timeout 이하로 맞추는 운영 계약이 함께
  필요하다.
- Swagger `ArraySchema.items`의 원소 제약은 현재 springdoc 조합에서 생성되지 않았다.
  호환 속성인 `schema`로 원소 minimum을 지정하고 실제 `/v3/api-docs` 결과를 테스트해야
  annotation만 보고 계약이 게시됐다고 판단하지 않게 된다.
- 문서 본문만 새 outbox 흐름으로 바꾸어도 기존 예약 생성 PNG/SVG가
  `NotificationHistory` 직접 저장을 보여 주면 독자는 오래된 흐름을 기준으로 이해한다.
  하나의 생성기에서 한·영과 light/dark 변형을 함께 만들고, 활성 자산의 금지 용어를
  별도로 검색해야 한다.

## 현재 증거와 남은 gate

- `:appointment-core:build :appointment-event:build :appointment-notification:build
  :appointment-api:build`: 총 1,347개 테스트, 실패·오류 0건, 2개 보류
- `:appointment-core:build`와 나머지 affected module `build/check/Kover`: 통과
- H2·PostgreSQL·MySQL migration/claim/실행 계획 테스트와 20,000개 backlog 합성
  부하: 통과
- 부하 측정 3회 모두 100개 병원, starvation 0, claim p95 14ms·p99 23ms,
  resolver/provider 동시성 8 이하
- 예약 생성, 환자 예약, outbox, 리마인더 다이어그램의 한·영 light/dark SVG 감사:
  XML·endpoint·connector·geometry·sequence 규칙 통과

남은 gate는 실제 병원에서 `24시간 + 1,000건` canary를 관측하고 allowlist를 확대해
`ACTIVE`로 전환한 뒤 전환기 listener를 제거하는 운영 작업이다. 이 작업은 코드 PR과
분리하며 후속 운영 이슈 #204에서 배포 증거와 함께 닫는다.

## 재사용 지침

- 예약과 알림 의도는 같은 caller transaction에서 기록한다.
- 아웃박스에는 회원 식별자와 닫힌 parameter만 저장하고 연락처와 본문은 발송 시점에
  알림 서비스가 채운다.
- durable retry와 provider retry의 최대 곱을 설정 검증으로 제한한다.
- lease를 잃은 worker의 결과는 fencing token과 attempt 조건으로 거절한다.
- 종료 상태 전환과 원본 필드 제거를 같은 update로 수행한다.
- metric과 운영 API는 개인정보 대신 닫힌 상태와 권장 조치만 제공한다.
- 실제 canary 관측 없이 direct 경로를 제거하거나 전체 rollout 완료를 선언하지 않는다.
- 소비 모듈 classpath가 다른 suspend 경계는 producer 모듈 단위 테스트만으로 ABI를
  증명하지 않는다.
- outbox 테스트 정리는 delivery attempt를 먼저 지우고 부모 행을 나중에 지운다.
- 독자용 다이어그램은 의미·언어·theme 변형을 한 생성 모델과 감사 명령으로 묶는다.
- 발송 worker poll과 backlog 관측 poll을 분리하고, 경보 임계값을 구분할 수 있는 최소
  상한만 조회한다.
- 전환기 executor 포화 시 호출자 thread에서 실행하지 않고 durable pending 행을 복구
  경계로 사용한다.
- 외부 호출 timeout은 wrapper와 provider adapter 자체 설정을 함께 검증한다.
