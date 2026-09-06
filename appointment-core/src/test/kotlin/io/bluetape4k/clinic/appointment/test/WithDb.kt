package io.bluetape4k.clinic.appointment.test

import io.bluetape4k.exposed.tests.withDb as providerWithDb
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction

/**
 * 지정된 [testDB]에 대해 트랜잭션 블록을 실행합니다.
 *
 * generic JDBC fixture lifecycle은 provider에 위임합니다. H2 commitment schema와
 * PostgreSQL launcher의 선택·정리는 [TestDB]와 [withTables]가 계속 소유합니다.
 *
 * @param testDB 사용할 테스트 데이터베이스
 * @param configure 데이터베이스 설정 빌더 람다 (선택사항)
 * @param statement 트랜잭션 내에서 실행할 블록
 */
fun withDb(
    testDB: TestDB,
    configure: (DatabaseConfig.Builder.() -> Unit)? = null,
    statement: JdbcTransaction.(TestDB) -> Unit,
) = providerWithDb(testDB.fixture, configure, statement)
