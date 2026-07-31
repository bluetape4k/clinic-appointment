# Issue #201 병원 permit registry 수명주기 구현 계획

> 승인된 기준 문서:
> `docs/superpowers/specs/2026-07-31-issue-201-clinic-permit-registry-lifecycle-design.md`

## 목표

`ProfileReevaluationDispatcher`가 프로세스 수명 동안 관찰한 모든 병원 키를
보관하지 않도록 한다. 병원별 동시성 상한은 그대로 유지하면서, permit을
보유하거나 기다리는 coroutine이 없는 항목은 즉시 제거한다.

완료 조건은 다음과 같다.

- 대기자를 포함한 참조 수가 0인 병원 항목만 제거한다.
- 같은 병원 키에 활성 `Semaphore`가 둘 생기지 않는다.
- 취소와 예외 경로에서도 참조가 남지 않는다.
- registry 크기와 제거 횟수를 식별자 tag 없이 관측한다.
- 기존 전역 및 병원별 동시성 테스트가 그대로 통과한다.

## 변경 파일

- 추가:
  `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/profile/ClinicPermitRegistry.kt`
- 수정:
  `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/profile/ProfileReevaluationDispatcher.kt`
- 수정:
  `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/profile/ProfileReevaluationMetrics.kt`
- 수정:
  `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/profile/ProfileReevaluationDispatcherTest.kt`

새 의존성, 설정 키, 데이터베이스 변경은 없다.

## Task 1. 현재 동작과 회귀 기준 고정

1. 기존 dispatcher 테스트를 실행한다.

   ```bash
   ./gradlew :appointment-api:test \
     --tests '*ProfileReevaluationDispatcherTest'
   ```

2. 다음 기존 계약이 통과하는지 기록한다.

   - 32개 병원 backlog에서 전역 동시성 최대 8
   - 병원별 동시성 최대 2
   - 공정 선점 cursor와 우선순위 동작 유지

3. 기준 문서와 실제 코드가 다르면 구현 전에 계획을 수정한다.

## Task 2. 누적·취소·경쟁 회귀 테스트를 먼저 RED로 만든다

`ProfileReevaluationDispatcherTest`에 dispatcher의 공개 동작과 Micrometer
registry만 사용하는 테스트를 추가한다. 구현 전에도 컴파일되도록 내부 map이나
reflection에는 의존하지 않는다.

### 2.1 대규모 병원 churn 뒤 registry가 비워지는지 검증

- 수백 개의 서로 다른 병원 작업을 여러 batch로 처리한다.
- `SimpleMeterRegistry`를 `ProfileReevaluationMetrics`에 주입한다.
- 모든 작업이 끝난 뒤 다음을 기대한다.

  ```kotlin
  registry.find("clinic.profile.reevaluation.clinic.permit.registry.size")
      .gauge()
      ?.value() shouldBeEqualTo 0.0

  registry.find("clinic.profile.reevaluation.clinic.permit.evictions")
      .counter()
      ?.count() shouldBeEqualTo clinicCount.toDouble()
  ```

- RED 단계에서는 아직 존재하지 않는 production 상수를 참조하지 않고 meter 이름
  문자열로 조회한다. 현재 구현에는 두 meter가 없으므로 컴파일은 통과하고 assertion
  실패가 발생해야 한다.
- 테스트 실패가 metric 부재 때문인지 확인하고 RED 증거를 남긴다.

### 2.2 permit 보유자와 대기자가 함께 취소되어도 참조가 남지 않는지 검증

- 같은 병원의 작업 세 건을 한 번에 반환하는 test store를 사용한다.
- `perClinicConcurrency = 1`로 첫 작업은 permit을 보유하고 나머지는 기다리게 한다.
- 첫 worker 진입을 `CompletableDeferred`로 확인한 뒤 `dispatchOnce()`의 parent job을
  취소한다.
- 취소 완료 뒤 registry size는 0, eviction은 1이어야 한다.
- worker의 `CancellationException`을 성공 결과로 바꾸지 않는다.

### 2.3 제거와 재확보 경쟁에서도 병원별 상한이 유지되는지 검증

- 같은 병원의 작업을 여러 `dispatchOnce()` 호출이 연속·동시에 claim하게 한다.
- worker의 활성 수와 최대 활성 수를 `AtomicInteger`로 측정한다.
- 각 작업 사이에 `yield()` 또는 짧은 `delay()`를 넣어 제거/재확보 경합을 늘린다.
- `perClinicConcurrency = 1`일 때 최대 활성 수는 항상 1이어야 한다.
- 마지막 registry size는 0이어야 한다.

### 2.4 RED 실행

```bash
./gradlew :appointment-api:test \
  --tests '*ProfileReevaluationDispatcherTest'
```

RED 판정:

- 새 테스트가 실제 assertion으로 실패한다.
- 기존 테스트는 계속 통과한다.
- 컴파일 오류, test fixture 오류, timeout은 유효한 RED로 인정하지 않는다.

## Task 3. 참조 계수 기반 `ClinicPermitRegistry` 구현

### 3.1 registry 구조

새 `ClinicPermitRegistry.kt`에 package-internal component를 둔다.

```kotlin
internal class ClinicPermitRegistry(
    private val permits: Int,
    private val metrics: ProfileReevaluationMetrics?,
) {
    private val entries = ConcurrentHashMap<ClinicKey, PermitEntry>()

    suspend fun <T> withPermit(
        scope: ProfileReevaluationScope,
        action: suspend () -> T,
    ): T
}
```

구현 규칙:

