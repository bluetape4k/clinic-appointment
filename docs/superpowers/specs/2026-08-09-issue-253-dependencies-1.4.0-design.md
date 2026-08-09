# Issue #253 의존성 1.4.0 전환 설계

## 목적

`bluetape4k-dependencies`를 1.4.0으로 올리면서 Timefold Solver 2.4.0,
bluetape4k projects 1.12.1, Leader 0.5.0, Exposed 1.12.1과 관련 전이 의존성을
하나의 검증 가능한 resolved graph로 정렬한다. 버전 문자열 변경만으로 성공을 판단하지
않고 solver 품질·실행시간, Redis 캐시 wire payload, Kafka4와 Exposed 통합까지 모듈 단위로
검증한다.

이 설계는 사용자가 승인한 Issue #253의 범위와
`codex/issue-253-dependencies-1.4.0` 독립 delivery lane을 그대로 문서화한다.

## 변경 전 기준선

기준 SHA는 `e790793a2e8eccf4269eba97f3faad084b7c568d`다.

| 좌표 | resolved version |
| --- | --- |
| `ai.timefold.solver:timefold-solver-core` | 2.2.0 |
| `io.github.bluetape4k.leader:bluetape4k-leader-redis-lettuce` | 0.4.0 |
| `org.apache.fory:fory-core` | 1.1.0 |
| `org.springdoc:springdoc-openapi-starter-webmvc-ui` | 3.0.3 |
| `org.apache.kafka:kafka-clients` | 4.2.1 |
| `org.jetbrains.exposed:exposed-core` | 1.3.0 |

`./gradlew :appointment-solver:test`는 68개 테스트가 통과했다. 동일 실행에서 solver
benchmark의 기준값은 다음과 같다.

| 시나리오 | score | 실행시간 |
| --- | --- | ---: |
| 소규모 | `0hard/0soft` | 5,027 ms |
| 중규모 | `0hard/-500soft` | 8,075 ms |
| 대규모 | `0hard/-2000soft` | 15,922 ms |

이 값은 동일 머신의 전후 비교 기준이며 production SLO가 아니다.

## 대안 비교

### A. 좌표별 단일 권한으로 정렬 (채택)

`bluetape4k-dependencies:1.4.0`을 bluetape ecosystem과 Timefold의 권한으로 사용한다.
Spring Boot, Kotlin, Coroutines BOM은 서로 다른 좌표군의 권한이므로 유지한다. 별도
Timefold BOM과 Timefold 직접 버전, Springdoc 직접 버전은 제거한다. Gradle plugin은
Maven BOM이 관리할 수 없으므로 Exposed plugin 1.4.0을 명시한다.

장점은 선언과 resolved graph가 같은 권한 모델을 표현하고 향후 ecosystem upgrade가
한 지점에서 검토된다는 것이다. 단점은 1.4.0이 가져오는 여러 런타임 변화의 호환성을
한 delivery lane에서 증명해야 한다는 점이다.

### B. BOM만 올리고 기존 직접 override 유지

diff는 작지만 Timefold 2.2.0과 Springdoc 3.0.3이 1.4.0의 관리 버전을 계속 가린다.
Issue #253의 단일 권한 목적과 resolved graph DoD를 만족하지 않아 채택하지 않는다.

### C. 의존성을 좌표별로 개별 upgrade

rollback 원인은 좁아지지만 bluetape ecosystem release train과 다른 조합을 새로 만든다.
지원되는 BOM 조합을 사용하는 이번 작업보다 검증 표면이 커지므로 채택하지 않는다.

## 선택 설계

### 버전 권한

- `gradle/libs.versions.toml`의 `bluetape4k-dependencies`를 1.4.0으로 올린다.
- Exposed Gradle plugin은 1.4.0으로 명시한다. runtime Exposed는 BOM 관리 버전만 쓴다.
- `timefold-solver = "2.2.0"`, local Timefold BOM alias와 root의 별도 Timefold BOM import를
  제거한다. core/benchmark module-only alias는 유지한다.
- Springdoc의 직접 version을 제거하고 module-only alias로 바꾼다.
- Kotlin 2.4.0과 Coroutines 1.11.0 override는 프로젝트가 Spring Boot보다 새 버전을
  의도적으로 사용하는 별도 좌표 권한이므로 유지한다.
- JJWT, LZ4, MockK, random-beans, datafaker, Resilience4j, kotlinx-benchmark처럼 1.4.0이
  관리하지 않는 프로젝트 로컬 버전은 변경하지 않는다.

### Redis 캐시 호환성과 rollback

`CacheConfig`의 기본 near-cache codec은 `LettuceBinaryCodecs.default()`가 제공하는
LZ4+Fory wire format을 사용한다. 같은 classpath에서 쓰고 읽는 round trip만으로는
Fory upgrade 호환성을 증명할 수 없다.

