package io.bluetape4k.clinic.appointment.api.service

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/** 예약 생성 멱등성 보관 기간 설정. */
@ConfigurationProperties(prefix = "scheduling.appointment.idempotency")
data class AppointmentIdempotencyProperties(
    val ttl: Duration = Duration.ofHours(24),
) {
    init {
        require(!ttl.isNegative && !ttl.isZero) { "scheduling.appointment.idempotency.ttl must be positive" }
    }
}
