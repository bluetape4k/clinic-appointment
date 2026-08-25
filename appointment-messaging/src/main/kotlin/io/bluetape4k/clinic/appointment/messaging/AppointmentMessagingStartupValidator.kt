package io.bluetape4k.clinic.appointment.messaging

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import org.springframework.beans.factory.SmartInitializingSingleton

/**
 * DataSource가 있는 애플리케이션에서 V22~V25 schema와 serializer 계약을 context
 * startup 때 fail-fast로 확인한다. broker outage는 relay readiness 문제이므로 startup을 막지 않는다.
 */
class AppointmentMessagingStartupValidator(
    private val properties: AppointmentMessagingProperties,
    private val readiness: AppointmentMessagingReadinessProbe,
    private val validator: AppointmentMessagingReadinessValidator,
) : SmartInitializingSingleton {
    override fun afterSingletonsInstantiated() {
        if (!properties.enabled) return

        validator.validate(readiness)
        val state = readiness.snapshot()
        if (state.diagnostics.isNotEmpty()) {
            log.warn {
                "Appointment messaging readiness diagnostics: ${state.diagnostics.joinToString { it.safeSummary() }}"
            }
        }
        check(state.schemaValid) {
            "appointment messaging V22~V25 schema contract is unavailable: " +
                state.diagnostics.filter { it.operation.startsWith("schema.") }.joinToString { it.safeSummary() }
        }
        check(state.registryValid) { "appointment messaging Schema Registry compatibility is unavailable" }
        check(state.serializerValid) { "appointment messaging serializer contract is unavailable" }
    }

    private fun AppointmentReadinessDiagnostic.safeSummary(): String =
        "${code}(operation=$operation, target=$target, retryable=$retryable)"

    private companion object : KLogging()
}
