# Spring-managed Exposed DataSource 표준화 구현 계획

> **에이전트 작업자 필수 안내:** 모든 code task에서 `test-driven-development`와 해당 Kotlin/Exposed/Spring pattern skill을 사용합니다. 각 checkbox를 순서대로 실행하고 fresh evidence를 기록합니다.

**목표:** 대상 Spring runtime Exposed handle이 주입된 application `DataSource`를 재사용하게 하면서 global default restoration을 보존하고, 의도적인 standalone database fixture를 문서화합니다.

**아키텍처:** `appointment-api`에 internal `ExposedDatabaseFactory` 하나와 destroy-time `ExposedDatabaseLifecycle`을 추가합니다. Factory는 `Database.connect(dataSource)`를 serialize하고 이전 `TransactionManager.defaultDatabase`를 복원합니다. Lifecycle은 Spring이 pool을 닫기 전에 factory-owned manager를 unregister합니다. 두 runtime configuration class가 이를 호출합니다. Spring context wiring test는 Hikari-backed `DataSource`를 제공하고, standalone test·migration/dialect fixture·Gatling은 독립적으로 유지하며 명시적 allowlist/audit로 다룹니다.

**기술 스택:** Kotlin 2.3, Spring Boot 4.1, Exposed JDBC v1, HikariCP, H2, JUnit 5, bluetape4k assertions, Gradle.

---

## 파일 맵과 소유권

| 파일 | 책임 |
|---|---|
| `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/ExposedDatabaseFactory.kt` | 공유 runtime connection/lifecycle 경계 |
| `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/ExposedDatabaseLifecycle.kt` | Factory-owned Exposed manager를 unregister하는 Spring destroy hook |
| `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/ServiceConfig.kt` | Commitment runtime bean이 factory를 호출 |
| `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/ProfileReevaluationConfiguration.kt` | Profile runtime bean이 factory를 호출 |
| `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/config/ExposedDatabaseFactoryTest.kt` | 주입된 pool과 default restoration에 대한 RED/GREEN proof |
| `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/config/AppointmentCommitmentApplicationWiringTest.kt` | Spring commitment wiring이 `DataSource` bean을 사용 |
| `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/config/ProfileReevaluationWiringTest.kt` | Spring profile wiring이 `DataSource` bean을 사용 |
| `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/config/NotificationReminderRecoveryWiringTest.kt` | Notification runtime wiring이 `DataSource` bean을 사용 |
| `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/DataSourceOwnershipContractTest.kt` | Production direct-setup guard와 allowlist assertion |
| `docs/runbooks/spring-managed-exposed-datasource.ko.md` | Korean lifecycle, qualifier, allowlist contract |
| `docs/lessons/2026-08-05-issue-223-spring-managed-exposed-datasource.md` | Durable decision과 향후 guard |
| `docs/reviews/2026-08-05-issue-223-spring-managed-exposed-datasource-review.ko.md` | Six-lens review finding과 P0/P1 gate |

Module registration, dependency catalog, Flyway schema, README public API 또는 tenant-isolation source 변경은 계획하지 않습니다. Audit에서 ownership defect를 찾지 않는 한 기존 migration/dialect/Gatling file은 변경하지 않습니다.

## Task 1: 실패하는 test로 factory contract 고정

**파일:**

