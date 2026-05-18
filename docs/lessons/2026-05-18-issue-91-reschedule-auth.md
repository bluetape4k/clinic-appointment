# Issue #91 — confirmReschedule() Authorization Bypass Fix

**Date**: 2026-05-18  
**Branch**: `fix/issue-91-reschedule-auth`  
**Scope**: `appointment-core`, `appointment-api`

## Root Cause

`RescheduleController.confirmReschedule()` accepted a `{candidateId}` path variable
but the service layer (`ClosureRescheduleService.confirmReschedule()`) ignored the
`originalAppointmentId` parameter entirely when querying the candidate.

An attacker (or misconfigured client) could pass any `candidateId` belonging to a
**different** appointment in the `{id}` URL slot and the system would process it without
error — cross-appointment reschedule authorization bypass.

## Decision

Propagate the `originalAppointmentId` (from the controller's `{id}` path variable) all
the way into the service, and add an ownership guard immediately after the candidate
lookup:

```kotlin
require(candidate.originalAppointmentId == originalAppointmentId) {
    "Candidate $candidateId does not belong to appointment $originalAppointmentId"
}
```

`IllegalArgumentException` is the correct type here — `GlobalExceptionHandler` maps it
to HTTP 400, which is the appropriate response for a bad/unauthorized request.

## Outcome

- `ClosureRescheduleService.confirmReschedule(candidateId, originalAppointmentId)` —
  ownership check added inside the same `transaction {}` block (no TOCTOU window).
- `RescheduleController.confirmReschedule()` — passes `id` path variable to service.
- `autoReschedule()` — internal call updated to forward `originalAppointmentId`.
- All `!!` occurrences replaced with `requireNotNull("param")` per project rules.

## Verification

- `ClosureRescheduleServiceTest` test 7: mismatch → `IllegalArgumentException` ✅
- `RescheduleControllerTest` cross-appointment confirm: HTTP 400 ✅
- 315 tests passing, 0 failures across `appointment-core` + `appointment-api`

## Future Guidance

- Whenever a REST path variable identifies a resource owner (`{appointmentId}`),
  the service must validate that the secondary resource (candidate, note, etc.)
  belongs to that owner before mutating state.
- Prefer `require(child.parentId == parentId)` over a separate query — it is atomic
  within the transaction and self-documenting.
- Never leave `id` path variables unused in controller methods; if the variable exists,
  it must be threaded through to the service.
- Always use `requireNotNull("param")` from `io.bluetape4k.support` instead of `!!` — 
  produces a clear error message and avoids NPE stack traces.
