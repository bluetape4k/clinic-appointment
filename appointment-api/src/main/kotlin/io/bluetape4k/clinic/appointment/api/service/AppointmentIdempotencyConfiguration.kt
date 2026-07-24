package io.bluetape4k.clinic.appointment.api.service

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AppointmentIdempotencyProperties::class)
class AppointmentIdempotencyConfiguration {
    @Bean
    fun appointmentIdempotencyClock(): Clock = Clock.systemUTC()
}
