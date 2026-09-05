# #455 Timefold 2.6.0 잔여 검증

## 목적과 범위

[#455](https://github.com/bluetape4k/clinic-appointment/issues/455)는
#450/#454에서 남았던 불변성, 직렬화·DB 왕복, 버전 간 동일 조건 비교를 실제 증거로 확인한다.
기준 소스는 develop `c70c7c6b4169c24b486fe74ad21e648d1743b597`이다.
운영 코드·API·의존성 버전은 변경하지 않는다. API의 실제 소비 경계를 검증하며,
존재하지 않는 Solver 객체 JSON 저장 기능은 추가하지 않는다.

Type B 테스트 보강이다. API 왕복, Solver 불변성, 비교 실험은 서로 독립된 테스트 영역이며
운영 계층이나 공개 계약을 바꾸는 결합 변경은 없다.

## 요구사항과 검증 근거

경로의 `solver/`는 `appointment-solver/src/test/kotlin/io/bluetape4k/clinic/appointment/solver/`,
`api/`는 `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/`를 뜻한다.

| 요구사항 | 실행한 근거 | 판정 |
|---|---|---|
| 실제 solve 뒤 pinned 불변성 | `solver/service/SolverServiceTest.kt`의 새 pinned 테스트: 실제 Solver 실행 결과의 의사·날짜·시작/종료 시각, apply 뒤 DB 상태·version 비교 | PASS |
| tenant/clinic 격리 | 같은 tenant의 다른 clinic 및 다른 tenant의 clinic을 각각 생성, target만 optimize, 외부 예약을 섞은 결과는 apply 거부, 양쪽 DB 불변 확인 | PASS |
| source version 변경 거부 | 기존 원본 version 변경 및 advisory 이후 동시 writer 테스트 | PASS |
| planningFactVersion 변경 거부 | 기존 clinic, doctor, schedule, absence, treatment, equipment, break, holiday, closure 변경 테스트 | PASS |
| 부분 적용 방지 | 기존 H2 CAS rollback 및 새 PostgreSQL 중복 assignment CAS 실패 rollback 테스트 | PASS |
| 실제 PostgreSQL 경합 | `SolverServicePostgresConcurrencyTest` 3개: clinic 변경, appointment lock 경합, 선행 assignment rollback | PASS |
| 실제 mapper·HTTP·DB 왕복 | `api/controller/AppointmentControllerTest.kt`의 새 JSON/DB 테스트: 주입된 Jackson 3 JsonMapper, POST, scoped Exposed 재조회, 별도 transaction 재조회, GET DTO 대조 | PASS |
| 계약 필드 보존 | 요청/응답 JSON 왕복과 DB의 clinic/doctor/treatment/member ID, 환자 정보, 날짜·시각, REQUESTED 상태, version 0 비교 | PASS |
| Solver JSON 계약 경계 | `appointment-api/build.gradle.kts`에 Solver 의존성 없음. 내부 ScheduleSolution 전체의 JSON 저장 계약 대신 실제 Appointment 요청/응답 경계 검증 | PASS |
| 현재 조합 compile·Spring Boot 통합 | Solver 전체 106개, PostgreSQL 프로필 API controller 27개 | PASS |
| 동일 코드·나머지 의존성 고정 | 아래 소스 SHA-256 및 실제 runtime artifact 162개 대조: Timefold 3개만 다름 | PASS |
| 예열·반복·점수 재계산·명시 실행 | 별도 `timefoldVersionComparison` 테스트 각 버전 1개 실행, 시나리오당 예열 2회+측정 5회, 독립 재계산 일치 | PASS |

양성 apply는 기존 원자적 assignment 성공 테스트와 새 pinned 테스트에서 확인한다.
음성 apply는 외부 scope 및 stale 결과를 거부하고 DB에 부분 변경을 남기지 않는지 확인한다.
JSON DTO에 없는 tenant/version을 임의로 추가하지 않는다. tenant/clinic은 scoped DB 조회,
version은 실제 저장 레코드에서 확인한다.

## 동일 조건 비교

2026-09-05 같은 Apple M5 / 메모리 32 GiB / macOS 26.6.2(25G83)에서 순차 실행했다.
Java는 Oracle GraalVM 25.0.4.1, Gradle wrapper는 9.7.1이다.
테스트 JVM은 `-Xms2g -Xmx4g`, fork 1개, JUnit 병렬 실행 비활성화,
Solver move thread는 `NONE`, seed는 37이다.

공통 `BenchmarkTest.buildSolution` fixture와 운영 `AppointmentConstraintProvider`를 사용했다.
의사 2명/예약 10개 및 의사 5명/예약 30개, 2026-03-23부터 5일,
30분 슬롯을 사용한다. Construction Heuristic은 `FIRST_FIT_DECREASING`,
Local Search는 `LATE_ACCEPTANCE`이며 Local Search 200 step으로 종료한다.
시간 제한은 쓰지 않는다. 같은 step 수가 같은 move 평가 횟수나 CPU 작업량을 보장하지는 않는다.

매 반복마다 새 문제와 Solver를 만들고 `solve` 구간만 nanoTime으로 측정한다.
최종 score를 별도로 보관한 뒤 solution.score를 비우고 새 SolutionManager의 ScoreDirector로
재계산해 일치를 검증한다. 이는 같은 제약 구현을 이용한 독립 재계산이며,
제약식 자체의 타당성을 별도 수학 모델로 증명한 것은 아니다.

| 의사/예약 | 버전 | hard/soft score | feasible | 최소/중앙값/최대(ms), 측정 5회 |
|---|---|---|---|---|
| 2/10 | 2.4.0 | 0 / -100 | 5/5 | 10.717 / 14.190 / 17.418 |
| 2/10 | 2.6.0 | 0 / -100 | 5/5 | 11.033 / 13.203 / 14.351 |
| 5/30 | 2.4.0 | 0 / -24300 | 5/5 | 13.227 / 14.582 / 15.115 |
| 5/30 | 2.6.0 | 0 / -24300 | 5/5 | 13.080 / 14.732 / 15.203 |

모든 예열·측정 결과도 feasible이며 독립 재계산과 일치했다. 두 버전의 최종 점수는 같다.
2/10에서는 2.6.0 중앙값이 작고, 5/30에서는 비슷한 값이 관측되었다.
표본이 작고 한 머신에서 2.4.0 다음 2.6.0 순서로 실행했으므로 실행 순서·JIT·GC 영향을 배제할 수 없다.
운영 부하의 처리량·p95·통계적 동등성 또는 보편적 성능 향상을 주장하지 않는다.
이는 측정을 미룬 항목이 아니라 이 실험으로 주장할 수 있는 범위의 한계다.

### 원시 자료와 동일성

- [2.4.0 원시 CSV](assets/issue-455/timefold-2.4.0.csv)
- [2.6.0 원시 CSV](assets/issue-455/timefold-2.6.0.csv)
- [2.4.0 runtime artifact](assets/issue-455/runtime-2.4.0.txt)
- [2.6.0 runtime artifact](assets/issue-455/runtime-2.6.0.txt)

각 runtime 목록은 Gradle `testRuntimeClasspath.resolvedConfiguration.resolvedArtifacts`의
moduleVersion ID를 정렬·중복 제거한 162개 항목이다.
Timefold core/benchmark/jaxb를 제외한 159개 좌표가 같고, Exposed는 모두 1.5.0이다.
양쪽 worktree에서 다음 SHA-256이 일치한다.

| 파일 | SHA-256 |
|---|---|
| BenchmarkTest.kt | `9efda009a5423c5b788517d0b6466e510bc722abe8754b317dea14c24fda3796` |
| VersionComparisonTest.kt | `1c8aacf30e71b24528626f74a5b50f494bf009b8a03cd6875f912e2d8f46a9e5` |
| AppointmentConstraintProvider.kt | `031920287578c1928115f4b6952c5338e0acf85aaec960b21af3bede8fd4f849` |

### 재현 명령

아래 명령은 각각 완료한 다음 다음 명령을 실행한다.

```bash
./gradlew :appointment-solver:cleanTest :appointment-solver:test detekt \
  --no-build-cache --no-parallel --no-daemon --max-workers=2 --console=plain

./gradlew :appointment-api:test --tests '*AppointmentControllerTest' \
  -Dspring.profiles.active=test,test-postgresql \
  --no-build-cache --no-parallel --no-daemon --max-workers=2 --console=plain

./gradlew :appointment-solver:timefoldVersionComparison \
  --no-build-cache --no-parallel --no-daemon --max-workers=2 --console=plain

bash scripts/verify-dependency-contract.sh
bash scripts/verify-dependency-locking.sh
./gradlew verifyDependencyGovernance --no-configuration-cache --no-daemon
```

2.4.0 기준선은 동일 기준 커밋의 별도 worktree에서 공통 BenchmarkTest,
VersionComparisonTest 및 비교 Test task만 동일하게 적용한다.
catalog의 timefold-solver와 solver lock의 core/benchmark/jaxb를 2.4.0으로 맞추고
나머지 좌표는 유지한다. 2.6.0 내부 API `updateShadowVariables`를 사용하는 기존
`IncrementalScoreRegressionTest.kt`만 기준선의 test source set에서 제외한다.
이 제외는 비교 코드와 무관한 컴파일 호환 조치이며, 배포 브랜치에는 적용하지 않는다.
2.6.0에서는 해당 점수 회귀 테스트를 포함한 106개를 모두 실행했다.

비교 task는 일반 test의 `version-comparison` 제외와 구분하여 명시적으로 실행한다.
태그 제외나 UP-TO-DATE를 측정 성공으로 세지 않는다. CSV는
`appointment-solver/build/issue-455/comparison.csv`에 생성되며 매 실행 교체되므로 보존본은 별도로 둔다.
기준선 worktree는 증거 재현용으로 보존하며 이 PR에 버전 하향 변경을 포함하지 않는다.

## 실패에서 얻은 재발 방지 규칙

1. 사용자 교정: 이전 작업에서 미검증을 남겨둔 채 종료하려 했다. 사용자가 이슈 등록 후 실제 검증을 요구했다.
   다음부터 원래 수락 조건과 실행 증거를 먼저 매핑하고, 후속 이슈 생성만으로 완료를 선언하지 않는다.
2. API 테스트의 첫 가정은 POST DTO와 GET DTO가 완전히 같다는 것이었다.
   실제로 `AppointmentRepository.save`는 `record.copy(id = id)`를 반환하고 DB 기본 timestamp를 재조회하지 않는다.
   nullable timestamp가 있는 POST와 DB 조회 GET을 무조건 같다고 비교한 테스트가 실패했다.
   운영 코드는 바꾸지 않고 각각의 JSON 왕복과 DB→GET 계약을 비교하도록 수정했다.
   앞으로 생성 기본값은 저장 반환값과 재조회값의 계약을 먼저 구분한다.
3. pinned 관찰 테스트에 비기본 timeLimit을 전달하면 주입한 factory 대신 별도 factory를 사용하는
   SolverService 분기로 들어갔다. timeLimit을 생략하고 실제 Solver로 위임하는 관찰 factory를 사용했다.
   captured solution을 직접 검사하여 관찰하지 않은 결과로 통과하지 못하게 한다.
4. 같은 Gradle 호출에 일반 test와 비교 Test task를 넣었을 때 실행 구간이 겹쳤다.
   해당 측정은 폐기하고 두 버전 모두 비교 task 단독으로 다시 실행했다.
   `mustRunAfter(tasks.test)`도 추가했다. 향후 측정은 독립 호출로 직렬화한다.
5. 처음부터 2.4.0 전체 테스트를 컴파일할 수 있다고 가정했으나 2.6.0 전용 내부 API가 있었다.
   비교 공통 코드를 수정하지 않고 기준선 전용 제외를 명시한다.
   버전 비교 전에 공통 API 경계와 실제 runtime 좌표를 대조한다.
6. 초기 구현 보조 에이전트가 응답 없이 지연되어 중단하고 주 세션이 이어받았다.
   부분 초안의 tenant 필드와 실제 Solver 관찰 경로를 직접 확인·수정했다.
   에이전트 실행이나 초안 존재 자체를 완료 증거로 삼지 않는다.

## 정적 분석과 검토

기본 root `detekt`는 `NO-SOURCE`였다. 실제 Kotlin 소스를 지정하자 도구가 요구하는
Kotlin 2.4.10과 BOM이 선택한 2.4.0의 불일치로 실패했다. 저장소 전체 설정 복구는
[#456](https://github.com/bluetape4k/clinic-appointment/issues/456)에 재현 근거와 함께 등록했다.
이 기본 task를 정적 분석 PASS로 세지 않는다.

이번 변경은 [공식 alpha6 release](https://github.com/detekt/detekt/releases/tag/v2.0.0-alpha.6)의
`detekt-cli-2.0.0-alpha.6-all.jar`로 별도 분석했다. 다운로드한 파일의 SHA-256은
GitHub release asset digest
`d46ca62ea4d62769b5d5c3ba94d49fa9b80ba11c7dba74ddb6df7fcc2c19c5fd`와 일치했다.
운영 dependency, lockfile, verification-metadata는 변경하지 않았다.

기준 커밋의 기존 4개 파일을 추출해 같은 CLI 기본 규칙으로 baseline을 생성했다.
기존 13개 발생 위치는 7개 고유 baseline ID에 해당한다. 현재 5개 Kotlin 파일의 새 위반 중
비교 테스트의 긴 CSV header는 줄을 나눴다. `SolverServiceTest`의 LargeClass는
같은 DB fixture에서 stale/scope/pinned 사례를 유지하기 위한 단일 클래스 예외로 명시했다.
테스트 동작이나 오류 탐지 규칙 전체를 끄지 않았으며, 기존 위반의 제거를 주장하지 않는다.

최종 명령은 `java -jar <검증한-detekt.jar> --input <변경한-Kotlin-5개-경로를-콜론으로-연결>
--baseline docs/lessons/assets/issue-455/detekt-baseline.xml --jvm-target 25
--language-version 2.4 --report checkstyle:<결과.xml>`이다. 종료 코드 0이며
[최종 차이 보고서](assets/issue-455/detekt-delta.xml)에 미처리 위반이 없다.
[기존 baseline](assets/issue-455/detekt-baseline.xml)과
[기존 발생 위치 보고서](assets/issue-455/detekt-baseline-report.xml)도 보존했다.
이 검사는 기본 light 정적 분석이며 full type-resolution과 동일하다고 주장하지 않는다.
타입·호환성은 실제 모듈 compile과 통합 테스트로 별도 검증했다.

독립 코드 리뷰의 POST 전체 필드 비교 P2는 전체 DTO 비교로 수정했고 API 27개를 재실행했다.
원인 분류 없이 NO-SOURCE/도구 실행 성공을 분석 성공으로 취급하지 않는 것을 재발 방지 규칙으로 남긴다.

## 검증 상태

로컬 Solver 106/106, PostgreSQL API controller 27/27,
각 버전 비교 task 1/1, 의존성 계약·잠금·governance 및 변경 범위 정적 분석 PASS.
최종 리뷰·정확한 PR head의 CI 결과는 PR DoD에 연결한다.
최종 머지와 이슈 종료는 별도 승인 뒤에 수행한다.
