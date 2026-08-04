# Issue #223 Spring-managed Exposed DataSource 변경 리뷰

## 결론

현재 변경에서 P0/P1 blocker는 확인되지 않았다. P2로 확인된 항목은 구현 또는
검증으로 닫았고, 기존 `application*.yml`의 sample credential/TLS 설정은 이번
Kotlin/Java ownership 변경에 포함되지 않는 별도 운영 후속으로 분류했다.

## 독립 review lens

| 관점 | 확인한 증거 | 판정 |
|---|---|---|
| Performance | `ExposedDatabaseFactoryTest`의 barrier 기반 동시 등록/해제, Hikari marker query, warm-up 이후 5회 반복 transaction의 `CountingDataSource` acquisition delta 5 | P0/P1 없음. 새 pool을 만들거나 benchmark dependency를 추가하지 않고 bounded reuse 계약을 고정했다. |
| Stability/Ops | `ExposedDatabaseFactory.release`가 `connect`와 같은 `ReentrantLock`을 사용하며, lifecycle context test가 Hikari `isClosed`와 Exposed manager 제거를 확인 | 초기 P2였던 global manager stale 위험과 context cleanup 검증을 닫았다. |
| Security | production Kotlin/Java에 직접 URL, pool 생성, `DriverManager`가 없고, 외부 `Database`에 lifecycle을 직접 호출해도 manager가 유지됨 | P0/P1 없음. 기존 resource YAML의 credential/TLS 정책은 diff 밖이며 해결을 주장하지 않는다. |
| Operator/Ops | Korean runbook에 Spring pool owner, shutdown 순서, multi-pool qualifier/marker 규칙, standalone allowlist와 점검 명령을 기록 | P0/P1 없음. allowlist root는 contract test로 bounded 검증한다. |
| Developer/API | `ExposedDatabaseFactory`와 `ExposedDatabaseLifecycle`로 registration/default restore/release 계약을 단일화하고, explicit Spring bean name으로 Kotlin `internal` mangling을 피함 | public API와 dependency catalog를 늘리지 않았다. |
| User/Caller | 세 wiring test가 주입된 Hikari `DataSource`의 marker를 읽고 feature/worker graph를 기존 조건대로 구성 | caller-visible 동작과 tenant/transaction 범위를 변경하지 않았다. |

## 발견사항과 처리

1. 초기 lifecycle name condition은 Kotlin `internal` bean method의 compiler-mangled
   이름과 어긋날 수 있었다. database/lifecycle bean에 explicit Spring name을 주고
   name condition을 유지해 실제 bean graph를 고정했다.
2. factory handle registry 해제가 registration lock 밖에서 실행되면 동시 context
   startup/shutdown이 stale `defaultDatabase`를 남길 수 있었다. `release`를 같은
   lock으로 직렬화하고 동시 connect/release test로 회귀를 잠갔다.
3. static guard가 API module만 검사하던 범위를 다섯 JVM module의 `src/main`으로
   확장했고, 비운영 `src/test`/`src/gatling` 직접 setup이 문서화된 root 아래인지
   별도 assertion으로 확인한다.

## 검증 명령

```text
./gradlew :appointment-api:test --tests '*ExposedDatabaseFactoryTest' --tests '*DataSourceOwnershipContractTest' --tests '*AppointmentCommitmentApplicationWiringTest' --tests '*ProfileReevaluationWiringTest' --tests '*NotificationReminderRecoveryWiringTest' --no-build-cache
```

위 targeted suite는 `BUILD SUCCESSFUL`이며 factory/lifecycle, static guard, 세 Spring
wiring path를 포함한다. 별도 compile 및 CI 결과는 delivery 단계에서 다시 확인한다.

## 잔여 위험과 범위 경계

- full MySQL/PostgreSQL/Testcontainers matrix와 Spring Boot starter 전체 shutdown
  상호작용은 로컬 targeted 범위가 아니다.
- 운영 profile의 credential/TLS 정책은 기존 resource 설정을 별도 issue에서 다룬다.
- 두 번째 runtime pool을 추가할 때는 explicit qualifier/bean name, pool-unique marker,
  lifecycle registry 검증을 같은 변경에 포함해야 한다.
