# Issue #334 Solver planning fact fence lesson

## Context

기존 `SolverResult`는 appointment `sourceVersions`만 보관했다. solve와 apply 사이에
clinic, doctor, treatment, equipment, 운영시간, 의사 스케줄·부재, 휴게·휴진, holiday,
treatment-equipment mapping이 바뀌어도 appointment version이 그대로면 stale assignment가
적용될 수 있었다. 이 작업은 master-data writer를 새 generation API로 이관하지 않고,
실제 solver snapshot 입력을 canonical SHA-256 version으로 고정하는 방식으로 해결했다.

## 결정과 재사용 규칙

- `PlanningFactVersionHasher`는 scope와 date range를 먼저 쓰고, `ScheduleSolution`의
  각 problem-fact collection을 안정 key로 정렬해 명시적인 field framing으로 기록한다.
- `null`과 empty collection을 구분하고, 문자열·숫자·날짜·시간·boolean의 타입과 UTF-8
  길이를 함께 기록한다. `toString()`과 DB 조회 순서를 digest에 사용하지 않는다.
- `appointments`와 mutable `score`는 기존 appointment CAS와 solver 결과 계약이 담당하므로
  planning-fact digest에서 제외한다.
- 앞으로 `ScheduleSolution`에 problem fact를 추가하면 hasher field ledger, deterministic
  hash test, fact mutation regression을 같은 변경에 포함한다.

## 구현 중 발견한 점

1. `ScheduleSolution.equipmentUnavailabilities`는 현재 loader에서 비어 있다. encoder에는
   field contract를 포함했지만, 실제 loader가 해당 fact를 채우기 전에는 그 테이블 변경을
   감지하지 않는다. loader가 활성화되는 순간 별도 omission test를 추가해야 한다.
2. `applyOptimizedAssignments`는 `SERIALIZABLE` transaction에서 snapshot hash를 확인하고
   appointment row를 잠근 뒤 hash를 한 번 더 확인한다. 이로써 lock 대기 중에 이미 관측 가능한
   fact 변이를 재검사하고, 기존 source version CAS/rollback을 보존한다.
3. PostgreSQL `SERIALIZABLE` transaction의 snapshot은 transaction 안에서 고정된다. 따라서
   apply가 이미 snapshot을 읽은 뒤 다른 transaction이 fact를 commit하는 상황을 SQLSTATE
   하나로 항상 재현한다고 주장하지 않는다. 이번 PostgreSQL 증거는 (a) transaction 시작 전
   clinic 변경에 대한 hash mismatch와 (b) writer가 appointment version을 소비하는 row-lock/CAS
   경합을 각각 검증한다. cross-transaction generation row나 writer 협력 lock은 이 issue 범위가
   아니다.
4. H2에서는 12개 planning-fact 추가·수정·삭제 회귀를 빠르게 확인하고, PostgreSQL에서는
   실제 MVCC/row-lock 의미를 확인했다. H2 GREEN만으로 PostgreSQL consistency를 주장하지
   않는다는 원칙을 유지한다.

## 검증 결과

- H2 `SolverServiceTest`: 22 tests passing
- PostgreSQL singleton `SolverServicePostgresConcurrencyTest`: 2 tests passing
- `PlanningFactVersionHasherTest`: 4 tests passing
- 전체 `appointment-solver`: 10 suites / 98 tests, skipped 0, failures 0, errors 0
- `appointment-solver:build`: `BUILD SUCCESSFUL`
- Colima healthy, Docker context `default`, `@Testcontainers`/`GenericContainer` scan clean

이 결과는 production 운영 증거가 아니라 bluetape4k Testcontainers 기반의 실제 PostgreSQL
consistency simulation이다. 실제 운영 승격·canary·배포 readiness를 의미하지 않는다.

## Future directive

새 planning fact loader 또는 direct writer를 추가할 때 다음을 함께 갱신한다.

1. `ScheduleSolution` 입력과 canonical field ledger
2. 추가·수정·삭제 H2 regression
3. PostgreSQL hash mismatch 또는 lock/CAS 경계 test
4. 해당 fact가 transaction snapshot 중간 변경까지 선형화되어야 하는지에 대한 별도 설계

canonical digest 비용이 실제 일정량에서 문제가 되면 hash를 제거하거나 appointment
version으로 되돌리지 말고, snapshot query count/latency benchmark와 generation/version
vector 대안을 별도 설계·승인한다.
