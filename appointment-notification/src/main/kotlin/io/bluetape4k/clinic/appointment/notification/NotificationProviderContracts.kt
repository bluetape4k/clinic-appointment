package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.clinic.appointment.event.notification.NotificationChannelType
import io.bluetape4k.clinic.appointment.event.notification.NotificationFailureCode
import io.bluetape4k.clinic.appointment.event.notification.NotificationIdempotencyKey
import io.bluetape4k.clinic.appointment.event.notification.NotificationProviderMessageReference
import io.bluetape4k.clinic.appointment.event.notification.NotificationSuppressionReasonCode
import io.bluetape4k.clinic.appointment.event.notification.NotificationTemplateKey
import io.bluetape4k.clinic.appointment.event.notification.NotificationTemplateVersion
import java.io.Serializable
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * provider adapter로 넘기는 발송 요청입니다.
 *
 * destination과 rendered body는 이 객체의 수명 동안만 메모리에 존재해야 하며 outbox,
 * history, metric tag, exception message에 복사하지 않는다.
 */
data class NotificationProviderRequest(
    val channel: NotificationChannelType,
    val destination: String,
    val idempotencyKey: NotificationProviderIdempotencyKey,
    val templateKey: NotificationTemplateKey,
    val templateVersion: NotificationTemplateVersion,
    val rendered: RenderedNotificationTemplate,
) : Serializable {
    init {
        validateRuntimeText(destination, "destination", 320)
    }

    override fun toString(): String =
        "NotificationProviderRequest(channel=$channel, destination=<redacted>, idempotencyKey=<redacted>, templateKey=$templateKey, templateVersion=$templateVersion, rendered=<redacted>)"

    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * provider에 전달할 수 있는 불투명 idempotency key입니다.
 *
 * 원문 예약·회원·tenant 식별자를 포함하지 않는 HMAC 결과만 허용한다.
 */
@JvmInline
value class NotificationProviderIdempotencyKey(val value: String) : Serializable {
    init {
        require(PROVIDER_IDEMPOTENCY_KEY.matches(value)) {
            "idempotencyKey must be a provider-domain HMAC-SHA256 digest"
        }
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

private val PROVIDER_IDEMPOTENCY_KEY = Regex("hmac-v1\\.[A-Za-z0-9_-]{43}")

/**
 * outbox의 이미 불투명한 idempotency key를 provider domain으로 다시 HMAC 처리합니다.
 *
 * caller가 임의 raw material을 전달하는 API는 제공하지 않는다.
 */
class NotificationProviderIdempotencyKeyFactory(
    secret: ByteArray,
    private val domain: String = "clinic-notification-provider-v1",
) {
    private val key = SecretKeySpec(secret.copyOf(), HMAC_ALGORITHM)
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    init {
        require(secret.size >= MIN_SECRET_BYTES) { "secret must be at least 32 bytes" }
        validateTemplateToken(domain, "domain", 128)
    }

    fun create(idempotencyKey: NotificationIdempotencyKey): NotificationProviderIdempotencyKey {
        require(IDEMPOTENCY_DIGEST.matches(idempotencyKey.value)) {
            "idempotencyKey must be an HMAC-SHA256 digest"
        }
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(key)
        val digest = mac.doFinal("$domain:${idempotencyKey.value}".toByteArray(Charsets.UTF_8))
        return NotificationProviderIdempotencyKey("hmac-v1.${encoder.encodeToString(digest)}")
    }

    private companion object {
        const val HMAC_ALGORITHM = "HmacSHA256"
        const val MIN_SECRET_BYTES = 32
        val IDEMPOTENCY_DIGEST = Regex("[0-9a-f]{64}")
    }
}

/**
 * provider 호출의 닫힌 결과입니다.
 *
 * 실패나 suppression은 Task2 enum으로만 표현하고 provider payload나 raw error message는
 * 이 계약에 싣지 않는다.
 */
sealed class NotificationProviderResult : Serializable {
    data class Accepted(
        val providerMessageReference: NotificationProviderMessageReference? = null,
    ) : NotificationProviderResult() {
        companion object {
            private const val serialVersionUID = 1L
        }
    }

    data class RetryableFailure(
        val failureCode: NotificationFailureCode,
    ) : NotificationProviderResult() {
        companion object {
            private const val serialVersionUID = 1L
        }
    }

    data class Suppressed(
        val reason: NotificationSuppressionReasonCode,
    ) : NotificationProviderResult() {
        companion object {
            private const val serialVersionUID = 1L
        }
    }

    companion object {
        private const val serialVersionUID = 1L

        fun accepted(reference: NotificationProviderMessageReference? = null): NotificationProviderResult =
            Accepted(reference)

        fun retry(failureCode: NotificationFailureCode): NotificationProviderResult =
            RetryableFailure(failureCode)

        fun suppressed(reason: NotificationSuppressionReasonCode): NotificationProviderResult =
            Suppressed(reason)
    }
}

/**
 * provider adapter가 worker에 전달하는 typed exception입니다.
 *
 * message에는 provider payload, credential, destination, raw id를 넣지 않는다.
 */
class NotificationProviderException(
    val failureCode: NotificationFailureCode,
) : RuntimeException(failureCode.name) {
    companion object {
        private const val serialVersionUID = 1L
    }
}

object NotificationProviderFailureMapper {
    /** 외부 예외를 원문 메시지 없이 Task2의 닫힌 failure code로 축약합니다. */
    fun fromException(exception: Throwable): NotificationFailureCode =
        when (exception) {
            is java.util.concurrent.TimeoutException -> NotificationFailureCode.PROVIDER_UNAVAILABLE
            is io.github.resilience4j.ratelimiter.RequestNotPermitted -> NotificationFailureCode.PROVIDER_RATE_LIMITED
            is io.github.resilience4j.circuitbreaker.CallNotPermittedException -> NotificationFailureCode.CIRCUIT_OPEN
            else -> NotificationFailureCode.PROVIDER_UNAVAILABLE
        }
}
