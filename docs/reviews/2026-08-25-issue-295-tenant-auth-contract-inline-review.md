# Issue #295 tenant API·인증 계약 구현 inline review

## 검토 범위와 기준

설계·계획 문서와 구현 diff를 다시 읽고, `TenantApiClient` 도입부터 관리 서비스 전환, 환자 cookie 인증, workforce bootstrap, 공통 session state, SSE, 대표 컴포넌트 fixture까지 한 번에 검토했다. 검토 기준은 Issue #295 완료 조건과 승인된 계획의 tenant path 단일화·인증 scope 분리·기존 Portal API 계약 보존이다.

이번 문서는 별도 worktree에서 구현한 변경에 대한 독립 inline review 기록이다. review 대상은 현재 worktree의 코드와 fresh 명령 결과이며, 과거 baseline 성공만으로 판정하지 않았다.

## 여섯 관점 판정

| 관점 | 확인 근거 | 판정 |
|---|---|---|
| 성능 | `TenantApiClient`는 URL·`HttpContext`·`HttpResponse` transport만 담당하고, Portal cache/ETag 변환은 기존 client에 남겼다. 서비스마다 transport를 새로 만들지 않는다. | P0=0, P1=0 |
| 안정성 | tenant 누락은 네트워크 전에 실패하고, 401/403은 patient/workforce scope별 `SessionStateService`로 전달한다. SSE 구독 취소는 기존 `AbortController` 계약을 유지한다. | P0=0, P1=0 |
| 보안 | patient 요청은 `patient-cookie`와 `withCredentials`만 사용하고 Bearer를 붙이지 않는다. workforce Bearer는 명시적 `workforce-bearer` scope에서만 붙으며 JWT token은 storage에 쓰지 않는다. | P0=0, P1=0 |
| 운영 | `AuthService.bootstrap(token, tenantCode?)`가 Gateway/host의 비영속 복원 seam을 제공하고, multi-tenant token은 명시 선택 없이는 복원하지 않는다. workforce login endpoint를 새로 만들지 않았다. | P0=0, P1=0 |
| 개발/API | management service public method와 DTO를 유지하고, raw `HttpClient`·`environment.apiUrl`·unscoped `/api/` 조립을 `TenantApiClient`로 통합했다. 정적 계약 테스트가 9개 management source를 검사한다. | P0=0, P1=0 |
| 사용자/화면 | role guard, patient/workforce 401·403, tenant-missing 상태가 공통 signal에 기록된다. 기존 portal ETag·Retry-After·structured error와 대표 patient E2E를 보존했다. | P0=0, P1=0 |

## 구현 검토 결과

- `TenantApiClient.url()`이 현재 `TenantContextService`를 읽어 `/api/{tenantCode}{path}`를 만들고, 절대 URL과 tenant 누락을 거부한다.
- `authInterceptor`는 `API_AUTH_SCOPE`가 `workforce-bearer`인 경우에만 Authorization을 추가한다. patient cookie와 scope 없는 raw request는 Bearer를 받지 않는다.
- `PortalApiClient`와 `PatientAuthService`는 같은 transport를 재사용하지만 각각 patient cookie option과 기존 ETag/cache/error 모델을 유지한다.
- `AppointmentService`, `ClinicService`, `DoctorService`, `EquipmentService`, `SlotService`, `RescheduleService`, `EquipmentUnavailabilityService`, `TreatmentTypeService`, `DashboardStatsService`가 모두 workforce scope를 명시한다.
- reschedule SSE도 `TenantApiClient.url()`로 tenant path를 만들고, 401/403을 workforce session state로 전파한다.
- `AuthService.bootstrap()`은 허용 tenant가 하나일 때만 자동 선택하고, 다중 tenant는 명시 선택을 요구하며 불일치 token/tenant를 폐기한다.
- patient notification SSE URL도 직접 `/api/...`를 조립하지 않고 `TenantApiClient.url()`을 사용한다.

## 검증 증거

- `npx ng test --watch=false --progress=false`: **44개 파일 / 322개 테스트 통과**, unhandled error 없음.
- `npm run build`: **성공**, production bundle이 `dist/appointment-frontend`에 생성됨.
- `npm run test:e2e`: **5개 Playwright Chromium 시나리오 통과**.
- `npx ng test --watch=false --progress=false --include 'src/app/core/api/tenant-api-contract.spec.ts'`: **9개 raw source 계약 통과**.
- management component fixture와 service spec은 `/api/tenant-a/...` 요청을 직접 검증한다.
- `git diff --check`: 통과.
- `git diff --name-only | rg '\\.kt$'`: 결과 없음. backend/mobile/Capacitor 변경도 없음.
- Gradle `./gradlew :frontend:appointment-frontend:build`는 코드 컴파일 전에 `node-22.22.3-darwin-arm64.tar.gz` dependency verification metadata 누락으로 중단됐다. npm build와 Angular test/E2E는 독립적으로 통과했다.

## Kotlin 패턴 게이트

이번 변경은 Angular TypeScript만 포함한다. Kotlin 파일 diff가 0개이므로 `bluetape-kotlin-patterns`의 KT-01 null safety, KT-02 data/value model, KT-03 coroutine/structured concurrency, KT-04 Exposed transaction, KT-05 test/container 규칙은 적용 대상이 아니다. 적용 제외 근거는 설계 문서의 범위와 fresh `git diff --name-only` 결과로 확인했다.

## 문서 게이트

- SPW-01~05: 설계·계획·구현 review·lesson의 독자, 실행 순서, 용어, 근거, read-back을 확인해 PASS.
- KO-01~07: 본 저장소의 Korean-only 문서·GitHub artifact 정책, 기술 토큰 보존, 명령/경로 정확성, 문서 diff check를 확인해 PASS.

## 잔여 위험과 후속 범위

1. workforce login endpoint와 host 통합 지점은 백엔드에 존재하지 않으므로 실제 Gateway/host는 page load마다 `AuthService.bootstrap()`을 호출해야 한다. 이 변경은 그 비영속 API seam과 검증만 제공하며 token 저장을 추가하지 않는다.
2. Gradle frontend task는 저장소의 dependency verification metadata가 Node archive를 포함할 때까지 환경 gap으로 남는다. 현재 npm 기반 Angular build/test/E2E를 통과한 상태다.
3. npm install 시 기존 audit 경고가 표시됐지만 dependency를 추가하거나 `npm audit fix`를 수행하지 않았다.

## 결론

구현 범위에서 P0/P1 blocker는 확인되지 않았다. Issue #295의 tenant transport·cookie/Bearer 분리·session 상태·관리 service 전환·대표 E2E 기준은 fresh 검증으로 충족했다. PR 생성·merge는 사용자 승인 범위와 별도 최신 head 승인 게이트를 따른다.

**판정: MERGE-READY (PR/merge 전용 승인 대기)**
