# 키별 coroutine 자원은 대기자까지 수명 참조에 포함한다

## 배경

프로필 재평가 dispatcher는 병원별 동시 실행 수를 제한하기 위해
`(tenantGroupId, clinicId)`마다 coroutine `Semaphore`를 사용한다. 최초 구현은
`ConcurrentHashMap.computeIfAbsent`로 semaphore를 만들고 제거하지 않았다. 실행 중인
병원 수가 아니라 프로세스가 시작된 뒤 한 번이라도 관찰한 병원 수만큼 map이 계속
커지는 구조였다.

오래 실행되는 다중 병원 시스템에서는 현재 backlog가 작아도 과거에 관찰한 병원 키가
누적된다. 단순 TTL이나 주기적 정리는 map 크기를 줄일 수 있지만, permit 보유자나
대기자가 있는 항목을 제거하면 같은 병원에 서로 다른 semaphore 두 개가 활성화될 수
있다. 그러면 병원별 동시 실행 상한을 지킬 수 없다.

## 원인

키별 동시성 자원의 수명은 “현재 permit 보유자 수”만으로 판단할 수 없다.

- permit을 기다리는 coroutine도 이미 해당 semaphore를 사용할 예정인 참조다.
- 대기자를 등록하기 전에 항목을 제거할 수 있으면 새 요청이 다른 semaphore를 만든다.
- 취소는 permit 대기 중에도 발생하므로 확보 이후 경로만 정리하면 참조가 남는다.

따라서 자원 확보와 map 보관 수명을 별도 상태로 다루면 경쟁 조건이 생긴다.

## 결정

병원별 entry는 semaphore와 참조 수를 함께 가진다. 참조 수는 permit을 기다리기 전에
증가하고, 작업 성공·실패·취소와 관계없이 `finally`에서 감소한다.

같은 키의 생성, 참조 증가, 참조 감소, 제거는 모두
`ConcurrentHashMap.compute` 안에서 수행한다.

```kotlin
val entry = retain(key)
return try {
    entry.semaphore.withPermit {
        action()
    }
} finally {
    release(key, entry)
}
```

참조 수가 0이면 보유자와 대기자가 모두 없으므로 entry를 즉시 제거할 수 있다. 제거와
새 참조 등록이 경쟁해도 `compute`가 같은 키의 변경을 직렬화한다. 새 요청은 기존
entry의 참조를 늘리거나, 완전히 제거된 뒤 새 entry를 만든다.

TTL, sweeper, 최대 map 크기는 사용하지 않는다. 시간 기반 정책은 안전한 제거 조건을
대체하지 못하고 별도 실행 자원과 설정을 추가하기 때문이다.

## 관측

키 식별자를 노출하지 않고 다음 process 수준 지표만 기록한다.

- `clinic.profile.reevaluation.clinic.permit.registry.size`
- `clinic.profile.reevaluation.clinic.permit.evictions`

tenant나 clinic을 tag에 넣으면 registry 문제를 확인하려다 metric cardinality가 다시
병원 수에 비례해 증가한다. 개별 병원 진단은 고객관리시스템이나 업무 로그의 책임으로
남기고, 이 지표는 자원 수명주기가 정상적으로 수렴하는지만 확인한다.

## 검증

- 구현 전 RED에서는 기존 dispatcher 테스트 7개가 통과하고 새 수명주기 테스트 3개가
  모두 registry size meter 부재를 원인으로 실패했다. 컴파일, fixture, timeout 실패는
  제거한 뒤 이 결과를 기준으로 구현을 시작했다.
- 서로 다른 512개 병원 작업이 끝난 뒤 registry 크기가 0인지 확인했다.
- permit 보유자와 같은 병원의 대기자를 함께 취소한 뒤 참조가 남지 않는지 확인했다.
- 제거와 재확보 경쟁을 같은 test JVM에서 10회 반복해 병원별 최대 동시 실행 수가 1을
  넘지 않는지 확인했다.
- 기존 32개 병원 backlog의 전역·병원별 동시성 회귀 테스트를 유지했다.
- `:appointment-api:test --rerun-tasks`에서 506개가 통과하고 2개가 보류되었다.
- `:appointment-api:build`와 Kover 검증을 통과했다.

## 재사용 지침

- 키별 `Mutex`, `Semaphore`, single-flight entry를 제거할 때는 보유자와 대기자를 모두
  참조 수에 포함한다.
- 참조 증가는 대기 전에, 감소는 `finally`에서 수행한다.
- 같은 키의 entry 교체와 참조 변경은 하나의 원자적 map 연산으로 묶는다.
- 자원 수명 지표에는 업무 식별자를 tag로 넣지 않는다.
- process-local 상한이 요구사항이면 이 패턴을 사용한다. 여러 API 인스턴스를 합친
  상한이 필요해질 때만 분산 `LeaderGroupElector`로 전환하고, 그때 lease 만료와 Redis
  장애 시 동작을 별도 기준 문서로 정의한다.
