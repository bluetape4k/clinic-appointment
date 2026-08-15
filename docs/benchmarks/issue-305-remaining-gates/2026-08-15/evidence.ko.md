# Issue #305 잔여 게이트 실행 증거

## 범위와 판정 기준

- 대상: `clinic-appointment` Issue #305 취소 이력 API/포털 구현
- 실행 worktree: `.worktrees/issue-305-cancellation-history`
- 실행 기준 HEAD: `444e5cfa23634352093a2eff8e1b2d2da85c5163`
- workflow: Type-A Full Feature, run `20260813T122143Z-b06b97df`
- 목적: 이전 검토에서 보류한 로컬 HTTP, readiness, bounded registry, PostgreSQL EXPLAIN 게이트를 실제 실행하고 production 증거와 분리해 기록한다.

이 문서는 로컬 singleton/Testcontainers와 테스트 profile에서 얻은 증거를 production 운영 증거로 승격하지 않는다. production 연결·권한·canary가 없으면 해당 항목은 `PENDING`으로 유지한다.

## 실행 환경

| 항목 | 값 |
|---|---|
| Docker context | `colima` |
| Colima | running, Docker runtime |
| Docker server | `28.4.0` |
| macOS Testcontainers socket | context-mode 셸에서 변수 미상속을 확인해 affected Gradle 명령에만 `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`를 명시 |
| launcher 정책 | `bluetape4k-testcontainers`의 `Containers.Postgres`/`Containers.MySql8` singleton 사용, `@Testcontainers`와 raw launcher 미사용 |

## 최신 실행 결과

### Issue #305 로컬 게이트

다음 단일 Gradle invocation은 `--no-daemon`과 Colima socket override를 사용했다.

```bash
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock ./gradlew :appointment-api:test \
  --tests 'io.bluetape4k.clinic.appointment.api.service.PatientHistoryReadinessTest' \
  --tests 'io.bluetape4k.clinic.appointment.api.service.PatientHistoryCursorCodecTest' \
  --tests 'io.bluetape4k.clinic.appointment.api.security.PatientCancellationHistoryHttpSecurityIntegrationTest' \
  --tests 'io.bluetape4k.clinic.appointment.api.integration.PatientCancellationHistoryQueryPlanTest' \
  --no-daemon
```

| 테스트 | 결과 | 닫힌 게이트 |
|---|---:|---|
| `PatientHistoryReadinessTest` | 2/2 | 60초 probe cache, registry 장애 fail-closed와 재복구 |
| `PatientHistoryCursorCodecTest` | 8/8 | bounded registry capacity와 5분 TTL 만료 회수 |
| `PatientCancellationHistoryHttpSecurityIntegrationTest` | 1/1 | anonymous 401, staff 403, patient의 sanitized 503 경계와 correlation/retry header |
| `PatientCancellationHistoryQueryPlanTest` | 1/1 | 실제 PostgreSQL production-schema join의 keyset 계획과 history index 사용 |
| **합계** | **12/12** | **실패 0, 오류 0, skip 0** |

HTTP 테스트는 Redis singleton을 띄운 Spring Boot `RANDOM_PORT` 컨텍스트에서 실행했으며, patient 요청은 test profile의 feature-disabled 경계를 통해 controller까지 도달한다. 이는 운영 활성화 증거가 아니라 route/security/error-envelope 통합 증거다.

PostgreSQL EXPLAIN 보고서는 테스트가 생성한 다음 artifact에 기록됐다.

`appointment-api/build/reports/performance/patient-cancellation-history-postgresql-explain.txt`

- 사용 index: `idx_cancellation_detail_patient_scope_time`
- history relation(`scheduling_appointment_cancellation_details`)의 `Seq Scan`: `false`
- commitment 전체 스캔은 4,000행 hash join의 planner 선택이며 history table full scan 판정과 분리했다.
- 대상 fixture에는 history 20행과 noise commitment 4,000행이 포함되어 patient-scope/occurred_at/id keyset 조건을 실제 계획에 반영했다.

### 이미 재검증한 인접 게이트

| 영역 | 결과 |
|---|---|
| Flyway H2 | `FlywayMigrationTest` 8/8 통과 |
| Flyway PostgreSQL | `FlywayPostgreSQLMigrationTest` 8/8 통과 |
| Flyway MySQL 8 | migration 8/8 통과; production endpoint 1건은 자격증명 부재로 의도적 pending |
| API targeted | 환자 이력 properties/controller/service/cursor/readiness 선택 테스트 41개 통과, production MySQL metadata 1건 pending |
| Core targeted | `AppointmentCancellationHistoryRepositoryTest`, `AppointmentCancellationDetailsSchemaTest` 통과 |
| Angular | 39개 파일, 266개 테스트 통과; patient cancellation history spec 3개 포함 |
| Angular build | `npm run build` 성공 |
| whitespace | `git diff --check` 통과 |

## Production preflight와 잔여 차단

현재 셸에서 다음 production MySQL 변수는 모두 설정되지 않았다.

```text
APPOINTMENT_PRODUCTION_MYSQL_JDBC_URL=UNSET
APPOINTMENT_PRODUCTION_MYSQL_USER=UNSET
APPOINTMENT_PRODUCTION_MYSQL_PASSWORD=UNSET
```

따라서 production JDBC 연결, 실제 HTTP smoke, preflight count artifact를 시도하지 않았다. 자격증명 없이 임의 endpoint나 production PASS를 만들지 않는 것이 이 증거의 범위다.

다음 운영 증거는 아직 `PENDING`이다.

1. 실제 production MySQL 연결의 migration preflight와 protected HTTP smoke
2. 모든 writer replica의 version fence 관측 및 60초 steady probe/alert artifact
3. shared external token registry의 restart/capacity/readiness 증거
4. production ACL, backup/restore, canary, SLO, rollback rehearsal

## DoD 상태

| 항목 | 상태 | 근거/제약 |
|---|---|---|
| 로컬 구현·targeted test | PASS | 위 12/12 및 인접 게이트 |
| 로컬 PostgreSQL query plan | PASS | `idx_cancellation_detail_patient_scope_time`, history `Seq Scan=false` |
| production activation | PENDING | production credential과 운영 관측 부재 |
| D8 본격 운영 | N/A | 현재 본격운영을 하지 않음 |
| D9 운영 인력 | N/A | 1인 개발자 범위로 운영 인력 분리 증거를 요구하지 않음 |
| 통합 판정 | **P1/BLOCK** | 로컬 게이트는 닫혔지만 production activation gate가 남음 |

결론적으로 이번 실행은 Issue #305의 남은 **로컬** 작업을 실제 증거로 닫았지만, merge-ready 또는 운영 활성화 완료를 의미하지 않는다. production 자격증명·운영 증거가 확보되기 전까지 구현 DoD는 `PENDING/BLOCK`으로 유지한다.
