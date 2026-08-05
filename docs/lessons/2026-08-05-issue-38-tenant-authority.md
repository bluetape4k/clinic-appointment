# Issue #38 tenant authority lesson

## 맥락

commitment API가 `/api/v2`와 tenant-aware path를 동시에 노출하면 path, JWT
membership, 내부 `tenantGroupId`가 서로 다른 authority가 된다. 아직 공개 API version
계약을 고정할 단계가 아니므로 공개 경로는 `/api/{tenantCode}/...` 하나로 통일했다.

## 결정

1. tenant code는 HTTP path에서만 선택하고, 권한은 검증된 JWT `allowedTenants`와
   active `TenantGroup` membership으로 결정한다. `X-Tenant-Code`, clinic/header,
   body의 internal ID는 authority가 아니다.
2. canonical slug 규칙과 reserved `v1`/`v2` root를 shared rule로 유지한다. malformed,
   encoded-ambiguous, servlet-path mismatch는 JWT보다 먼저 privacy-safe 404로 끝낸다.
3. multi-tenant principal은 path tenant를 `ActorContext.selectedTenantCode`에
   보존하고 downstream resolver가 다시 검증한다. 허용 tenant가 하나라는 가정이나
   `singleOrNull()` 기반 authority 선택을 복구하지 않는다.
4. tenant filter와 service access의 lookup budget을 route별로 문서화하고 테스트한다.
   최적화가 필요해도 cross-layer cache를 먼저 도입하지 않고 query/predicate 증거를
   확인한다.

## 결과

- customer/admin controller와 OpenAPI, README, API 문서, 운영 runbook을 tenant path
  contract로 정렬했다.
- filter, JWT, authorization manager, actor resolver, controller matcher에 대해
  malformed path, reserved root, role matrix, membership, inactive/missing tenant,
  stale context, DB outage, privacy-safe log 회귀를 추가했다.
- route lookup budget(tenant filter 1회, direct create/confirm 2회, query 1회)을
  focused test로 고정했다.
- 첫 전체 회귀에서 공유 fixture가 `tenant-default`를 지우는 문제와 낡은 OpenAPI
  path assertion을 발견했다. 각 테스트가 기본 tenant를 복구하고 active operation
  assertion을 갱신한 뒤 affected suite는 15건 통과했다.

## 놓친 점

기존 `/api/v2` 경로가 production source에만 없는지 확인하는 것으로 충분하다고
생각하면 안 된다. OpenAPI 계약 테스트와 README/runbook의 version 문구도 같은
변경에서 함께 갱신해야 한다. 또한 새 path가 tenant DB lookup을 활성화하므로 기존
통합 fixture가 공유 seed를 삭제하지 않는지 확인해야 한다.

## 다음 작업의 guard

1. 새 tenant API는 `/api/{tenantCode}` base path와 shared canonical rule을 재사용하고
   `/api/v1`/`/api/v2` alias를 추가하지 않는다.
2. tenant authority를 header/body 또는 `allowedTenants.singleOrNull()`로 되돌리지
   않는다. 내부 `tenantGroupId`는 key/FK와 내부 로그 경계를 벗어나지 않는다.
3. security filter를 bean으로 등록할 때 servlet 자동 등록과 Security chain 중복 실행을
   함께 점검한다.
4. 통합 테스트 fixture는 namespaced row만 정리하고 공용 tenant seed를 복구하며,
   변경 후에는 affected suite와 전체 CI를 모두 실행한다.
