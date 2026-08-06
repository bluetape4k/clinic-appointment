package io.bluetape4k.clinic.appointment.api.stats

import io.bluetape4k.clinic.appointment.statemachine.AppointmentState
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import java.time.LocalDate

data class AppointmentStatsProjectionRow(
    val date: LocalDate,
    val status: AppointmentState,
    val count: Long,
)

/** Tenant-scoped stats projection의 fenced upsert와 dashboard read를 제공합니다. */
class AppointmentStatsProjectionRepository {
    /** 호출자는 반드시 Exposed [org.jetbrains.exposed.v1.jdbc.transactions.transaction] 안에서 실행해야 합니다. */
    fun upsert(
        tenantGroupId: Long,
        clinicId: Long,
        eventDate: LocalDate,
        status: AppointmentState,
        eventVersion: Long,
        eventId: String,
    ): Boolean {
        require(tenantGroupId > 0 && clinicId > 0) { "projection scope must be positive" }
        require(eventVersion >= 0) { "eventVersion must not be negative" }
        require(eventId.isNotBlank() && eventId.length <= 128) { "eventId must be bounded" }

        val inserted = AppointmentStatsProjectionTable.insertIgnore {
            it[AppointmentStatsProjectionTable.tenantGroupId] = tenantGroupId
            it[AppointmentStatsProjectionTable.clinicId] = clinicId
            it[AppointmentStatsProjectionTable.eventDate] = eventDate
            it[AppointmentStatsProjectionTable.status] = status
            it[AppointmentStatsProjectionTable.appointmentCount] = 1L
            it[AppointmentStatsProjectionTable.lastEventVersion] = eventVersion
            it[AppointmentStatsProjectionTable.lastEventId] = eventId
        }.insertedCount == 1
        if (inserted) return true

        val predicate: Op<Boolean> =
            (AppointmentStatsProjectionTable.tenantGroupId eq tenantGroupId) and
                (AppointmentStatsProjectionTable.clinicId eq clinicId) and
                (AppointmentStatsProjectionTable.eventDate eq eventDate) and
                (AppointmentStatsProjectionTable.status eq status)
        val existing = AppointmentStatsProjectionTable
            .select(
                AppointmentStatsProjectionTable.appointmentCount,
                AppointmentStatsProjectionTable.lastEventVersion,
                AppointmentStatsProjectionTable.lastEventId,
            )
            .where { predicate }
            .forUpdate()
            .single()

        if (existing[AppointmentStatsProjectionTable.lastEventId] == eventId ||
            eventVersion < existing[AppointmentStatsProjectionTable.lastEventVersion]
        ) {
            return false
        }

        AppointmentStatsProjectionTable.update({ predicate }) {
            it[AppointmentStatsProjectionTable.appointmentCount] = existing[AppointmentStatsProjectionTable.appointmentCount] + 1L
            it[AppointmentStatsProjectionTable.lastEventVersion] = eventVersion
            it[AppointmentStatsProjectionTable.lastEventId] = eventId
        }
        return true
    }

    fun countByDateAndStatus(
        tenantGroupId: Long,
        clinicId: Long,
        dateRange: ClosedRange<LocalDate>,
        statuses: List<AppointmentState>? = null,
    ): List<AppointmentStatsProjectionRow> {
        require(tenantGroupId > 0 && clinicId > 0) { "projection scope must be positive" }
        return AppointmentStatsProjectionTable
            .select(
                AppointmentStatsProjectionTable.eventDate,
                AppointmentStatsProjectionTable.status,
                AppointmentStatsProjectionTable.appointmentCount,
            )
            .where {
                (AppointmentStatsProjectionTable.tenantGroupId eq tenantGroupId) and
                    (AppointmentStatsProjectionTable.clinicId eq clinicId)
            }
            .andWhere { AppointmentStatsProjectionTable.eventDate greaterEq dateRange.start }
            .andWhere { AppointmentStatsProjectionTable.eventDate lessEq dateRange.endInclusive }
            .let { query ->
                if (statuses != null) query.andWhere { AppointmentStatsProjectionTable.status inList statuses }
                else query
            }
            .map {
                AppointmentStatsProjectionRow(
                    date = it[AppointmentStatsProjectionTable.eventDate],
                    status = it[AppointmentStatsProjectionTable.status],
                    count = it[AppointmentStatsProjectionTable.appointmentCount],
                )
            }
    }
}
