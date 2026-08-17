package io.bluetape4k.clinic.appointment.test

import io.bluetape4k.jdbc.JdbcDrivers
import io.bluetape4k.logging.KLogging
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.jdbc.Database
import java.sql.Connection

/**
 * 빠른 순수 단위·배선 테스트에서 H2만 사용할지 여부입니다.
 *
 * Gradle 실행 시 `-PuseFastDB=true` 로 지정하면 H2만 테스트합니다.
 * 예: `./gradlew test -PuseFastDB=true`
 */
val useFastDB: Boolean = System.getProperty("exposed.test.useFastDB", "false").toBoolean()

/** Exposed 테스트가 사용하는 H2 fixture와 PostgreSQL singleton launcher를 제공합니다. */
enum class TestDB(
    val connection: () -> String,
    val driver: String,
    val user: String = "test",
    val pass: String = "test",
    val beforeConnection: () -> Unit = {},
    val afterConnection: (connection: Connection) -> Unit = {},
    val afterTestFinished: () -> Unit = {},
    val dbConfig: DatabaseConfig.Builder.() -> Unit = {},
) {
    /** 빠른 순수 단위·배선 테스트용 H2입니다. */
    H2(
        connection = { "jdbc:h2:mem:regular-v2;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;" },
        driver = JdbcDrivers.DRIVER_CLASS_H2,
        dbConfig = {
            defaultIsolationLevel = Connection.TRANSACTION_READ_COMMITTED
        }
    ),

    /** 방문 commitment fixture가 공유 H2 schema와 충돌하지 않도록 분리한 H2입니다. */
    H2_COMMITMENT(
        connection = {
            "jdbc:h2:mem:visit-commitment;MODE=PostgreSQL;" +
                "DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;" +
                "DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;"
        },
        driver = JdbcDrivers.DRIVER_CLASS_H2,
        dbConfig = {
            defaultIsolationLevel = Connection.TRANSACTION_READ_COMMITTED
        },
    ),

    /** PostgreSQL production SQL과 동시성 계약을 검증하는 singleton Testcontainers fixture입니다. */
    POSTGRESQL(
        connection = {
            Containers.Postgres.jdbcUrl + "?lc_messages=en_US.UTF-8"
        },
        driver = JdbcDrivers.DRIVER_CLASS_POSTGRESQL,
        afterConnection = { connection ->
            connection.createStatement().use { stmt ->
                stmt.execute("SET TIMEZONE='UTC';")
            }
        }
    );

    @Volatile
    var db: Database? = null

    fun connect(configure: DatabaseConfig.Builder.() -> Unit = {}): Database {
        val config = DatabaseConfig {
            dbConfig()
            configure()
        }

        return Database.connect(
            url = connection(),
            databaseConfig = config,
            user = user,
            password = pass,
            driver = driver,
            setupConnection = { afterConnection(it) }
        )
    }

    companion object : KLogging() {
        val ALL_H2 = setOf(H2, H2_COMMITMENT)
        val ALL_POSTGRES = setOf(POSTGRESQL)
        val ALL = entries.toSet()

        /**
         * 테스트 대상 DB 목록을 반환합니다.
         *
         * 우선순위:
         * 1. `-PuseDB=H2,POSTGRESQL`로 명시적 지정
         * 2. `-PuseFastDB=true`이면 H2만 실행
         * 3. 기본값은 H2와 PostgreSQL
         */
        fun enabledDialects(): Set<TestDB> {
            val useDB = System.getProperty("exposed.test.useDB")
            if (!useDB.isNullOrBlank()) {
                return useDB
                    .split(",")
                    .map { it.trim() }
                    .mapNotNull { name -> entries.find { it.name.equals(name, true) } }
                    .toSet()
                    .ifEmpty { setOf(H2) }
            }
            return if (useFastDB) {
                setOf(H2)
            } else {
                setOf(H2, POSTGRESQL)
            }
        }
    }
}
