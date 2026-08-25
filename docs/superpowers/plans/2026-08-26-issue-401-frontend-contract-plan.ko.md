# Issue #401 frontend 계약 문서 계획

## 목표

실제 Angular 22 package와 route/source 계약을 기준으로 root·module README와
프런트엔드 요구사항 문서의 버전, tenant routing 완료 범위, endpoint 상태를
정렬한다. 환자 포털의 완료 범위와 직원·관리자 legacy JWT residual을 섞지
않고 기록한다.

## 실행 순서

1. `package.json`, app/portal route, tenant context, patient API client와 직원
   서비스를 읽어 현재 계약을 고정한다.
2. root·module README와 `docs/requirements/frontend.md`의 Angular 18/21,
   Karma, 전체 endpoint 완료 및 tenant routing 후속 문구를 source에 맞게
   고친다.
3. `docs:verify` 정적 검증을 추가해 package major, route, tenant URL, 문서와
   #295 residual의 drift를 재발 시 실패시킨다.
4. README architecture/module overview의 Angular 18 표기를 생성기와 PNG/SVG
   산출물에서 Angular 22로 갱신한다.
5. 한국어 문서 audit, frontend build/test와 7-Tier 검토를 실행한다.

## 보존할 계약

- 이번 이슈에서는 source behavior, API endpoint, 인증 방식, 직원 서비스의
  URL을 변경하지 않는다.
- 환자 포털은 `/api/{tenantCode}/...`를 사용하고 `patientAuthGuard`가
  보호한다는 현재 구현을 문서화한다.
- 직원·관리자 화면의 `/api/...` legacy JWT 경로는 미완료 residual로 남기고
  [Issue #295](https://github.com/bluetape4k/clinic-appointment/issues/295)에
  연결한다.
- API의 tenant scope와 로컬 seed `/api/tenant-default/...` 예시는 유지한다.

## 완료 기준

- root·module README와 요구사항 문서가 Angular 22/package 계약과 일치한다.
- tenant routing 완료 범위와 직원/auth residual이 모든 reader 문서에 명시된다.
- `npm run docs:verify`가 문서·route·source 검사에서 0건 실패한다.
- 7-Tier blocker P0/P1/P2/P3가 0/0/0/0이고 frontend CI가 exact head에서
  통과한다.