- 생성: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/config/ExposedDatabaseFactoryTest.kt`

- [x] **Step 1: RED test 추가**

Hikari-backed H2 pool과 해당 pool로 생성한 `marker_value` table을 사용합니다. Sentinel Exposed default를 설정하고 아직 생성하지 않은 factory를 호출한 뒤 반환된 handle로 marker를 query하며 sentinel이 복원되는지 assertion합니다. 완료된 test는 barrier-bounded concurrent registration, instrumented `DataSource`를 통한 반복 transaction, factory lifecycle cleanup, 외부에서 등록된 `Database`에 대한 no-op 동작도 다룹니다. 각 test는 자신이 생성한 resource만 닫습니다.

```kotlin
val database = ExposedDatabaseFactory.connect(injectedHikariDataSource)
transaction(database) {
    exec("SELECT marker_value FROM datasource_marker") { rows ->
        rows.next()
        rows.getInt(1)
    }
} shouldBeEqualTo 223
TransactionManager.defaultDatabase shouldBeEqualTo sentinel
```

`bluetape4k-assertions`를 사용하며 JUnit `assertEquals`나 `!!`를 추가하지 않습니다.

- [x] **Step 2: RED test 실행**

Run:

```bash
./gradlew :appointment-api:test --tests '*ExposedDatabaseFactoryTest' --no-build-cache
```

예상 결과: `ExposedDatabaseFactory`가 없으므로 compilation이 실패합니다. Hikari/Exposed API syntax 때문에 test가 실패하면, failure가 missing factory symbol을 정확히 가리킬 때까지 test를 수정합니다.

## Task 2: 공유 runtime factory 구현 및 green 확인

**파일:**

- 생성: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/ExposedDatabaseFactory.kt`
- 테스트: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/config/ExposedDatabaseFactoryTest.kt`

- [x] **Step 1: 최소 factory 추가**

구현은 registration lock 하나를 유지하고 `finally`에서 이전 default를 복원하며, factory가 생성한 handle을 기록하고 `ExposedDatabaseLifecycle`이 사용하는 guarded release operation을 노출합니다.

Korean KDoc으로 Spring이 `DataSource` 생성/close를 소유하고 factory는 Exposed handle만 생성하며 registration 이후 global default가 복원된다는 점을 문서화합니다. 각 eligible runtime database에 `ExposedDatabaseLifecycle` bean을 등록해 context destruction이 Spring의 injected pool close 전에 factory-owned release guard를 호출하게 합니다. Context가 external `Database`를 제공하면 lifecycle guard는 no-op이고 해당 external manager를 unregister하지 않습니다.

- [x] **Step 2: GREEN test 실행**

Task 1 command를 다시 실행합니다. 예상 결과: 하나의 test가 compilation warning이나 resource-leak failure 없이 통과합니다.

- [x] **Step 3: 격리된 factory commit**

```bash
git add appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/ExposedDatabaseFactory.kt appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/config/ExposedDatabaseFactoryTest.kt
git commit -m "Centralize Spring-managed Exposed database creation" -m "Keep one synchronized DataSource-to-Database registration boundary before migrating Spring wiring tests.\n\nConstraint: Spring owns the injected HikariDataSource lifecycle and Exposed global default restoration must remain compatible.\nRejected: Repository-wide direct URL replacement would break standalone dialect and Gatling fixtures.\nConfidence: high\nScope-risk: moderate\nDirective: Future multi-pool runtime paths require explicit qualifiers and marker-query wiring tests.\nTested: ExposedDatabaseFactoryTest RED/GREEN and git diff --check.\nNot-tested: full multi-backend matrix."
```

## Task 3: 두 runtime configuration을 factory로 연결

**파일:**

- 수정: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/ServiceConfig.kt:144,536-542`
- 수정: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/ProfileReevaluationConfiguration.kt:47-65`

- [x] **Step 1: 중복 registration state 제거**

각 configuration의 private `ReentrantLock`, `withLock` import, inline `TransactionManager.defaultDatabase` save/restore block을 삭제합니다. Database bean condition, parameter injection, feature flag, return type은 변경하지 않습니다. Runtime database bean은 explicit Spring bean name을 선언해 lifecycle condition이 compiler-mangled Kotlin `internal` method name에 의존하지 않게 합니다.

- [x] **Step 2: Factory에 위임**

두 method를 기존 injected parameter를 사용하는 expression body로 바꿉니다.

```kotlin
internal fun appointmentCommitmentDatabase(dataSource: DataSource): Database =
    ExposedDatabaseFactory.connect(dataSource)
```

```kotlin
fun profileReevaluationDatabase(dataSource: DataSource): Database =
    ExposedDatabaseFactory.connect(dataSource)
