package io.bluetape4k.clinic.appointment.test

import io.bluetape4k.logging.KLogging
import io.bluetape4k.testcontainers.database.PostgreSQLServer

/** PostgreSQL production SQL과 동시성 계약을 검증하는 singleton launcher입니다. */
object Containers : KLogging() {
    val Postgres: PostgreSQLServer by lazy { PostgreSQLServer.Launcher.postgres }
}
