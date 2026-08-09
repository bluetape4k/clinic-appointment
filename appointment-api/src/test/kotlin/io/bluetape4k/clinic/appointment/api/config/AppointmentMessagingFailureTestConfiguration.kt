package io.bluetape4k.clinic.appointment.api.config

import io.bluetape4k.clinic.appointment.event.notification.CancellationReasonCode
import io.bluetape4k.clinic.appointment.messaging.AppointmentMessagingContext
import io.bluetape4k.clinic.appointment.messaging.AppointmentMessagingContractException
import io.bluetape4k.clinic.appointment.messaging.AppointmentMessagingFailureCode
import io.bluetape4k.clinic.appointment.messaging.AppointmentOutboxWriter
import io.bluetape4k.clinic.appointment.messaging.DefaultAppointmentOutboxWriter
import io.bluetape4k.clinic.appointment.model.dto.AppointmentRecord
import io.bluetape4k.clinic.appointment.model.service.TenantClinicScope
import io.bluetape4k.clinic.appointment.statemachine.AppointmentState
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary

/** Closure API가 실제 outbox writer 실패를 주입해 transaction rollback을 검증하도록 하는 스위치입니다. */
class AppointmentMessagingFailureSwitch {
    @Volatile
    var failStatusChanged: Boolean = false
}

/** 실제 writer의 다른 capability는 유지하고 상태 이벤트만 deterministic하게 실패시킵니다. */
@TestConfiguration(proxyBeanMethods = false)
class AppointmentMessagingFailureTestConfiguration {

    @Bean
    fun appointmentMessagingFailureSwitch(): AppointmentMessagingFailureSwitch =
        AppointmentMessagingFailureSwitch()

    @Bean
    @Primary
    fun failureAppointmentOutboxWriter(
        failureSwitch: AppointmentMessagingFailureSwitch,
    ): AppointmentOutboxWriter = FailingAppointmentOutboxWriter(
        delegate = DefaultAppointmentOutboxWriter(),
        failureSwitch = failureSwitch,
    )
}

private class FailingAppointmentOutboxWriter(
    private val delegate: AppointmentOutboxWriter,
    private val failureSwitch: AppointmentMessagingFailureSwitch,
) : AppointmentOutboxWriter {

    override fun created(
        scope: TenantClinicScope,
        appointment: AppointmentRecord,
        context: AppointmentMessagingContext,
    ) = delegate.created(scope, appointment, context)

    override fun statusChanged(
        scope: TenantClinicScope,
        appointment: AppointmentRecord,
        fromState: AppointmentState,
        context: AppointmentMessagingContext,
        reasonCode: CancellationReasonCode?,
    ) {
        if (failureSwitch.failStatusChanged) {
            throw AppointmentMessagingContractException(
                failureCode = AppointmentMessagingFailureCode.OUTBOX_PERSISTENCE_UNAVAILABLE,
            )
        }
        delegate.statusChanged(scope, appointment, fromState, context, reasonCode)
    }

    override fun cancelled(
        scope: TenantClinicScope,
        appointment: AppointmentRecord,
        context: AppointmentMessagingContext,
        reasonCode: CancellationReasonCode?,
    ) = delegate.cancelled(scope, appointment, context, reasonCode)

    override fun rescheduled(
        scope: TenantClinicScope,
        original: AppointmentRecord,
        replacement: AppointmentRecord,
        context: AppointmentMessagingContext,
    ) = delegate.rescheduled(scope, original, replacement, context)
}
