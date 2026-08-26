# Issue #311 waitlist fenced production 위험 등록부

## 적용 범위

이 등록부는 `LettuceFencedLock`과 V31 DB fence를 실제 scheduler 경로에 연결하는 동안
발생할 수 있는 Redis, DB, lifecycle, 관측, rollout 위험을 구현 계획과 검증 명령에
연결한다. 모든 항목은 fresh test/diagnostic 결과로 상태를 갱신한다.

| ID | 위험과 트리거 | 영향 | 완화 및 검증 신호 | rollback/rerun 지점 | 상태 |
|---|---|---|---|---|---|
| R-01 | Redis acquire가 `Ambiguous`인데 owner/request 상태를 잃음 | lease가 살아 있는지 모른 채 두 worker가 시작될 수 있음 | 같은 `LockOwnerId`/`LockRequestId`로 한 번만 `reconcile`; 명확한 handle 전에는 DB mutation 금지. fake와 Redis integration에서 reconcile 호출 인자/호출 횟수 확인 | `enabled=false`, 다음 poll에서 reconcile; key/counter를 되돌리지 않음 | 계획됨 |
| R-02 | fixed lease가 tick보다 짧거나 watchdog가 무제한 | 정상 worker가 expiry되어 stale terminal write 또는 중복 실행 | properties에서 양수·상한 검증, tick duration timer와 expiry takeover test, p95/p99가 lease budget 안인지 확인 | scheduler 비활성화 후 lease 설정을 늘리고 동일 Redis namespace로 재실행 | 계획됨 |
| R-03 | Redis failover/expiry 뒤 이전 worker가 DB를 갱신 | 잘못된 offer/hold 상태가 business DB에 반영될 수 있음 | `claimFenced` strict-greater와 terminal exact-match가 0 row를 반환하는 H2/PostgreSQL/Redis 회귀 | app만 off, DB fence와 `bt4k:coord:v1` counter 보존, fix 후 새 token으로 재실행 | 계획됨 |
| R-04 | 두 dialect 중 하나의 V31 DDL이 기존 schema와 불일치 | 배포 시 migration 중단 또는 readiness false | H2/PostgreSQL/MySQL Flyway contract와 JDBC metadata/default assertion | V31 적용 전 배포 중단; 실패 dialect script만 수정 후 clean migration rerun | 계획됨 |
| R-05 | `fence_epoch/sequence`가 legacy claim에서 실수로 덮임 | 새 owner의 monotonic fence가 낮아지거나 token 우회 | legacy `claim`은 token 없는 strategy로만 유지, typed adapter fake에서 `claimFenced`만 호출하는 호출 검증 | fenced scheduler off; 기존 legacy path는 유지하고 typed path 재배포 | 계획됨 |
| R-06 | owner/request/key/token이 log 또는 metric tag로 노출 | 내부 coordination identity와 tenant correlation이 외부로 유출 | allowlist tag set exact match, raw Base58/key/token 문자열 부재 assertion, sanitized `LockObservationSink` | 관측 sink를 NOOP/allowlist 버전으로 되돌리고 로그 보존 범위 점검 | 계획됨 |
| R-07 | cancellation/close와 release가 경합해 duplicate release 또는 task leak 발생 | lock handle 누수, 다음 poll 차단, graceful shutdown 지연 | `ACQUIRED → RELEASED/UNKNOWN/LOST` 단일 전이, duplicate release no-op, close 후 task count 0 | scheduler close 후 새 acquire 금지; Redis connection은 shared bean이 소유 | 계획됨 |
| R-08 | required dispatcher/recovery port가 빠졌는데 no-op bean으로 scheduler 활성화 | business mutation 없이 성공처럼 보이거나 운영자가 잘못된 상태를 인지 | `@ConditionalOnProperty` + `@ConditionalOnBean` + V31 readiness; incomplete context에는 runner 부재 | `enabled=false` 유지; missing port를 명시적으로 제공한 뒤 context 재실행 | 계획됨 |
| R-09 | strict-greater predicate와 row lock 순서가 어긋남 | contention에서 같은 job을 두 worker가 claim할 수 있음 | Exposed transaction/`FOR UPDATE` 경로와 PostgreSQL contention test에서 higher tuple만 승리하는지 확인 | failed test의 SQL/row count를 근거로 repository predicate만 수정 | 계획됨 |
| R-10 | Redis integration이 Docker/Colima 문제로 skip됨 | 실제 expiry/failover 증거 없이 완료를 주장할 수 있음 | `Containers.Redis` launcher와 `colima status`, `docker context show`, `docker info` 진단; skip은 PASS로 집계하지 않음 | 환경 복구 후 동일 테스트 재실행, 복구 불가면 최종 PENDING | 계획됨 |

## 운영 신호와 중단 조건

- `lease_acquire_total{outcome=failed|ambiguous}`가 증가하면 새 tick을 즉시 반복하지 않고
  bounded backoff와 다음 poll에서만 재시도한다.
- `ownership_loss_total{source=redis|db}`가 발생하면 해당 tick의 dispatcher 결과를
  성공으로 집계하지 않고 stale handle/claim을 폐기한다.
- readiness probe가 V31 column을 읽지 못하면 bean 생성 단계에서 typed failure를 남기고
  no-op scheduler를 만들지 않는다.
- fixed lease가 configured 상한을 넘거나 timer 결과가 tick 예산을 초과하면
  `enabled=false`로 rollback하고 원인 수정 뒤 migration/Redis 상태를 보존한 채 재검증한다.

## 계획·체크리스트 연결

- R-01/R-02/R-07 → 계획 Task 4, Task 6, Task 7; checklist A-05.
- R-03/R-05/R-09 → 계획 Task 1, Task 2; checklist A-06.
- R-04/R-08 → 계획 Task 3, Task 6, Task 8; checklist A-05/A-07.
- R-06 → 계획 Task 5, Task 7; checklist A-07/A-08.
- R-10 → 계획 Task 7, Task 9; checklist CL-05/A-07.

## SPW writer DoD

- SPW-01: 위험 범위와 구현 경계를 Issue #311 및 승인 설계에 연결했다.
- SPW-02: 각 위험에 영향, 완화 신호, rollback/rerun 지점을 기록했다.
- SPW-03: 한국어 운영 문장과 기술 식별자를 분리해 점검했다.
- SPW-04: 모든 위험을 계획 task와 실행 checklist 항목으로 추적했다.
- SPW-05: 중단 조건과 운영 신호를 read-back했다.
