# Issue #211 Solver nullable planning boundary 설계

상태: 구현 완료, 모듈 검증 완료
작성일: 2026-08-02
대상: `appointment-solver`
관련 이슈: #211

## 1. 결정 요약

`AppointmentPlanning`의 `doctorId`, `appointmentDate`, `startTime`은
Timefold가 부분 초기화된 입력을 다루기 위해 nullable planning variable로
유지한다. 다만 이 nullable 상태를 제약식과 결과 변환기가 직접 `!!`로
해제하지 않도록, 완전 배정 값을 한 번에 전달하는 도메인 헬퍼를 추가한다.

핵심 결정은 다음과 같다.

1. `AppointmentPlanning.withAssigned { doctorId, appointmentDate, startTime, endTime -> ... }`
   형태의 inline 헬퍼를 `solver.domain`에 둔다. 네 planning 값이 모두 준비된
   경우에만 block을 실행하고, 그렇지 않으면 `null`을 반환한다.
2. `HardConstraints`는 헬퍼를 사용해 시간·날짜·의사·장비 조건을 평가한다.
   단순 equality join은 nullable planning property method reference를 사용해
   nullable assertion을 제거한다. `forEachIncludingUnassigned()`는 도입하지 않는다.
3. `SolutionConverter.extractResults`는 완전 배정된 비-pinned 예약만 변환한다.
   헬퍼가 `null`을 반환하는 항목은 기존 동작처럼 결과에서 제외한다.
4. `SolverService`의 repository ID와 solver score는 우연한 NPE가 아니라
   boundary invariant다. 이 경계에서는 `requireNotNull`로 명시적인 오류를
   발생시켜 원인을 보존한다.
5. `allowsUnassigned=true`를 추가하거나 미배정 penalty를 도입하지 않는다.
   현재 도메인에서 `null`은 최종 비즈니스 상태가 아니라 계획 중 상태다.

## 2. 배경과 문제

현재 planning entity의 세 변수와 `endTime`은 Kotlin nullable이다.
Timefold는 부분 초기화된 입력 solution을 허용하고, 기본 `forEach()`는
진짜 planning variable이 `null`인 entity를 constraint stream에서 제외한다.
따라서 계획 중 `null`은 정상 입력 상태지만, 다음과 같은 직접 assertion은
그 계약을 Kotlin의 incidental NPE 위험으로 바꾼다.

| 영역 | 현재 위험 | 보존할 동작 |
|---|---|---|
| `HardConstraints` | filter 이후 property smart cast가 되지 않아 여러 `!!` 사용 | 부분 entity는 점수 계산에서 제외되고 완전 entity만 평가 |
| `SolutionConverter` | filter 이후 `doctorId!!`, `appointmentDate!!`, `startTime!!`, `endTime!!` 사용 | 불완전 비-pinned entity는 결과에서 제외 |
| `SolverService` | repository ID와 `result.score`의 `!!` 사용 | 저장 레코드 ID와 score가 없으면 명시적 boundary 오류 |

이번 변경은 계획 모델의 nullable semantics나 점수 규칙을 바꾸지 않고,
각 경계에서 nullable 상태를 처리하는 방식을 명시하는 refactoring이다.

## 3. 목표와 비목표

### 목표

- production solver 코드에서 nullable planning 값의 `!!` 해제를 제거한다.
- 완전 배정 상태를 한 곳에서 정의해 제약식과 결과 변환기가 같은 계약을 사용한다.
- 부분 초기화 solution이 constraint evaluation과 result extraction에서 incidental NPE를
  발생시키지 않도록 한다.
- 저장 레코드 ID와 solver score 누락은 원인과 대상이 드러나는 명시적 예외로 바꾼다.
- 기존의 완전 예약 추출, 불완전 예약 제외, score 계산 semantics를 유지한다.

### 비목표

- `AppointmentPlanning` planning variable을 non-null로 변경하지 않는다.
- `allowsUnassigned=true`, `forEachIncludingUnassigned()`, 미배정 penalty를 추가하지 않는다.
- 불완전 예약을 새 API 응답이나 진단 카운터로 노출하지 않는다.
- solver 제약의 우선순위, weight, join 조건, 시간 슬롯 정책을 변경하지 않는다.
- repository ID 생성·DB schema·solver 종료 정책을 변경하지 않는다.

## 4. 기존 계약과 근거

구현 전에 확인한 주요 파일은 다음과 같다.

