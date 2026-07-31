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
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.batchInsert
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
     * proposal에 포함된 item row를 bulk 검증·append하고 입력 순서대로 반환합니다.
     *
     * 패키지 상품은 하나의 방문 proposal에 수백 개의 세부 진료를 포함할 수 있습니다.
     * 따라서 Plan revision treatment를 item마다 조회하지 않고 `(revision, treatmentKey)`
     * composite key 전체를 한 번에 조회하며, 검증을 모두 통과한 뒤 하나의 batch insert로
     * 저장합니다. 검증 실패 시에는 어떤 item도 저장하지 않습니다.
     *
     * @param scope proposal, appointment, tenant, clinic, patient가 공유해야 하는 저장 경계입니다.
     * @param items 구매 당시 고정된 Plan revision treatment snapshot과 일치해야 하는 item입니다.
     * @return 생성된 식별자가 채워진 item을 caller 입력 순서대로 반환합니다.
     * @throws IllegalArgumentException item이 비어 있거나 중복되거나 scope 또는 immutable
     * treatment snapshot과 일치하지 않을 때 발생합니다.
     * @throws IllegalStateException JDBC driver가 batch insert 결과를 모두 반환하지 않을 때
     * 발생합니다.
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
        requirePlanTreatmentScope(scope, items)

        val insertedRows = AppointmentItems.batchInsert(items) { item ->
            this[AppointmentItems.appointmentId] = scope.appointmentId
            this[AppointmentItems.proposalId] = scope.proposalId
            this[AppointmentItems.planRevisionId] = item.planRevisionId
            this[AppointmentItems.treatmentKey] = item.treatmentKey
            this[AppointmentItems.representativeTreatmentName] = item.representativeTreatmentName
            this[AppointmentItems.detailedTreatmentCodesPayload] = encodeStringList(item.detailedTreatmentCodes)
            this[AppointmentItems.preparationMinutes] = item.preparationMinutes
            this[AppointmentItems.treatmentMinutes] = item.treatmentMinutes
            this[AppointmentItems.recoveryMinutes] = item.recoveryMinutes
            this[AppointmentItems.attemptNumber] = item.attemptNumber
        }
        check(insertedRows.size == items.size) {
            "batch insert did not return every appointment item"
        }
        return items.zip(insertedRows).map { (item, insertedRow) ->
            AppointmentItemRecord(
                id = insertedRow[AppointmentItems.id].value,
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
        if (scope.memberStableRef != null) {
            require(row[Appointments.patientExternalId] == scope.memberStableRef.value) {
                "appointment patientExternalId must match memberStableRef"
            }
        }
    }

    private fun requirePlanTreatmentScope(
        scope: AppointmentItemAppendScope,
        items: List<AppointmentItemDraft>,
    ) {
        val treatmentKeys =
            items.map { item ->
                EntityID(item.planRevisionId, AppointmentPlanRevisions) to item.treatmentKey
            }
        val rowsByTreatmentKey =
            (PlanRevisionTreatments innerJoin AppointmentPlanRevisions innerJoin AppointmentPlans)
                .selectAll()
                .where {
                    ((PlanRevisionTreatments.planRevisionId to PlanRevisionTreatments.treatmentKey) inList
                        treatmentKeys) and
                        (AppointmentPlans.tenantGroupId eq scope.tenantGroupId) and
                        (AppointmentPlans.clinicId eq scope.clinicId) and
                        (AppointmentPlans.patientReferenceFingerprint eq scope.patientReferenceFingerprint)
                }
                .associateBy { row ->
                    row[PlanRevisionTreatments.planRevisionId].value to row[PlanRevisionTreatments.treatmentKey]
                }

        items.forEach { item ->
            val row = rowsByTreatmentKey[item.planRevisionId to item.treatmentKey]
            requireNotNull(row) {
                "appointment item must reference treatment in same scoped plan revision"
            }
            require(item.matches(row)) {
                "appointment item must match immutable plan revision treatment"
            }
        }
    }

    private fun AppointmentItemDraft.matches(row: ResultRow): Boolean =
        representativeTreatmentName == row[PlanRevisionTreatments.representativeTreatmentName] &&
            detailedTreatmentCodes == decodeStringList(row[PlanRevisionTreatments.detailedTreatmentCodesPayload]) &&
            preparationMinutes == row[PlanRevisionTreatments.preparationMinutes] &&
            treatmentMinutes == row[PlanRevisionTreatments.treatmentMinutes] &&
            recoveryMinutes == row[PlanRevisionTreatments.recoveryMinutes]
}
