# Issue #305 PostgreSQL concurrent index migration 교훈

## 상황

Issue #305의 V28→V30 취소 이력 migration을 `bluetape4k-testcontainers` singleton
launcher로 PostgreSQL, MySQL 8, H2에서 검증했다. `Containers.Postgres`는
`PostgreSQLServer.Launcher.postgres`를 사용하므로 이 저장소의 Testcontainers 규칙과
동일하다. 테스트 클래스에 `@Testcontainers`를 추가하거나 별도 launcher를 만들 필요가
없었다.

## 재현된 실패

PostgreSQL에서 V29 `CREATE INDEX CONCURRENTLY`가 30초 후 SQLSTATE `57014`로 실패했다.
동시에 다음 두 세션을 관찰했다.

- Flyway transactional advisory lock connection: `idle in transaction`,
  `SELECT COUNT(*) FROM pg_namespace WHERE nspname=$1`
- migration connection: `active`, `wait_event=Lock:virtualxid`, `CREATE INDEX CONCURRENTLY ...`

V29 script의 `executeInTransaction=false`는 migration 자체의 transaction만 끊는다. Flyway
transactional lock connection이 별도 snapshot transaction을 유지하면 concurrent index가
필요로 하는 virtualxid를 계속 기다릴 수 있다.

## 해결책

PostgreSQL profile에 다음 설정을 명시한다.

```yaml
spring:
  flyway:
    postgresql:
      transactional-lock: false
```

migration contract test도 동일하게 `flyway.postgresql.transactional.lock=false`를
configuration map으로 적용한다. 특정 테스트만 통과시키는 환경 변수나 timeout 증가는
원인을 숨기므로 사용하지 않는다. V13 전용 migration contract는 `target("13")`으로
bounded scope를 고정해 최신 V29/V30 index migration을 우연히 실행하지 않게 한다.

## 검증 결과와 경계

- PostgreSQL `FlywayPostgreSQLMigrationTest`: 8/8 통과
- MySQL 8 `FlywayMySQLMigrationTest`: migration 8/8 통과, production endpoint 1건 skip
- H2 `FlywayMigrationTest`: 8/8 통과

이 결과는 로컬 dialect migration 계약을 증명한다. production endpoint, replica writer
version fence, steady probe/alert, shared registry, EXPLAIN·성능, ACL·backup·canary·SLO는
별도 운영 증거가 없으므로 여전히 `PENDING`이다.

## 재발 방지 규칙

1. PostgreSQL `CREATE INDEX CONCURRENTLY` migration은 script의 non-transactional 설정과
   Flyway PostgreSQL transactional lock 설정을 함께 검증한다.
2. dialect smoke는 H2만으로 끝내지 않고 PostgreSQL→MySQL→H2 순서로 실제 singleton을
   순차 실행한다.
3. Testcontainers startup 오류와 migration SQL 오류를 구분한다. Docker socket 오류를
   timeout 증가로 덮지 않고, daemon 환경 변수와 Colima context를 먼저 고정한다.
4. migration smoke 통과를 production activation 증거로 확대 해석하지 않는다.
