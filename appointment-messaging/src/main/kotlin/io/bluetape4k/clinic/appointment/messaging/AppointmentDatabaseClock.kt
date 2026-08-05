package io.bluetape4k.clinic.appointment.messaging

import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime

/** Exposed transaction 안에서 authoritative database timestamp를 읽는 production clock port다. */
fun interface AppointmentDatabaseClock {
    fun now(): Instant

    companion object {
        /** 현재 Exposed transaction의 DB clock을 사용한다. 테스트는 별도 fake를 주입한다. */
        val current: AppointmentDatabaseClock = AppointmentDatabaseClock {
            TransactionManager.current().dbCurrentTimestamp()
        }
    }
}

private fun JdbcTransaction.dbCurrentTimestamp(): Instant =
    exec("SELECT CURRENT_TIMESTAMP") { resultSet ->
        if (!resultSet.next()) error("SELECT CURRENT_TIMESTAMP returned no rows")
        resultSet.getObject(1).toAppointmentDatabaseInstant()
    } ?: error("SELECT CURRENT_TIMESTAMP returned no result set")

private fun Any?.toAppointmentDatabaseInstant(): Instant =
    when (this) {
        is Instant -> this
        is Timestamp -> toInstant()
        is OffsetDateTime -> toInstant()
        is ZonedDateTime -> toInstant()
        is LocalDateTime -> toInstant(ZoneOffset.UTC)
        else -> error("Unsupported CURRENT_TIMESTAMP value: ${this?.javaClass?.name ?: "null"}")
    }
