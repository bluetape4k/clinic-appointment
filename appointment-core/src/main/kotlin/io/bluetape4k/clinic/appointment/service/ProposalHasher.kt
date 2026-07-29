package io.bluetape4k.clinic.appointment.service

import io.bluetape4k.clinic.appointment.model.commitment.AppointmentItemDraft
import io.bluetape4k.clinic.appointment.model.commitment.AppointmentProposalDraft
import io.bluetape4k.clinic.appointment.model.commitment.ResourceAllocationDraft
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * 동의 대상인 예약 제안 전체의 canonical SHA-256 hash를 생성합니다.
 *
 * 날짜만 hash하지 않고 항목별 Plan provenance와 준비·진료·회복 시간, 실제 자원 점유,
 * 정책 스냅샷, 대체 대상까지 포함합니다. 따라서 고객이 동의한 뒤 중요한 조건이
 * 달라지면 새 proposal revision과 새 동의가 필요합니다.
 *
 * DB가 생성하는 appointment/proposal ID는 hash에서 제외합니다. 최초 예약은 ID 생성
 * 전에 외부 동의 서비스가 같은 의미의 proposal을 검증해야 하며, 영속화 뒤에는
 * commitment·proposal 소유권과 전역 unique evidence가 ID 재사용을 별도로 차단합니다.
 */
object ProposalHasher {

    /**
     * [proposal]의 의미 있는 모든 필드를 길이 framing해 소문자 16진 SHA-256로 반환합니다.
     *
     * 항목 순서는 실제 수행 순서이므로 보존합니다. allocation은 입력 collection 순서가
     * 의미가 없으므로 안정적인 자원·시간 키로 정렬합니다.
     */
    fun hash(proposal: AppointmentProposalDraft): String =
        MessageDigest.getInstance("SHA-256")
            .apply { updateProposal(proposal) }
            .digest()
            .joinToString("") { byte -> "%02x".format(byte) }

    private fun MessageDigest.updateProposal(proposal: AppointmentProposalDraft) {
        updateField("revision", proposal.revision)
        updateField("startsAt", proposal.startsAt)
        updateField("endsAt", proposal.endsAt)
        updateField("policySnapshotId", proposal.policySnapshotId)
        updateField("supersedesProposalId", proposal.supersedesProposalId)
        updateField("items.size", proposal.items.size)
        proposal.items.forEachIndexed { index, item -> updateItem("items[$index]", item) }

        val sortedAllocations = proposal.allocations.sortedWith(
            compareBy<ResourceAllocationDraft>(
                { it.resourceType.name },
                ResourceAllocationDraft::resourceId,
                ResourceAllocationDraft::startsAt,
                ResourceAllocationDraft::endsAt,
                ResourceAllocationDraft::appointmentItemKey,
            ),
        )
        updateField("allocations.size", sortedAllocations.size)
        sortedAllocations.forEachIndexed { index, allocation ->
            updateAllocation("allocations[$index]", allocation)
        }
    }

    private fun MessageDigest.updateItem(prefix: String, item: AppointmentItemDraft) {
        updateField("$prefix.planRevisionId", item.planRevisionId)
        updateField("$prefix.treatmentKey", item.treatmentKey)
        updateField("$prefix.representativeTreatmentName", item.representativeTreatmentName)
        updateList("$prefix.detailedTreatmentCodes", item.detailedTreatmentCodes)
        updateField("$prefix.preparationMinutes", item.preparationMinutes)
        updateField("$prefix.treatmentMinutes", item.treatmentMinutes)
        updateField("$prefix.recoveryMinutes", item.recoveryMinutes)
        updateField("$prefix.attemptNumber", item.attemptNumber)
    }

    private fun MessageDigest.updateAllocation(
        prefix: String,
        allocation: ResourceAllocationDraft,
    ) {
        updateField("$prefix.resourceType", allocation.resourceType)
        updateField("$prefix.resourceId", allocation.resourceId)
        updateField("$prefix.startsAt", allocation.startsAt)
        updateField("$prefix.endsAt", allocation.endsAt)
        updateField("$prefix.capacityUnits", allocation.capacityUnits)
        updateField("$prefix.allocationMode", allocation.allocationMode)
        updateField("$prefix.appointmentItemKey", allocation.appointmentItemKey)
    }

    private fun MessageDigest.updateList(name: String, values: List<String>) {
        updateField("$name.size", values.size)
        values.forEachIndexed { index, value -> updateField("$name[$index]", value) }
    }

    private fun MessageDigest.updateField(name: String, value: Any?) {
        update(name.toByteArray(StandardCharsets.UTF_8))
        update(0)
        if (value == null) {
            update(-1)
        } else {
            val valueBytes = value.toString().toByteArray(StandardCharsets.UTF_8)
            update(valueBytes.size.toString().toByteArray(StandardCharsets.UTF_8))
            update(0)
            update(valueBytes)
        }
        update(0)
    }
}
