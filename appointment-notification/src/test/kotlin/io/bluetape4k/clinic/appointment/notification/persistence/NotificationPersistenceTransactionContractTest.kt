package io.bluetape4k.clinic.appointment.notification.persistence

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.clinic.appointment.event.notification.NotificationIdempotencyDigest
import io.bluetape4k.clinic.appointment.event.notification.NotificationOutboxCodec
import org.junit.jupiter.api.Test
import java.time.Duration

class NotificationPersistenceTransactionContractTest {
    @Test
    fun `repository는 caller transaction이 없으면 실패한다`() {
        val repository = JdbcNotificationOutboxRepository(
            codec = NotificationOutboxCodec(),
            leaseDuration = Duration.ofMinutes(5),
        )

        assertFailsWith<IllegalStateException> {
            repository.containsIdempotency(
                NotificationIdempotencyDigest(
                    keyId = "active-key",
                    version = 1,
                    value = "digest-1",
                ),
            )
        }
    }
}
