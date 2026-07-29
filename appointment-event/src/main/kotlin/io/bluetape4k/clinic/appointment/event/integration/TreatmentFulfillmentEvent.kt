package io.bluetape4k.clinic.appointment.event.integration

import io.bluetape4k.clinic.appointment.model.plan.ExecutionTreatment
import io.bluetape4k.support.requireNotBlank
import java.io.Serializable
import java.security.MessageDigest
import java.time.Instant

/**
 * 임상·환불 소유 서비스가 확정한 Plan 진료 의무의 외부 사실 묶음입니다.
 *
 * 예약서비스는 임상 완료나 환불 가능 여부를 결정하지 않습니다. 이 event의 verified
 * fact를 불변 Plan revision과 예약 재계산 dirty-set으로 투영합니다.
 */
data class TreatmentFulfillmentEvent(
    val sourceAggregateId: String,
    val sourceAggregateVersion: Long,
    val tenantGroupId: Long,
    val clinicId: Long,
    val sourcePurchaseAuthority: String,
    val sourcePurchaseId: String,
    val facts: List<TreatmentFulfillmentFact>,
) : Serializable {
    init {
        sourceAggregateId.requireNotBlank("sourceAggregateId")
        require(sourceAggregateVersion > 0) { "sourceAggregateVersion must be positive" }
        require(tenantGroupId > 0) { "tenantGroupId must be positive" }
        require(clinicId > 0) { "clinicId must be positive" }
        sourcePurchaseAuthority.requireNotBlank("sourcePurchaseAuthority")
        sourcePurchaseId.requireNotBlank("sourcePurchaseId")
        require(facts.isNotEmpty()) { "facts must not be empty" }
        require(facts.map(TreatmentFulfillmentFact::treatmentKey).distinct().size == facts.size) {
            "facts must contain at most one outcome per treatmentKey"
        }
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * Plan treatment 하나에 대한 권위 있는 결과입니다.
 *
 * @property type 완료, 부분 이행, 자원 장애 분리, 환불 중 하나입니다.
 * @property occurredAt 외부 소유 서비스가 확정한 UTC 업무 시각입니다.
 * @property completedTreatment 부분 이행 또는 자원 장애 전 실제 완료된 부분의 정확한
 * 실행 정의입니다. 원 항목과 같은 key를 사용하며 완료하지 않은 세부 진료를 포함하면
 * 안 됩니다.
 * @property remainingTreatment 부분 이행 또는 자원 장애로 별도 방문이 필요한 잔여
 * 의무의 완전한 실행 정의입니다. 예약서비스가 원 진료명·시간을 추측하지 않도록
 * producer가 제공합니다.
 * @property reasonCode 자원 장애 같은 운영 원인을 표현하는 안정적인 code입니다.
 */
data class TreatmentFulfillmentFact(
    val treatmentKey: String,
    val type: TreatmentFulfillmentFactType,
    val occurredAt: Instant,
    val completedTreatment: ExecutionTreatment? = null,
    val remainingTreatment: ExecutionTreatment? = null,
    val reasonCode: String? = null,
) : Serializable {
    init {
        treatmentKey.requireNotBlank("treatmentKey")
        when (type) {
            TreatmentFulfillmentFactType.COMPLETED,
            TreatmentFulfillmentFactType.REFUNDED,
            -> require(completedTreatment == null && remainingTreatment == null) {
                "$type must not contain split treatment definitions"
            }

            TreatmentFulfillmentFactType.PARTIALLY_FULFILLED -> {
                requireNotNull(completedTreatment) {
                    "PARTIALLY_FULFILLED requires completedTreatment"
                }
                requireNotNull(remainingTreatment) {
                    "PARTIALLY_FULFILLED requires remainingTreatment"
                }
            }

            TreatmentFulfillmentFactType.RESOURCE_DISRUPTED -> {
                requireNotNull(completedTreatment) {
                    "RESOURCE_DISRUPTED requires completedTreatment"
                }
                requireNotNull(remainingTreatment) {
                    "RESOURCE_DISRUPTED requires remainingTreatment"
                }
                require(!reasonCode.isNullOrBlank()) {
                    "RESOURCE_DISRUPTED requires reasonCode"
                }
            }
        }
        completedTreatment?.let {
            require(it.treatmentKey == treatmentKey) {
                "completedTreatment must retain the original treatmentKey"
            }
        }
        remainingTreatment?.let {
            require(it.treatmentKey != treatmentKey) {
                "remainingTreatment must use a new treatmentKey"
            }
        }
    }

    companion object {
        private const val serialVersionUID = 1L

        fun completed(treatmentKey: String, completedAt: Instant) =
            TreatmentFulfillmentFact(treatmentKey, TreatmentFulfillmentFactType.COMPLETED, completedAt)

        fun partiallyFulfilled(
            treatmentKey: String,
            completedAt: Instant,
            completedTreatment: ExecutionTreatment,
            remainingTreatment: ExecutionTreatment,
        ) = TreatmentFulfillmentFact(
            treatmentKey,
            TreatmentFulfillmentFactType.PARTIALLY_FULFILLED,
            completedAt,
            completedTreatment,
            remainingTreatment,
        )

        fun resourceDisrupted(
            treatmentKey: String,
            occurredAt: Instant,
            completedTreatment: ExecutionTreatment,
            remainingTreatment: ExecutionTreatment,
            reasonCode: String,
        ) = TreatmentFulfillmentFact(
            treatmentKey,
            TreatmentFulfillmentFactType.RESOURCE_DISRUPTED,
            occurredAt,
            completedTreatment,
            remainingTreatment,
            reasonCode,
        )

        fun refunded(treatmentKey: String, refundedAt: Instant) =
            TreatmentFulfillmentFact(treatmentKey, TreatmentFulfillmentFactType.REFUNDED, refundedAt)
    }
}

/** 외부 사실이 예약 Plan에 미치는 의미입니다. */
enum class TreatmentFulfillmentFactType {
    /** 원 진료 의무가 임상적으로 모두 완료됐습니다. */
    COMPLETED,

    /** 원 의무 일부가 완료됐고 producer가 별도 예약할 잔여 의무를 제공했습니다. */
    PARTIALLY_FULFILLED,

    /** 장비·공간 등 자원 장애로 잔여 의무를 별도 방문으로 분리해야 합니다. */
    RESOURCE_DISRUPTED,

    /** 환불 서비스가 원 의무와 `BLOCKING` 후속 의무의 취소를 확정했습니다. */
    REFUNDED,
}

/**
 * 외부 사실 전체를 안정적인 field frame으로 계산하는 SHA-256 hasher입니다.
 */
object TreatmentFulfillmentPayloadHasher {
    fun hash(event: TreatmentFulfillmentEvent): String =
        MessageDigest.getInstance("SHA-256")
            .digest(canonicalBytes(event))
            .joinToString("") { byte -> "%02x".format(byte) }

    /**
     * payload hash와 실제 크기 제한이 공유하는 canonical bytes입니다.
     *
     * hash 문자열 길이가 아니라 이 byte 배열의 크기를 검사해야 대량 중첩 treatment
     * 정의가 object 경계를 통해 직접 주입되는 경우에도 메모리·DB 경계를 지킬 수
     * 있습니다.
     */
    internal fun canonicalBytes(event: TreatmentFulfillmentEvent): ByteArray =
        CanonicalFrameWriter().apply {
            string("sourceAggregateId", event.sourceAggregateId)
            long("sourceAggregateVersion", event.sourceAggregateVersion)
            long("tenantGroupId", event.tenantGroupId)
            long("clinicId", event.clinicId)
            string("sourcePurchaseAuthority", event.sourcePurchaseAuthority)
            string("sourcePurchaseId", event.sourcePurchaseId)
            int("facts.size", event.facts.size)
            event.facts.forEachIndexed { index, fact ->
                string("facts[$index].treatmentKey", fact.treatmentKey)
                string("facts[$index].type", fact.type.name)
                string("facts[$index].occurredAt", fact.occurredAt.toString())
                string("facts[$index].reasonCode", fact.reasonCode)
                fact.completedTreatment?.let { completed ->
                    executionTreatment("facts[$index].completed", completed)
                }
                fact.remainingTreatment?.let { remaining ->
                    executionTreatment("facts[$index].remaining", remaining)
                }
            }
        }.toByteArray()

    private fun CanonicalFrameWriter.executionTreatment(
        prefix: String,
        treatment: ExecutionTreatment,
    ) {
        string("$prefix.key", treatment.treatmentKey)
        string("$prefix.componentProductId", treatment.componentProductId)
        string("$prefix.componentProductVersionId", treatment.componentProductVersionId)
        string("$prefix.sourceBomItemId", treatment.sourceBomItemId)
        int("$prefix.sequence", treatment.sequence)
        string("$prefix.name", treatment.representativeTreatmentName)
        int("$prefix.codes.size", treatment.detailedTreatmentCodes.size)
        treatment.detailedTreatmentCodes.forEachIndexed { index, code ->
            string("$prefix.codes[$index]", code)
        }
        int("$prefix.preparation", treatment.preparationMinutes)
        int("$prefix.treatment", treatment.treatmentMinutes)
        int("$prefix.recovery", treatment.recoveryMinutes)
    }
}
