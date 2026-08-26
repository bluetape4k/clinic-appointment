package io.bluetape4k.clinic.appointment.event.notification

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test

class NotificationOutboxWriterContractTest {
    @Test
    fun `writer receipt는 opaque id만 노출한다`() {
        val receipt = NotificationOutboxWriteReceipt(7L)

        receipt.id shouldBeEqualTo 7L
        NotificationOutboxWriteReceipt::class.java.declaredFields
            .map { it.name }
            .none { it in setOf("status", "rowKind", "leaseToken", "attemptNumber", "resultRow") }
            .shouldBeTrue()
    }

    private class RecordingWriter : NotificationOutboxWriter {
        override fun enqueue(draft: SendableNotificationDraft): NotificationOutboxWriteReceipt =
            NotificationOutboxWriteReceipt(1L)

        override fun suppressLegacy(draft: LegacySuppressionDraft): NotificationOutboxWriteReceipt =
            NotificationOutboxWriteReceipt(2L)

        override fun containsIdempotency(digest: NotificationIdempotencyDigest): Boolean = false

        override fun suppressOutstandingReminders(
            tenantGroupId: TenantGroupId,
            clinicId: ClinicId,
            appointmentId: AppointmentId,
            suppressionReason: NotificationSuppressionReasonCode,
        ): Int = 0
    }
}
