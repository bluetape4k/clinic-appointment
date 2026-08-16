# Issue #334 Solver planning fact fence Step 6-R 코드 리뷰

## 검토 범위와 기준

- 모듈 slice: `appointment-solver`
- 기준 커밋: `90e50da4b49e35d667911418cc9578ab538898e3`
- 검토 대상 HEAD: `2c821ed0` (`advisory snapshot 날짜 범위 검증을 명시한다`)
- 대상: `SolverResult`, `SolverService`, canonical planning-fact hasher, H2 회귀,
  `PostgreSQLServer.Launcher.postgres` 동시성 테스트, test dependency 변경
- 제외: HTTP 인증/인가, Flyway migration, 배포·릴리스, 실제 운영 데이터

## fresh verification 증거

| 명령 | 결과 |
|---|---|
| `./gradlew :appointment-solver:test --no-build-cache --no-daemon` | 10 suites / 98 tests / skipped 0 / failures 0 / errors 0 |
| `./gradlew :appointment-solver:build --no-build-cache --no-daemon` | `BUILD SUCCESSFUL` |
| `./gradlew :appointment-solver:test --tests 'io.bluetape4k.clinic.appointment.solver.service.SolverServicePostgresConcurrencyTest' --no-build-cache` | PostgreSQL 2 tests passing |
| `colima status; docker context show; docker info` | Colima running, context `default`, Docker 28.4.0 |
| 금지 패턴·미완료 표식·`git diff --check` scan | 출력 없음 |

전체 테스트 수는 현재 `appointment-solver/build/test-results/test/TEST-*.xml`의
`testsuite` 10개를 합산했다. 첫 전체 실행에서 daemon이 benchmark 단계에서 종료되어
`--no-daemon`으로 재실행했으며, 재실행 결과를 최종 증거로 사용한다.

## 여섯 관점 독립 검토

| 관점 | P0 | P1 | P2 | P3 | 판정 |
|---|---:|---:|---:|---:|---|
| 성능 | 0 | 0 | 1 | 0 | apply의 snapshot 재조회·canonical hash 비용을 stale 안전성에 대한 의도된 비용으로 기록 |
| 안정성 | 0 | 0 | 1 | 0 | SQLSTATE `40001`/`40P01` 전용 단위 주입 테스트는 미구현이지만, 예외 분류·PostgreSQL lock/CAS 경계는 검증 |
| 보안 | 0 | 0 | 0 | 0 | 변경된 입력 경계·권한·직렬화 취약점 없음. N/A |
| 운영/Ops | 0 | 0 | 0 | 0 | 배포 surface와 migration이 없는 예제 모듈. Testcontainers consistency simulation으로 한정 |
| 개발자/API | 0 | 0 | 0 | 0 | `SolverResult` metadata 기본값·legacy reject·한국어 KDoc·기존 CAS 호환 확인 |
| 사용자/호출자 | 0 | 0 | 0 | 0 | HTTP/README contract가 아닌 solver 내부 결과 계약. KDoc가 misuse 경계를 설명 |

### P2 후속 기록

1. [성능] `SolverService.kt:158-174`는 appointment row lock 전후로 snapshot을 두 번
   읽어 hash를 비교한다. 각 snapshot은 여러 Exposed 조회를 수행하지만, issue의 stale
   방지 우선순위와 현재 예제 규모를 고려해 이번 범위에서는 허용한다. 대규모 일정량을
   도입할 때 snapshot query count/latency benchmark를 별도 이슈로 만든다.
2. [안정성] `SolverService.kt:197-199`는 cause chain과 `SQLException.nextException`의
   SQLSTATE `40001`/`40P01`만 `false`로 수렴한다. 실제 PostgreSQL test는 hash mismatch와
   appointment lock/CAS를 증명했지만, 강제 serialization/deadlock 예외 주입은 포함하지
   않았다. 예상하지 못한 SQL 예외를 삼키지 않는 현재 동작은 유지하고, retry/재현 harness가
   필요해질 때 별도 테스트를 추가한다.

## 현재 세션 통합 판정

- 변경된 모듈 slice: `appointment-solver` 하나
- 요구사항·설계·계획과 코드/테스트 traceability: `SolverResult.kt`, `SolverService.kt`,
  `PlanningFactVersionHasher.kt`, H2 12개 fact 변이 test, PostgreSQL 2개 test
- 기존 appointment source version/CAS, duplicate rollback, pinned/empty/no-score 회귀:
  전체 suite에 포함되어 통과
- `@Testcontainers`/`GenericContainer` 미사용, singleton launcher만 사용
- P0 = 0, P1 = 0, P2 = 2(위 rationale로 deferred), P3 = 0

최종 review gate는 `P0 = 0, P1 = 0`으로 수렴했다. P2는 숨기지 않고 본 문서와
lesson에 영향·후속 조건을 기록했으며, PR merge를 막는 blocker로 분류하지 않는다.
