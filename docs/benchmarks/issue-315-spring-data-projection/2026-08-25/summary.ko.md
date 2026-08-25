# Issue #315 후속 검증 결과

## 결론

기존 test-only Spring Data projection 파일럿에 pool 동시성, 실제 SQL 컬럼
범위, 기존 인가 checker 경계를 추가로 검증했다. PostgreSQL Hikari pool은
2개 커넥션을 유지한 상태에서 4개 worker를 대기시킨 뒤 모두 완료했고, 네
호출의 결과는 동일했다. 반면 `bluetape4k-exposed-spring-boot-jdbc:1.12.1`
repository는 `Clinics` 8개 컬럼을 모두 읽는 full-row DAO SQL만 만들었다.
column-level projection capability는 현재 artifact에서 확인되지 않았다.

따라서 결과·tenant 격리·인가 경계는 보강됐지만, full-row 비용과 기존
benchmark의 candidate 성능 열세 때문에 production repository 교체와 운영
채택은 계속 **보류**한다. 실제 authenticated route 연결은 candidate가
test-only이므로 이번 범위의 적용 대상이 아니다.

## 후속 게이트

| 게이트 | 증거 | 상태 |
|---|---|---|
| PostgreSQL pool contention | Hikari `maximumPoolSize=2`, 4 workers, 2개 holder를 해제한 뒤 4개 호출 완료 | PASS |
| 결과 보존 | 4개 호출의 `ClinicRecord` 결과 equality | PASS |
| pool latency 기록 | `min=11,791,625ns`, `median=12,972,250ns`, `p95=16,407,542ns` | PASS |
| full-row SQL 범위 | 1.12.1 repository가 `Clinics` 8/8 컬럼을 선택 | PASS |
| column-level projection | 현재 published artifact에서 별도 column projection capability 없음 | **NOT_AVAILABLE** |
| tenant predicate | candidate 조회와 기존 checker가 tenant 범위를 유지 | PASS |
| clinic allow-list | 허용 clinic 1건, 다른 clinic 거부 1건 | PASS |
| tenant allow-list | 다른 tenant 거부 1건 | PASS |
| workforce role | `PATIENT` role의 clinic 조회 거부 1건 | PASS |
| authenticated route 통합 | candidate가 test-only adapter라 route wiring 없음 | **N/A** |
| 운영 채택 | full-row 비용·기존 candidate 성능 열세·route 미연결 | **보류** |

기본 H2 실행에서는 Hikari PostgreSQL pool이 없어 contention을 측정하지
않으며 `NOT_TESTED`를 출력한다. pool 수치와 contention 판정은
`test-postgresql` 실행 결과만 근거로 삼는다.

## 재현과 산출물

- raw: [`raw/followup-run.txt`](raw/followup-run.txt)
- 테스트: [`ClinicSpringDataProjectionPilotTest.kt`](../../../../appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/projection/ClinicSpringDataProjectionPilotTest.kt)
- 기존 baseline: [`2026-08-23/summary.ko.md`](../2026-08-23/summary.ko.md)

```bash
./gradlew --no-daemon :appointment-api:test --tests \
  "io.bluetape4k.clinic.appointment.api.projection.ClinicSpringDataProjectionPilotTest" --rerun-tasks

./gradlew --no-daemon :appointment-api:test \
  -Dspring.profiles.active=test-postgresql \
  --tests "io.bluetape4k.clinic.appointment.api.projection.ClinicSpringDataProjectionPilotTest" \
  --rerun-tasks
```

두 프로파일 모두 10개 테스트가 통과했고, PostgreSQL context close 뒤 고유
schema drop과 Exposed global `Database` 복원 검증도 기존 fixture에서 계속
수행했다. 운영 코드, public API, runtime dependency, schema migration은
변경하지 않았다.
