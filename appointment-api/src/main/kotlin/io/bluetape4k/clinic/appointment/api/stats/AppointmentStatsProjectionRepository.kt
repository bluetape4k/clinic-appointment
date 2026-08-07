package io.bluetape4k.clinic.appointment.api.stats

import io.bluetape4k.clinic.appointment.statemachine.AppointmentState
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.neq
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
        aggregateId: String,
        eventVersion: Long,
        eventId: String,
    ): Boolean {
        require(tenantGroupId > 0 && clinicId > 0) { "projection scope must be positive" }
        require(eventVersion >= 0) { "eventVersion must not be negative" }
        require(aggregateId.isNotBlank() && aggregateId.length <= 128) { "aggregateId must be bounded" }
        require(eventId.isNotBlank() && eventId.length <= 128) { "eventId must be bounded" }

        val latestAggregateVersion = AppointmentStatsProjectionEventTable
            .select(AppointmentStatsProjectionEventTable.eventVersion)
            .where {
                (AppointmentStatsProjectionEventTable.tenantGroupId eq tenantGroupId) and
                    (AppointmentStatsProjectionEventTable.clinicId eq clinicId) and
                    (AppointmentStatsProjectionEventTable.aggregateId eq aggregateId)
            }
            .orderBy(AppointmentStatsProjectionEventTable.eventVersion to org.jetbrains.exposed.v1.core.SortOrder.DESC)
            .limit(1)
            .singleOrNull()
            ?.get(AppointmentStatsProjectionEventTable.eventVersion)
        if (latestAggregateVersion != null && eventVersion <= latestAggregateVersion) return false

        val eventInserted = AppointmentStatsProjectionEventTable.insertIgnore {
            it[AppointmentStatsProjectionEventTable.tenantGroupId] = tenantGroupId
            it[AppointmentStatsProjectionEventTable.clinicId] = clinicId
            it[AppointmentStatsProjectionEventTable.aggregateId] = aggregateId
            it[AppointmentStatsProjectionEventTable.eventId] = eventId
            it[AppointmentStatsProjectionEventTable.eventVersion] = eventVersion
            it[AppointmentStatsProjectionEventTable.eventDate] = eventDate
            it[AppointmentStatsProjectionEventTable.status] = status
        }.insertedCount == 1
        if (!eventInserted) return false

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

        // 같은 aggregate의 더 높거나 같은 version이 먼저 반영된 경우에는
        // dashboard bucket을 다시 증가시키지 않습니다. 다른 aggregate의 version과
        // bucket-level lastEventVersion을 비교하면 정상 event를 잃으므로 aggregate로
        // 한정한 ledger 조회를 사용합니다.
        val newerAggregateEventExists = AppointmentStatsProjectionEventTable
            .select(AppointmentStatsProjectionEventTable.eventId)
            .where {
                (AppointmentStatsProjectionEventTable.tenantGroupId eq tenantGroupId) and
                    (AppointmentStatsProjectionEventTable.clinicId eq clinicId) and
                    (AppointmentStatsProjectionEventTable.aggregateId eq aggregateId) and
                    (AppointmentStatsProjectionEventTable.eventVersion greaterEq eventVersion) and
                    (AppointmentStatsProjectionEventTable.eventId neq eventId)
            }
            .limit(1)
            .singleOrNull() != null
        if (newerAggregateEventExists) return false

        AppointmentStatsProjectionTable.update({ predicate }) {
            it[AppointmentStatsProjectionTable.appointmentCount] = existing[AppointmentStatsProjectionTable.appointmentCount] + 1L
            if (eventVersion >= existing[AppointmentStatsProjectionTable.lastEventVersion]) {
                it[AppointmentStatsProjectionTable.lastEventVersion] = eventVersion
                it[AppointmentStatsProjectionTable.lastEventId] = eventId
            }
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
