# 예약 정책 유효 스냅숏 조회 — Task 7 검토 기록

## 결과

Task 7은 예약 생성·변경·재조정이 참조할 불변 `EffectiveSchedulingPolicy`를
테넌트·병원·정책 세대·의사결정 시각·시술 시각 단위로 조회한다. 캐시는 성능
최적화일 뿐 권위 저장소가 아니며, 데이터베이스 세대를 확인할 수 없으면 이전
캐시 값을 반환하지 않고 닫힌 실패를 수행한다.

## 내부 호출 계약

`EffectiveSchedulingPolicyService.getEffective`의 호출자는 API Gateway가 전달한
인증정보를 검증한 뒤 확정된 양의 정수 `tenantGroupId`, 해당 테넌트 소속임을 확인한
`clinicId`, 정확한 UTC `decisionAt`과 `serviceAt`을 전달한다. 로컬 시각의 DST
gap/overlap 해석은 이 서비스에 들어오기 전에 끝나야 한다.

```kotlin
val policy = effectiveSchedulingPolicyService.getEffective(
    tenantGroupId = TenantContext.requireCurrent().id,
    clinicId = verifiedClinicId,
    decisionAt = commandReceivedAt,
    serviceAt = requestedTreatmentAt,
)
```

반환된 `snapshotHash`, `generation`, `sourceVersions`는 예약·자원배정 의사결정을
재현하는 근거다. 호출자는 이를 현재 상품 BOM snapshot과 합쳐 덮어쓰지 않고,
서로 다른 변경 이력을 가진 별도 참조로 보존해야 한다.

## 정확성 순서

1. 권위 데이터베이스에서 tenant/clinic 세대 벡터를 읽는다.
2. 그 세대와 두 UTC 시각이 모두 일치하는 항목만 캐시에서 찾는다.
3. tenant 기본 정책과 clinic override를 한 번에 읽어 컴파일한다.
4. 데이터베이스 세대를 다시 읽고 변경되었으면 결과를 폐기한다.
5. 두 scope head를 고정된 순서로 잠그고 세대를 재검사한 뒤 불변 스냅숏을
   삽입하거나 같은 canonical bytes를 가진 기존 행을 재사용한다.
6. 영속화가 끝난 뒤에만 프로세스 로컬 캐시에 보관한다.

활성화 이벤트에 의한 cache invalidation은 다른 인스턴스의 적중률을 높이는
가속 수단이다. 이벤트가 지연되거나 누락되어도 1단계의 데이터베이스 세대 검증이
stale cache 반환을 막으므로 정확성 조건으로 사용하지 않는다.

## 안정 오류 계약

| 상황 | HTTP | 안정 코드 | 재시도 |
|---|---:|---|---|
| 컴파일 중 정책 활성화가 계속 겹침 | `409` | `POLICY_EFFECTIVE_READ_CONFLICT` | 짧은 backoff 후 가능 |
| 권위 저장소 조회·디코딩·영속화 불가 | `503` | `POLICY_EFFECTIVE_READ_UNAVAILABLE` | 저장소 회복 후 가능 |

두 응답은 공용 `SchedulingApiErrorResponse`로 정규화한다. 원인 예외 메시지,
SQL, JWT claim, 정책 payload, tenant/clinic 식별자는 공개 응답에 포함하지 않는다.
크기 추정기나 로컬 캐시 보관 실패는 경고로 기록하되 이미 커밋된 권위
스냅숏의 성공 응답을 뒤집지 않는다.

## 6-R 독립 검토 요약

| 관점 | P0 | P1 | 처리한 주요 의견 |
|---|---:|---:|---|
| 성능 | 0 | 0 | active definition SQL 범위 제한, tenant quota counter 상수 시간화, 동시성 검증 강화 |
| 안정성 | 0 | 0 | canonical bytes 재검사, bounded retry, cache 실패와 권위 조회 분리 |
| 보안 | 0 | 0 | tenant/clinic 격리, fail-closed 조회, 안전한 오류·로그 |
| 운영 | 0 | 0 | 저카디널리티 실패 사유, 이후 metrics/config/invalidation 연결 경계 |
| 개발자/API | 0 | 0 | Exposed transaction 소유권, wire 오류 registry와 handler 연결 |
| 사용자/호출자 | 0 | 0 | 상세 한국어 KDoc, 호출 전제와 cache 비권위성 문서화 |

## 검증 증거

- 집중 테스트: cache/effective 19개와 보안 보정 11개, 실패 0
- 전체 빌드:
  - `appointment-core`: 196개, 실패 0
  - `appointment-event`: 60개, 실패 0
  - `appointment-api`: 215개, 실패 0, 환경 의존 테스트 2개 skip
- `git diff --check`: 통과
- 변경 Kotlin 공개 type KDoc 감사: 한국어 KDoc 누락 0
- constructor 속성·인자 496개 감사: `@property`/`@param` 또는 inline KDoc 누락 0

실제 PostgreSQL/MySQL의 lock·constraint 동등성, cache metrics와 설정 외부화,
activation event invalidation wiring은 승인된 후속 Task의 검증 범위다.
