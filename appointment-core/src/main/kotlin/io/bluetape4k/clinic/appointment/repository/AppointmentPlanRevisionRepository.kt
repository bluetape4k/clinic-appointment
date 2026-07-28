package io.bluetape4k.clinic.appointment.repository

import io.bluetape4k.clinic.appointment.model.dto.AppointmentPlanRevisionAggregateRecord
import io.bluetape4k.clinic.appointment.model.dto.PersistedAppointmentPlanRevisionAggregateRecord
import io.bluetape4k.clinic.appointment.model.dto.PersistedAppointmentPlanRevisionRecord
import io.bluetape4k.clinic.appointment.model.dto.PlanRevisionDependencyRecord
import io.bluetape4k.clinic.appointment.model.dto.PlanRevisionTreatmentRecord
import io.bluetape4k.clinic.appointment.model.tables.AppointmentPlanRevisions
import io.bluetape4k.clinic.appointment.model.tables.PlanRevisionDependencies
import io.bluetape4k.clinic.appointment.model.tables.PlanRevisionTreatments
import io.bluetape4k.support.requirePositiveNumber
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

/**
 * caller transaction 안에서 동일 구매 Plan의 불변 revision을 append하고 활성화합니다.
 */
class AppointmentPlanRevisionRepository {

    /**
     * header와 treatment/dependency child를 한 transaction primitive로 저장합니다.
     */
    fun append(
        aggregate: AppointmentPlanRevisionAggregateRecord,
    ): PersistedAppointmentPlanRevisionAggregateRecord {
        val revision = aggregate.revision
        val treatmentKeys = aggregate.treatments.map(PlanRevisionTreatmentRecord::treatmentKey)
        require(treatmentKeys.isNotEmpty()) { "revision treatments must not be empty" }
        require(treatmentKeys.size == treatmentKeys.toSet().size) {
            "revision treatment keys must be unique"
        }
        val knownTreatmentKeys = treatmentKeys.toSet()
        require(
            aggregate.dependencies.all {
                it.predecessorTreatmentKey in knownTreatmentKeys &&
                    it.successorTreatmentKey in knownTreatmentKeys
            },
        ) {
            "revision dependency must reference treatments in the same revision"
        }
        if (revision.active) {
            require(
                AppointmentPlanRevisions.selectAll().where {
                    (AppointmentPlanRevisions.planId eq revision.planId) and
                        (AppointmentPlanRevisions.active eq true)
                }.count() == 0L,
            ) {
                "plan already has an active revision"
            }
        }

        val revisionId = AppointmentPlanRevisions.insertAndGetId {
            it[planId] = revision.planId
            it[AppointmentPlanRevisions.revision] = revision.revision
            it[productVersionId] = revision.productVersionId
            it[snapshotHash] = revision.snapshotHash
            it[active] = revision.active
        }.value
        PlanRevisionTreatments.batchInsert(aggregate.treatments) { treatment ->
            this[PlanRevisionTreatments.planRevisionId] = revisionId
            this[PlanRevisionTreatments.treatmentKey] = treatment.treatmentKey
            this[PlanRevisionTreatments.componentProductId] = treatment.componentProductId
            this[PlanRevisionTreatments.componentProductVersionId] = treatment.componentProductVersionId
            this[PlanRevisionTreatments.productVersionId] = treatment.productVersionId
            this[PlanRevisionTreatments.status] = treatment.status
        }
        PlanRevisionDependencies.batchInsert(
            aggregate.dependencies,
            shouldReturnGeneratedValues = false,
        ) { dependency ->
            this[PlanRevisionDependencies.planRevisionId] = revisionId
            this[PlanRevisionDependencies.predecessorTreatmentKey] = dependency.predecessorTreatmentKey
            this[PlanRevisionDependencies.successorTreatmentKey] = dependency.successorTreatmentKey
            this[PlanRevisionDependencies.type] = dependency.type
        }
        return requireNotNull(findById(revisionId))
    }