```

각 eligible runtime handle에 lifecycle bean을 추가합니다. Destroy callback이 factory-owned handle만 unregister하고 별도로 제공된 `Database`는 건드리지 않는지 검증합니다. Production에는 현재 runtime context당 candidate `Database`가 하나 있습니다.

- [x] **Step 3: Focused compilation/test 실행**

```bash
./gradlew :appointment-api:test --tests '*ExposedDatabaseFactoryTest' --tests '*AppointmentCommitmentApplicationWiringTest' --tests '*ProfileReevaluationWiringTest' --no-build-cache
```

예상 결과: 선택한 모든 test가 통과하고 configuration bean은 conditional 상태를 유지하며 관련 없는 tenant 또는 transaction source 변경이 나타나지 않습니다.

## Task 4: Spring wiring test를 injected Hikari DataSource로 전환

**파일:**

- 수정: `AppointmentCommitmentApplicationWiringTest.kt`
- 수정: `ProfileReevaluationWiringTest.kt`
- 수정: `NotificationReminderRecoveryWiringTest.kt`

- [x] **Step 1: Direct Database supplier 교체**

Body에서 직접 `Database.connect`를 호출하는 supplier를 모두 제거합니다.
`HikariDataSource(HikariConfig().apply {
jdbcUrl = "jdbc:h2:mem:wiring_<scope>_${System.nanoTime()};DB_CLOSE_DELAY=-1"
driverClassName = "org.h2.Driver"
username = "sa"
})`를 사용하는 named `DataSource` supplier를 추가합니다. Context가 bean lifecycle을 소유하므로 test가 같은 instance를 수동으로 닫으면 안 됩니다. 유지한 Hikari instance로 scope-unique marker table을 seed하고 context가 생성한 `Database`가 그 marker를 읽는지 assertion합니다. Supplier reference를 유지하고 각 context close 이후 `HikariDataSource.isClosed`를 assertion합니다.

- [x] **Step 2: Context-created Database assertion**

기존 wiring assertion을 유지하고 `context.getBean(Database::class.java)`를 추가해 conditional production bean이 injected DataSource로 생성되었는지 확인합니다. Profile, commitment, notification context에서는 injected pool의 `datasource_marker`/`marker_value` row를 `transaction(database) { ... }`에서 읽는 것을 가장 작은 runtime proof로 사용합니다. 기존 bluetape assertion과 설명적인 backtick test name을 사용합니다.

- [x] **Step 3: Wiring test 실행**

```bash
./gradlew :appointment-api:test --tests '*AppointmentCommitmentApplicationWiringTest' --tests '*ProfileReevaluationWiringTest' --tests '*NotificationReminderRecoveryWiringTest' --no-build-cache
```

예상 결과: 기존 bean-count/feature-flag assertion과 새 DataSource transaction check가 모두 통과합니다. Hikari를 열린 상태로 남기는 failure는 lifecycle defect이므로 진행하기 전에 수정해야 합니다.

## Task 5: Production direct-setup audit와 allowlist 문서화 추가

**파일:**

- 생성: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/DataSourceOwnershipContractTest.kt`
- 생성: `docs/runbooks/spring-managed-exposed-datasource.ko.md`

- [x] **Step 1: Production-boundary audit assertion 작성**

Test는 모든 `appointment-*/src/main` Kotlin/Java source를 읽고 `Database.connect(`가 `ExposedDatabaseFactory.kt`에만 나타나는지 assertion합니다. Production source에는 `HikariDataSource`, `SimpleDriverDataSource`, `DriverManager.getConnection`, `jdbc:` literal이 없어야 합니다. Source cleanup 전에 실행해 두 configuration file을 지목하는 예상 RED 결과를 관찰합니다. Cleanup 이후 audit를 추가했다면 green guard로 유지하고 pre-cleanup RED evidence를 lesson에 기록합니다. 현재 순서가 실행 가능한 것처럼 꾸미지 않습니다.

- [x] **Step 2: Source 갱신 후 audit 재실행**

Task 3 이후 같은 test를 다시 실행합니다. 예상 결과는 runtime factory caller 두 개가 있고 production에 direct pool/URL creation이 없는 PASS입니다.

- [x] **Step 3: Korean runbook 작성**

다음 내용을 포함합니다.

1. Spring이 `DataSource` 생성, pooling, configuration, shutdown을 소유함.
2. Exposed code는 `Database`/`DataSource`를 injection으로 받고 transaction boundary를 명시적으로 유지함. Request code는 pool을 닫지 않음.
3. Multi-pool runtime path는 explicit qualifier와 marker/wiring test를 사용해야 함.
4. Standalone unit test, migration/dialect fixture, Gatling에 대한 allowlist row. 각 row에 Spring context를 사용할 수 없거나 사용하지 않는 이유와 resource를 닫는 주체를 기록함.
5. 정확한 repository audit command와 예상 production-source 경계.

