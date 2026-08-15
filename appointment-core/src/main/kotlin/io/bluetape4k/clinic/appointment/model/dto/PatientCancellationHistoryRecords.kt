package io.bluetape4k.clinic.appointment.model.dto

import io.bluetape4k.clinic.appointment.model.commitment.AppointmentCommitmentStatus
import io.bluetape4k.support.requirePositiveNumber
import java.io.Serializable
import java.time.Instant

/** 환자 취소 이력 keyset cursor가 가리키는 정렬 경계입니다. */
data class CancellationHistoryBoundary(
    val occurredAt: Instant,
    val detailId: Long,
) : Serializable {
    init {
        detailId.requirePositiveNumber("detailId")
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/** API/security 타입을 참조하지 않는 환자 취소 이력 내부 read model입니다. */
data class PatientCancellationHistoryRecord(
    val detailId: Long,
    val tenantGroupId: Long,
    val clinicId: Long,
    val appointmentId: Long,
    val commitmentId: Long,
    val proposalId: Long,
    val patientScopeFingerprint: String,
    val reasonCode: String,
    val reasonDetail: String?,
    val fromCommitmentStatus: AppointmentCommitmentStatus?,
    val toCommitmentStatus: AppointmentCommitmentStatus,
    val actorRole: String,
    val occurredAt: Instant,
    val visitStartAt: Instant,
    val visitEndAt: Instant,
    val productName: String? = null,
    val sessionNumber: Int? = null,
    val totalSessions: Int? = null,
) : Serializable {
    init {
        detailId.requirePositiveNumber("detailId")
        tenantGroupId.requirePositiveNumber("tenantGroupId")
        clinicId.requirePositiveNumber("clinicId")
        appointmentId.requirePositiveNumber("appointmentId")
        commitmentId.requirePositiveNumber("commitmentId")
        proposalId.requirePositiveNumber("proposalId")
        require(patientScopeFingerprint.isNotBlank()) { "patientScopeFingerprint must not be blank" }
        require(reasonCode.isNotBlank()) { "reasonCode must not be blank" }
        require(actorRole.isNotBlank()) { "actorRole must not be blank" }
        require(toCommitmentStatus == AppointmentCommitmentStatus.CANCELLED) {
            "patient cancellation history must be terminally cancelled"
        }
        require(visitEndAt.isAfter(visitStartAt)) { "visitEndAt must be after visitStartAt" }
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/** 환자 취소 이력 page의 내부 반환값입니다. */
data class PatientCancellationHistoryPage(
    val entries: List<PatientCancellationHistoryRecord>,
    val hasNext: Boolean,
    /** bounded metadata fan-out으로 모호하게 처리한 detail 수입니다. */
    val metadataAmbiguousCount: Int = 0,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
