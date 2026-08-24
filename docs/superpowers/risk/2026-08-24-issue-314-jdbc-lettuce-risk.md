# Issue #314 `jdbc-lettuce` 파일럿 위험 예측

## 범위

이 위험 등록부는 `appointment-api` 테스트 전용 probe와 dependency catalog
변경만 다룬다. production repository, NearCache, API 응답, Redis v3
namespace는 변경 대상이 아니며, 각 위험은 targeted test에서 재현 가능해야
한다.

## 위험 항목

| ID | 위험·신호 | 완화·검증 | rollback/rerun |
|---|---|---|---|
| R1 | `findAll(where)`가 scope-list hit을 제공하지 않아 SQL이 반복됨. 두 번째 candidate 조회의 SELECT 수가 0이 아님. | legacy 첫/두 번째 호출과 candidate `findAll`/`get`을 `StatementInterceptor`로 분리 집계한다. 운영 채택을 `보류`한다. | production 변경 없음. SQL 증거를 lesson에 남기고 필요하면 list-key 후속 Issue로 분리한다. |
| R2 | Redis GET/SET/warm 장애가 test를 지연시키거나 DB 결과를 잃음. 실패 client가 200ms 이상 대기하거나 결과가 null임. | 사용하지 않는 loopback 포트와 200ms timeout을 사용하고, 실패 probe의 `findAll`/`get`이 DB record를 반환하는지 확인한다. | 실패 시 failed client만 `close`/`shutdown`하고 targeted test부터 재실행한다. singleton Redis는 닫지 않는다. |
| R3 | 타입별 key prefix 또는 codec이 기존 `clinic-*-v3` 값과 충돌함. raw key가 `issue314:jdbc-lettuce:<type>:` 밖으로 생성됨. | 타입별 prefix, explicit Jackson3 codec, positive TTL을 raw String command로 확인하고 payload는 출력하지 않는다. | candidate prefix만 clear하고 probe를 제거한다. production namespace는 건드리지 않는다. |
| R4 | test-only artifact가 runtime/bootJar로 유출되거나 lockfile 범위가 넓어짐. `runtimeClasspath`/`jar tf`에 artifact가 나타나거나 lockfile에 runtime scope가 생김. | version catalog alias와 `testImplementation`을 확인하고 `appointment-api/gradle.lockfile`을 `--write-locks`로 갱신한 뒤 runtimeClasspath·bootJar 검사를 순차 실행한다. | dependency scope/lockfile을 복구하고 leakage 검사부터 재실행한다. production build가 PASS하기 전 진행하지 않는다. |
| R5 | fixture가 shared Spring/H2/Redis 상태를 오염시키거나 병렬 실행과 충돌함. 다른 integration test가 schema/data/key assertion에서 실패함. | `AbstractApiIntegrationTest`의 `ResourceLock`/`SAME_THREAD`, 자식 우선 `deleteAll`, 고유 prefix, `@AfterEach` cleanup을 재사용한다. | 해당 class targeted test를 단독 재현하고, cleanup을 고친 뒤 module regression을 처음부터 재실행한다. |
| R6 | `READ_ONLY` probe의 추상 write mapping이 실수로 DB write를 수행함. update/insert mapping이 test에서 호출됨. | `READ_ONLY` 설정, DB write mapping은 API 만족용으로만 두고 `put`/`putAll`을 호출하지 않는다. stale 검증은 직접 DB update/delete 후 `invalidate`로 수행한다. | probe만 제거/수정한다. production writer나 wrapper를 추가하지 않는다. |
| R7 | `close()` cleanup 실패가 원래 assertion을 가림. 종료 중 예외로 원인 분석이 불가능함. | `clear`/`close`를 `runCatching`으로 분리하고 close를 두 번 호출하는 lifecycle assertion을 둔다. reflection으로 내부 상태를 읽지 않는다. | cleanup 실패를 별도 증거로 보존하고 targeted test를 다시 실행한다. |

## 위험 gate

- R1 또는 R3가 실패하면 운영 전환은 자동으로 `보류`다.
- R2, R4, R5, R7 중 하나라도 재현되면 module regression 전까지 PR을 만들지
  않는다.
- 모든 위험이 테스트 evidence로 수렴하고 production diff가 없는 경우에만
  lesson/PR DoD 단계로 이동한다.
