# Issue #313 JDBC Caffeine 정책 기준 데이터 캐시 파일럿 설계

## 결정 요약

현재 `EffectivePolicyCache`의 생산 계약은 유지하고, `bluetape4k-exposed`의
`bluetape4k-exposed-jdbc-caffeine:1.12.1`을 `appointment-api`의
`testImplementation`으로만 추가한다. 테스트 전용 파일럿이
`JdbcCaffeineSnapshotCache<EffectivePolicyCacheKey, EffectiveSchedulingPolicy>`를
사용해 JDBC 최상위 트랜잭션의 commit-only publication과 로컬 fence를 검증한다.

이번 변경에서 생산 빈, `EffectiveSchedulingPolicyService`, Flyway 스키마, Redis
계층, 다중 노드 일관성은 변경하지 않는다. 파일럿 결과가 생산 도입을 정당화하지
못하면 결과를 `HOLD`로 기록하고 의존성과 테스트 fixture만 되돌릴 수 있어야 한다.

## SPW-01 — 독자·목적·근거

- **독자:** `clinic-appointment` 유지보수자와 Issue #313 검토자
- **언어:** 저장소 로컬 규칙에 따라 한국어. 코드 토큰·명령·좌표·URL은 원문 유지
- **목적:** 현재 정책 캐시를 대체하지 않고 JDBC Caffeine 트랜잭션 연계 경로의 채택 가능성을
  재현 가능한 테스트와 측정으로 판단
- **결정 질문:** commit 전 캐시 오염 없이 세대/fence 계약을 보존하면서, 기존 로컬
  캐시보다 추가 비용이 수용 가능한가?
