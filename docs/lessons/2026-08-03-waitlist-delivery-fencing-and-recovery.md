# Issue #170 waitlist 전달 fencing과 recovery lesson

## 배경

delivery flag와 Redis leader lease만으로 vacancy와 confirm을 제어하면 process loss 뒤에
중복 offer·appointment가 생길 수 있다. provider I/O가 길어져 appointment lock을 잡은 채
대기하는 것도 같은 경계의 장애를 키운다.

## 결정

1. vacancy, offer, hold, command record의 terminal 권위는 DB version/owner fence로 둔다.
   Redis는 scheduler 중복을 줄이는 신호이며 terminal write 권한이 아니다.
2. confirm은 짧은 idempotency reservation transaction, 별도 business transaction, 결과
   completion transaction으로 분리한다. `PROCESSING`은 retry 때 appointment를 재조회해
   `SUCCEEDED` 또는 stable `FAILED`로 닫는다.
3. notification worker는 claim과 pre-send CAS만 transaction으로 수행하고 profile/provider
   호출은 transaction 밖에서 한다. offer expiry가 provider 결과보다 우선한다.
4. global-off와 clinic allowlist 제거에서도 expiry, suppression, stuck-hold reconcile은
   계속 실행한다. rollback은 새 dispatch를 끄는 일이지 안전 회수를 끄는 일이 아니다.

## 확인된 실패와 보강

공유 H2 schema seed가 병렬 module test에서 중복되던 문제는 JVM-local schema lock과 존재 확인
seed로 고쳤다. 성공 결과 기록 직전 process loss는 `PROCESSING`을 남겨야 하므로 application
test가 해당 crash window를 직접 재현한다.

## 검증 증거

- core/event/notification/api 모듈의 waitlist 회귀와 migration contract를 순차 실행한다.
- API confirm은 동일 key replay에서 replacement 호출 1회와 hold consume 1회를 검증한다.
- scheduler는 global-off에서도 expiry/suppression/reconcile 호출을 검증한다.
- health/retention 테스트는 high-cardinality ID와 active/legal-hold purge를 거부한다.

## 후속 경계

실제 Redis failover와 provider staging latency는 production-like drill에서 재확인해야 한다.
patient self-service와 외부 CRM/campaign attribution은 이 issue의 범위로 확장하지 않는다.
