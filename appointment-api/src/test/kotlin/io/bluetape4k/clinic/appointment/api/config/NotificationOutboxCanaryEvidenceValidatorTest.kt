package io.bluetape4k.clinic.appointment.api.config

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper

/**
 * Issue #204 canary report가 production SLO 주장이나 원문 payload를 포함하지 않는지 검증합니다.
 */
internal object NotificationOutboxCanaryEvidenceValidator {

    fun validate(report: JsonNode) {
        require(report.requiredText("evidenceMode") == "production-like-container-backed") {
            "evidenceMode must identify the container-backed simulation"
        }
        require(!report.requiredBoolean("productionSloEvidence")) {
            "production SLO evidence must remain false"
        }
        require(!report.requiredBoolean("productionClaim")) {
            "production claim must remain false"
        }
        require(report.requiredInt("workload.logicalNotifications") == LOGICAL_NOTIFICATIONS) {
            "workload must contain exactly $LOGICAL_NOTIFICATIONS logical notifications"
        }
        require(report.requiredBoolean("rollback.queuePreserved")) {
            "rollback must preserve the queue"
        }
        require(report.requiredBoolean("rollback.workerStoppedAndRestarted")) {
            "rollback must stop and restart the worker"
        }
        listOf(
            "deliveryResultUnknown",
            "duplicateProviderResults",
            "criticalAlerts",
            "claimFailures",
            "unresolvedRows",
            "redisLeakedKeys",
            "kafkaLagRecords",
        ).forEach { threshold ->
            require(report.requiredInt("thresholds.$threshold") == 0) {
                "threshold $threshold must be zero"
            }
        }
        require(report.requiredInt("rollback.providerCallsDuringPause") == 0) {
            "paused rollback must not call the provider"
        }
        require(report.requiredInt("redaction.terminalRowViolations") == 0) {
            "terminal rows must not retain raw routing fields"
        }
        require(report.requiredInt("idempotency.replayedRequests") >= 1) {
            "provider idempotency replay must be exercised"
        }
        require(report.requiredInt("idempotency.duplicateAcceptedResults") == 0) {
            "idempotency replay must not create a duplicate accepted result"
        }
        require(report.requiredText("health.component") == "notificationOutboxHealth") {
            "health evidence must identify notificationOutboxHealth"
        }
        require(report.requiredText("health.status") == "UP") {
            "notificationOutboxHealth must be UP"
        }
        require(report.requiredBoolean("health.redacted")) {
            "health evidence must be redacted"
        }
        require(report.requiredLong("thresholds.oldestReadyAgeSeconds") == 0L) {
            "final oldest ready age must be zero"
        }
        require(report.requiredLong("thresholds.readyBacklog") == 0L) {
            "final ready backlog must be zero"
        }
        require(report.requiredDouble("thresholds.providerThroughputPerSecond") > 0.0) {
            "provider throughput must be measured"
        }
        listOf("rawPayloadFields", "secretFields", "destinationFields").forEach { field ->
            require(report.requiredInt("redaction.$field") == 0) {
                "redaction field $field must be zero"
            }
        }
        require(!containsForbiddenField(report)) {
            "report must not contain raw payload, destination, credential, or token fields"
        }
    }

    private fun containsForbiddenField(node: JsonNode): Boolean {
        if (node.isObject) {
            val fields = node.properties().asSequence().map { it.key }.toList()
            if (fields.any { field -> field.lowercase() in FORBIDDEN_FIELDS }) return true
            return fields.any { field -> containsForbiddenField(node[field]) }
        }
        if (node.isArray) return node.any(::containsForbiddenField)
        return false
    }

    private fun JsonNode.requiredText(path: String): String = requiredNode(path).stringValue()
        ?: error("$path must be a text value")

    private fun JsonNode.requiredBoolean(path: String): Boolean = requiredNode(path).takeIf { it.isBoolean }?.booleanValue()
        ?: error("$path must be a boolean value")

    private fun JsonNode.requiredInt(path: String): Int = requiredNode(path).takeIf { it.isIntegralNumber }?.intValue()
        ?: error("$path must be an integer value")

    private fun JsonNode.requiredLong(path: String): Long = requiredNode(path).takeIf { it.isIntegralNumber }?.longValue()
        ?: error("$path must be an integer value")

    private fun JsonNode.requiredDouble(path: String): Double = requiredNode(path).takeIf { it.isNumber }?.doubleValue()
        ?: error("$path must be a numeric value")

    private fun JsonNode.requiredNode(path: String): JsonNode {
        var current = this
        path.split('.').forEach { segment ->
            current = current.get(segment) ?: error("missing report field: $path")
        }
        return current
    }

    private const val LOGICAL_NOTIFICATIONS = 1_000
    private val FORBIDDEN_FIELDS = setOf(
        "destination",
        "destinationvalue",
        "memberid",
        "parameters",
        "payload",
        "rawpayload",
        "rendered",
        "secret",
        "credential",
        "token",
    )
}

internal class NotificationOutboxCanaryEvidenceValidatorTest {

    private val objectMapper = jacksonObjectMapper()

    @Test
    fun `고정 canary report는 production-like 경계와 1000건 rollback 계약을 가진다`() {
        val report = javaClass.getResourceAsStream(REPORT_RESOURCE)?.use(objectMapper::readTree)
            ?: error("canary report fixture is missing: $REPORT_RESOURCE")

        report.path("evidenceMode").stringValue() shouldBeEqualTo "production-like-container-backed"
        report.path("productionSloEvidence").asBoolean() shouldBeEqualTo false
        report.path("workload").path("logicalNotifications").asInt() shouldBeEqualTo 1_000
        report.path("rollback").path("queuePreserved").asBoolean().shouldBeTrue()
        NotificationOutboxCanaryEvidenceValidator.validate(report)
    }

    @Test
    fun `검증기는 production 주장과 원문 식별자 필드를 fail closed 한다`() {
        val report = objectMapper.readTree(
            javaClass.getResourceAsStream(REPORT_RESOURCE)?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?.replace(Regex("\\\"productionSloEvidence\\\"\\s*:\\s*false"), "\"productionSloEvidence\": true")
                ?: error("canary report fixture is missing: $REPORT_RESOURCE")
        )

        assertFailsWith<IllegalArgumentException> {
            NotificationOutboxCanaryEvidenceValidator.validate(report)
        }

        val redactedReport = objectMapper.readTree(
            javaClass.getResourceAsStream(REPORT_RESOURCE)?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?.replace(
                    Regex("\\\"redaction\\\"\\s*:\\s*\\{"),
                    "\"redaction\": {\n    \"memberId\": \"member-raw\",",
                )
                ?: error("canary report fixture is missing: $REPORT_RESOURCE")
        )

        assertFailsWith<IllegalArgumentException> {
            NotificationOutboxCanaryEvidenceValidator.validate(redactedReport)
        }
    }

    private companion object {
        const val REPORT_RESOURCE = "/notification-outbox-canary/production-like-report.json"
    }
}
