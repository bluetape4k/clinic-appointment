package io.bluetape4k.clinic.appointment.messaging

import java.net.URI
import java.time.Duration

/** Schema Registry endpoint를 Spring Boot binding으로 받는 단순 값 경계입니다. */
data class AppointmentSchemaRegistryBindingProperties(
    val enabled: Boolean = false,
    val baseUri: String? = null,
    val subject: String = StaticAppointmentSchemaRegistry.DEFAULT_SUBJECT,
    val timeout: Duration = Duration.ofSeconds(2),
    val credentialReference: String? = null,
)

/** binding 이후 URI/timeout/subject invariant를 검증한 immutable 설정입니다. */
data class AppointmentSchemaRegistryProperties(
    val enabled: Boolean = false,
    val baseUri: URI? = null,
    val subject: String = StaticAppointmentSchemaRegistry.DEFAULT_SUBJECT,
    val timeout: Duration = Duration.ofSeconds(2),
    val credentialReference: String? = null,
) {
    init {
        require(subject.matches(SUBJECT_PATTERN)) { "schema registry subject is not canonical" }
        require(!timeout.isNegative && !timeout.isZero) { "schema registry timeout must be positive" }
        if (enabled) {
            requireNotNull(baseUri) { "schema registry baseUri is required when enabled" }
        }
        credentialReference?.let {
            require(it.matches(REFERENCE_PATTERN)) { "schema registry credentialReference is not canonical" }
        }
    }

    companion object {
        private val SUBJECT_PATTERN = Regex("^[A-Za-z0-9._-]{1,128}$")
        private val REFERENCE_PATTERN = Regex("^[A-Za-z0-9._:/-]{1,256}$")
    }
}