변경 전 dependency graph로 실제 `DoctorRecord`, `EquipmentRecord`,
`TreatmentTypeRecord` 목록의 legacy payload를 생성해 고정 fixture로 보존한다. 변경 후
기본 codec이 이 fixture를 읽고 값과 타입을 복원하는지 테스트한다. 새 payload를 구버전
codec으로 읽는 rollback 방향도 별도 classpath 검증으로 확인한다.

양방향 호환성이 증명되면 기존 remote cache name을 유지한다. 어느 방향이든 실패하면
Spring `Cache`의 논리 이름은 유지하되 Redis remote name만 `clinic-doctors-v2`,
`clinic-equipments-v2`, `clinic-treatment-types-v2`로 분리한다. 배포 전에는 v2만 비우고,
rollback 전에는 v1을 비워 stale payload 재노출을 막는다. 캐시 TTL은 최대 1시간이므로
구 namespace 삭제는 배포 성공 뒤 TTL 경과 후 수행한다. cache name에는 Redis Cluster
hash-slot 의미를 바꾸는 `:` 또는 동적 tenant 식별자를 추가하지 않는다.

### Solver 품질과 성능

`BenchmarkTest`의 고정 dataset, seed와 time limit를 유지한 채 같은 머신에서 변경 전후를
비교한다. 소·중·대 시나리오는 모두 hard score가 0 이상이고 기존 soft score 하한을
만족해야 한다. wall time은 기존 테스트 상한을 만족해야 하며, 단일 실행의 작은 편차는
회귀로 판단하지 않는다. 변경 후 동일 class를 두 번 실행해 warm-up 이후 측정값을 기록하고
기준 대비 25%를 넘는 악화가 반복되면 원인을 분석한다.

### 모듈 호환성

- `appointment-core`, `appointment-event`: Exposed plugin/runtime과 JDBC/R2DBC 컴파일·테스트.
- `appointment-solver`: Timefold 2.4.0 단일 해석, planning model validation, score와 시간.
- `appointment-notification`: Leader 0.5.0과 Kafka4/Redis 연동 회귀. 신규 관측성 도입은 #254.
- `appointment-messaging`: Kafka clients/Spring Kafka 및 replay/outbox 테스트. lifecycle 변경은 #249.
- `appointment-api`: Springdoc OpenAPI, Fory near-cache fixture, Exposed/Flyway/API 통합.
- `appointment-messaging-benchmark`: compile/test 및 smoke benchmark. API 역의존 제거는 #250.

## 오류와 복구

- resolved graph에 Timefold 2.2.0 또는 직접 Springdoc override가 남으면 build 성공과 무관하게
  실패로 처리한다.
- Timefold 2.4.0 validation이 planning model 오류를 드러내면 validation을 우회하지 않고
  모델 또는 fixture의 실제 계약 오류만 최소 수정한다.
- Redis legacy payload decode가 실패하면 예외를 삼키는 adapter 동작에 기대지 않고 v2
  namespace로 분리한다.
- module test 또는 benchmark가 실패하면 1.4.0 전환과 동작 변경을 한 PR에 섞지 않는다.
  범위를 벗어난 결함은 별도 이슈로 남기고 이 lane을 merge-ready로 표시하지 않는다.

## 검증 계획

1. 변경 전후 핵심 좌표의 `dependencyInsight`를 비교한다.
2. legacy LZ4+Fory fixture의 양방향 호환성을 검증한다. 실패하면 v2 namespace와 운영
   rollback 절차를 적용하고 테스트한다.
3. `appointment-solver` 전체 테스트와 benchmark를 반복 실행해 score와 시간을 기록한다.
4. 각 non-frontend module을 개별 test/build하고 마지막에 root build를 실행한다.
5. Kafka/Exposed/Springdoc/Flyway 관련 integration test와 messaging benchmark smoke를 실행한다.
6. dependency vulnerability report가 저장소에 구성되어 있으면 실행하고, 없으면 resolved
   graph와 GitHub advisory/Dependabot 상태를 검토 근거로 남긴다.
7. 독립 7-tier와 Kotlin 패턴 검토에서 `P0=0`, `P1=0`을 확인한다.

## 수용 기준과 DoD

- `bluetape4k-dependencies:1.4.0`과 Exposed plugin 1.4.0이 선언된다.
- Timefold core/benchmark는 BOM이 관리하는 2.4.0 하나로 해석되고 2.2.0이 남지 않는다.
- Springdoc 직접 version과 별도 Timefold BOM이 제거된다.
- 직접 override의 유지·제거 근거가 이 문서와 version catalog 주석에 일치한다.
- Redis cache payload의 양방향 호환성 또는 v2 namespace rollback 계약이 실행 가능한
  테스트와 runbook으로 증명된다.
- solver score 하한과 시간 상한을 유지하고 module별 테스트 및 전체 build가 통과한다.
- #249, #250, #254의 기능·아키텍처 변경을 이 PR에 포함하지 않는다.
- 독립 검토에 P0/P1 blocker가 없고 exact PR head의 CI 상태가 확인된다.

