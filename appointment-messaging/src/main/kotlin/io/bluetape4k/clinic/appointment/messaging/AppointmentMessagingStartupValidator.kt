package io.bluetape4k.clinic.appointment.messaging

import org.springframework.beans.factory.SmartInitializingSingleton

/**
 * DataSource가 있는 애플리케이션에서 V22/V23 schema와 serializer 계약을 context
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
        check(state.schemaValid) { "appointment messaging V22/V23 schema contract is unavailable" }
        check(state.serializerValid) { "appointment messaging serializer contract is unavailable" }
    }
}
