package io.bluetape4k.clinic.appointment.consumer

import io.bluetape4k.clinic.appointment.event.notification.NotificationOutboxCodec
import io.bluetape4k.clinic.appointment.event.notification.NotificationOutboxEnvelope
import io.bluetape4k.clinic.appointment.event.notification.NotificationOutboxHasher
import io.bluetape4k.clinic.appointment.event.notification.NotificationOutboxWriteReceipt
import io.bluetape4k.clinic.appointment.event.notification.NotificationOutboxWriter
import io.bluetape4k.clinic.appointment.event.notification.SendableNotificationDraft
import kotlin.reflect.KClass

private val pureEventTypes: List<KClass<*>> = listOf(
    NotificationOutboxWriter::class,
    NotificationOutboxWriteReceipt::class,
    SendableNotificationDraft::class,
    NotificationOutboxEnvelope::class,
    NotificationOutboxCodec::class,
    NotificationOutboxHasher::class,
)

/**
 * event module이 persistence 구현 없이 소비할 수 있는 순수 계약 surface다.
 */
@Suppress("UNUSED_PARAMETER")
fun verifyEventNotificationContractSurface(
    writer: NotificationOutboxWriter? = null,
): List<KClass<*>> = pureEventTypes
