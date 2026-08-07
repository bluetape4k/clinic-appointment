# Issue #91 — confirmReschedule() 권한 우회 수정

**일자**: 2026-05-18
**브랜치**: `fix/issue-91-reschedule-auth`
**범위**: `appointment-core`, `appointment-api`

## 원인

`RescheduleController.confirmReschedule()`는 `{candidateId}` path variable을
받았지만 service layer(`ClosureRescheduleService.confirmReschedule()`)는 candidate를
조회할 때 `originalAppointmentId` parameter를 완전히 무시했다.

공격자(또는 잘못 설정된 client)가 `{id}` URL 위치에 **다른** appointment에 속한
`candidateId`를 전달해도 시스템은 오류 없이 처리했다. 그 결과 appointment 간
reschedule 권한 우회가 가능했다.

## 결정

`originalAppointmentId`(controller의 `{id}` path variable)를 service까지 전달하고,
candidate 조회 직후 ownership guard를 추가한다.

```kotlin
require(candidate.originalAppointmentId == originalAppointmentId) {
    "Candidate $candidateId does not belong to appointment $originalAppointmentId"
}
```

여기서는 `IllegalArgumentException`이 올바른 타입이다.
`GlobalExceptionHandler`가 이를 HTTP 400으로 매핑하며, 잘못되었거나 권한이 없는
요청에 적절한 응답이기 때문이다.

## 결과

- `ClosureRescheduleService.confirmReschedule(candidateId, originalAppointmentId)` —
  ownership check를 같은 `transaction {}` block 안에 추가했다(TOCTOU window 없음).
- `RescheduleController.confirmReschedule()` — `id` path variable을 service에 전달한다.
- `autoReschedule()` — `originalAppointmentId`를 전달하도록 내부 호출을 수정했다.
- 프로젝트 규칙에 따라 모든 `!!`을 `requireNotNull("param")`으로 교체했다.

## 검증

- `ClosureRescheduleServiceTest` test 7: mismatch → `IllegalArgumentException` ✅
- `RescheduleControllerTest` cross-appointment confirm: HTTP 400 ✅
- `appointment-core` + `appointment-api` 전체에서 테스트 315개 통과, 실패 0개

## 향후 지침

- REST path variable이 resource owner(`{appointmentId}`)를 식별한다면 상태를
  변경하기 전에 service가 secondary resource(candidate, note 등)가 해당 owner에
  속하는지 검증해야 한다.
- 별도 query보다 `require(child.parentId == parentId)`를 우선한다. transaction 안에서
  원자적으로 수행되고 의도가 코드에 드러난다.
- controller method에 `id` path variable을 사용하지 않은 채 남겨 두지 않는다.
  변수가 있다면 service까지 전달해야 한다.
- `!!` 대신 항상 `io.bluetape4k.support`의 `requireNotNull("param")`을 사용한다.
  명확한 오류 메시지를 만들고 NPE stack trace를 피할 수 있다.
