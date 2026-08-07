# 작업 중 - clinic-appointment

스냅숏: 2026-06-02 KST
범위: `debop`에 할당되고 2026-01-01 이후 생성된 열린 GitHub 이슈
열린 이슈 수: 19개

## 최근 완료

- **#82** bluetape4k artifact ID 표준화 컨벤션 통일 (commit `8133de0`).
- **#79** `bluetape4k-dependencies` 단일 BOM 소스 전환 (commit `9df3a9f`).
- **#52** Repository cache → Spring `@Cacheable`/`@CacheEvict` 전환 완료 (commit `3fef28c`, `ceac3a0`). → **#97 close 대기**.
- **#60** CI paths-filter + nightly workflow 적용 완료 (commit `991cde7`, `b0be270`). → **#97 close 대기**.
- **#61** Kluent → `bluetape4k-assertions` 마이그레이션 완료 (commit `dd69e84`).

## 실행 항목

- [ ] Close issue #52 (work merged — see #97)
- [ ] Close issue #60 (work merged — see #97)

## 새로 발견한 버그 (2026-05-18)

| 이슈 | 심각도 | 제목 |
|---|---|---|
| [#90](https://github.com/bluetape4k/clinic-appointment/issues/90) | HIGH | `AppointmentService` `findByIdOrNull!!` — potential NPE in updateStatus / cancel |
| [#91](https://github.com/bluetape4k/clinic-appointment/issues/91) | CRITICAL | `confirmReschedule()` 미검증 — 다른 예약의 candidateId로 무단 재배정 가능 |
| [#92](https://github.com/bluetape4k/clinic-appointment/issues/92) | HIGH | 모든 컨트롤러 `@RequestBody`에 `@Valid` 누락 — Bean Validation 미적용 |
| [#96](https://github.com/bluetape4k/clinic-appointment/issues/96) | MEDIUM | `cancel()` 취소 사유 하드코딩 `"Cancelled by user"` — 전달된 reason 무시 |

## 새로 발견한 기능 (2026-05-18)

| 이슈 | 우선순위 | 제목 |
|---|---|---|
| [#93](https://github.com/bluetape4k/clinic-appointment/issues/93) | P1 | 목록 엔드포인트 페이지네이션 — clinics/doctors/equipment/treatment-types |
| [#94](https://github.com/bluetape4k/clinic-appointment/issues/94) | P2 | 모든 컨트롤러 OpenAPI `@Operation`/`@ApiResponse` 어노테이션 추가 |
| [#95](https://github.com/bluetape4k/clinic-appointment/issues/95) | P2 | `GET /api/appointments/{id}/history` — 상태 이력 조회 API 신설 |

## 현재 방향

- **P0 버그**: #91 (authorization bypass) 최우선 수정
- **P1 버그**: #90 (NPE), #92 (validation) 순차 처리
- **P1 기능**: #93 (pagination) — 운영 데이터 증가 전 조기 적용 권장
- **설계 작업**: #36 (multitenancy) 계속 진행

## 우선순위 큐

| 우선순위 | 이슈 | 난이도 | 비고 |
|---|---|---:|---|
| P0 | [#91](https://github.com/bluetape4k/clinic-appointment/issues/91) `confirmReschedule` 인가 우회 | S | candidateId ↔ appointmentId 소유 검증 추가 |
| P1 | [#90](https://github.com/bluetape4k/clinic-appointment/issues/90) `AppointmentService` NPE | S | `!!` → `?: throw NoSuchElementException` 2곳 |
| P1 | [#92](https://github.com/bluetape4k/clinic-appointment/issues/92) 컨트롤러 `@Valid` 누락 | S | 각 `@RequestBody`에 `@Valid` 추가 |
| P1 | [#93](https://github.com/bluetape4k/clinic-appointment/issues/93) 목록 엔드포인트 페이지네이션 | M | ExposedPage 활용, page/size 쿼리 파라미터 |
| P2 | [#96](https://github.com/bluetape4k/clinic-appointment/issues/96) 취소 사유 하드코딩 | S | reason 파라미터 체인 연결 |
| P2 | [#95](https://github.com/bluetape4k/clinic-appointment/issues/95) 상태 이력 API 엔드포인트 | S | GET /api/appointments/{id}/history |
| P2 | [#94](https://github.com/bluetape4k/clinic-appointment/issues/94) OpenAPI 어노테이션 | M | 8개 컨트롤러 전체 |
| P2 | [#36](https://github.com/bluetape4k/clinic-appointment/issues/36) 멀티테넌시 전략 결정/설계 | M | 설계를 먼저 진행하고 구현은 분리 |

## 의존성 맵

```text
#91 confirmReschedule 인가 수정 (독립)
#90 AppointmentService NPE 수정 (독립)
#92 컨트롤러 @Valid 추가 (독립)
#93 페이지네이션 (독립)
#96 취소 사유 (독립)
#95 이력 엔드포인트 (독립)
#94 OpenAPI 어노테이션 (#95 완료 후 함께 작업 권장)
#36 멀티테넌시 전략
  -> #37 TenantGroup entity
  -> #38 JWT tenantId claim
  -> #39 Exposed tenant filter
  -> #46 hardcoded clinicId 해소 (선행 권장)
```

## 작업 중 한도

| lane | 한도 | 다음 작업 |
|---|---:|---|
| Bug fix | 2 | `#91` (P0), `#90` (P1) |
| Feature | 1 | `#93` pagination |
| Architecture/design | 1 | `#36` design-only |
