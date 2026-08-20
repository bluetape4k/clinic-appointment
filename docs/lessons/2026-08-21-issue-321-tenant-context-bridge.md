# Lesson — Issue #321 tenant context bridge 검토

## 문서 목적

Issue #321의 목표는 현재 `TenantContext`의 요청 경계, coroutine dispatcher hop,
중첩 scope, 예외·취소 정리 규칙을 보존하면서 bluetape4k 공통 bridge를 재사용할 수
있는지 확인하는 것이었다. 이 문서는 현재 코드와 upstream Issue #1320의 상태를
대조한 결정 기록이다.

## 배경과 현재 계약

- `TenantContext`는 `ThreadLocal<TenantInfo?>`를 저장소로 사용한다.
- `withTenant`는 이전 값을 저장하고 블록이 정상 종료하거나 예외를 던져도 `finally`에서
  이전 값을 복원한다.
- `TenantContextElement`는 coroutine dispatcher가 바뀔 때 `ThreadContextElement`로
  현재 tenant를 설치하고 이전 값을 복원한다.
- `TenantContextFilter`는 요청 진입 시 stale context를 지우고, active tenant 조회를
  `transaction {}` 안에서 수행한 뒤 `withTenant`로 filter chain을 감싼다. 요청 처리가
  끝나면 `finally`에서 다시 지운다.
- `SchedulingUserPrincipal.allowedTenants`는 인증 주체의 권한 검증에 사용한다.
  `TenantContext`는 요청 경로에서 해석한 active tenant를 전달하는 보조 컨텍스트일 뿐,
  인증 주체나 권한을 대신하지 않는다.
- controller 경계 밖의 업무 서비스·repository는 `tenantGroupId`를 명시적으로 전달한다.
  예외적으로 `ServiceConfig.kt`의 `PolicyTenantBoundaryVerifier`는 authorization
  boundary에서 요청 tenant와 `PolicyScopeRef.tenantGroupId`를 대조하기 위해
  `TenantContext.current()`를 읽는다. 이 한 곳은 업무 데이터 접근이 아니라 요청
  권한 검증 adapter이므로 현재 경계로 유지한다.

## 검증한 시나리오

기존 `TenantContextTest` 6개에 다음 두 계약 테스트를 추가했다.

1. `StructuredTaskScopeTester`의 virtual thread 작업에서 tenant를 각 작업 안에서
   명시적으로 bind하고, 작업이 끝난 뒤 `current()`가 `null`인지 확인했다. virtual
   thread는 일반 `ThreadLocal`을 부모 작업에서 암묵적으로 상속하지 않으므로, 공통
   bridge가 도입되더라도 명시적 adapter 경계가 필요하다는 사실을 고정한다.
2. `MultithreadingTester`의 재사용 platform thread에서 tenant A/B 요청을 반복 실행하고,
   각 scope 뒤에 이전 tenant가 관측되지 않는지 확인했다. 64개 작업에서 A/B가 각각
   32회 관측되고 scope 밖 값은 모두 비어 있었다.

기존 `TenantContextFilterTest` 9개는 authenticated/public/unauthenticated 요청,
lookup 오류, stale context, filter chain 종료 뒤 정리를 이미 검증하므로 production
filter 코드는 변경하지 않았다. transaction 경계도 active tenant 조회와 업무 코드의
명시적 `tenantGroupId` 전달을 분리한 현재 구현을 유지했다. policy authorization
adapter만 transaction에 들어가기 전에 요청 context와 명시적 scope ID를 대조한다.

## 결정

결론은 **hold**다. bluetape4k-projects Issue [#1320](https://github.com/bluetape4k/bluetape4k-projects/issues/1320)은
공통 core API와 Servlet/virtual-thread, coroutine, Reactor adapter를 제안하지만 현재
공개 상태는 `OPEN`이고 이 checkout에서 사용할 release artifact나 구현 소스는 찾지
못했다. 따라서 이번 변경에서 새 dependency를 추가하거나 production bridge를 이식하면
미확정 API를 로컬 계약으로 굳히게 된다.

이번 작업의 결과는 다음과 같다.

- 현재 local `ThreadLocal`·coroutine element·servlet filter 조합을 유지한다.
- 인증·인가 모델과 업무 코드의 명시적 tenant source를 변경하지 않는다.
- common bridge artifact가 공개되면 같은 nested/exception/cancellation/dispatcher
  hop/virtual thread/concurrent request 계약 테스트를 adapter별로 재실행한다.
- 공통 API가 `current/currentOrNull`, 중첩 복원, 요청 종료 cleanup, 명시적 “context 없음”
  정책을 문서화하고 기존 authorization 경계를 보존할 때만 migration을 재검토한다.

## 검증 결과

- `./gradlew :appointment-api:test --tests 'io.bluetape4k.clinic.appointment.api.tenant.TenantContextTest' --no-build-cache --console=plain`
  — `SUCCESS: Executed 8 tests in 1.5s`
- `TenantContextFilterTest` — 기존 9개 테스트 범위를 유지
- `rg -n "TenantContext\\.current\\(\)" appointment-api/src/main | rg -v "api/tenant/"`
  — 1건, `ServiceConfig.kt`의 `PolicyTenantBoundaryVerifier`라는 의도된 authorization
  boundary 사용
- `git diff --check` — 통과

README와 공개 API 예제는 production behavior/API를 변경하지 않았으므로 이번 범위에서
갱신하지 않았다.

## 미스와 다음 guard

가장 큰 오해 가능성은 `ThreadLocal` holder를 공통 bridge로 바꾸면 virtual thread와
coroutine 모두 자동 전파될 것이라고 가정하는 것이다. 실제 전파 단위는 실행 모델별
adapter이며, 현재 코드의 안전한 기본값은 “작업에 명시적으로 tenant를 설치하고 종료 시
제거한다”이다. 새 tenant-scoped endpoint를 추가할 때는 다음 guard를 유지한다.

- 인증 주체의 `allowedTenants` 검증과 tenant context 설치를 같은 책임으로 합치지 않는다.
- `ServiceConfig.kt`의 policy authorization adapter 외에는 업무 서비스·repository에
  `TenantContext.current()` 의존을 추가하지 않는다.
- transaction 내부의 tenant source는 명시적 `tenantGroupId`를 우선한다.
- 요청·작업 종료 뒤 `TenantContext.current()`가 남지 않는 회귀 테스트를 추가한다.
- upstream bridge가 release되기 전에는 공통 dependency나 alias를 추가하지 않는다.

## 근거

- [clinic-appointment Issue #321](https://github.com/bluetape4k/clinic-appointment/issues/321)
- [bluetape4k-projects Issue #1320](https://github.com/bluetape4k/bluetape4k-projects/issues/1320)
- `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/tenant/TenantContext.kt`
- `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/tenant/TenantContextElement.kt`
- `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/tenant/TenantContextFilter.kt`
- `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/tenant/TenantContextTest.kt`
- `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/tenant/TenantContextFilterTest.kt`
