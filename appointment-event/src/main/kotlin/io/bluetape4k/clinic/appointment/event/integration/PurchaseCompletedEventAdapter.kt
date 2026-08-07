package io.bluetape4k.clinic.appointment.event.integration

/**
 * 현재 구매 스키마와 바로 이전 구매 스키마를 정규화합니다.
 */
class PurchaseCompletedEventAdapter(
    private val currentSchemaVersion: Int = CURRENT_SCHEMA_VERSION,
) {
    fun adapt(
        envelope: TrustedSchedulingEventEnvelope<PurchaseCompletedEvent>,
    ): TrustedSchedulingEventEnvelope<PurchaseCompletedEvent> {
        require(envelope.schemaVersion in (currentSchemaVersion - 1)..currentSchemaVersion) {
            "Unsupported purchase event schema version"
        }
        PurchaseEventBounds.validate(envelope)
        return envelope.copy(schemaVersion = currentSchemaVersion)
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 2
    }
}
