# 멀티테넌시 #36~#39 완료 상태 감사

## 목적

PR #118이 EPIC #16만 닫고 #36~#39를 열린 상태로 남긴 이유를 재확인하고, 현재
`develop`이 각 이슈의 계약을 어디까지 충족하는지 기록한다. 이 문서는 구현 완료를
추정하지 않는다. 병합 PR, 현재 소스, 기존 테스트가 실제로 증명하는 범위와 후속 공백을
분리한다.

## 감사 기준

- 기준 commit: `27429b9485eb41404bb29de83c357bd78a5836b4`
- 기반 구현: [PR #118](https://github.com/bluetape4k/clinic-appointment/pull/118), `feat/issue-16-multitenancy`
- 관련 이슈: [#36](https://github.com/bluetape4k/clinic-appointment/issues/36), [#37](https://github.com/bluetape4k/clinic-appointment/issues/37), [#38](https://github.com/bluetape4k/clinic-appointment/issues/38), [#39](https://github.com/bluetape4k/clinic-appointment/issues/39)
- 설계 기준: `docs/superpowers/specs/2026-05-19-multitenancy-design.md`
- 실행일: 2026-08-04
- 실행한 기준선:

  ```bash
  ./gradlew :appointment-core:test \
    --tests 'io.bluetape4k.clinic.appointment.repository.TenantGuardRepositoryTest' \
    :appointment-api:test \
    --tests 'io.bluetape4k.clinic.appointment.api.integration.MultitenancyIntegrationTest' \
    --tests 'io.bluetape4k.clinic.appointment.api.migration.MultitenancyMigrationTest' \
    --no-parallel
  ```

  결과는 `BUILD SUCCESSFUL`이며 API의 대상 test 5건이 통과했다. 이 기준선은
  Holiday/Slot/Solver의 cross-tenant scheduling 결과를 검증하지 않는다.

## 결론

PR #118은 `TenantGroups`, 3-dialect V3~V6 migration, JWT `allowedTenants`, tenant path,
핵심 repository JOIN guard를 도입했다. 그러나 GitHub issue와 현재 구현의 계약이 달라
#36~#39를 일괄 종료할 수 없다. 먼저 #36에서 identity와 key authority를 고정하고,
#37 → #38 → #39 순서로 정합화해야 한다.

```text
#36 identity/key ADR
 ├─ #37 schema·migration·repository contract
 └─ #38 HTTP/JWT tenant authority
      └─ #39 repository·solver isolation audit
```

## 이슈별 판정

| 이슈 | 판정 | 현재 증거 | 남은 계약 |
|---|---|---|---|
| #36 | 부분 충족 | 상세 spec·research·lesson과 PR #118 구현 존재 | `docs/requirements/architecture.md`의 canonical ADR 부재. 이 변경에서 ADR-14로 보완 |
| #37 | 부분 충족 | `TenantGroups`, Clinic/Holiday FK, V3~V6, `TenantGroupRepository` 존재 | GitHub issue의 지역 필드, KR/JP/EN seed, locale backfill, `CASCADE`가 현재의 최소 tenant, `tenant-default`, `RESTRICT`와 충돌. ADR-14 기준으로 issue 정정 필요 |
| #38 | 부분 충족 | path tenant filter, JWT `allowedTenants`, authorization manager, tenant context 존재 | `/api/v2`는 Gateway-selected single tenant mode다. 두 authority 모드와 context 전파 규칙을 명시하고 endpoint별 test 필요 |
| #39 | 부분 충족 | 핵심 resource의 `findByIdAndTenant`와 cross-tenant negative test 존재 | Holiday·일부 clinic child·solver query가 tenant scope를 명시하지 않는다. externally reachable query 전수 감사 필요 |

## 현재 Key 구조

### 유지할 기반

- `TenantGroups.id`는 내부 `tenantGroupId: Long` authority다.
- `tenantCode`는 URL/JWT에서 사용하는 외부의 불투명 lower-case ASCII tenant slug다.
- Clinic과 child resource의 DB PK는 전역 `Long` surrogate를 유지한다.
- child table은 Clinic FK로 tenant에 얕게 귀속하고, 외부 ID 조회는 tenant JOIN guard를 사용한다.
- 이후 추가된 appointment plan, booking reliability, profile reevaluation, waitlist,
  notification/outbox는 tenant 또는 tenant+clinic logical scope를 key에 포함한다.

### 명문화가 필요했던 규칙

- clinic-local cache, event, outbox, idempotency/dedup key는
  `(tenantGroupId, clinicId, business key)`를 최소 범위로 사용한다.
- `/api/{tenantCode}`와 `/api/v2`는 서로 다른 tenant 선택 모드이지만 동일한 내부
  `(tenantGroupId, clinicId)` authority로 수렴해야 한다.
- locale, timezone, currency, 국가 코드는 tenant identity가 아니다.
- HTTP-bound adapter/helper는 `TenantContext`를 읽을 수 있지만 즉시 명시적인 scope로
  변환한다. core/background/coroutine/event 경계에는 scope ID를 명시 전달한다.

이 규칙의 canonical decision은 `docs/requirements/architecture.md` ADR-14에 둔다.

## 발견 사항

### P1 — Holiday 조회가 tenant 경계를 표현하지 않는다

`HolidayRepository.existsByDate(date)`와 `findByDateRange(start, end)`는 `tenantGroupId`를
받지 않는다. `SlotCalculationService`와 `SolverService`가 이를 사용하므로, tenant A의
휴일이 tenant B의 슬롯 또는 solver 판단에 영향을 줄 수 있다. 기존 multitenancy test는
Holiday composite unique와 core resource ID guard는 검증하지만 이 scheduling 결과 격리는
검증하지 않는다.

조치: #39에서 Holiday repository API를 tenant-aware하게 만들고 두 tenant가 같은 날짜에
서로 다른 휴일을 가진 시나리오를 slot/solver test로 잠근다.

### P2 — #37 live 계약과 채택된 모델이 다르다

GitHub issue는 locale/timezone/currency 필드, KR/JP/EN seed, locale backfill, `CASCADE`를
요구한다. 현재 구현과 상세 설계는 최소 tenant record, `tenant-default` backfill,
`RESTRICT`를 사용한다. 이를 구현 누락으로 간주해 지역 필드나 cascade를 추가하면
`Tenant != locale` 원칙과 데이터 보존 경계가 깨진다.

조치: #37 구현 전에 issue 본문을 ADR-14와 일치시키고 migration/repository의 실제 누락만
작업한다.

### P2 — HTTP tenant authority가 두 모드로 발전했다

PR #118의 `/api/{tenantCode}` mode 외에 appointment commitment `/api/v2`는 Gateway JWT의
단일 tenant와 선택 clinic을 사용한다. `TenantPathResolver`도 `v2`를 reserved segment로
제외한다. 이는 현재 코드에서 의도된 경계지만 기존 #36/#38 문서에는 없다.

v2 ingress는 JWT의 tenant/clinic claim membership을 검증하지만 중앙 DB ownership guard를
일괄 수행하지 않는다. 실제 tenant-clinic 관계 검증은 downstream tenant-aware 조회에 분산돼
있다.

조치: ADR-14에 두 모드를 기록한다. #38에서 endpoint inventory와 401/403/404, multi-tenant
JWT fail-closed, DB clinic ownership, context clear/coroutine 전파 test를 보강한다.

### P2 — tenantCode 정규형이 계층 전체에서 강제되지 않는다

ADR-14는 lower-case ASCII slug를 canonical form으로 채택한다. 그러나 MySQL의 현재
`utf8mb4_unicode_ci` 비교는 대소문자를 구분하지 않고, 모든 ingress가 정규형이 아닌 값을
일관되게 거부한다는 test도 없다. 따라서 “대소문자까지 정확히 비교한다”는 현재 사실이 아니다.

조치: #37에서 dialect별 unique/lookup 동작을, #38에서 path/JWT 입력 정규형 거부를 test로
고정한다.

### P2 — clinicId-only cache key가 목표 계약보다 좁다

`DoctorRepository`, `EquipmentRepository`, `TreatmentTypeRepository`의 `@Cacheable` key는
`#clinicId`만 사용한다. clinic PK가 전역이어서 즉시 cross-tenant 충돌이 재현되지는 않지만,
logical key가 tenant authority를 드러내지 않아 PK 정책이나 cache namespace 변화에 취약하다.

조치: #39의 query audit에서 cache API를 tenant-aware하게 만들고
`(tenantGroupId, clinicId)` 범위로 negative test를 추가한다.

### P2 — legacy appointment event에는 tenantGroupId가 없다

`AppointmentDomainEvent`와 `AppointmentEventLogs`는 `clinicId`까지만 보유한다. 현재는 내부
Spring event와 legacy event log 경계이므로 신규 durable outbox 계약과 동일하다고 간주하지
않는다. Kafka 등 외부 broker로 내보내기 전에 tenant scope와 replay/dedup key를 보강해야 한다.

조치: #39에서 legacy event와 durable integration event를 분류하고, 외부화 대상에는
`tenantGroupId`를 필수 key로 추가한다.

## 후속 순서와 종료 조건

1. #36: 거절 대안과 한·영 diagram을 포함한 ADR-14 및 이 감사 기록을 병합하고 issue를 닫는다.
2. #37: GitHub issue를 ADR-14에 맞게 정정한 뒤 schema/migration/repository 공백만 구현한다.
3. #38: endpoint별 tenant authority와 context 전파를 검증한다.
4. #39: repository/slot/solver/background 격리를 전수 검사하고 P1 Holiday gap을 수정한다.

#36 이후에도 P1 Holiday 공백이 남으므로 전체 multitenancy 완료 처리는 아직 불가능하다. 이
문서는 해당 공백을 숨기지 않고 #39의 선행 acceptance criterion으로 남긴다.
