# 이슈 #334 solver planning fact version fence 위험 예측

상태: `IMPLEMENTATION RISK BASELINE`

대상 범위는 `appointment-solver`가 읽은 planning fact snapshot과 최적화 결과 적용
transaction이다. 이 문서는 실제 운영 배포 승인이나 SLO 증거가 아니라, H2 회귀와
`PostgreSQLServer.Launcher.postgres` 기반 DB 일관성 시뮬레이션의 중단 기준을 고정한다.

## 위험 ledger

| ID | 위험과 trigger | 관찰할 증거 | 완화책 | 중단 조건 |
|---|---|---|---|---|
| R1 | fact 수가 증가할수록 canonical SHA-256 비용과 apply 재조회 비용이 선형으로 증가한다. 대형 clinic snapshot에서 hash 시간이 solver 적용 시간보다 커질 수 있다. | hasher 단위 test, solver test log의 snapshot record 수와 elapsed time, PostgreSQL targeted run 결과 | immutable snapshot을 한 번만 hash하고, apply는 같은 loader를 재사용한다. 새 DB index나 별도 cache를 이번 범위에 추가하지 않는다. | module test에서 timeout·메모리 오류가 발생하거나 fact field 누락을 피하려고 unordered/`toString()` encoding을 도입해야 하는 경우 구현을 멈추고 설계를 재검토한다. |
| R2 | PostgreSQL `SERIALIZABLE` transaction이 동시 writer와 충돌해 SQLSTATE `40001` 또는 `40P01`을 반환한다. | PostgreSQL race test의 exception state, apply 반환값, appointment version/status, transaction rollback 여부 | 두 state만 stale `false`로 수렴시키고, 예기치 않은 SQL exception은 전파한다. 부분 assignment가 남지 않는지 CAS/rollback assertion으로 확인한다. | `40001`/`40P01`을 성공이나 무한 재시도로 처리하거나, conflict 뒤 일부 appointment가 commit되면 구현을 중단한다. |
| R3 | H2의 isolation/MVCC가 PostgreSQL과 달라 H2 green이 실제 race를 증명하지 못한다. | H2 fact add/update/delete test와 별도의 PostgreSQL singleton mismatch/lock race test | 두 환경의 역할을 분리한다. H2는 fact field coverage와 rollback을, PostgreSQL은 transaction isolation과 lock 경합을 검증한다. | PostgreSQL test가 컨테이너 bind-mount 오류를 skip으로 처리하거나 실행되지 않은 채 verification을 통과하면 중단한다. |
| R4 | canonical field 목록에서 fact field가 빠지거나 collection iteration 순서가 흔들려 false negative/false positive가 발생한다. | hasher fixture의 각 fact type, reversed collection 동등성, nullable/empty 차이, 64자리 hex assertion | record type과 field 순서를 이 문서·계획·hasher test에 함께 고정하고 stable key 정렬과 length framing을 사용한다. | 새 `ScheduleSolution` fact가 추가됐는데 ledger/test/encoder 중 하나가 갱신되지 않거나 `toString()` hash가 도입되면 merge를 차단한다. |
| R5 | legacy `SolverResult` 또는 blank metadata가 적용 경로로 들어와 fence 없이 assignment를 commit한다. | null `dateRange`/blank `planningFactVersion` advisory 및 apply regression | metadata가 없으면 advisory와 apply 모두 `false`로 종료하고, 기존 appointment `sourceVersions` CAS는 별도 유지한다. | legacy-safe test가 true를 반환하거나 source version만으로 적용이 성공하면 구현을 중단한다. |
| R6 | scope/date range가 hash와 재조회에서 달라져 다른 tenant 또는 다른 날짜의 fact를 비교한다. | result scope/dateRange 보존 assertion, hash input ledger, apply current snapshot path | result에 원래 날짜 범위를 보존하고 hash input에 tenant/clinic scope와 date range를 포함한다. apply는 result scope/dateRange로만 loader를 호출한다. | apply가 caller의 새 date range나 raw clinic id를 사용하거나 cross-tenant row를 읽는 path가 발견되면 중단한다. |

## 검증 순서

1. canonical encoder 단위 RED/GREEN에서 deterministic order, null framing, field mutation을 고정한다.
2. H2 `SolverServiceTest`에서 planning fact 종류별 add/update/delete와 기존 appointment CAS/rollback/pinned을 순차 실행한다.
3. PostgreSQL singleton에서 fact 변경 전후 hash mismatch와 appointment lock 경합을 실행한다.
4. `:appointment-solver:test --no-build-cache`, `build`, `git diff --check`, 금지 annotation/container scan, 독립 review를 실행한다.

실패한 단계는 `PASS`로 표시하지 않는다. Docker/Colima runtime 오류는 오류 원인과
실행하지 못한 명령을 lesson/review에 남기며, VM 재시작이나 test skip으로 증거를
만들지 않는다.

## 수용 기준

- R1의 hash 비용은 snapshot cardinality와 targeted test 결과로 설명 가능하다.
- R2의 serialization/deadlock conflict는 부분 commit 없이 `false`가 된다.
- R3의 H2와 PostgreSQL 증거가 각각 실제 실행 로그로 남는다.
- R4의 field ledger가 `ScheduleSolution`의 mutable planning input/value range와 일치한다.
- R5의 metadata 없는 결과는 안전하게 거부된다.
- R6의 scope/dateRange가 결과와 current snapshot 비교 양쪽에 동일하게 전달된다.