    /** 현재 활성 revision과 child를 반환합니다. */
    fun findActive(planId: Long): PersistedAppointmentPlanRevisionAggregateRecord? {
        planId.requirePositiveNumber("planId")
        return AppointmentPlanRevisions
            .selectAll()
            .where {
                (AppointmentPlanRevisions.planId eq planId) and
                    (AppointmentPlanRevisions.active eq true)
            }
            .singleOrNull()
            ?.let { findById(it[AppointmentPlanRevisions.id].value) }
    }

    /**
     * 현재 활성 row를 잠그고 expected ID가 일치할 때만 새 revision을 활성화합니다.
     */
    fun activate(
        planId: Long,
        expectedActiveRevisionId: Long,
        newRevisionId: Long,
    ): Boolean {
        planId.requirePositiveNumber("planId")
        expectedActiveRevisionId.requirePositiveNumber("expectedActiveRevisionId")
        newRevisionId.requirePositiveNumber("newRevisionId")
        val current = AppointmentPlanRevisions
            .selectAll()
            .where {
                (AppointmentPlanRevisions.planId eq planId) and
                    (AppointmentPlanRevisions.active eq true)
            }
            .forUpdate()
            .singleOrNull()
            ?: return false
        if (current[AppointmentPlanRevisions.id].value != expectedActiveRevisionId) {
            return false
        }
        val targetExists = AppointmentPlanRevisions
            .selectAll()
            .where {
                (AppointmentPlanRevisions.id eq newRevisionId) and
                    (AppointmentPlanRevisions.planId eq planId) and
                    (AppointmentPlanRevisions.active eq false)
            }
            .count() == 1L
        if (!targetExists) {
            return false
        }
        val deactivated = AppointmentPlanRevisions.update(
            where = {
                (AppointmentPlanRevisions.id eq expectedActiveRevisionId) and
                    (AppointmentPlanRevisions.active eq true)
            },
        ) {
            it[active] = false
        }
        val activated = AppointmentPlanRevisions.update(
            where = {
                (AppointmentPlanRevisions.id eq newRevisionId) and
                    (AppointmentPlanRevisions.active eq false)
            },
        ) {
            it[active] = true
        }
        check(deactivated == 1 && activated == 1) {
            "plan revision activation lost its locked compare-and-set invariant"
        }
        return true
    }

    private fun findById(revisionId: Long): PersistedAppointmentPlanRevisionAggregateRecord? =
        AppointmentPlanRevisions
            .selectAll()
            .where { AppointmentPlanRevisions.id eq revisionId }
            .singleOrNull()
            ?.let { row ->
                val treatments = PlanRevisionTreatments
                    .selectAll()
                    .where { PlanRevisionTreatments.planRevisionId eq revisionId }
                    .orderBy(PlanRevisionTreatments.id, SortOrder.ASC)
                    .map {
                        PlanRevisionTreatmentRecord(
                            treatmentKey = it[PlanRevisionTreatments.treatmentKey],
                            componentProductId = it[PlanRevisionTreatments.componentProductId],
                            componentProductVersionId = it[PlanRevisionTreatments.componentProductVersionId],
                            productVersionId = it[PlanRevisionTreatments.productVersionId],
                            status = it[PlanRevisionTreatments.status],
                        )
                    }
                val dependencies = PlanRevisionDependencies
                    .selectAll()
                    .where { PlanRevisionDependencies.planRevisionId eq revisionId }
                    .orderBy(PlanRevisionDependencies.id, SortOrder.ASC)
                    .map {
                        PlanRevisionDependencyRecord(
                            predecessorTreatmentKey = it[PlanRevisionDependencies.predecessorTreatmentKey],
                            successorTreatmentKey = it[PlanRevisionDependencies.successorTreatmentKey],
                            type = it[PlanRevisionDependencies.type],
                        )
                    }
                PersistedAppointmentPlanRevisionAggregateRecord(
                    revision = PersistedAppointmentPlanRevisionRecord(
                        id = row[AppointmentPlanRevisions.id].value,
                        planId = row[AppointmentPlanRevisions.planId].value,
                        revision = row[AppointmentPlanRevisions.revision],
                        productVersionId = row[AppointmentPlanRevisions.productVersionId],
                        snapshotHash = row[AppointmentPlanRevisions.snapshotHash],
                        active = row[AppointmentPlanRevisions.active],
                    ),
                    treatments = treatments,
                    dependencies = dependencies,
                )
            }
}
