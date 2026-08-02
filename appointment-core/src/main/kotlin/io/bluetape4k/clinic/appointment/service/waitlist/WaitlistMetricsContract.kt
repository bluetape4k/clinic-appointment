package io.bluetape4k.clinic.appointment.service.waitlist

import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistReasonCode
import java.io.Serializable

/** waitlist metric label의 검증된 low-cardinality snapshot입니다. */
data class WaitlistMetricLabels(
    val values: Map<String, String>,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * waitlist core가 외부 metric adapter에 제공하는 이름·label 계약입니다.
 *
 * 이 계약은 Micrometer를 직접 의존하지 않습니다. core는 식별자 없는 결과만 노출하고,
 * 실제 registry wiring은 API/운영 adapter가 담당합니다.
 */
object WaitlistMetricsContract {
    const val OFFER_ACTIVE = "waitlist_offer_active"
    const val HOLD_ACTIVE = "waitlist_hold_active"
    const val CLAIM_CONFLICT_TOTAL = "waitlist_claim_conflict_total"
    const val DECISION_UNAVAILABLE_TOTAL = "waitlist_decision_unavailable_total"
    const val EXPIRY_BACKLOG = "waitlist_expiry_backlog"
    const val HOLD_RECONCILE_AGE_SECONDS = "waitlist_hold_reconcile_age_seconds"

    val METER_NAMES: Set<String> = setOf(
        OFFER_ACTIVE,
        HOLD_ACTIVE,
        CLAIM_CONFLICT_TOTAL,
        DECISION_UNAVAILABLE_TOTAL,
        EXPIRY_BACKLOG,
        HOLD_RECONCILE_AGE_SECONDS,
    )

    private val allowedLabelNames = setOf(
        "tenant_partition",
        "clinic_partition",
        "result",
        "reason",
        "resource_type",
    )
    private val forbiddenLabelNames = setOf(
        "member_id",
        "offer_id",
        "hold_id",
        "entry_id",
        "correlation_id",
        "actor_ref",
        "phone",
        "email",
        "name",
    )
    private val safeLabelValue = Regex("^[A-Za-z][A-Za-z0-9_-]{0,31}$")
    private val sensitiveMarkers = setOf("member", "patient", "phone", "email", "jwt", "token")

    /** 허용된 결과 code와 이미 비식별화한 tenant/clinic partition만 label로 반환합니다. */
    fun labels(vararg labels: Pair<String, String>): WaitlistMetricLabels {
        require(labels.isNotEmpty()) { "labels must not be empty" }
        val values = labels.toMap()
        require(values.size == labels.size) { "duplicate metric label names are not allowed" }
        values.forEach { (name, value) ->
            require(name !in forbiddenLabelNames) { "metric label name is forbidden" }
            require(name in allowedLabelNames) { "metric label name is not allowlisted" }
            require(safeLabelValue.matches(value)) { "metric label value contains unsafe characters" }
            require(sensitiveMarkers.none { value.contains(it, ignoreCase = true) }) {
                "metric label value looks sensitive"
            }
        }
        return WaitlistMetricLabels(values)
    }

    /** bounded reason code를 metric label용 소문자 값으로 변환합니다. */
    fun reasonLabel(reason: WaitlistReasonCode): String = reason.code.lowercase()
}
