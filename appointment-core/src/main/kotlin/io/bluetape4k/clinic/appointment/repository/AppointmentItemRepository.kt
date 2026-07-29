package io.bluetape4k.clinic.appointment.repository

import io.bluetape4k.clinic.appointment.model.commitment.AppointmentItemDraft
import io.bluetape4k.clinic.appointment.model.dto.AppointmentItemAppendScope
import io.bluetape4k.clinic.appointment.model.dto.AppointmentItemRecord
import io.bluetape4k.clinic.appointment.model.dto.ResourceAllocationRequest
import io.bluetape4k.clinic.appointment.model.tables.AppointmentCommitments
import io.bluetape4k.clinic.appointment.model.tables.AppointmentItems
import io.bluetape4k.clinic.appointment.model.tables.AppointmentPlanRevisions
import io.bluetape4k.clinic.appointment.model.tables.AppointmentPlans
import io.bluetape4k.clinic.appointment.model.tables.AppointmentProposals
import io.bluetape4k.clinic.appointment.model.tables.Appointments
import io.bluetape4k.clinic.appointment.model.tables.PlanRevisionTreatments
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll

/**
 * caller-owned Exposed transaction에서 proposal별 immutable appointment item을 append합니다.
 *
 * repository는 transaction을 열지 않습니다. proposal·commitment·appointment 소유권과
 * Plan revision tenant/clinic/patient 경계, immutable treatment snapshot 일치를 저장 직전에
 * 검증합니다.
 */
class AppointmentItemRepository {
    /**
     * proposal에 포함된 item row를 append하고 저장된 순서대로 반환합니다.
     */
    fun appendValidated(
        scope: AppointmentItemAppendScope,
        items: List<AppointmentItemDraft>,
    ): List<AppointmentItemRecord> {
        require(items.isNotEmpty()) { "appointment items must not be empty" }
        val treatmentKeys = items.map(AppointmentItemDraft::treatmentKey)
        require(treatmentKeys.size == treatmentKeys.toSet().size) {
            "appointment item treatmentKey must be unique within proposal"
        }
        val itemKeys = items.map { Triple(it.planRevisionId, it.treatmentKey, it.attemptNumber) }
        require(itemKeys.size == itemKeys.toSet().size) {
            "appointment item attempts must be unique within proposal"
        }
        requireProposalScope(scope)
        items.forEach { item -> requirePlanTreatmentScope(scope, item) }

        return items.map { item ->
            val insertedId =
                AppointmentItems
                    .insertAndGetId {
                        it[appointmentId] = scope.appointmentId
                        it[proposalId] = scope.proposalId
                        it[planRevisionId] = item.planRevisionId
                        it[treatmentKey] = item.treatmentKey
                        it[representativeTreatmentName] = item.representativeTreatmentName
                        it[detailedTreatmentCodesPayload] = encodeStringList(item.detailedTreatmentCodes)
                        it[preparationMinutes] = item.preparationMinutes
                        it[treatmentMinutes] = item.treatmentMinutes
                        it[recoveryMinutes] = item.recoveryMinutes
                        it[attemptNumber] = item.attemptNumber
                    }.value
            AppointmentItemRecord(
                id = insertedId,
                appointmentId = scope.appointmentId,
                proposalId = scope.proposalId,
                planRevisionId = item.planRevisionId,
                treatmentKey = item.treatmentKey,
                representativeTreatmentName = item.representativeTreatmentName,
                detailedTreatmentCodes = item.detailedTreatmentCodes,
                preparationMinutes = item.preparationMinutes,
                treatmentMinutes = item.treatmentMinutes,
                recoveryMinutes = item.recoveryMinutes,
                attemptNumber = item.attemptNumber,
            )
        }
    }

    /**
     * 자원 점유의 item 참조가 proposal에 저장된 불변 item을 정확히 가리키는지 검증합니다.
     *
     * `null` 참조는 방문 전체에 필요한 담당자·공간 같은 자원을 뜻합니다. 값이 있으면
     * proposal 안에서 유일한 `treatmentKey`와 일치해야 하며 다른 proposal 또는 존재하지
     * 않는 항목을 참조할 수 없습니다.
     */
    fun requireResourceReferences(
        proposalId: Long,
        requests: List<ResourceAllocationRequest>,
    ) {
        val treatmentKeys =
            AppointmentItems
                .select(AppointmentItems.treatmentKey)
                .where { AppointmentItems.proposalId eq proposalId }
                .map { row -> row[AppointmentItems.treatmentKey] }
        require(treatmentKeys.size == treatmentKeys.toSet().size) {
            "persisted appointment item treatmentKey must be unique within proposal"
        }
        val knownTreatmentKeys = treatmentKeys.toSet()
        requests.forEach { request ->
            request.allocation.appointmentItemKey?.let { itemKey ->
                require(itemKey in knownTreatmentKeys) {
                    "resource appointmentItemKey must reference an item in the same proposal"
                }
            }
        }
    }

    private fun requireProposalScope(scope: AppointmentItemAppendScope) {
        val query =
            (AppointmentProposals innerJoin AppointmentCommitments innerJoin Appointments)
                .selectAll()
                .where {
                    (AppointmentProposals.id eq scope.proposalId) and
                        (AppointmentCommitments.appointmentId eq scope.appointmentId) and
                        (Appointments.id eq scope.appointmentId) and
                        (Appointments.clinicId eq scope.clinicId) and
                        (Appointments.patientReferenceFingerprint eq scope.patientReferenceFingerprint)
                }
        val row = query.singleOrNull()
        requireNotNull(row) { "proposal must belong to scoped appointment" }
        if (scope.patientExternalStableRef != null) {
            require(row[Appointments.patientExternalId] == scope.patientExternalStableRef) {
                "appointment patientExternalId must match patientExternalStableRef"
            }
        }
    }

    private fun requirePlanTreatmentScope(
        scope: AppointmentItemAppendScope,
        item: AppointmentItemDraft,
    ) {
        val row =
            (PlanRevisionTreatments innerJoin AppointmentPlanRevisions innerJoin AppointmentPlans)
                .selectAll()
                .where {
                    (PlanRevisionTreatments.planRevisionId eq item.planRevisionId) and
                        (PlanRevisionTreatments.treatmentKey eq item.treatmentKey) and
                        (AppointmentPlans.tenantGroupId eq scope.tenantGroupId) and
                        (AppointmentPlans.clinicId eq scope.clinicId) and
                        (AppointmentPlans.patientReferenceFingerprint eq scope.patientReferenceFingerprint)
                }.singleOrNull()
        requireNotNull(row) { "appointment item must reference treatment in same scoped plan revision" }
        require(item.matches(row)) {
            "appointment item must match immutable plan revision treatment"
        }
    }

    private fun AppointmentItemDraft.matches(row: ResultRow): Boolean =
        representativeTreatmentName == row[PlanRevisionTreatments.representativeTreatmentName] &&
            detailedTreatmentCodes == decodeStringList(row[PlanRevisionTreatments.detailedTreatmentCodesPayload]) &&
            preparationMinutes == row[PlanRevisionTreatments.preparationMinutes] &&
            treatmentMinutes == row[PlanRevisionTreatments.treatmentMinutes] &&
            recoveryMinutes == row[PlanRevisionTreatments.recoveryMinutes]
}
