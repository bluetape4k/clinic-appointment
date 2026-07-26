package io.bluetape4k.clinic.appointment.event.integration

/**
 * Normalizes the current and immediately previous purchase schemas.
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
