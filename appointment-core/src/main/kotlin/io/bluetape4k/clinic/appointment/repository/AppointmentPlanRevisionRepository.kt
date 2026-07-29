package io.bluetape4k.clinic.appointment.repository

import io.bluetape4k.clinic.appointment.model.dto.AppointmentPlanRevisionAggregateRecord
import io.bluetape4k.clinic.appointment.model.dto.PersistedAppointmentPlanRevisionAggregateRecord
import io.bluetape4k.clinic.appointment.model.dto.PersistedAppointmentPlanRevisionRecord
import io.bluetape4k.clinic.appointment.model.dto.PlanRevisionDependencyRecord
import io.bluetape4k.clinic.appointment.model.dto.PlanRevisionGroupingConstraintRecord
import io.bluetape4k.clinic.appointment.model.dto.PlanRevisionTreatmentRecord
import io.bluetape4k.clinic.appointment.model.tables.AppointmentPlanRevisions
import io.bluetape4k.clinic.appointment.model.tables.PlanRevisionDependencies
import io.bluetape4k.clinic.appointment.model.tables.PlanRevisionGroupingConstraints
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
        val normalizedGroupingConstraints = aggregate.groupingConstraints.map { constraint ->
            require(constraint.firstTreatmentKey != constraint.secondTreatmentKey) {
                "revision grouping constraint must connect different treatments"
            }
            require(
                constraint.firstTreatmentKey in knownTreatmentKeys &&
                    constraint.secondTreatmentKey in knownTreatmentKeys,
            ) {
                "revision grouping constraint must reference treatments in the same revision"
            }
            if (constraint.firstTreatmentKey <= constraint.secondTreatmentKey) {
                constraint
            } else {
                constraint.copy(
                    firstTreatmentKey = constraint.secondTreatmentKey,
                    secondTreatmentKey = constraint.firstTreatmentKey,
                )
            }
        }
        require(
            normalizedGroupingConstraints
                .map { it.firstTreatmentKey to it.secondTreatmentKey }
                .distinct()
                .size == normalizedGroupingConstraints.size,
        ) {
            "revision grouping constraint pairs must be unique"
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
            this[PlanRevisionTreatments.sourceBomItemId] = treatment.sourceBomItemId
            this[PlanRevisionTreatments.sequence] = treatment.sequence
            this[PlanRevisionTreatments.representativeTreatmentName] = treatment.representativeTreatmentName
            this[PlanRevisionTreatments.detailedTreatmentCodesPayload] =
                encodeStringList(treatment.detailedTreatmentCodes)
            this[PlanRevisionTreatments.preparationMinutes] = treatment.preparationMinutes
            this[PlanRevisionTreatments.treatmentMinutes] = treatment.treatmentMinutes
            this[PlanRevisionTreatments.recoveryMinutes] = treatment.recoveryMinutes
            this[PlanRevisionTreatments.practitionerQualificationsPayload] =
                encodeStringList(treatment.practitionerQualifications)
            this[PlanRevisionTreatments.equipmentTypesPayload] = encodeStringList(treatment.equipmentTypes)
            this[PlanRevisionTreatments.spaceCapabilitiesPayload] =
                encodeStringList(treatment.spaceCapabilities)
        }
        PlanRevisionDependencies.batchInsert(
            aggregate.dependencies,
            shouldReturnGeneratedValues = false,
        ) { dependency ->
            this[PlanRevisionDependencies.planRevisionId] = revisionId
            this[PlanRevisionDependencies.predecessorTreatmentKey] = dependency.predecessorTreatmentKey
            this[PlanRevisionDependencies.successorTreatmentKey] = dependency.successorTreatmentKey
            this[PlanRevisionDependencies.type] = dependency.type
            this[PlanRevisionDependencies.minimumIntervalDays] = dependency.minimumIntervalDays
            this[PlanRevisionDependencies.preferredIntervalDays] = dependency.preferredIntervalDays
            this[PlanRevisionDependencies.maximumIntervalDays] = dependency.maximumIntervalDays
        }
        PlanRevisionGroupingConstraints.batchInsert(
            normalizedGroupingConstraints,
            shouldReturnGeneratedValues = false,
        ) { constraint ->
            this[PlanRevisionGroupingConstraints.planRevisionId] = revisionId
            this[PlanRevisionGroupingConstraints.firstTreatmentKey] = constraint.firstTreatmentKey
            this[PlanRevisionGroupingConstraints.secondTreatmentKey] = constraint.secondTreatmentKey
            this[PlanRevisionGroupingConstraints.type] = constraint.type
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
     * 특정 불변 revision과 모든 child를 identity로 조회합니다.
     *
     * 이 조회는 과거 완료 provenance가 새 revision 생성 뒤에도 바뀌지 않았는지
     * 검증하거나, outbox consumer가 정확한 revision을 재구성할 때 사용합니다.
     */
    fun findById(revisionId: Long): PersistedAppointmentPlanRevisionAggregateRecord? {
        revisionId.requirePositiveNumber("revisionId")
        return findAggregateById(revisionId)
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

    private fun findAggregateById(revisionId: Long): PersistedAppointmentPlanRevisionAggregateRecord? =
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
                            sourceBomItemId = it[PlanRevisionTreatments.sourceBomItemId],
                            sequence = it[PlanRevisionTreatments.sequence],
                            representativeTreatmentName = it[PlanRevisionTreatments.representativeTreatmentName],
                            detailedTreatmentCodes =
                                decodeStringList(it[PlanRevisionTreatments.detailedTreatmentCodesPayload]),
                            preparationMinutes = it[PlanRevisionTreatments.preparationMinutes],
                            treatmentMinutes = it[PlanRevisionTreatments.treatmentMinutes],
                            recoveryMinutes = it[PlanRevisionTreatments.recoveryMinutes],
                            practitionerQualifications =
                                decodeStringList(it[PlanRevisionTreatments.practitionerQualificationsPayload]),
                            equipmentTypes = decodeStringList(it[PlanRevisionTreatments.equipmentTypesPayload]),
                            spaceCapabilities =
                                decodeStringList(it[PlanRevisionTreatments.spaceCapabilitiesPayload]),
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
                            minimumIntervalDays = it[PlanRevisionDependencies.minimumIntervalDays],
                            preferredIntervalDays = it[PlanRevisionDependencies.preferredIntervalDays],
                            maximumIntervalDays = it[PlanRevisionDependencies.maximumIntervalDays],
                        )
                    }
                val groupingConstraints = PlanRevisionGroupingConstraints
                    .selectAll()
                    .where { PlanRevisionGroupingConstraints.planRevisionId eq revisionId }
                    .orderBy(
                        PlanRevisionGroupingConstraints.firstTreatmentKey to SortOrder.ASC,
                        PlanRevisionGroupingConstraints.secondTreatmentKey to SortOrder.ASC,
                    )
                    .map {
                        PlanRevisionGroupingConstraintRecord(
                            firstTreatmentKey = it[PlanRevisionGroupingConstraints.firstTreatmentKey],
                            secondTreatmentKey = it[PlanRevisionGroupingConstraints.secondTreatmentKey],
                            type = it[PlanRevisionGroupingConstraints.type],
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
                    groupingConstraints = groupingConstraints,
                )
            }
}
