package io.bluetape4k.clinic.appointment.model.dto

import io.bluetape4k.clinic.appointment.model.identity.MemberId
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import java.io.Serializable

/**
 * proposal item append가 신뢰할 수 있는 Plan·방문 경계입니다.
 *
 * application service는 인증/권한 검사를 마친 tenant, clinic, 환자 reference fingerprint를
 * 넘겨야 합니다. [memberStableRef]가 있으면 방문 identity의 회원 식별자도
 * 같은 transaction 안에서 함께 검증합니다.
 */
class AppointmentItemAppendScope(
    appointmentId: Long,
    proposalId: Long,
    tenantGroupId: Long,
    clinicId: Long,
    patientReferenceFingerprint: String,
    memberStableRef: MemberId? = null,
) : Serializable {
    val appointmentId = appointmentId.requirePositiveNumber("appointmentId")
    val proposalId = proposalId.requirePositiveNumber("proposalId")
    val tenantGroupId = tenantGroupId.requirePositiveNumber("tenantGroupId")
    val clinicId = clinicId.requirePositiveNumber("clinicId")
    val patientReferenceFingerprint =
        patientReferenceFingerprint.requireNotBlank("patientReferenceFingerprint")
    val memberStableRef = memberStableRef

    init {
        require(this.patientReferenceFingerprint.length <= 128) {
            "patientReferenceFingerprint must not exceed 128 characters"
        }
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * proposal에 고정된 방문별 실행 item row입니다.
 */
data class AppointmentItemRecord(
    val id: Long,
    val appointmentId: Long,
    val proposalId: Long,
    val planRevisionId: Long,
    val treatmentKey: String,
    val representativeTreatmentName: String,
    val detailedTreatmentCodes: List<String>,
    val preparationMinutes: Int,
    val treatmentMinutes: Int,
    val recoveryMinutes: Int,
    val attemptNumber: Int,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
