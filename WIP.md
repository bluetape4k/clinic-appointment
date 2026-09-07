# 작업 중 - clinic-appointment

기준 시각: 2026-09-07 KST
범위: `debop`에 할당되고 2026-01-01 이후 생성된 열린 GitHub 이슈
열린 이슈 수: 19개

## 2026-09-07 2.1.0-SNAPSHOT 예제 소비선

중앙 BOM을 `bluetape4k-dependencies:2.1.0-SNAPSHOT`으로 전환하고 9개
consumer lockfile을 새 개발선으로 갱신했다. 외부 stable artifact는 SHA-256
검증을 유지하고, 내용이 게시마다 바뀌는 `io.github.bluetape4k` 계열의 현재
`1.1.0-SNAPSHOT`·`2.1.0-SNAPSHOT` 개발 버전만 제한적으로 trust한다.
`scripts/verify-dependency-locking.sh`가 이 경계를 회귀 검증한다.

PR #463의 hosted CI에서 계약 스크립트가 Fory `1.6.0`을 요구하지만 실제
lockfile과 resolved graph는 `1.7.1`을 선택하는 누락을 확인했다. Fory 두
좌표와 Leader 기대값을 각각 `1.7.1`, `1.1.0-SNAPSHOT`으로 정렬하고 이전
버전은 금지 목록에 남긴다. BOM 전환 검증은 locking guard와
`scripts/verify-dependency-contract.sh`를 모두 실행해야 한다. 대표 컴파일이나
locking guard 하나의 성공을 전체 CI 계약 검증으로 간주하지 않는다.
Gradle의 timestamped SNAPSHOT 출력은 정확한 SNAPSHOT 버전에 한해서만
허용한다. `scripts/test-dependency-insight-header.sh`의 12개 사례가 일반
release, timestamp, 다른 버전·좌표 및 requested-version 화살표의 경계를
검증하며 전체 계약 검사에서 항상 실행된다.

새 head CI의 API testCompileClasspath 해석에서 `guava-parent:33.7.1-jre`
POM checksum 누락도 확인했다. Maven Central의 두 공개 endpoint 원본과
SHA-256 sidecar가 모두 `4015e615a5674755c88d8112d8bbd763e8afde561be76c6790699edb5d5608f5`
로 일치함을 확인하고 해당 POM 한 개만 추가했다. stable artifact 검증은
완화하지 않으며 API test 컴파일까지 전달 검증에 포함한다.

API 전체 테스트의 `NearCacheForyCompatibilityTest`에서도 현재 graph에 대한
기대값이 Fory `1.6.0`으로 남은 실패를 재현했다. resolved provenance만
`2.1.0-SNAPSHOT`/Fory `1.7.1`로 정렬하며 source fixture `1.3.1`의
생성 버전·commit·checksum·wire 데이터는 그대로 보존한다. legacy DTO 복원,
codegen/동시 round-trip 및 압축 테스트는 기존 검증을 유지한다.

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
