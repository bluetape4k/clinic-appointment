# 병원별 permit registry 수명주기 설계

> 상태: 대화 설계 승인 완료, 기준 문서 리뷰 대기
>
> 기준일: 2026-07-31
>
> 관련 이슈: [#201 Bound clinic semaphore registry growth in profile reevaluation dispatcher](https://github.com/bluetape4k/clinic-appointment/issues/201)
>
> 관련 설계:
> [프로필 변경 기반 진행 중 예약 재평가 설계](./2026-07-30-profile-change-reservation-reevaluation-design.md)

## 1. 문제

`ProfileReevaluationDispatcher`는 병원별 동시 실행 한도를 적용하기 위해
`ClinicKey`마다 coroutine `Semaphore`를 생성한다.

```kotlin
private val clinicPermits = ConcurrentHashMap<ClinicKey, Semaphore>()
```

현재 구현은 `computeIfAbsent`로 만든 항목을 제거하지 않는다. 따라서 registry
크기는 현재 실행 중이거나 대기 중인 병원 수가 아니라, 프로세스가 시작된 뒤 한
번이라도 처리한 tenant와 clinic 조합 수에 비례한다. 장시간 실행되는 SaaS
환경에서 병원 유입과 이탈이 반복되면 사용하지 않는 semaphore가 프로세스가
종료될 때까지 남는다.

단순한 만료 시간 기반 제거는 안전하지 않다. permit 보유자나 대기자가 있는
항목을 제거한 뒤 같은 `ClinicKey`로 새 semaphore가 생성되면 동일 병원에 서로
다른 semaphore가 동시에 활성화되어 `perClinicConcurrency`를 초과할 수 있다.

## 2. 목표

1. registry 크기를 현재 permit을 보유하거나 기다리는 병원 수로 제한한다.
2. 동일 `ClinicKey`에 활성 semaphore가 둘 생기지 않게 한다.
3. permit 보유자와 대기자가 있는 항목을 제거하지 않는다.
4. 완료, 실패, 대기 취소와 실행 취소에서 참조를 누수하지 않는다.
5. 기존 전역 및 병원별 동시 실행 한도를 유지한다.
6. 병원 식별자를 노출하지 않는 저카디널리티 운영 지표를 제공한다.
7. 향후 여러 API 인스턴스를 합친 병원별 제한이 필요할 때 분산
   `LeaderGroupElector`로 전환할 기준을 남긴다.

## 3. 비목표

- 프로필 재평가 대상, 우선순위, mutation policy와 예약 상태 전이 변경
- semaphore 또는 permit 상태의 외부 저장
- 환자나 예약 식별자를 registry key로 사용
- Redis, JDBC 또는 다른 분산 저장소 의존성 추가
- 여러 API 인스턴스를 합친 클러스터 전역 동시성 제한
- 시간 기반 TTL, 주기적인 청소 작업 또는 최대 항목 수 기반 강제 축출

## 4. 핵심 결정

### 4.1 프로세스별 제한을 유지한다

이번 변경의 `perClinicConcurrency`는 한 `ProfileReevaluationDispatcher`
인스턴스 안에서 적용한다. 전역 semaphore와 claim 크기, 병원별 permit 수는
바꾸지 않는다.

병원 수가 많다는 사실만으로 분산 coordination이 필요한 것은 아니다. 분산
제한은 여러 API 인스턴스에서 실행되는 작업 수의 합을 병원별로 제한해야 할 때
도입한다.

### 4.2 시간 대신 사용 참조로 항목을 관리한다

registry 항목은 다음 상태만 가진다.

```text
ClinicPermitEntry
  semaphore: Semaphore(perClinicConcurrency)
  users: permit을 보유하거나 기다리는 coroutine 수
```

`users`는 permit 획득 성공 여부가 아니라 registry 항목을 사용하기로 확정한
coroutine 수다. 대기자가 semaphore에 진입하기 전에 참조를 증가시키고, 작업
완료 또는 취소 뒤 `finally`에서 감소시킨다.

이 방식은 별도의 idle TTL이나 sweep 주기가 필요 없다. `users == 0`인 항목은
즉시 제거하므로 registry 크기는 활성 작업과 대기 작업이 존재하는 병원 수로
제한된다.

### 4.3 항목 확보와 제거는 같은 key의 원자 연산으로 처리한다

항목 확보는 `ConcurrentHashMap.compute` 안에서 수행한다.

```text
acquireEntry(key):
  compute(key):
    기존 항목이 있으면 users 증가
    없으면 users=1인 새 항목 생성
  확보한 항목 반환
```

항목 반환도 같은 key의 `compute` 안에서 수행한다.

```text
releaseEntry(key, acquiredEntry):
  compute(key):
    현재 값이 acquiredEntry와 같은 인스턴스인지 확인
    users 감소
    users가 0이면 null을 반환해 제거
    아니면 acquiredEntry 유지
```

새 사용자가 마지막 반환과 경쟁하면 두 연산 가운데 하나가 같은 key의
`compute`를 먼저 실행한다.

- 새 사용자가 먼저 실행하면 `users`가 증가하므로 마지막 반환은 항목을
  유지한다.
- 마지막 반환이 먼저 실행하면 기존 항목의 사용자가 없어진 뒤 제거된다. 새
  사용자는 제거가 끝난 뒤 새 항목을 만든다.

두 경우 모두 사용 중인 이전 semaphore와 새 semaphore가 겹치지 않는다.
registry의 현재 값이 확보 당시 항목과 다른 경우는 불변식 위반이며 조용히
무시하지 않는다.

### 4.4 취소와 실패에서도 반환 경로를 하나로 유지한다

dispatcher는 다음 구조로 병원별 permit을 사용한다.

```text
entry = registry.acquire(key)
try:
  entry.semaphore.withPermit:
    worker.process(job)
finally:
  registry.release(key, entry)
```

다음 경로가 모두 같은 `finally`를 통과해야 한다.

- permit 획득 전 대기 취소
- permit 획득 후 worker 취소
- worker 예외
- 정상 완료
- 상위 `coroutineScope` 취소

프로세스가 강제 종료되는 경우 메모리 registry도 함께 사라지므로 별도 shutdown
영속화는 하지 않는다.

### 4.5 registry 수명주기는 별도 내부 구성요소가 소유한다

`ProfileReevaluationDispatcher`가 map과 참조 계산을 직접 다루지 않도록
profile 패키지의 내부 `ClinicPermitRegistry`가 다음 책임을 가진다.

- `ClinicKey`별 항목 생성과 재사용
- 보유자와 대기자를 포함한 참조 수 관리
- 안전한 항목 제거
- 현재 registry 크기와 제거 횟수 기록

dispatcher는 전역 semaphore와 공정 claim을 계속 소유하고, 병원별 실행 구간만
registry에 위임한다. 외부로 노출되는 public API는 추가하지 않는다.

## 5. 관측 지표

기존 `ProfileReevaluationMetrics`에 다음 지표를 추가한다.

| 지표 | 종류 | 설명 |
|---|---|---|
| `clinic.profile.reevaluation.clinic.permit.registry.size` | gauge | 현재 registry 항목 수 |
| `clinic.profile.reevaluation.clinic.permit.evictions` | counter | 참조 수가 0이 되어 제거한 항목 수 |

두 지표에는 tenant, clinic, patient, appointment와 같은 식별자 tag를 추가하지
않는다. registry 갱신과 metric 기록이 실패할 수 있는 별도 외부 I/O를 만들지
않는다.

## 6. 동시성 불변식

구현과 테스트는 다음 조건을 만족해야 한다.

1. 한 `ClinicKey`에는 registry에 등록된 항목이 최대 하나다.
2. `users`는 1 이상인 항목만 registry에 존재한다.
3. `users`는 해당 항목을 확보하고 아직 반환하지 않은 coroutine 수와 같다.
4. permit 보유자 또는 대기자가 있으면 항목을 제거하지 않는다.
5. `users`가 0이 된 항목은 같은 key의 원자 연산에서 제거한다.
6. 같은 병원의 동시 worker 실행 수는 `perClinicConcurrency`를 넘지 않는다.
7. 전체 동시 worker 실행 수는 `globalConcurrency`를 넘지 않는다.
8. 모든 coroutine이 종료되면 registry 크기는 0이 된다.

## 7. 오류 처리

- 잘못된 `perClinicConcurrency` 입력 검증은 기존 dispatcher 계약을 유지한다.
- 참조 수가 음수가 되거나 registry의 현재 항목이 반환 대상과 다르면 내부
  상태 손상으로 처리한다.
- `CancellationException`은 소비하거나 다른 예외로 감싸지 않는다.
- metric에는 병원 key나 작업 payload를 기록하지 않는다.
- 참조 반환은 suspend 함수나 외부 I/O에 의존하지 않는다.

## 8. 테스트 전략

### 8.1 결함 재현

서로 다른 병원 key를 연속 처리한 뒤 기존 구현의 registry 크기가 누적되는
테스트를 먼저 추가한다. 테스트는 구현 세부 map을 reflection으로 읽지 않고
registry 크기 metric을 관측한다.

### 8.2 lifecycle

- 많은 고유 병원을 순차 처리한 뒤 registry 크기가 0인지 확인한다.
- 같은 병원의 permit 보유자와 대기자가 존재하는 동안 항목이 유지되는지
  확인한다.
- 대기자를 취소해도 보유자가 사용하는 항목이 바뀌지 않고, 마지막 사용자가
  끝난 뒤 제거되는지 확인한다.
- worker 실행을 취소한 뒤 registry가 비워지고 취소가 상위로 전파되는지
  확인한다.

### 8.3 concurrency

- 제거와 새 확보가 경쟁해도 같은 병원의 최대 동시 실행 수가 설정값을 넘지
  않는지 반복 검증한다.
- 기존 32개 병원 backlog 테스트로 전역 및 병원별 상한을 다시 검증한다.
- registry 크기 gauge와 eviction counter가 식별자 tag 없이 갱신되는지
  확인한다.

실제 thread 경쟁보다 coroutine 취소와 semaphore 대기 순서를 검증해야 하므로
프로젝트의 suspend 테스트 도구를 우선 사용한다. 별도 인프라와 Testcontainers는
필요하지 않다.

## 9. 향후 분산 `LeaderGroupElector` 전환

다음 조건이 모두 충족되면 별도 이슈와 설계로 분산 전환을 검토한다.

1. 프로필 재평가 dispatcher가 둘 이상의 API 인스턴스에서 동시에 실행된다.
2. 병원별 상한을 인스턴스별이 아니라 클러스터 전체 합계로 보장해야 한다.
3. Redis 또는 다른 backend의 장애와 지연을 재평가 처리 경로가 수용할 수 있다.
4. 작업 최대 시간, lease 시간, 명시적 lease 연장과 lease 상실 처리가 정의된다.
5. backend key의 TTL, 정리 정책과 운영 지표가 정의된다.

단순히 `LocalLeaderGroupElector`나 `LettuceLeaderGroupElector`로 바꾸는 것은
이번 문제의 해결책이 아니다. 두 구현 모두 동적 `lockName`을 로컬 map에
보관하므로 별도 수명주기 정책이 없으면 같은 process-lifetime 누적 문제가
남는다.

분산 전환에서는 coroutine 경로에 맞는 suspend 구현을 사용하고,
`ClinicKey`를 안정적인 저카디널리티 lock name으로 변환한다. lease가 작업보다
먼저 만료되어 동시 실행 한도를 일시적으로 초과하지 않도록 명시적 연장과
lease 상실 시 작업 중단 계약을 먼저 정해야 한다.

## 10. 변경 범위

예상 변경 파일은 다음과 같다.

- `ProfileReevaluationDispatcher.kt`
- 병원별 permit registry 내부 구현 파일
- `ProfileReevaluationMetrics.kt`
- `ProfileReevaluationDispatcherTest.kt`
- registry 단위 lifecycle/concurrency 테스트
- 필요할 경우 짧은 lessons 문서

Spring configuration property, database schema, Flyway migration, 공개 API,
README와 분산 leader 의존성은 변경하지 않는다.

## 11. 완료 조건

- registry가 고유 병원 churn 뒤에도 활성·대기 병원 수 이상으로 누적되지 않는다.
- permit 보유자와 대기자가 있는 항목을 제거하지 않는다.
- 제거와 재생성 경쟁에서 동일 병원의 활성 semaphore가 둘 생기지 않는다.
- 취소와 실패 경로에서 참조가 남지 않는다.
- 기존 전역 및 병원별 동시 실행 상한 테스트가 통과한다.
- registry 크기와 제거 횟수를 저카디널리티 지표로 확인할 수 있다.
- 분산 `LeaderGroupElector` 전환 조건과 제외 범위가 문서에 남는다.