- **Issue:** [#313](https://github.com/bluetape4k/clinic-appointment/issues/313)
- **현재 생산 코드:**
  - `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/service/EffectivePolicyCache.kt`
  - `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/policy/EffectiveSchedulingPolicyService.kt`
  - `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/ServiceConfig.kt`
- **현재 회귀 테스트:**
  - `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/policy/EffectivePolicyCacheTest.kt`
  - `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/policy/EffectiveSchedulingPolicyServiceTest.kt`
- **재사용할 bluetape4k 계약:**
  - `/Users/debop/work/bluetape4k/bluetape4k-exposed/exposed/jdbc-caffeine/src/main/kotlin/io/bluetape4k/exposed/jdbc/caffeine/snapshot/JdbcCaffeineSnapshotCache.kt`
  - `/Users/debop/work/bluetape4k/bluetape4k-exposed/exposed/jdbc-caffeine/src/main/kotlin/io/bluetape4k/exposed/jdbc/caffeine/snapshot/JdbcSnapshotTransaction.kt`
  - `exposed-jdbc-caffeine/README.ko.md`의 `stageSnapshot`/`stageInvalidation` 예제
- **정확한 외부 좌표:**
  `io.github.bluetape4k.exposed:bluetape4k-exposed-jdbc-caffeine:1.12.1`
- **미확정 또는 측정 한계:** 이 파일럿은 production DB 트래픽과 다중 JVM 간
  일관성을 증명하지 않는다. 해당 근거가 없으면 생산 도입 결론은 `HOLD`다.

## 범위와 제외 범위

### 포함

1. 현재 `EffectivePolicyCache`의 key, 세대, tenant/clinic quota, 무효화 동작을
   회귀 테스트로 고정한다.
2. `JdbcCaffeineSnapshotCache`를 테스트 fixture로 생성하고 `CacheSnapshot`에
   `EffectiveSchedulingPolicy`를 담는다.
3. Exposed `JdbcTransaction.stageSnapshot`이 성공한 최상위 transaction의 commit
   뒤에만 정책 기준 데이터를 게시하는지 검증한다.
4. rollback, save/generation conflict, stale local fill, clinic invalidation,
   tenant/clinic 격리와 miss token 재사용 방지를 검증한다.
5. baseline과 candidate의 hot hit, cold fill/commit, invalidation, cold-start
   latency와 thread allocation을 고정 warm-up/measurement 프로토콜로 반복 측정한다.
6. 원자료(JSON), 한국어 해석 문서, 의미가 명확한 SVG chart를 생성한다.

### 제외

- `EffectivePolicyCache` 생산 구현 교체 또는 `ServiceConfig` 생산 wiring 변경
- `EffectiveSchedulingPolicyService` 공개 API와 정책 기준 데이터 저장 스키마 변경
- Flyway migration, Redis 다중 노드 일관성, outbox/repair 경로
- 새로운 추상화 계층이나 새 의존성 추가(지정된 test-only bluetape4k artifact 제외)
- production rollout, feature flag의 운영 배포, Maven publish

## 대안 비교와 선택

| 대안 | 장점 | 비용·위험 | 결정 |
|---|---|---|---|
| 생산 경로에 JDBC Caffeine 연결 | 실제 요청 경로의 수치를 바로 얻음 | Issue #313의 검토 범위를 넘어 생산 계약·rollback을 함께 바꿈 | 제외 |
| 별도 JMH/benchmark 모듈 추가 | JVM benchmark 격리가 좋음 | 새 모듈·플러그인·실행 계약과 유지 비용이 생김 | 보류 |
| 테스트 전용 정책 기준 데이터 fixture | 생산 변경 없이 commit/fence 계약과 비용을 재현, 실패 시 삭제 가능 | production DB와 다중 노드 비용은 별도 근거가 필요 | **선택** |

선택안은 기존 `bluetape4k-exposed` API를 그대로 사용한다. `LocalCacheConfig`나
  repository cache를 transaction-aware cache처럼 포장하지 않으며, 캐시가 DB writer나
  트랜잭션 수명을 소유하지 않게 한다.

## 구성 요소와 데이터 흐름

### 파일럿 fixture

테스트 소스에만 다음 책임을 둔다.

- `JdbcCaffeineSnapshotCache<EffectivePolicyCacheKey, EffectiveSchedulingPolicy>` 생성
- `CaffeineSnapshotCacheConfig`의 정적 namespace/schema와 bounded capacity 설정
- `EffectivePolicyCacheKey`를 DB 조회 전 `lookup`하고 miss token을 한 번만 claim
- generation/hash를 `CacheSnapshot.revision`에 기록하되 캐시가 값을 해석하지 않게 함
- 성공한 최상위 `JdbcTransaction`에서만 `stageSnapshot` 또는 `stageInvalidation` 호출
- 파일럿 토글이 꺼져 있으면 기존 baseline fixture를 사용하고, 켜져 있으면 candidate를
  사용하도록 하여 즉시 rollback 가능한 테스트 경계를 제공

### 정상 fill

```text
DB 조회 전 lookup(key)
  ├─ hit: 정책 기준 데이터 반환
  └─ miss: miss token 확보
       ↓
권위 generation 읽기 → 정책 컴파일 → saveIfGenerationMatches
       ├─ 실패/세대 불일치: stageSnapshot 호출 금지, 캐시 미게시
       └─ 성공: 같은 최상위 JdbcTransaction에서 stageSnapshot
                    ↓ commit
              Caffeine local 정책 기준 데이터 공개
```

`saveIfGenerationMatches`가 현재 production 구현처럼 별도 transaction을 소유하는
경우에는 파일럿 fake store가 그 결과를 재현하고, 실제 cache publication은 성공 결과를
받은 뒤 별도 최상위 transaction에서 stage한다. 어느 경우에도 save 실패 전에 stage하지
않는 불변식을 테스트로 고정한다.

### 무효화와 fence

정책 scope head가 변경되면 해당 `EffectivePolicyCacheKey` 또는 clinic scope를
`stageInvalidation`으로 표시한다. 이미 시작한 오래된 miss가 더 새로운 local
invalidation 뒤에 publish되면 `JdbcCaffeineSnapshotCache`의 opaque fence가 이를
거부해야 한다. tenant와 clinic ID가 같은 payload를 공유해도 key가 다르면 서로의
정책 기준 데이터를 읽지 않아야 한다.

## 실패·호환성 계약

- commit 전 예외 또는 명시적 rollback: 정책 기준 데이터 count가 증가하지 않는다.
- `saveIfGenerationMatches` 실패: `stageSnapshot`을 실행하지 않고 cache miss 상태를
  유지한다. stale 정책 기준 데이터로 우회하지 않는다.
- miss token을 두 번 claim/stage: 실패하며 기존 cache 값은 변경하지 않는다.
- 현재 cache 값보다 낮은 local fence: 게시 결과가 `REJECTED`가 된다.
- tenant/clinic 불일치 key: hit가 아니라 miss가 된다.
- bounded capacity 초과: Caffeine이 정한 eviction만 허용하며 quota를 넘는 상태를
  성공으로 기록하지 않는다.
- `CacheSnapshot` payload는 `Serializable` detached DTO다. Exposed Entity나
  transaction-bound object를 직접 저장하지 않는다.
- 의존성은 `testCompileClasspath`와 `testRuntimeClasspath`에만 나타나야 하며
  `runtimeClasspath`, `productionRuntimeClasspath`, `bootJar`에는 나타나지 않는다.
- 기존 `EffectivePolicyCache`와 API 서비스 테스트는 수정 없이 통과해야 한다.

## 측정 프로토콜과 chart

### 비교 프로필

| 프로필 | baseline | candidate | 해석 방향 |
|---|---|---|---|
| hot hit | `EffectivePolicyCache.get` | `JdbcCaffeineSnapshotCache.lookup().snapshot` | 낮은 p50/p95가 좋음 |
| cold fill | `put` 후 `get` | miss → `stageSnapshot` → commit | 낮은 p50/p95가 좋음 |
| invalidation | `invalidateClinic` | `stageInvalidation` → commit | 낮은 p50/p95가 좋음 |
| cold-start | 새 인스턴스의 첫 연산 | 새 JDBC Caffeine 인스턴스의 첫 연산 | 첫 요청 비용을 별도 표시 |

- warm-up 5회, measurement 20회 이상, 고정 seed와 동일 payload를 사용한다.
- `System.nanoTime()` 기반 p50/p95/p99와 sample count를 JSON에 기록한다.
- `com.sun.management.ThreadMXBean`의 `getThreadAllocatedBytes`를 사용할 수 있는
  환경에서는 thread allocation을 별도 기록한다. 지원되지 않으면 값을 추정하지 않고
  `N/A`로 남긴다.
- JVM, Java/Kotlin/Gradle, git SHA, OS, DB profile, test command를 원자료에 기록한다.
- 산출물은 `build/reports/issue-313/jdbc-caffeine-pilot.json`(재생성 원자료),
  `docs/benchmarks/issue-313-jdbc-caffeine-pilot.md`(한국어 해석),
  `docs/benchmarks/issue-313-jdbc-caffeine-pilot.svg`(chart)다.
- chart는 latency 단위와 “낮을수록 좋음”을 표시하고, baseline/candidate의
  cold-start와 allocation `N/A`를 숨기지 않는다.

수치가 production DB·다중 JVM 정책을 대표한다고 표현하지 않는다. 현재 파일럿의
결론은 `ADOPT`가 아니라 `HOLD` 또는 제한적인 추가 검증 권고가 기본값이다.

## 테스트와 수용 기준

### 필수 테스트

1. 기존 `EffectivePolicyCacheTest`와 `EffectiveSchedulingPolicyServiceTest` 전체 통과
2. candidate hit/miss와 detached DTO 반환
3. commit 후 게시, rollback 전 미게시
4. generation mismatch/save conflict 시 미게시
5. clinic invalidation과 stale fill fence 거부
6. tenant/clinic 격리와 bounded capacity
7. miss token one-shot 및 candidate 토글 OFF rollback
8. 의존성 경계(`runtimeClasspath`/`bootJar`)와 lockfile read-back
9. benchmark report 생성 및 chart 사실성 검토

### 수용 기준

- production Kotlin/Java 소스와 Spring bean wiring 변경 없음
- `bluetape4k-exposed-jdbc-caffeine:1.12.1`은 test-only configuration에만 존재
- commit/rollback/generation/fence 계약에 실패하는 테스트가 있으면 `HOLD`
- 반복 측정 원자료와 chart가 동일 run metadata를 가리킴
- 성능 수치만으로 production 도입을 선언하지 않고, 관찰된 한계와 다음 실험을 기록

## SPW-02 — 산출물 계약과 DoD 매핑

| 산출물 | 필수 내용 | 연결된 DoD |
|---|---|---|
| 테스트 fixture | 재사용 API, production 비변경, rollback 경계 | cache contract, dependency boundary |
| 회귀/파일럿 테스트 | 성공·실패·격리·fence 증거 | transaction contract, regression |
| JSON report | raw samples와 환경/sha | benchmark reproducibility |
| Markdown summary | 선택/보류 판단, 한계, 다음 단계 | Issue #313 decision |
| SVG chart | 단위·방향·프로필·N/A 표시 | benchmark chart |

## SPW-03 — 한국어 기술 문체와 용어

`스냅숏`, `캐시 미스`, `커밋 후 게시`, `무효화`, `세대`, `로컬 fence`,
`생산 경로`, `테스트 전용`을 문서 전체에서 동일한 의미로 사용한다. `효율적이다`,
`강력하다` 같은 근거 없는 평가는 쓰지 않고 p50/p95, sample 수, 실제 실패 결과로
기술한다. API 이름·Gradle 좌표·명령·경로·URL은 번역하지 않는다.

## SPW-04 — 기술 의미와 추적성

- 현재 cache hit는 DB generation을 먼저 읽는다는 기존 테스트와 candidate lookup을
  혼동하지 않는다. candidate는 production service를 대체하지 않는다.
- `stageSnapshot`의 commit-only 의미는 sibling `JdbcSnapshotTransaction.kt`와
  `README.ko.md`의 문장에 대응한다.
- 세대 불일치의 권위 판단은 `EffectivePolicyStore.saveIfGenerationMatches`에
  남기고, JDBC Caffeine은 local fence와 publication만 담당한다.
- 성능 비교는 같은 payload와 고정 반복 프로토콜을 사용하되, production DB 대표성은
  주장하지 않는다.

## SPW-05 — 작성자 read-back

- 미완성 표식 없음
- 포함/제외 범위와 production 미변경 원칙이 서로 충돌하지 않음
- 정상 fill, 실패, rollback, 측정, chart 산출물의 흐름이 일치함
- 모든 파일 경로와 dependency 좌표를 현재 저장소/sibling source와 대조함
- **작성자 verdict:** P0 0건, P1 0건, unresolved claim은 production 대표성 한계 1건
- **현재 상태:** 설계 요약은 사용자 승인 완료. 이 문서 자체의 사용자 read-back 후
  구현 계획으로 전환한다.
