package io.bluetape4k.clinic.appointment.api.dto.commitment

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonAnySetter
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import java.io.Serializable
import java.time.Instant

/**
 * commitment v2 body에서 schema에 없는 필드를 fail-closed로 거절한다.
 *
 * 애플리케이션의 전역 Jackson 설정이 다른 legacy DTO의 호환성을 위해 unknown property를
 * 허용하더라도 actor, scope, 정책, 자원 필드를 v2 DTO에 몰래 추가할 수 없어야 한다.
 */
abstract class StrictAppointmentCommitmentBody : Serializable {
    @JsonAnySetter
    fun rejectUnknownProperty(
        name: String,
        @Suppress("UNUSED_PARAMETER") value: Any?,
    ): Nothing = throw IllegalArgumentException("Unknown appointment commitment field: $name")

    private companion object {
        const val serialVersionUID = 1L
    }
}

/**
 * 고객이 최초 가예약을 요청할 때 제공하는 일정 의도이다.
 *
 * actor, tenant, clinic, patient subject는 Gateway 인증 주체에서만 얻는다. 상품·정책
 * snapshot과 실제 자원 배정도 서버가 [appointmentPlanId]를 기준으로 해석하므로 이 DTO에
 * 정책 방식, 담당자, 진료 유형, 약관 hash를 추가하면 안 된다.
 *
 * @property appointmentPlanId 구매 시점 상품 BOM으로 만들어진 불변 예약 Plan 식별자.
 * @property preferredStartAt 고객이 희망하는 방문 시작 UTC 시각.
 * @property preferredEndAt [preferredStartAt]보다 뒤인 희망 방문 종료 UTC 시각.
 * @property evidence 외부 동의 권위가 발행한 opaque 증빙 참조.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
data class CreateAppointmentRequestV2(
    @field:Positive
    val appointmentPlanId: Long,
    val preferredStartAt: Instant,
    val preferredEndAt: Instant,
    @field:Valid
    val evidence: ConsentEvidenceRequest,
) : StrictAppointmentCommitmentBody() {
    init {
        require(preferredStartAt < preferredEndAt) {
            "preferredStartAt must be before preferredEndAt"
        }
    }

    private companion object {
        const val serialVersionUID = 1L
    }
}

/**
 * 병원 관리자가 새 예약을 직접 생성할 때 제공하는 일정 의도이다.
 *
 * 직접 확정 가능 여부, 동의 유형, 약관, 자원 mapping은 유효 정책 snapshot과 병원
 * inventory에서 서버가 결정한다.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
data class DirectCreateAppointmentRequest(
    @field:Positive
    val appointmentPlanId: Long,
    val preferredStartAt: Instant,
    val preferredEndAt: Instant,
    @field:Valid
    val evidence: ConsentEvidenceRequest,
) : StrictAppointmentCommitmentBody() {
    init {
        require(preferredStartAt < preferredEndAt) {
            "preferredStartAt must be before preferredEndAt"
        }
    }

    private companion object {
        const val serialVersionUID = 1L
    }
}

/**
 * 고객 최초 proposal을 병원이 승인할 때 지정하는 불변 proposal 식별자이다.
 *
 * version과 멱등성 키는 각각 `If-Match`, `Idempotency-Key` header로만 받는다.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
data class ApproveProposalRequest(
    @field:Positive
    val proposalId: Long,
) : StrictAppointmentCommitmentBody() {
    private companion object {
        const val serialVersionUID = 1L
    }
}

/**
 * 고객이 변경 proposal을 수락할 때 제출하는 외부 동의 증빙 참조이다.
 *
 * proposal ID는 path, version은 `If-Match`에서 얻으며 body가 다른 proposal이나
 * commitment version을 선택할 수 없다.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
data class ProposalDecisionRequest(
    @field:Valid
    val evidence: ConsentEvidenceRequest,
) : StrictAppointmentCommitmentBody() {
    private companion object {
        const val serialVersionUID = 1L
    }
}

/**
 * 고객이 변경 proposal을 거절할 때 기록할 제한된 업무 사유이다.
 *
 * 자유 텍스트 대신 운영 시스템과 합의한 code를 사용해 개인정보·민감정보가 감사
 * 이벤트나 로그로 흘러가지 않게 한다.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
data class DeclineProposalRequest(
    @field:NotBlank
    @field:Size(max = 64)
    @field:Pattern(regexp = "[A-Z][A-Z0-9_]{0,63}")
    val reasonCode: String,
) : StrictAppointmentCommitmentBody() {
    init {
        require(REASON_CODE.matches(reasonCode)) {
            "reasonCode must be a registered uppercase business code"
        }
    }

    private companion object {
        val REASON_CODE = Regex("[A-Z][A-Z0-9_]{0,63}")
        const val serialVersionUID = 1L
    }
}

/**
 * 관리자가 기존 확정 예약을 다시 확정할 때 선택하는 proposal 식별자이다.
 *
 * 동의 허용 범위와 projection 대상은 서버 정책·inventory가 결정한다.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
data class DirectConfirmRequest(
    @field:Positive
    val proposalId: Long,
    @field:Valid
    val evidence: ConsentEvidenceRequest,
) : StrictAppointmentCommitmentBody() {
    private companion object {
        const val serialVersionUID = 1L
    }
}

/**
 * 기존 확정 예약을 유지한 채 새 일정 proposal을 만드는 요청이다.
 *
 * 자원·담당자 식별자를 직접 받지 않고 희망 시간만 받는다. application resolver가 현재
 * Plan revision과 병원 inventory로 proposal item과 allocation을 계산한다.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
data class CreateChangeProposalRequest(
    val preferredStartAt: Instant,
    val preferredEndAt: Instant,
) : StrictAppointmentCommitmentBody() {
    init {
        require(preferredStartAt < preferredEndAt) {
            "preferredStartAt must be before preferredEndAt"
        }
    }

    private companion object {
        const val serialVersionUID = 1L
    }
}

/**
 * 동의 서비스가 발행한 전역 opaque evidence reference이다.
 *
 * 원문 환자 식별자, 이름, 전화번호, JWT, credential은 허용하지 않는다.
 * [evidenceAuthority]는 tenant namespace를 포함한 등록된 권위 이름이고
 * [evidenceId]는 추측 불가능한 opaque ID이다. 서버 resolver는 이 참조로 원본 동의와
 * 현재 proposal·약관을 다시 검증한다.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
data class ConsentEvidenceRequest(
    @field:NotBlank
    @field:Size(max = 128)
    val evidenceAuthority: String,
    @field:NotBlank
    @field:Size(min = 20, max = 128)
    val evidenceId: String,
) : StrictAppointmentCommitmentBody() {
    init {
        require(evidenceAuthority.length <= 128) {
            "evidenceAuthority must not exceed 128 characters"
        }
        require(evidenceId.length in 20..128) {
            "evidenceId length must be between 20 and 128 characters"
        }
        require(SAFE_AUTHORITY.matches(evidenceAuthority)) {
            "evidenceAuthority must be an opaque tenant-namespaced authority"
        }
        require(OPAQUE_ID.matches(evidenceId)) {
            "evidenceId must be an opaque reference"
        }
    }

    private companion object {
        val SAFE_AUTHORITY =
            Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}:[A-Za-z0-9][A-Za-z0-9._:/-]{1,126}")
        val OPAQUE_ID = Regex("[A-Za-z0-9_-]{20,128}")
        const val serialVersionUID = 1L
    }
}
