package io.bluetape4k.clinic.appointment.api.reliability

/** migration/table/index readiness를 기능 mode와 분리해 표현하는 결과입니다. */
data class BookingReliabilitySchemaReadiness(
    val migrationVersion: Int?,
    val requiredTablesPresent: Boolean,
    val requiredIndexesPresent: Boolean,
    val migrationCurrent: Boolean,
) {
    val ready: Boolean
        get() = requiredTablesPresent && requiredIndexesPresent && migrationCurrent

    fun allows(mode: BookingReliabilityProperties.Mode): Boolean =
        mode != BookingReliabilityProperties.Mode.ENFORCE || ready
}

/** DB metadata adapter가 제공하는 최소 readiness probe입니다. */
fun interface BookingReliabilitySchemaProbe {
    fun read(): BookingReliabilitySchemaReadiness
}

class DefaultBookingReliabilitySchemaReadiness(
    private val probe: BookingReliabilitySchemaProbe,
) {
    fun current(): BookingReliabilitySchemaReadiness = probe.read()

    fun canStartWorker(properties: BookingReliabilityProperties): Boolean =
        properties.workerEnabled && current().ready

    fun canEnforce(properties: BookingReliabilityProperties): Boolean =
        current().allows(properties.mode)
}
