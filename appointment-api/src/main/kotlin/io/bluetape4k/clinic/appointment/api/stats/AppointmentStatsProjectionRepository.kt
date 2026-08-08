package io.bluetape4k.clinic.appointment.api.stats

import io.bluetape4k.clinic.appointment.statemachine.AppointmentState
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import java.time.LocalDate

data class AppointmentStatsProjectionRow(
    val date: LocalDate,
    val status: AppointmentState,
    val count: Long,
)

/** Tenant-scoped stats projection의 aggregate-locking upsert와 event-state read를 제공합니다. */
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

        lockAggregate(tenantGroupId, clinicId, aggregateId)
        val previous = latestEvent(tenantGroupId, clinicId, aggregateId)
        if (previous != null && eventVersion <= previous.eventVersion) return false

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

        val bucketChanged = previous == null || previous.eventDate != eventDate || previous.status != status
        if (previous != null && bucketChanged) {
            decrementBucket(tenantGroupId, clinicId, previous.eventDate, previous.status)
        }
        if (bucketChanged) {
            incrementBucket(tenantGroupId, clinicId, eventDate, status, eventVersion, eventId)
        } else {
            touchBucket(tenantGroupId, clinicId, eventDate, status, eventVersion, eventId)
        }
        return true
    }

    /** 같은 aggregate의 최초 event도 경합하지 않도록 durable lock row를 먼저 확보합니다. */
    private fun lockAggregate(
        tenantGroupId: Long,
        clinicId: Long,
        aggregateId: String,
    ) {
        val predicate =
            (AppointmentStatsProjectionAggregateLockTable.tenantGroupId eq tenantGroupId) and
                (AppointmentStatsProjectionAggregateLockTable.clinicId eq clinicId) and
                (AppointmentStatsProjectionAggregateLockTable.aggregateId eq aggregateId)
        AppointmentStatsProjectionAggregateLockTable.insertIgnore {
            it[AppointmentStatsProjectionAggregateLockTable.tenantGroupId] = tenantGroupId
            it[AppointmentStatsProjectionAggregateLockTable.clinicId] = clinicId
            it[AppointmentStatsProjectionAggregateLockTable.aggregateId] = aggregateId
        }
        AppointmentStatsProjectionAggregateLockTable
            .select(AppointmentStatsProjectionAggregateLockTable.aggregateId)
            .where { predicate }
            .forUpdate()
            .single()
    }

    private fun latestEvent(
        tenantGroupId: Long,
        clinicId: Long,
        aggregateId: String,
    ): LatestProjectionEvent? = AppointmentStatsProjectionEventTable
        .select(
            AppointmentStatsProjectionEventTable.eventDate,
            AppointmentStatsProjectionEventTable.status,
            AppointmentStatsProjectionEventTable.eventVersion,
        )
        .where {
            (AppointmentStatsProjectionEventTable.tenantGroupId eq tenantGroupId) and
                (AppointmentStatsProjectionEventTable.clinicId eq clinicId) and
                (AppointmentStatsProjectionEventTable.aggregateId eq aggregateId)
        }
        .orderBy(AppointmentStatsProjectionEventTable.eventVersion to SortOrder.DESC)
        .limit(1)
        .singleOrNull()
        ?.let {
            LatestProjectionEvent(
                eventDate = it[AppointmentStatsProjectionEventTable.eventDate],
                status = it[AppointmentStatsProjectionEventTable.status],
                eventVersion = it[AppointmentStatsProjectionEventTable.eventVersion],
            )
        }

    private fun incrementBucket(
        tenantGroupId: Long,
        clinicId: Long,
        eventDate: LocalDate,
        status: AppointmentState,
        eventVersion: Long,
        eventId: String,
    ) {
        val predicate = bucketPredicate(tenantGroupId, clinicId, eventDate, status)
        val inserted = AppointmentStatsProjectionTable.insertIgnore {
            it[AppointmentStatsProjectionTable.tenantGroupId] = tenantGroupId
            it[AppointmentStatsProjectionTable.clinicId] = clinicId
            it[AppointmentStatsProjectionTable.eventDate] = eventDate
            it[AppointmentStatsProjectionTable.status] = status
            it[AppointmentStatsProjectionTable.appointmentCount] = 1L
            it[AppointmentStatsProjectionTable.lastEventVersion] = eventVersion
            it[AppointmentStatsProjectionTable.lastEventId] = eventId
        }.insertedCount == 1
        if (inserted) return

        val existing = AppointmentStatsProjectionTable
            .select(
                AppointmentStatsProjectionTable.appointmentCount,
                AppointmentStatsProjectionTable.lastEventVersion,
            )
            .where { predicate }
            .forUpdate()
            .single()
        AppointmentStatsProjectionTable.update({ predicate }) {
            it[AppointmentStatsProjectionTable.appointmentCount] = existing[AppointmentStatsProjectionTable.appointmentCount] + 1L
            if (eventVersion >= existing[AppointmentStatsProjectionTable.lastEventVersion]) {
                it[AppointmentStatsProjectionTable.lastEventVersion] = eventVersion
                it[AppointmentStatsProjectionTable.lastEventId] = eventId
            }
        }
    }

    private fun touchBucket(
        tenantGroupId: Long,
        clinicId: Long,
        eventDate: LocalDate,
        status: AppointmentState,
        eventVersion: Long,
        eventId: String,
    ) {
        val predicate = bucketPredicate(tenantGroupId, clinicId, eventDate, status)
        val updated = AppointmentStatsProjectionTable.update({ predicate }) {
            it[AppointmentStatsProjectionTable.lastEventVersion] = eventVersion
            it[AppointmentStatsProjectionTable.lastEventId] = eventId
        }
        check(updated == 1) {
            "projection bucket missing for aggregate state: tenantGroupId=$tenantGroupId, " +
                "clinicId=$clinicId, eventDate=$eventDate, status=$status"
        }
    }

    private fun decrementBucket(
        tenantGroupId: Long,
        clinicId: Long,
        eventDate: LocalDate,
        status: AppointmentState,
    ) {
        val predicate = bucketPredicate(tenantGroupId, clinicId, eventDate, status)
        val existing = AppointmentStatsProjectionTable
            .select(AppointmentStatsProjectionTable.appointmentCount)
            .where { predicate }
            .forUpdate()
            .singleOrNull()
            ?: error(
                "projection bucket missing for aggregate state: tenantGroupId=$tenantGroupId, " +
                    "clinicId=$clinicId, eventDate=$eventDate, status=$status",
            )
        val count = existing[AppointmentStatsProjectionTable.appointmentCount]
        if (count <= 1L) {
            AppointmentStatsProjectionTable.deleteWhere { predicate }
        } else {
            AppointmentStatsProjectionTable.update({ predicate }) {
                it[AppointmentStatsProjectionTable.appointmentCount] = count - 1L
            }
        }
    }

    private fun bucketPredicate(
        tenantGroupId: Long,
        clinicId: Long,
        eventDate: LocalDate,
        status: AppointmentState,
    ): Op<Boolean> =
        (AppointmentStatsProjectionTable.tenantGroupId eq tenantGroupId) and
            (AppointmentStatsProjectionTable.clinicId eq clinicId) and
            (AppointmentStatsProjectionTable.eventDate eq eventDate) and
            (AppointmentStatsProjectionTable.status eq status)

    private data class LatestProjectionEvent(
        val eventDate: LocalDate,
        val status: AppointmentState,
        val eventVersion: Long,
    )

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