- [x] **Step 4: Audit와 diff check 실행**

```bash
./gradlew :appointment-api:test --tests '*DataSourceOwnershipContractTest' --no-build-cache
git diff --check
```

## Task 6: 검증과 durable lesson 완료

**파일:**

- 생성: `docs/lessons/2026-08-05-issue-223-spring-managed-exposed-datasource.md`
- 선택적 수정: 승인된 map 밖의 production file은 없음

- [x] **Step 1: Targeted module proof를 순차 실행**

```bash
./gradlew :appointment-api:test --tests '*ExposedDatabaseFactoryTest' --tests '*DataSourceOwnershipContractTest' --tests '*AppointmentCommitmentApplicationWiringTest' --tests '*ProfileReevaluationWiringTest' --tests '*NotificationReminderRecoveryWiringTest' --no-build-cache
./gradlew :appointment-api:compileKotlin :appointment-api:compileTestKotlin --no-build-cache
```

Testcontainers/dialect task는 위 command 이후에만 실행하고 다른 real-DB task와 병렬 실행하지 않습니다. 기존 standalone fixture를 regression scope로 유지하며 task runner를 사용할 수 없는 한 전체 multi-backend matrix를 local에서 실행하지 않습니다.

- [x] **Step 2: Final inventory 점검**

```bash
rg -n 'Database\\.connect|HikariDataSource|SimpleDriverDataSource|DriverManager\\.getConnection|jdbc:' appointment-*/src/main appointment-*/src/test appointment-api/src/gatling
```

남은 occurrence를 allowlist table 기준으로 기록합니다. 새로운 main-source match는 P1이며 delivery를 차단합니다.

- [x] **Step 3: Lesson 작성**

Context, decision, 예상 밖 failure 또는 부재, test evidence, review miss, future audit guard를 기록합니다. Diff를 검토한 뒤 새 lesson이 남지 않으면 filler 대신 구체적인 N/A evidence를 적습니다.

- [x] **Step 4: Final check 실행**

```bash
git diff --check
git status --short --branch
git log --oneline --decorate -5
```

예상 결과: clean diff check, 승인된 file만 존재, P0=0/P1=0, tracked lesson이 확인됩니다.

- [x] **Step 5: Lesson과 verification scope commit**

`Tested` trailer에 fresh Gradle command를 나열하고 `Not-tested` trailer에 external DB 또는 full matrix gap과 evidence를 나열하는 Lore commit을 사용합니다.

## Task 7: Delivery 전에 review와 workflow gate 종료

**파일:**

- Review: 이 worktree의 승인된 source, test, runbook, lesson file 전체
- Evidence: workflow receipt, plan/spec commit, review-lane report, fresh test output

- [x] **Step 1: Plan-review finding 적용**

동일한 injected pool에 barrier와 여러 `connect` call을 사용하는 deterministic concurrent factory test를 추가합니다. 각 반환 handle은 marker query에 응답해야 하고 각 caller는 이전 default가 복원되었음을 확인해야 하며, test는 sleep이나 real external DB 없이 bounded completion을 assertion해야 합니다. 하나의 instrumented/injected `DataSource`로 반복 transaction에 대한 bounded pool-reuse validation을 추가하고, 관찰한 acquisition count를 기록하며 baseline/threshold 또는 benchmark를 scope 밖으로 둔 명시적 이유를 적습니다.

- [x] **Step 2: Six review lens 실행**

Performance, Stability, Security, Operator/Ops, Developer/API, User/caller에 대한 독립 finding을 기록합니다. Main lane은 finding을 deduplicate하고 P0/P1이 하나라도 있으면 delivery를 차단합니다. P2는 승인된 file map에서 수정하거나 문서화된 bounded follow-up으로 남겨야 하며, P3 finding은 scope를 확장하지 않습니다.

- [x] **Step 3: Kotlin/Exposed/workflow checklist 재실행**

