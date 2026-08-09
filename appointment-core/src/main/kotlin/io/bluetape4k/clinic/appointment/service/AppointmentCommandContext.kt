package io.bluetape4k.clinic.appointment.service

import java.io.Serializable
import java.util.UUID

/**
 * 예약 mutation을 추적하는 검증된 correlation 식별자다.
 *
 * 이 값은 trace continuity만 표현하며 tenant 권한, audit 주체, 중복 제거 키로
 * 사용하지 않는다. HTTP header의 원문을 그대로 신뢰하지 않고 application 경계에서
 * 한 번 검증한 뒤 이 타입으로 전달한다.
 */
@JvmInline
value class AppointmentCorrelationId(val value: String) : Serializable {
    init {
        validateAppointmentCommandMetadata(value, "correlationId")
    }
}

/**
 * 예약 mutation을 직접 유발한 command/event의 서버 생성 식별자다.
 */
@JvmInline
value class AppointmentCausationId(val value: String) : Serializable {
    init {
        validateAppointmentCommandMetadata(value, "causationId")
    }
}

/**
 * 예약 command의 trace lineage를 dependency-neutral하게 전달하는 core 계약.
 *
 * root command는 correlation과 causation을 동일하게 설정한다. downstream command는
 * 직접 원인이 된 event/command id를 causation으로 보존한다. 이 타입은
 * `appointment-core`가 event/messaging 모듈에 역으로 의존하지 않도록 core에 둔다.
 */
class AppointmentCommandContext private constructor(
    val correlationId: AppointmentCorrelationId,
    val causationId: AppointmentCausationId,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L

        /** HTTP 또는 내부 workflow의 root command context를 만든다. */
        @JvmStatic
        fun root(correlationId: String): AppointmentCommandContext {
            val correlation = AppointmentCorrelationId(correlationId)
            return AppointmentCommandContext(
                correlationId = correlation,
                causationId = AppointmentCausationId(correlation.value),
            )
        }

        /** HTTP caller correlation은 보존하고 command causation은 서버에서 새로 만든다. */
        @JvmStatic
        fun httpRoot(correlationId: String): AppointmentCommandContext =
            derived(
                correlationId = correlationId,
                causationId = "http-command-${UUID.randomUUID()}",
            )

        /** upstream event/command가 직접 원인이 된 child command context를 만든다. */
        @JvmStatic
        fun derived(
            correlationId: String,
            causationId: String,
        ): AppointmentCommandContext = AppointmentCommandContext(
            correlationId = AppointmentCorrelationId(correlationId),
            causationId = AppointmentCausationId(causationId),
        )

        /** 이미 검증된 식별자로 root context를 만든다. */
        internal fun root(correlationId: AppointmentCorrelationId): AppointmentCommandContext =
            AppointmentCommandContext(correlationId, AppointmentCausationId(correlationId.value))

        /** 이미 검증된 식별자로 child context를 만든다. */
        internal fun derived(
            correlationId: AppointmentCorrelationId,
            causationId: AppointmentCausationId,
        ): AppointmentCommandContext = AppointmentCommandContext(correlationId, causationId)
    }

    override fun toString(): String =
        "AppointmentCommandContext(correlationId=${correlationId.value}, causationId=${causationId.value})"
}

private val appointmentCommandMetadataPattern = Regex("^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$")
private val appointmentEmailLikePattern = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")

private fun validateAppointmentCommandMetadata(value: String, fieldName: String) {
    require(value.isNotBlank()) { "$fieldName must not be blank" }
    require(value.length <= 128) { "$fieldName must not exceed 128 characters" }
    require(appointmentCommandMetadataPattern.matches(value)) {
        "$fieldName must use bounded opaque characters"
    }
    require(!appointmentEmailLikePattern.containsMatchIn(value)) {
        "$fieldName must not contain email-like values"
    }
    require(value.none(Char::isISOControl)) { "$fieldName must not contain control characters" }
}
