package io.bluetape4k.clinic.appointment.model.commitment

import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import java.io.Serializable
import java.time.Instant

/**
 * append-only 동의 기록이 결합되는 업무 대상입니다.
 *
 * [type]만 같다는 이유로 동의가 유효해지는 것은 아닙니다. 구체 subtype의 식별자,
 * revision, hash가 실제 command 대상과 모두 일치해야 합니다.
 */
interface ConsentSubject : Serializable {
    /** 동의가 예약 제안인지 상품 version 전환인지 구분하는 안정적인 분류입니다. */
    val type: ConsentSubjectType
}

/**
 * 고객 동의를 요구하는 대상 종류입니다.
 */
enum class ConsentSubjectType {
    /** 방문 날짜·항목·자원·정책을 포함한 특정 예약 제안입니다. */
    APPOINTMENT_PROPOSAL,

    /** 동일 구매 Plan의 미래 항목에 적용할 특정 상품 version 전환입니다. */
    PRODUCT_VERSION_MIGRATION,
}

/**
 * 특정 예약 제안에 결합된 동의 대상입니다.
 *
 * @property proposalId 수정 불가능하게 발행된 양수 proposal 식별자입니다.
 * @property proposalRevision commitment 범위에서 단조 증가하는 양수 revision입니다.
 * @property proposalHash 방문 시각, 항목, 자원, 정책 스냅샷을 모두 포함한 canonical
 * hash입니다. 같은 ID라도 hash가 다르면 원본 계약 위반으로 취급합니다.
 */
data class ProposalConsentSubject(
    val proposalId: Long,
    val proposalRevision: Long,
    val proposalHash: String,
) : ConsentSubject {

    override val type: ConsentSubjectType = ConsentSubjectType.APPOINTMENT_PROPOSAL

    init {
        proposalId.requirePositiveNumber("proposalId")
        proposalRevision.requirePositiveNumber("proposalRevision")
        proposalHash.requireNotBlank("proposalHash")
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * 승인된 상품 version 전환에 결합된 동의 대상입니다.
 *
 * @property migrationId 상품 서비스가 승인 절차와 함께 발행한 안정적인 전환 식별자입니다.
 * @property fromProductVersionId 현재 Plan revision이 고정한 상품 version입니다.
 * @property toProductVersionId 미래 항목이 승계될 상품 version입니다.
 * @property mappingHash `KEEP/REPLACE/SPLIT/MERGE/REMOVE/ADD` 전환표 전체의 canonical
 * hash입니다. 실제 일정 변경 동의를 대신하지 않습니다.
 */
data class ProductVersionMigrationConsentSubject(
    val migrationId: String,
    val fromProductVersionId: String,
    val toProductVersionId: String,
    val mappingHash: String,
) : ConsentSubject {

    override val type: ConsentSubjectType = ConsentSubjectType.PRODUCT_VERSION_MIGRATION

    init {
        migrationId.requireNotBlank("migrationId")
        fromProductVersionId.requireNotBlank("fromProductVersionId")
        toProductVersionId.requireNotBlank("toProductVersionId")
        mappingHash.requireNotBlank("mappingHash")
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * 고객 또는 적법한 대리인이 남긴 append-only 동의 결정입니다.
 *
 * @property subject 동의가 정확히 결합된 불변 대상입니다.
 * @property decision 수락 또는 거부 결정입니다. 최신 row로 이전 결정을 덮어쓰지 않습니다.
 * @property evidenceAuthority 증빙 원본을 소유하고 검증할 수 있는 서비스의 논리 이름입니다.
 * @property evidenceId 원본 서비스 범위에서 안정적인 증빙 식별자입니다.
 * @property evidenceHash 원문을 저장하지 않고 동일한 증빙임을 검증하기 위한 hash입니다.
 * 개인정보나 동의 원문을 로그에 남기는 용도로 사용하지 않습니다.
 * @property decidedAt 원본 권위가 기록한 UTC 결정 시각입니다.
 * @property actorRef 고객 또는 적법한 대리인을 나타내는 비민감 감사 참조입니다.
 */
data class ConsentDecision(
    val subject: ConsentSubject,
    val decision: ConsentDecisionType,
    val evidenceAuthority: String,
    val evidenceId: String,
    val evidenceHash: String,
    val decidedAt: Instant,
    val actorRef: String,
) : Serializable {

    init {
        evidenceAuthority.requireNotBlank("evidenceAuthority")
        evidenceId.requireNotBlank("evidenceId")
        evidenceHash.requireNotBlank("evidenceHash")
        actorRef.requireNotBlank("actorRef")
    }

    /**
     * 이 결정이 정확한 예약 제안을 수락한 증빙인지 검사합니다.
     */
    fun acceptsProposal(
        proposalId: Long,
        proposalRevision: Long,
        proposalHash: String,
    ): Boolean {
        val proposalSubject = subject as? ProposalConsentSubject ?: return false
        return decision == ConsentDecisionType.ACCEPTED &&
            proposalSubject.proposalId == proposalId &&
            proposalSubject.proposalRevision == proposalRevision &&
            proposalSubject.proposalHash == proposalHash
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * 동의 대상에 대한 고객 결정입니다.
 */
enum class ConsentDecisionType {
    /** 대상의 정확한 version과 hash에 동의했습니다. */
    ACCEPTED,

    /** 대상을 거부했습니다. 기존 확정 예약을 자동 취소한다는 뜻은 아닙니다. */
    DECLINED,
}