Korean KDoc/runbook/lesson language, explicit transaction boundary, Spring bean lifecycle ownership, Exposed global-default restoration, new dependency 또는 public API surface 부재, workflow receipt evidence, clean diff, required targeted Gradle command를 검증합니다. API mismatch 또는 review finding이 동작을 바꾸면 구현을 계속하기 전에 plan/spec를 새로 고칩니다.

## Task 8: 승인된 변경 게시 및 CI readiness 증명

**파일:**

- GitHub issue `#223`
- `issue-223-datasource-standardization`에서 `develop`으로 보내는 Pull request

- [ ] **Step 1: Delivery authority와 exact head 확인**

현재 user instruction이 이 issue의 push/PR/CI closeout을 승인하는지 확인한 뒤, 모든 local commit 이후 worktree branch와 exact commit SHA를 캡처합니다. 이 task에서는 merge하거나 auto-merge를 활성화하지 않습니다.

- [ ] **Step 2: Push하고 English PR 생성**

Feature branch를 push하고 remote head를 확인한 뒤에만 PR을 생성합니다. `#223`을 연결하고 `debop`을 assign하며 issue의 label과 milestone을 미러링합니다. PR body에는 scope, test, known gap과 마지막 `## DoD Status` section을 포함해야 합니다.

- [ ] **Step 3: Live PR metadata와 CI 검증**

`gh`로 live PR body, issue link, assignee, label, milestone, head SHA, review state, status check를 다시 읽습니다. Required CI를 기다리고 actionable failure 또는 review comment를 처리합니다. 최종 green-check evidence를 캡처하며 workflow receipt는 delivery record의 일부로 유지합니다.

## Task 9: Fresh merge-approval gate에서 중단

- [ ] **Step 1: Merge-ready DoD 보고**

최종 DoD에 exact PR head, green CI/review evidence, local/remote parity, 남은 모든 risk를 보고합니다. `gh pr merge`를 실행하기 전에 정확히 검증한 head에 연결된 fresh explicit approval을 요청합니다.

- [ ] **Step 2: Fresh approval 이후에만 실행**

Auto-merge 없이 PR을 merge하고 merged state를 검증합니다. Root `develop` checkout을 synchronize하고, merge되었음이 입증된 feature worktree/branch만 제거한 뒤 status와 관련 helper check를 다시 실행합니다. Fresh approval이 없으면 PR을 열어 둔 채 worktree도 유지합니다.

## Acceptance 추적성

| Spec criterion | 계획 task | 증거 |
|---|---|---|
| 대상 production direct setup 없음 | Tasks 3, 5 | `DataSourceOwnershipContractTest` + `rg` inventory |
| Shared factory, default restoration, manager cleanup | Tasks 1–3 | Hikari marker/sentinel + lifecycle unregister test |
| Concurrent registration과 pool reuse가 bounded임 | Task 7 | Barrier-based factory test + instrumented DataSource validation |
| Spring wiring이 injected pool을 사용하고 닫음 | Task 4 | Marker query 3개, context-runner test, `isClosed` assertion |
| Standalone fixture 문서화 | Task 5 | Korean runbook allowlist |
| Search와 targeted test | Task 6 | fresh `rg`, Gradle targeted/module compile, diff check |
| PR/CI metadata와 delivery evidence | Tasks 8–9 | Live `gh` head/body/check와 merge-approval gate |
| Issue #39 미변경 | Tasks 3, 6 | Final diff path review와 tenant test scope 불변 |

## Rollback과 중단 조건

- Commit 전에 이 feature worktree의 승인된 file만 revert하며 `develop`을 건드리거나 관련 없는 state를 버리지 않습니다.
- Partial context startup이 발생하면 먼저 context를 닫고 lifecycle bean이 factory-owned handle을 unregister하게 한 뒤, 유지된 Hikari reference가 닫혔는지 확인하고 재시도합니다. Rollback은 factory를 삭제하기 전에 lifecycle bean을 제거하고 두 original configuration block을 복원합니다.
- Hikari 또는 Exposed API compilation이 plan snippet과 다르면 해당 task에서 중단하고 실제 dependency source를 점검한 뒤 동작을 바꾸기 전에 plan/spec를 수정합니다.
- P0/P1 review finding, targeted test failure, 관련 없는 dirty file, workflow receipt evidence 누락은 PR delivery를 차단합니다.
