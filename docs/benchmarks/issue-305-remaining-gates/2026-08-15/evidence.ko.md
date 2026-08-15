# Issue #305 잔여 게이트 실행 증거

## 범위와 판정 기준

- 대상: `clinic-appointment` Issue #305 취소 이력 API/포털 구현
- 실행 worktree: `.worktrees/issue-305-cancellation-history`
- 실행 기준 HEAD: `e5a60d940b78fe56d1755929a270906cd6b6bfc5`
- workflow: Type-A Full Feature, policy-resume run `20260815T142224Z-abc83cea`
- 목적: 이전 검토에서 보류한 로컬 HTTP, readiness, bounded registry, PostgreSQL EXPLAIN 게이트를 실제 production-like Testcontainers 환경에서 실행하고, 실제 production 운영 증거와 분리해 기록한다.

이 저장소는 실제 운영 서비스가 아닌 clinic appointment 예제 애플리케이션이다. 따라서 repository-level DoD는 `bluetape4k-testcontainers` singleton이 제공하는 PostgreSQL·Redis production-like 시뮬레이션과 고정 fixture·bounded probe로 닫는다. 실제 production 연결·권한·canary·트래픽 증거는 별도 명시적 운영 게이트이며, 이 문서의 PASS에 필요하지 않다.

## 실행 환경

| 항목 | 값 |
|---|---|
| Docker context | `colima` |
| Colima | running, Docker runtime |
| Docker server | `28.4.0` |
| macOS Testcontainers socket | `colima` Docker socket `unix:///Users/debop/.colima/default/docker.sock`에 `/_ping=OK`; affected Gradle 명령에 `DOCKER_HOST`와 `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`를 명시 |
| launcher 정책 | `bluetape4k-testcontainers`의 `PostgreSQLServer.Launcher.postgres`와 `RedisServer.Launcher.redis` singleton 사용, `@Testcontainers`와 raw `GenericContainer` 미사용 |

## 최신 실행 결과

### Issue #305 로컬 게이트

Testcontainers가 서로 다른 singleton을 동시에 초기화하지 않도록 무거운 컨테이너 게이트를 class별로 순차 실행했다. 각 invocation은 `--no-daemon`, `--rerun-tasks`, Colima socket override를 사용했다.

```bash
DOCKER_HOST=unix:///Users/debop/.colima/default/docker.sock \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
TESTCONTAINERS_RYUK_DISABLED=true \
./gradlew :appointment-api:test \
  --tests 'io.bluetape4k.clinic.appointment.api.security.PatientCancellationHistoryHttpSecurityIntegrationTest' \
  --no-daemon --rerun-tasks

DOCKER_HOST=unix:///Users/debop/.colima/default/docker.sock \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
TESTCONTAINERS_RYUK_DISABLED=true \
./gradlew :appointment-api:test \
  --tests 'io.bluetape4k.clinic.appointment.api.integration.PatientCancellationHistoryQueryPlanTest' \
  --no-daemon --rerun-tasks

./gradlew :appointment-api:test \
  --tests 'io.bluetape4k.clinic.appointment.api.service.PatientHistoryReadinessTest' \
  --tests 'io.bluetape4k.clinic.appointment.api.service.PatientHistoryCursorCodecTest' \
  --no-daemon --rerun-tasks
```

| 테스트 | 결과 | 닫힌 게이트 |
|---|---:|---|
| `PatientHistoryReadinessTest` | 2/2 | 60초 probe cache, registry 장애 fail-closed와 재복구 |
| `PatientHistoryCursorCodecTest` | 8/8 | bounded registry capacity와 5분 TTL 만료 회수 |
| `PatientCancellationHistoryHttpSecurityIntegrationTest` | 1/1 | Redis singleton 기반 실제 servlet/security chain에서 anonymous 401, staff 403, patient의 sanitized 503 경계와 correlation/retry header |
| `PatientCancellationHistoryQueryPlanTest` | 1/1 | PostgreSQL singleton 기반 실제 production-schema join의 keyset 계획과 history index 사용 |
| **합계** | **12/12** | **실패 0, 오류 0, skip 0** |

