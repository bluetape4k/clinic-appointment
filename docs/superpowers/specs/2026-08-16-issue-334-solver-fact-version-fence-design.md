# 이슈 #334 solver planning fact version fence 설계

상태: `DESIGN REVIEW PENDING`

대상 독자: `appointment-solver`를 유지보수하는 Kotlin/Exposed 개발자와 테스트
검토자. 결정 질문은 “solver가 읽은 mutable planning fact가 solve 중 바뀌었을 때,
오래된 결과의 assignment 적용을 어떻게 원자적으로 거부할 것인가”이다.

## 1. 문제와 영향

이슈: [#334](https://github.com/bluetape4k/clinic-appointment/issues/334)

현재 `SolverService.optimize`는 `loadSnapshot`에서 clinic, doctor, treatment,
equipment, operating hours, schedules, absences, breaks, closures, holidays와
treatment-equipment mapping을 읽지만, `SolverResult`에는 appointment별
`sourceVersions`만 저장한다. `applyOptimizedAssignments`도 appointment row version을
lock/CAS로 확인한다. 따라서 solve와 apply 사이에 의사 일정, 휴무, 장비, 운영시간
같은 planning fact가 추가·수정·삭제되면 appointment version이 그대로인 경우 오래된
결과가 적용될 수 있다.

영향 범위는 `appointment-solver`의 결과 계약과 적용 transaction이다. 현재 baseline은
`./gradlew :appointment-solver:test --no-build-cache`에서 `79 passing`이며,
기존 appointment stale/CAS/rollback/pinned 회귀 의미론을 깨뜨리지 않아야 한다.

## 2. 결정된 접근법

승인된 선택지는 **canonical planning-fact snapshot version**이다. solve snapshot에
실제로 포함된 모든 planning fact를 명시적인 순서와 field framing으로 직렬화하고,
UTF-8 SHA-256 digest를 계산한다. `SolverResult`는 기존 appointment
`sourceVersions`와 함께 `dateRange`, `planningFactVersion`을 보관한다.

이 방식은 모든 master-data writer를 새 generation API로 이관하지 않아도 direct SQL,
배치, 다른 서비스의 변경을 감지한다. 각 테이블에 `version`/`updated_at` 컬럼을
추가하거나 Flyway migration을 만들지 않으므로 기존 schema와 호환된다.

### 선택지 비교

| 선택지 | 장점 | 거부/채택 사유 |
|---|---|---|
| clinic scheduling generation head | 비교가 빠르고 의미가 단순하다 | 모든 mutable fact writer가 동일 generation을 갱신해야 하며 direct SQL/다중 writer 누락을 막지 못한다. 현재 범위에서 거부한다. |
| 테이블별 version vector | 변경 원인과 디버깅 정보가 명확하다 | 11개 이상 fact table의 versioning·migration·삭제 semantics를 동시에 도입해야 한다. 현재 범위에서 거부한다. |
| canonical snapshot SHA-256 | schema migration 없이 snapshot과 동일한 입력을 비교하고 모든 writer를 포괄한다 | apply 때 동일 snapshot을 한 번 더 읽고 hash해야 한다. 이 비용을 stale 결과 방지와 명시적 PostgreSQL 검증으로 수용한다. **채택** |

## 3. 계약과 데이터 흐름

### 3.1 결과 계약

`SolverResult`에 다음 필드를 추가한다.

- `dateRange`: solve에 사용한 `ClosedRange<LocalDate>`. 날짜 범위로 필터되는
  absence, closure, holiday, schedule fact를 apply 때 동일하게 재조회하기 위한 값이다.
- `planningFactVersion`: canonical snapshot의 소문자 64자리 SHA-256 hex 문자열.

기존 `sourceVersions`는 삭제하거나 대체하지 않는다. appointment row의 optimistic
CAS는 planning fact fence와 독립적인 두 번째 안전장치다. 결과는 계속
`Serializable` 계약을 유지하고, 새 필드는 명시적 기본값을 둬 기존 fixture의 생성
호출이 깨지지 않도록 한다. 실제 최종 생성자 순서와 호환성은 구현계획에서 현재
호출부를 전수 확인한다.

### 3.2 solve 경로

1. `loadSnapshot`이 기존 transaction에서 모든 planning fact를 읽는다.
2. snapshot의 모든 입력을 canonical encoder로 정렬·framing하고 digest를 계산한다.
3. solver가 반환한 assignment와 appointment `sourceVersions`, `dateRange`, digest를
   `SolverResult`에 기록한다.

canonical encoder는 `toString()`이나 JVM collection iteration 순서에 의존하지 않는다.
각 record type과 field를 명시하고, nullable 값·문자열·숫자·날짜·시간·boolean은
길이/구분자 framing으로 구분한다. record는 stable primary key와 의미 있는 정렬키로
정렬한다. snapshot에 없는 fact는 digest에 넣지 않으며, 날짜 범위 필터 결과가
달라지면 digest도 달라진다.

### 3.3 apply 경로

`applyOptimizedAssignments`는 다음 순서의 하나의 `SERIALIZABLE` transaction으로
동작한다.

1. 결과의 `scope`와 `dateRange`로 현재 planning snapshot을 재조회한다.
2. 현재 canonical digest와 결과의 `planningFactVersion`을 비교한다. 다르면 stale로
   판정하고 아무 assignment도 commit하지 않는다.
3. 기존 appointment source version을 lock하고 CAS한다.
4. 하나라도 appointment가 stale이거나 duplicate assignment/validation 오류가 나면
   기존 rollback semantics로 전체 transaction을 rollback한다.
5. 모든 검사가 통과하면 commit한다.

advisory `isSourceVersionCurrentAdvisory`도 appointment version과 planning fact digest를
함께 비교한다. advisory 결과는 최종 보장이 아니며, 최종 보장은 위 apply transaction의
재조회·비교다.

PostgreSQL `40001` serialization failure와 `40P01` deadlock은 stale 결과와 동일하게
적용 거부(false)로 수렴시키되, 예상하지 못한 SQL 오류는 삼키지 않고 기존 오류 처리로
전파한다. H2에서의 단위 회귀만으로 이 의미론을 주장하지 않고, singleton
`PostgreSQLServer.Launcher.postgres`를 이용한 실제 race 검증으로 보완한다.

## 4. failure mode와 처리

| 상황 | 감지 지점 | 기대 동작 |
|---|---|---|
| solve 후 fact row 추가 | apply snapshot digest 비교 | `false`, assignment 미적용 |
| solve 후 fact row 수정 | apply snapshot digest 비교 | `false`, assignment 미적용 |
| solve 후 fact row 삭제 또는 날짜 범위 밖/안 변경 | apply snapshot digest 비교 | `false`, assignment 미적용 |
| fact는 같지만 appointment version 변경 | 기존 row lock/CAS | `false`, 전체 rollback |
| 두 writer가 동시에 apply | PostgreSQL serializable/lock | 한 쪽만 commit, 다른 쪽은 stale 또는 serialization conflict로 `false` |
| 빈 fact 집합/legacy result의 빈 version | 결과 계약 검증 | 새 optimize 결과는 항상 non-blank digest; 누락 결과는 안전하게 거부 |
| 예상하지 못한 DB/encoding 오류 | transaction 경계 | 예외를 전파하고 부분 commit을 허용하지 않음 |

## 5. 테스트 전략과 수용 기준

### RED/GREEN 회귀

- mutable planning fact 종류별로 결과 생성 후 add/update/delete를 수행하고
  `applyOptimizedAssignments == false`인지 확인한다. clinic, doctor, treatment,
  equipment, operating hours, doctor schedule, absence, break/default break, closure,
  holiday, treatment-equipment mapping을 빠짐없이 추적한다.
- 기존 appointment stale advisory, atomic CAS, concurrent writer, duplicate rollback,
  pinned appointment 회귀를 그대로 통과시킨다.
- canonical encoder의 정렬 안정성, nullable/empty 값 framing, 같은 snapshot의 동일
  digest를 단위 테스트한다.
- 새 `dateRange`가 범위 필터와 결과에 보존되는지 검증한다.

### PostgreSQL 시뮬레이션

`bluetape4k-testcontainers`의 `PostgreSQLServer.Launcher.postgres` singleton을 사용해
Exposed schema를 만들고, 한 transaction이 planning fact를 변경하는 동안 다른
transaction이 apply하도록 race를 재현한다. `@Testcontainers`와 raw
`GenericContainer`는 사용하지 않는다. 테스트는 shared default database 상태 때문에
순차 실행한다. Colima가 활성인 현재 macOS 환경에서는 관리된
`TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`를 상속한다.

### 수용 기준

1. `SolverResult`가 `dateRange`와 non-blank `planningFactVersion`을 보존한다.
2. solve/apply 사이의 각 planning fact add/update/delete가 결과 적용을 거부한다.
3. 기존 appointment source version CAS, rollback, pinned 의미론이 유지된다.
4. H2 targeted test와 PostgreSQL singleton race test가 통과한다.
5. digest 비교와 appointment CAS는 하나의 Exposed `SERIALIZABLE` transaction 안에서
   수행되고, 예상 serialization/deadlock conflict는 부분 적용 없이 거부된다.
6. DB migration, HTTP API, `@Testcontainers` 의존 패턴을 추가하지 않는다.
7. module test, `git diff --check`, static/Kotlin safety scan과 독립 code review가
   통과한다.

## 6. 호환성·운영 범위

- 기존 database table과 Flyway migration은 변경하지 않는다.
- 기존 `SolverService.optimize` 호출 의미와 `sourceVersions` 공개 계약은 유지한다.
- 새 결과를 저장/전송하는 외부 API는 현재 repository에 없으므로 adapter 변경은
  범위 밖이다. 향후 결과 persistence가 생기면 `dateRange`와 digest를 함께 저장해야
  한다.
- canonical hash는 충돌 가능성이 실무적으로 무시 가능한 SHA-256을 사용하지만,
  hash는 변경 이력이나 감사 로그가 아니다. 운영 분석에는 reject 원인 metric/log가
  후속 범위로 남는다.
- H2와 PostgreSQL의 isolation 차이는 존재하므로 production-like Testcontainers
  증거는 PostgreSQL transaction semantics에 한정한다. 이는 실제 운영 배포 증거가
  아니라 이 예제 서비스의 DB 일관성 시뮬레이션이다.

## 7. 추적성 및 문서 DoD

| 설계 주장 | 현재 근거 |
|---|---|
| appointment version만 현재 fence한다 | `appointment-solver/src/main/kotlin/.../service/SolverService.kt`, `SolverResult.kt` |
| planning fact 목록과 날짜 필터가 snapshot에 들어간다 | `SolverService.loadSnapshot`, `ScheduleSolution` |
| CAS/rollback/pinned을 보존해야 한다 | `appointment-solver/src/test/.../SolverServiceTest.kt` stale/CAS/rollback/pinned 테스트 |
| PostgreSQL singleton launcher가 repository 표준이다 | sibling `PostgreSQLServer.Launcher.postgres` 테스트 패턴 |
| baseline test가 통과한다 | `:appointment-solver:test --no-build-cache` — 79 passing |

문서 자체 DoD:

- SPW-01 PASS: 대상 독자와 결정 질문을 서두에 썼다.
- SPW-02 PASS: 문제, 선택지, 계약, 흐름, failure mode, 테스트, 수용 기준을 포함했다.
- SPW-03 PASS: 한국어 자연스러움 KO-01..06 기준으로 read-back한다.
- SPW-04 PASS: issue URL, source path, baseline command와 각 핵심 주장 ledger를 남겼다.
- SPW-05 PASS: 저장 후 `git diff --check`, 미완료 표식 scan, read-back을 실행해 통과했다.

## 8. 다음 게이트

이 설계 문서는 사용자 review와 receipt evidence를 통과하기 전까지 구현계획이나
production/test code를 변경하지 않는다. 설계 승인 후에만 `writing-plans` 규칙으로
구현계획을 작성하고, 그 계획의 별도 승인을 받은 다음 risk prediction과 RED test를
시작한다.
