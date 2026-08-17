# Issue #308 PostgreSQL 단일 코어 계약 Step 3-R 계획 검토

## 1. 검토 범위와 기준

- 대상 계획: `docs/superpowers/plans/2026-08-17-issue-308-postgresql-core-contract-plan.md`
- 승인 설계: `docs/superpowers/specs/2026-08-17-issue-308-postgresql-core-contract-design.md`
- 기준 ref: `260a40973f5df30baa5209a2e0e99155bf8e812f`
- 대상 branch: `chore/issue-308-postgresql-core-contract`
- 검토 단계: Type-A Step 3-R

계획은 API/Flyway Slice B와 운영 배포 증거를 현재 슬라이스에서 제외하고, core/event/messaging의 source·test contract만 변경한다.

## 2. 독립 관점 검토

| 관점 | 확인 내용 | 판정 |
|---|---|---:|
| 성능 | MySQL/MariaDB/Cockroach matrix 제거, PostgreSQL Testcontainers는 순차 실행, 별도 benchmark는 범위 밖 N/A로 명시했다. | P0=0, P1=0 |
| 안정성 | H2 lock fixture를 PostgreSQL fixture로 옮기고 launcher/Docker 실패를 skip으로 숨기지 않으며, lock timeout 복원 경계를 점검한다. | P0=0, P1=0 |
| 보안 | MySQL session 변수와 vendor SQL 분기를 제거하고 기존 parameterized Exposed 경로·opaque ID를 보존한다. 새 trust boundary는 없다. | P0=0, P1=0 |
| 운영 | API profile/Flyway와 외부 deployment/canary/SLO는 Slice B 또는 N/A로 분리했다. rollback은 branch 미merge 또는 revert로 가능하다. | P0=0, P1=0 |
| 개발자/API | public repository signature와 domain 결과를 유지하고, 정확한 파일·task·검증 command를 순서대로 배치했다. | P0=0, P1=0 |
| 호출자 | H2가 production dialect 증거가 아님을 test/KDoc 이름으로 명시하고, API README 갱신은 Slice B로 연결했다. | P0=0, P1=0 |

## 3. 계획 계약 점검

| 점검 항목 | 근거 | 결과 |
|---|---|---|
| 설계 요구사항 매핑 | 설계의 TestDB 축소, production branch 제거, PG singleton, H2 제한, Slice B 경계를 Task 2~6과 Task 8에 매핑했다. | PASS |
| 실행 순서 | RED inventory → fixture/dependency → core → waitlist → event/messaging → PG evidence → full validation 순서다. | PASS |
| 테스트 경로 | H2 unit, core targeted test, event/messaging targeted test, PostgreSQL Testcontainers와 `--no-parallel` 명령을 명시했다. | PASS |
| 실패·복구 | Docker/Colima, Exposed API, H2 capability, unsupported backend의 대응을 설계와 계획에 기록했다. | PASS |
| 문서·언어 | 한국어 문서, exact API/command token, Lore commit, PR `## DoD Status`와 Slice B N/A를 포함한다. | PASS |
| 범위 수렴 | API/Flyway/migration directory, catalog alias 삭제, workflow 변경을 의도적으로 제외했다. | PASS |

## 4. 발견사항

P0/P1 차단 사항은 없다. 다음 P2 범위는 계획에 이미 처리 방법이 있다.

1. H2 기반 waitlist claim test는 PostgreSQL로 이동하므로 Docker가 없는 로컬에서는 targeted evidence가 실행되지 않는다. 이는 테스트 실패를 숨기지 않고 infrastructure blocker로 보고한다.
2. Exposed migration generator의 build-time H2 URL은 runtime 지원과 다른 경계다. Slice B의 Flyway directory 삭제 때 generator 영향이 없는지 다시 확인한다.
3. API 모듈의 MySQL/Flyway/Testcontainers catalog alias는 Slice A에서 미사용으로 단정하지 않는다. Slice B에서 source 제거 후 alias를 정리한다.

## 5. 문서 품질 gate

- **SPW-01 PASS** — 계획·설계·기준 ref·파일 범위·외부 운영 증거 N/A를 고정했다.
- **SPW-02 PASS** — exact task order, files, RED/GREEN, failure/recovery, rollback, verification, approval/merge gate를 포함했다.
- **SPW-03 PASS** — 한국어 기술 문체, 일관된 PostgreSQL/H2/Testcontainers 용어, 명령·식별자 보존을 확인했다. `references/korean-naturalness-checklist.md` KO-01~KO-06을 적용했다.
- **SPW-04 PASS** — 실제 `TestDB.kt`, launcher, core repository 4곳, event 2곳, messaging outbox, build 파일의 현재 구조와 계획을 대조했다.
- **SPW-05 PASS** — 표·목록·code fence·P0/P1 판정을 다시 읽었고, 계획과 설계가 같은 Slice A 경계를 가리킨다.

## 6. 최종 판정

- P0: `0`
- P1: `0`
- P2: 구현 중 fresh evidence로 닫거나 N/A로 기록
- P3: 없음

계획은 구현 단계로 진행할 수 있다. 구현 중 API/Flyway 또는 외부 운영 범위가 필요해지면 현재 계획을 확장하지 않고 Slice B 이슈/계획으로 분리한다.
