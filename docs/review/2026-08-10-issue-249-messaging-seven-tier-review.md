# 이슈 #249 `appointment-messaging` 7-tier 검토

## 검토 범위

- 저장소: `bluetape4k/clinic-appointment`
- 모듈: `:appointment-messaging`
- 기준 커밋: `ea131f7fe11e1775d161469e152e57a87574e6b2`
- 검토 기준: `$bluetape-kotlin-patterns`, 7-tier code review
- 이번 실행 범위: replay `requestId` 경합과 `SmartLifecycle` relay 종료 경계

기존 이슈 본문에 포함된 consumer side effect/inbox 원자성, 모듈 전체
`Serializable`/`serialVersionUID`, assertion 및 fixture 규칙은 별도 범위로
남긴다. 이번 수정에서 이를 완료한 것으로 표시하지 않는다.

## 기준선에서 확인한 결함

`AppointmentReplayService`는 `requestId`를 먼저 조회하지 못한 두 요청이 동시에
`insertIgnore`를 수행할 때, 충돌한 요청을 곧바로 idempotent replay로 취급했다.
따라서 같은 `requestId`에 다른 partition이 경합해도 두 호출이 성공할 수 있었다.
테스트만 추가한 detached 기준선에서 다음 회귀 테스트가 실패했다.

```text
AppointmentReplayServiceTest.concurrent insert conflict rejects a different partition binding
Expected <2> to equal to <1>, but was not.
... AppointmentReplayServiceTest.kt:247
Executed 1 tests in 1.8s (1 failed)
```

또한 `AppointmentOutboxRelayLifecycle.stop(callback)`은 production
`runBlocking`으로 scheduler와 in-flight tick을 기다리고 있었다. 이 경로는
Spring lifecycle 종료 호출 스레드를 coroutine 작업 완료까지 직접 block한다.

## 7-tier 결과

| Tier | 현재 결과 | 근거 및 후속 범위 |
|---|---|---|
| 성능 | PASS (이번 범위) | 종료 호출 스레드의 `runBlocking`을 제거하고 owned shutdown coroutine으로 넘겼다. 단, 처리량 benchmark는 범위 밖이다. |
| 안정성 | PASS (이번 범위) | unique 충돌 뒤 승자 row의 hash version, request hash, partition binding을 재조회·검증한다. shutdown은 deadline 안에서 tick을 취소하고 callback을 실행한다. |
| 보안/개인정보 | PASS | 이번 변경에서 새로운 입력 경계나 개인정보 노출은 확인하지 못했다. replay 권한 검사는 기존 경계를 유지한다. |
| 운영 | PENDING (P2) | handler side effect와 `markProcessed`의 crash/rebalance 원자성은 기존 이슈 범위에 남아 있다. 별도 transactional inbox 설계가 필요하다. |
| 개발자/API | PENDING (P2) | public consumer handler의 idempotency key/version fence 계약과 모듈 전체 data class 직렬화 규칙은 후속 범위다. |
| 사용자/호출자 | PENDING (P2) | 안전한 consumer 처리 계약 없이 비멱등 side effect를 구현할 수 있는 문제는 이번 변경으로 해결하지 않았다. |
| 통합/테스트 | PASS (이번 범위) | 동시 partition 경합 및 비동기 lifecycle callback 회귀 테스트를 추가했다. 기존 module-wide assertion/fixture compliance는 후속 검증 항목이다. |

이번 실행에서 근거가 있는 P0/P1 결함은 0건이다. 위 표의 PENDING 항목은
기존 이슈 본문의 P2 후속 범위이며, 이 커밋의 완료 조건으로 오인하지 않는다.

## 수정 내용

1. `insertIgnore`가 0을 반환하면 동일 `requestId` row를 다시 읽고
   `hashVersion`, `requestHash`, `partitionNumber`를 모두 검증한다. 다른
   scope/partition은 기존과 동일한 명시적 `IllegalArgumentException`으로
   거부한다.
2. `SmartLifecycle.stop(callback)`은 `shutdownScope.launch`에서 scheduler
   종료와 active tick deadline/cancellation을 수행하고, 정리 후 callback을
   호출한다. production `appointment-messaging/src/main`에는
   `runBlocking`이 남아 있지 않다.
3. 두 동시 호출의 서로 다른 partition을 재현하는 회귀 테스트와 비동기
   lifecycle callback 완료를 기다리는 테스트를 추가했다.

## 검증 증거

| 검증 | 결과 |
|---|---|
| 기준선 RED 회귀 테스트 | 1건 실패 (`Expected <2> ... <1>`) |
| 대상 테스트 | `AppointmentReplayServiceTest` + `AppointmentOutboxRelayLifecycleTest`: `SUCCESS: Executed 12 tests in 2.2s` |
| 모듈 전체 테스트 | `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`로 `SUCCESS: Executed 114 tests in 19.4s`, `BUILD SUCCESSFUL in 23s` |
| production blocking scan | `rg -n "runBlocking" appointment-messaging/src/main` 결과 0건 |
| diff 위생 | `git diff --check` 통과 |

override 없이 실행한 전체 테스트는 코드 실패가 아니라 이 머신의 Colima
Docker socket mount 오류(`/Users/debop/.colima/default/docker.sock`)로 중단됐다.
원격 CI, PR/merge, production broker 검증은 이 실행에 포함하지 않았다.