HTTP 테스트는 Redis singleton을 띄운 Spring Boot `RANDOM_PORT` 컨텍스트에서 실행했으며, patient 요청은 test profile의 feature-disabled 경계를 통해 controller까지 도달한다. 이는 운영 활성화 증거가 아니라 route/security/error-envelope 통합 증거다.

전체 `appointment-api` 모듈도 같은 Colima 환경에서 `./gradlew :appointment-api:test --no-daemon --rerun-tasks`로 fresh 실행했다. `801`개 테스트가 실패 0개로 통과했고, production credential이 필요한 성능 설명 테스트와 MySQL endpoint 확인을 포함한 `3`개만 의도적으로 skip됐다. 이 skip은 repository-level production-like 시뮬레이션 판정을 차단하지 않는다.

전체 모듈 실행에서 공유 H2의 다른 테스트가 기본 tenant row를 바꾼 뒤에도 보안 테스트가 canonical `tenant-default`를 보장하도록 격리 setup을 추가했다. 그 수정 후 focused HTTP와 전체 모듈 실행이 모두 통과했다.

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
| API 전체 | `:appointment-api:test` 801개 통과, 3개 의도적 skip, 실패 0 |
| API build | `:appointment-api:build -x test --no-daemon --rerun-tasks` 성공 |
| API targeted | 환자 이력 focused gate 12/12 통과; production MySQL endpoint 1건은 별도 운영 게이트 |
| Core targeted | `AppointmentCancellationHistoryRepositoryTest`, `AppointmentCancellationDetailsSchemaTest` 통과 |
| Angular | 39개 파일, 266개 테스트 통과; patient cancellation history spec 3개 포함 |
| Angular build | `npm run build` 성공 |
| whitespace | `git diff --check` 통과 |

## 실제 production 운영 게이트(별도 범위)

현재 셸에서 다음 production MySQL 변수는 모두 설정되지 않았다.

```text
APPOINTMENT_PRODUCTION_MYSQL_JDBC_URL=UNSET
APPOINTMENT_PRODUCTION_MYSQL_USER=UNSET
APPOINTMENT_PRODUCTION_MYSQL_PASSWORD=UNSET
```

따라서 production JDBC 연결, 실제 HTTP smoke, preflight count artifact를 시도하지 않았다. 자격증명 없이 임의 endpoint나 production PASS를 만들지 않는 것은 의도적인 범위 경계다. 이 항목은 production-like repository DoD의 차단 사유가 아니다.

향후 실제 운영을 시작할 때 별도 승인과 증거 수집으로 열 수 있는 항목은 다음과 같다.

1. 실제 production MySQL 연결의 migration preflight와 protected HTTP smoke
2. 모든 writer replica의 version fence 관측 및 60초 steady probe/alert artifact
3. shared external token registry의 restart/capacity/readiness 증거
4. production ACL, backup/restore, canary, SLO, rollback rehearsal

## DoD 상태

| 항목 | 상태 | 근거/제약 |
|---|---|---|
| 로컬 구현·targeted test | PASS | 위 12/12 및 인접 게이트 |
| production-like PostgreSQL/Redis 시뮬레이션 | PASS | `PostgreSQLServer.Launcher.postgres`, `RedisServer.Launcher.redis`, fixed fixture, bounded probe |
| 로컬 PostgreSQL query plan | PASS | `idx_cancellation_detail_patient_scope_time`, history `Seq Scan=false` |
| 실제 production activation | N/A | 예제 서비스이며 본 실행에서 본격 운영을 하지 않음; 별도 명시적 운영 게이트로 분리 |
| D8 본격 운영 | N/A | 현재 본격운영을 하지 않음 |
| D9 운영 인력 | N/A | 1인 개발자 범위로 운영 인력 분리 증거를 요구하지 않음 |
| 통합 판정 | **PASS** | repository-level production-like 게이트 통과; 실제 production 게이트는 별도 범위 |

결론적으로 이번 실행은 Issue #305의 구현과 repository-level production-like 검증을 실제 증거로 닫았다. 이는 실제 production 활성화나 운영 트래픽 증거를 주장하지 않으며, 그 증거가 필요해지는 시점에는 별도 운영 게이트로 다시 수집한다.
