package io.bluetape4k.clinic.appointment.api.test

import io.bluetape4k.logging.KLogging
import io.bluetape4k.testcontainers.database.MySQL8Server
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.utils.ShutdownQueue

/**
 * API 통합 테스트용 DB 컨테이너.
 */
object Containers : KLogging() {

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
}