| 근거 | 확인한 계약 |
|---|---|
| `appointment-solver/.../domain/AppointmentPlanning.kt` | 세 planning variable과 nullable `endTime` |
| `appointment-solver/.../domain/ScheduleSolution.kt` | nullable `HardSoftScore`와 planning entity 목록 |
| `appointment-solver/.../constraint/HardConstraints.kt` | H1~H11의 filter/join 및 assertion 위치 |
| `appointment-solver/.../converter/SolutionConverter.kt` | 완전 배정된 비-pinned 예약만 `AppointmentRecord`로 변환 |
| `appointment-solver/.../service/SolverService.kt` | repository 로딩, solver 실행, score/result 경계 |
| `appointment-solver/src/test/...` | 완전/부분 converter와 solver service의 현재 기대 동작 |

Timefold 공식 문서는 다음 규칙을 명시한다.

- `solve(solution)` 입력은 부분 또는 완전 초기화일 수 있다.
- `allowsUnassigned=true`가 없으면 `null`은 최종 미배정 business state가 아니다.
- 기본 `forEach()`는 genuine planning variable이 `null`인 entity를 제외한다.
- `forEachIncludingUnassigned()`는 실제 미배정을 점수화해야 할 때만 사용한다.

참고:

- [Modeling planning problems](https://docs.timefold.ai/timefold-solver/latest/domain-modeling/modeling-planning-problems)
- [Common patterns](https://docs.timefold.ai/timefold-solver/latest/domain-modeling/common-patterns)
- [Library integration](https://docs.timefold.ai/timefold-solver/latest/running-timefold-solver/library/library-integration)

## 5. 선택 설계

### 5.1 완전 배정 헬퍼

`AppointmentPlanning` 자체의 mutable planning variable을 바꾸지 않고,
도메인 패키지의 확장 함수로 완전 배정 경계를 표현한다.

개념적인 계약은 다음과 같다.

```kotlin
inline fun <T> AppointmentPlanning.withAssigned(
    block: (doctorId: Long, appointmentDate: LocalDate, startTime: LocalTime, endTime: LocalTime) -> T,
): T? {
    val doctorId = this.doctorId ?: return null
    val appointmentDate = this.appointmentDate ?: return null
    val startTime = this.startTime ?: return null
    val endTime = this.endTime ?: return null
    return block(doctorId, appointmentDate, startTime, endTime)
}
```

실제 구현에서는 현재 repository의 Kotlin formatting과 public API 노출 범위를
따른다. `inline` block은 constraint stream lambda에서 호출하므로 별도 assignment
객체를 만들지 않고, mutable planning variable을 snapshot으로 저장하지 않는다.
`endTime`은 기존 계산 property를 재사용해 duration 계산 규칙을 중복하지 않는다.

헬퍼의 의미는 “최종적으로 유효하다”가 아니라 “이 lambda에서 네 값이 모두
읽을 수 있다”이다. 따라서 이 헬퍼를 사용해 entity를 자동으로 pinned 처리하거나
solver를 조기에 종료하지 않는다.

### 5.2 Constraint Streams 적용 규칙

제약식은 다음 두 패턴으로 정리한다.

1. planning variable 자체를 key로 쓰는 equality join은 기존 appointment-to-appointment
   join과 같은 method reference 패턴을 사용한다. doctor fact join도
   `AppointmentPlanning::doctorId`와 `DoctorFact::id` 형태로 표현해 `!!`를 사용하지
   않는다. 기본 `forEach()`의 unassigned 제외 semantics가 유지된다.
2. 날짜의 `dayOfWeek`, 시간 구간 비교처럼 여러 값이 동시에 필요한 predicate는
   `withAssigned` block 안에서 non-null local value를 사용한다. block 결과가
   `null`이거나 `false`이면 해당 조건은 match하지 않는다.

다음은 허용하지 않는다.

- `filter { ... }` 뒤의 property에 대한 `!!` 재도입
- nullable property를 임의의 기본값(`0L`, `LocalDate.MIN`, `LocalTime.MIN`)으로
  바꿔 constraint match를 만드는 것
- `forEachIncludingUnassigned()`로 기존 H1~H11의 점수 대상을 넓히는 것

### 5.3 결과 변환 적용 규칙

`extractResults`는 `solution.appointments.asSequence()` 또는 현재 collection
형태를 유지하면서 다음 순서를 따른다.

1. pinned entity를 제외한다.
2. `withAssigned`가 네 값을 제공하지 못하면 `null`을 반환한다.
3. 제공된 non-null local value로 `AppointmentRecord`를 만든다.
4. `mapNotNull` 결과만 반환한다.

이렇게 하면 현재 테스트가 보장하는 “missing doctor/date/time은 skip” 동작을
그대로 유지하면서 production `!!`만 제거할 수 있다. `originalAppointments`의
optional metadata도 현재처럼 `original?.field`로 유지한다.

### 5.4 Service boundary 적용 규칙

`SolverService`에서는 nullable 값을 다음처럼 분류한다.

| 값 | 처리 | 이유 |
|---|---|---|
| repository record `id` | `requireNotNull(id) { context }` | DB projection은 저장 ID가 있어야 하며 누락은 데이터/mapper 계약 위반 |
| `ScheduleSolution.score` after `solve` | `requireNotNull(score) { context }` | solver 완료 결과는 score를 가져야 하며 누락은 solver lifecycle 계약 위반 |
| planning variable | `withAssigned` 또는 stream 기본 filter | 부분 초기화가 허용된 solver 상태 |

예외 메시지는 clinic/date range 또는 record context를 포함하되 환자 개인정보를
포함하지 않는다. 이 변경은 예외 타입을 새로 만들지 않고 현재 service의
`IllegalArgumentException`/`IllegalStateException` 관례를 따른다.

## 6. 테스트 계약

구현 전에 RED 테스트를 먼저 추가하거나 기존 테스트를 해당 계약으로 확장한다.

### 6.1 Planning helper / constraints

- doctor/date/start 중 하나라도 `null`인 entity가 각 관련 constraint를 평가해도
  `NullPointerException`이 발생하지 않는다.
- 네 값이 모두 있는 entity는 기존과 같은 constraint match/score를 만든다.
- `endTime`은 기존 duration 계산과 동일하며 helper가 기본값을 만들어내지 않는다.
- H1~H11에 대해 `forEachIncludingUnassigned()`를 사용하지 않는다.

### 6.2 Converter

- 완전 배정된 비-pinned entity는 기존과 동일한 `AppointmentRecord`로 변환된다.
- doctor/date/start 중 하나가 빠진 비-pinned entity는 결과에서 제외된다.
- pinned entity는 완전 배정 여부와 관계없이 기존처럼 결과에서 제외된다.
- empty solution과 original appointment metadata 누락 동작을 유지한다.

### 6.3 Service boundary

- solver 결과 score가 `null`이면 명시적인 예외와 context가 발생한다.
- repository record ID가 `null`이면 `!!` NPE가 아니라 명시적인 invariant 오류가 발생한다.
- 정상 full solution의 `SolverResult` score, feasibility, count, appointment 결과는
  기존 값과 동일하다.

### 6.4 Static/targeted verification

- 변경 대상 production solver Kotlin 소스에 not-null assertion(`!!`)이 남지 않는다.
- `./gradlew :appointment-solver:test`
- `./gradlew :appointment-solver:build`
- `git diff --check`

전체 repository build는 module build가 통과한 뒤 필요할 때만 추가 실행한다.

## 7. 위험과 완화

| 위험 | 완화 |
|---|---|
| helper가 일부 constraint의 null 조건을 빠뜨림 | H1~H11별 RED 테스트와 production `!!` 검색을 함께 실행 |
| method reference의 nullable key 추론이 Timefold API와 맞지 않음 | 컴파일 가능한 기존 H7/H8 join pattern을 기준으로 한 constraint씩 적용 |
| helper가 새 allocation 또는 lambda 비용을 추가함 | `inline` block과 property snapshot 금지, module build 후 benchmark 차이가 관찰될 때만 별도 이슈화 |
| `requireNotNull` 메시지에 민감정보가 들어감 | clinic/date range와 record ID 같은 운영 context만 허용하고 patient fields는 금지 |
| 부분 결과 skip이 장애를 숨김 | 이번 issue에서는 기존 결과 계약을 유지하고, 누락 count/진단 API는 별도 이슈로 분리 |

## 8. 대안과 기각 이유

### 최소 local `let` 치환

각 constraint lambda에 nullable local extraction을 직접 반복하는 방식은 가장 작은
diff지만, 날짜·시간·의사 조합의 동일한 null 경계를 여러 파일에서 다시 구현하게 된다.
이번 issue는 생산 코드 전체의 boundary semantics를 명시해야 하므로 선택하지 않았다.

### `allowsUnassigned=true`와 미배정 penalty

실제로 예약을 “미배정” 상태로 저장하고 사용자에게 그 상태를 보여줘야 할 때의
Timefold 모델이다. 현재 clinic appointment 결과 계약은 완전 배정 예약만 변환하고,
미배정은 계획 중 누락으로 취급한다. 이 선택은 scoring semantics와 API 의미를 바꾸므로
이번 refactoring에서 기각한다.

## 9. 완료 기준

- `HardConstraints`, `SolutionConverter`, `SolverService`의 production `!!`가 제거되거나
  명시적 helper/boundary 계약으로 대체된다.
- 부분 초기화 planning entity의 score 평가와 result extraction이 incidental NPE 없이
  기존 skip semantics를 유지한다.
- full solution의 score와 변환 결과가 기존 테스트와 동일하다.
- `:appointment-solver:test` 및 `:appointment-solver:build`가 통과한다.
- 독립 review에서 P0/P1이 0이다.
- 구현 계획과 테스트 계획이 이 설계 문서와 일치한다.
