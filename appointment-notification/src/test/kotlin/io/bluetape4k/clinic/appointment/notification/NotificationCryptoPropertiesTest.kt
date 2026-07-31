package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import java.time.Instant
import org.junit.jupiter.api.Test

internal class NotificationCryptoPropertiesTest {

    @Test
    fun `crypto 설정은 active secret reference만 허용하고 raw key material을 오류와 toString에 노출하지 않는다`() {
        val failure = assertFailsWith<IllegalStateException> {
            NotificationCryptoProperties(
                active = NotificationCryptoProperties.KeyReference(
                    keyId = "active-2026-07",
                    secretReference = "raw:super-secret-key-material",
                    activatedAt = Instant.parse("2026-07-01T00:00:00Z"),
                    expiresAt = Instant.parse("2026-08-01T00:00:00Z"),
                ),
            ).validate(Instant.parse("2026-07-15T00:00:00Z"))
        }

        failure.message!!.contains("super-secret") shouldBeEqualTo false
        assertFailsWith<IllegalStateException> {
            NotificationCryptoProperties(
                active = NotificationCryptoProperties.KeyReference(
                    keyId = "active-2026-07",
                    secretReference = "super-secret-key-material",
                    activatedAt = Instant.parse("2026-07-01T00:00:00Z"),
                    expiresAt = Instant.parse("2026-08-01T00:00:00Z"),
                ),
            ).validate(Instant.parse("2026-07-15T00:00:00Z"))
        }
        NotificationCryptoProperties.KeyReference(
            keyId = "active-2026-07",
            secretReference = "aws-secretsmanager:/clinic/notification/active",
            activatedAt = Instant.parse("2026-07-01T00:00:00Z"),
            expiresAt = Instant.parse("2026-08-01T00:00:00Z"),
        ).toString().contains("aws-secretsmanager") shouldBeEqualTo false
    }

    @Test
    fun `crypto key ring은 active 만료와 previous 중복 또는 과도한 overlap을 거절한다`() {
        val now = Instant.parse("2026-07-31T00:00:00Z")

        assertFailsWith<IllegalStateException> {
            NotificationCryptoProperties(
                active = key("active", expiresAt = Instant.parse("2026-07-30T00:00:00Z")),
            ).validate(now)
        }
        assertFailsWith<IllegalStateException> {
            NotificationCryptoProperties(
                active = key("same"),
                previous = key("same"),
            ).validate(now)
        }
        assertFailsWith<IllegalStateException> {
            NotificationCryptoProperties(
                active = key("active"),
                previous = key("previous", expiresAt = Instant.parse("2026-06-20T00:00:00Z")),
            ).validate(now)
        }
        assertFailsWith<IllegalStateException> {
            NotificationCryptoProperties(
                active = key("active"),
                previous = key(
                    keyId = "previous",
                    activatedAt = Instant.parse("2026-06-01T00:00:00Z"),
                    expiresAt = Instant.parse("2026-08-31T00:00:00Z"),
                ),
            ).validate(now)
        }
    }

    @Test
    fun `이전 key는 active 전부터 사용했고 35일 이내 overlap이면 허용한다`() {
        NotificationCryptoProperties(
            active = key("active"),
            previous = key(
                keyId = "previous",
                activatedAt = Instant.parse("2026-04-01T00:00:00Z"),
                expiresAt = Instant.parse("2026-08-04T00:00:00Z"),
            ),
        ).validate(Instant.parse("2026-07-31T00:00:00Z"))
    }

    private fun key(
        keyId: String,
        activatedAt: Instant = Instant.parse("2026-07-01T00:00:00Z"),
        expiresAt: Instant = Instant.parse("2026-08-31T00:00:00Z"),
    ): NotificationCryptoProperties.KeyReference =
        NotificationCryptoProperties.KeyReference(
            keyId = keyId,
            secretReference = "vault:/clinic/notification/$keyId",
            activatedAt = activatedAt,
            expiresAt = expiresAt,
        )
}
