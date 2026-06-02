# WIP - clinic-appointment

Snapshot: 2026-06-02 KST
Scope: open GitHub issues assigned to `debop`, created on or after 2026-01-01.
Open count: 19 issues.

## Recently Completed

- **#82** bluetape4k artifact ID 표준화 컨벤션 통일 (commit `8133de0`).
- **#79** `bluetape4k-dependencies` 단일 BOM 소스 전환 (commit `9df3a9f`).
- **#52** Repository cache → Spring `@Cacheable`/`@CacheEvict` 전환 완료 (commit `3fef28c`, `ceac3a0`). → **#97 close 대기**.
- **#60** CI paths-filter + nightly workflow 적용 완료 (commit `991cde7`, `b0be270`). → **#97 close 대기**.
- **#61** Kluent → `bluetape4k-assertions` 마이그레이션 완료 (commit `dd69e84`).

## Action Items

- [ ] Close issue #52 (work merged — see #97)
- [ ] Close issue #60 (work merged — see #97)

## Newly Discovered Bugs (2026-05-18)

| Issue | Severity | Title |
|---|---|---|
| [#90](https://github.com/bluetape4k/clinic-appointment/issues/90) | HIGH | `AppointmentService` `findByIdOrNull!!` — potential NPE in updateStatus / cancel |
| [#91](https://github.com/bluetape4k/clinic-appointment/issues/91) | CRITICAL | `confirmReschedule()` 미검증 — 다른 예약의 candidateId로 무단 재배정 가능 |
| [#92](https://github.com/bluetape4k/clinic-appointment/issues/92) | HIGH | 모든 컨트롤러 `@RequestBody`에 `@Valid` 누락 — Bean Validation 미적용 |
| [#96](https://github.com/bluetape4k/clinic-appointment/issues/96) | MEDIUM | `cancel()` 취소 사유 하드코딩 `"Cancelled by user"` — 전달된 reason 무시 |

## Newly Discovered Features (2026-05-18)

| Issue | Priority | Title |
|---|---|---|
| [#93](https://github.com/bluetape4k/clinic-appointment/issues/93) | P1 | 목록 엔드포인트 페이지네이션 — clinics/doctors/equipment/treatment-types |
| [#94](https://github.com/bluetape4k/clinic-appointment/issues/94) | P2 | 모든 컨트롤러 OpenAPI `@Operation`/`@ApiResponse` 어노테이션 추가 |
| [#95](https://github.com/bluetape4k/clinic-appointment/issues/95) | P2 | `GET /api/appointments/{id}/history` — 상태 이력 조회 API 신설 |

## Current Direction

- **P0 버그**: #91 (authorization bypass) 최우선 수정
- **P1 버그**: #90 (NPE), #92 (validation) 순차 처리
- **P1 기능**: #93 (pagination) — 운영 데이터 증가 전 조기 적용 권장
- **설계 작업**: #36 (multitenancy) 계속 진행

## Priority Queue

| Priority | Issue | Difficulty | Notes |
|---|---|---:|---|
| P0 | [#91](https://github.com/bluetape4k/clinic-appointment/issues/91) Authorization bypass in confirmReschedule | S | candidateId ↔ appointmentId 소유 검증 추가 |
| P1 | [#90](https://github.com/bluetape4k/clinic-appointment/issues/90) NPE in AppointmentService | S | `!!` → `?: throw NoSuchElementException` 2곳 |
| P1 | [#92](https://github.com/bluetape4k/clinic-appointment/issues/92) Missing @Valid on controllers | S | 각 `@RequestBody`에 `@Valid` 추가 |
| P1 | [#93](https://github.com/bluetape4k/clinic-appointment/issues/93) Pagination for list endpoints | M | ExposedPage 활용, page/size 쿼리 파라미터 |
| P2 | [#96](https://github.com/bluetape4k/clinic-appointment/issues/96) Hardcoded cancel reason | S | reason 파라미터 체인 연결 |
| P2 | [#95](https://github.com/bluetape4k/clinic-appointment/issues/95) State history API endpoint | S | GET /api/appointments/{id}/history |
| P2 | [#94](https://github.com/bluetape4k/clinic-appointment/issues/94) OpenAPI annotations | M | 8개 컨트롤러 전체 |
| P2 | [#36](https://github.com/bluetape4k/clinic-appointment/issues/36) Multitenancy strategy decision/design | M | Design-first; keep implementation separate. |

## Dependency Map

```text
#91 confirmReschedule authorization fix  (standalone)
#90 NPE fix in AppointmentService        (standalone)
#92 @Valid on controllers                (standalone)
#93 pagination                           (standalone)
#96 cancel reason                        (standalone)
#95 history endpoint                     (standalone)
#94 OpenAPI annotations                  (#95 완료 후 함께 작업 권장)
#36 multitenancy strategy
  -> #37 TenantGroup entity
  -> #38 JWT tenantId claim
  -> #39 Exposed tenant filter
  -> #46 hardcoded clinicId 해소 (선행 권장)
```

## WIP Limits

| Lane | Limit | Current next |
|---|---:|---|
| Bug fix | 2 | `#91` (P0), `#90` (P1) |
| Feature | 1 | `#93` pagination |
| Architecture/design | 1 | `#36` design-only |
