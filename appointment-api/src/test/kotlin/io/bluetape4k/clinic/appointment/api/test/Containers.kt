package io.bluetape4k.clinic.appointment.api.test

import io.bluetape4k.logging.KLogging
import io.bluetape4k.testcontainers.database.MySQL8Server
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.testcontainers.storage.RedisServer
import io.bluetape4k.utils.ShutdownQueue
import org.flywaydb.core.Flyway

/**
 * API 통합 테스트용 컨테이너.
 */
object Containers : KLogging() {

    val Redis: RedisServer by lazy { Redis88Launcher.redis }

    val Postgres: PostgreSQLServer by lazy { PostgreSQLServer.Launcher.postgres }

    val MySql8: MySQL8Server by lazy {
        MySQL8Server()
            .apply {
                withCommand(
                    "--character-set-server=utf8mb4",
                    "--collation-server=utf8mb4_bin"
                )
                start()
                ShutdownQueue.register(this)
            }
    }

    /** 공유 PostgreSQL 테스트 schema를 다음 test class를 위해 비웁니다. */
    fun cleanPostgresSchema() {
        val postgres = Postgres
        cleanSchema(postgres.jdbcUrl, postgres.username ?: "test", postgres.password ?: "")
    }

    /** 공유 MySQL 테스트 schema를 다음 test class를 위해 비웁니다. */
    fun cleanMySqlSchema() {
        val mysql = MySql8
        cleanSchema(mysql.jdbcUrl, mysql.username ?: "test", mysql.password ?: "")
    }

    private fun cleanSchema(
        jdbcUrl: String,
        username: String,
        password: String,
    ) {
        Flyway.configure()
            .dataSource(jdbcUrl, username, password)
            .cleanDisabled(false)
            .load()
            .clean()
    }
}
