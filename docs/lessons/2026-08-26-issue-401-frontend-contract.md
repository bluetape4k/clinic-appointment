# Issue #401 작업 교훈

## 재사용 우선 판단

새 frontend routing이나 API adapter를 만들지 않고 이미 구현된
`TenantContextService`, `PatientAuthService`, `PortalApiClient`, Angular route와
직원 legacy service를 문서 검증의 source로 사용했다. 문서 drift를 막는 데 필요한
범위만 `docs:verify` script로 고정했다.

## 증거·경계 교훈

- package version만 읽어서는 tenant routing 완료 범위를 알 수 없다. route,
  guard, URL builder, session storage와 residual service를 함께 확인해야 한다.
- backend endpoint가 tenant-scoped라는 사실과 frontend가 모든 화면에서 이를
  소비한다는 주장은 다르다. 환자 포털과 직원 화면을 분리해 쓰지 않으면 문서가
  보안 경계를 과장한다.
- 생성된 README diagram도 버전 표기를 재생성 가능한 generator와 함께 갱신해야
  다음 실행에서 Angular 18이 되살아나지 않는다.

## 후속 경계

직원·관리자 legacy JWT service를 `/api/{tenantCode}/...`로 전환하거나 route/auth
모델을 통합하는 작업은 #401에 포함하지 않는다. 구현 변경은 #295에서 별도 설계,
테스트, rollout 검토 후 진행한다.