- `PermitEntry`는 coroutine `Semaphore`와 `referenceCount`를 가진다.
- 참조 수는 permit 확보 전에 증가한다. 따라서 보유자와 대기자를 모두 포함한다.
- 같은 키의 생성·증가·감소·제거는 `ConcurrentHashMap.compute` 안에서 수행한다.
- `withPermit`의 `finally`에서 정확히 한 번 참조를 반환한다.
- 반환할 때 현재 map의 entry가 확보 당시 entry와 같은 인스턴스인지 검사한다.
- 참조 수가 0일 때만 `compute`가 `null`을 반환해 항목을 제거한다.
- TTL, sweeper, 최대 크기, blocking monitor는 추가하지 않는다.

핵심 순서는 다음과 같다.

```kotlin
val entry = retain(key)
return try {
    entry.semaphore.withPermit(action)
} finally {
    release(key, entry)
}
```

이 순서로 취소가 permit 대기 중 발생해도 참조가 반드시 감소한다.

### 3.2 dispatcher 연결

`ProfileReevaluationDispatcher`의 다음 코드를 제거한다.

```kotlin
private val clinicPermits = ConcurrentHashMap<ClinicKey, Semaphore>()
private fun clinicSemaphore(job: ProfileReevaluationJobRecord): Semaphore
private data class ClinicKey(...)
```

대신 생성 시 한 번 registry를 만든다.

```kotlin
private val clinicPermitRegistry =
    ClinicPermitRegistry(perClinicConcurrency, metrics)
```

작업 실행은 다음 중첩 순서를 유지한다.

```kotlin
globalPermits.withPermit {
    clinicPermitRegistry.withPermit(job.scope) {
        worker.process(job)
    }
}
```

전역 permit의 의미와 claim 정책은 바꾸지 않는다.

## Task 4. 저카디널리티 운영 지표 구현

`ProfileReevaluationMetrics`에 다음 meter를 추가한다.

| 이름 | 종류 | tag | 갱신 시점 |
|---|---|---|---|
| `clinic.profile.reevaluation.clinic.permit.registry.size` | gauge | 없음 | entry 생성 +1, 제거 -1 |
| `clinic.profile.reevaluation.clinic.permit.evictions` | counter | 없음 | 참조 수 0으로 제거할 때 +1 |

구현 규칙:

- gauge 값은 `AtomicInteger`로 보관한다.
- counter는 metrics 생성 시 한 번 등록한다.
- tenant, clinic, patient, appointment 식별자를 meter 이름이나 tag에 넣지 않는다.
- entry 생성과 제거가 확정된 `compute` 경로에서만 값을 바꾼다.
- 여러 dispatcher가 같은 `ProfileReevaluationMetrics`를 공유하면 process 전체
  registry 항목 수를 합산하는 의미를 유지한다.

## Task 5. GREEN과 동시성 안정성 검증

1. 새 테스트만 실행한다.

   ```bash
   ./gradlew :appointment-api:test \
     --tests '*ProfileReevaluationDispatcherTest'
   ```

2. race 테스트를 반복한다.

   ```bash
   repeat 10 ./gradlew :appointment-api:test \
     --tests '*ProfileReevaluationDispatcherTest*제거와 재확보*'
   ```

   `repeat` helper가 없으면 Gradle의 동일 test 반복 실행을 shell loop로 수행한다.

3. 전체 API module 테스트를 실행한다.

   ```bash
   ./gradlew :appointment-api:test
   ```

4. module build와 정적 검증을 실행한다.

   ```bash
   ./gradlew :appointment-api:build
   git diff --check
   ```

실패가 발생하면 원인을 변경 범위와 기존 환경 문제로 구분하고, 변경으로 인한
실패는 모두 수정한다.

## Task 6. 구현 검토와 완료 증거

다음 관점으로 diff를 검토한다.

- Correctness: 보유자·대기자 참조가 정확히 한 번 증가/감소하는가
- Concurrency: 같은 키의 entry 교체가 `compute` 경계를 벗어나지 않는가
- Cancellation: acquire 이전·대기 중·action 실행 중 취소가 모두 정리되는가
- Operability: meter에 식별자 tag가 없고 최종 gauge가 0으로 돌아오는가
- Regression: 전역/병원별 동시성, 공정 claim, redrive가 바뀌지 않았는가

검토 결과 P0/P1은 커밋 전에 해결한다. 완료 시 다음 증거를 남긴다.

- RED 실패 원인과 test 이름
- GREEN 타깃 테스트 결과
- 반복 race 테스트 결과
- `:appointment-api:test` 및 `:appointment-api:build` 결과
- `git diff --check`
- 변경 파일 목록과 잔여 위험

## Task 7. 커밋·PR·CI

1. Lore protocol 형식으로 구현 커밋을 만든다.
2. `fix/201-clinic-permit-registry-lifecycle`를 push한다.
3. `develop` 대상 PR을 만들고 issue #201을 연결한다.
4. issue의 assignee, milestone, label을 PR metadata에 맞춘다.
5. PR 본문의 마지막 절을 `## DoD Status`로 둔다.
6. exact head SHA 기준으로 모든 필수 CI와 review thread를 확인한다.
7. merge는 수행하지 않고 merge-ready 상태와 정확한 head SHA를 보고한다.

## 자체 검토 결과

- 승인된 기준 문서의 lifecycle, 동시성, 취소, metric 요구를 모두 실행 단계로
  연결했다.
- 테스트는 내부 map이나 reflection 없이 외부 동작과 meter로 관측한다.
- process-local 구현만 포함하고 `LeaderGroupElector` 기반 분산 상한은 후속 확장으로
  남겼다.
- 설정값, 데이터베이스, API 계약을 변경하지 않아 issue #201의 범위를 넘지 않는다.
- 구현 전 baseline으로 `ProfileReevaluationDispatcherTest` 7개와
  `ProfileReevaluationMetricsTest` 5개가 모두 통과했다.
