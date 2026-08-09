package io.bluetape4k.clinic.appointment.api.config

import io.bluetape4k.clinic.appointment.event.notification.CancellationReasonCode
import io.bluetape4k.clinic.appointment.messaging.AppointmentMessagingContext
import io.bluetape4k.clinic.appointment.messaging.AppointmentOutboxWriter
import io.bluetape4k.clinic.appointment.model.dto.AppointmentRecord
import io.bluetape4k.clinic.appointment.model.service.TenantClinicScope
import io.bluetape4k.clinic.appointment.statemachine.AppointmentState
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** ApplicationContextRunner가 messaging auto-configuration을 의도적으로 생략할 때 사용하는 명시적 writer입니다. */
@Configuration(proxyBeanMethods = false)
internal class AppointmentMessagingTestConfiguration {

    @Bean
    fun appointmentOutboxWriter(): AppointmentOutboxWriter = NoopAppointmentOutboxWriter
}

private object NoopAppointmentOutboxWriter : AppointmentOutboxWriter {
    override fun created(
        scope: TenantClinicScope,
        appointment: AppointmentRecord,
        context: AppointmentMessagingContext,
    ) = Unit

    override fun statusChanged(
        scope: TenantClinicScope,
        appointment: AppointmentRecord,
        fromState: AppointmentState,
        toState: AppointmentState,
        context: AppointmentMessagingContext,
        reasonCode: CancellationReasonCode?,
    ) = Unit

    override fun cancelled(
        scope: TenantClinicScope,
        appointment: AppointmentRecord,
        context: AppointmentMessagingContext,
        reasonCode: CancellationReasonCode?,
    ) = Unit

    override fun rescheduled(
        scope: TenantClinicScope,
        original: AppointmentRecord,
        replacement: AppointmentRecord,
        context: AppointmentMessagingContext,
    ) = Unit
}
