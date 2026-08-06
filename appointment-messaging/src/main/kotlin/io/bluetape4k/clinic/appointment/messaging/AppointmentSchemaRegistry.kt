package io.bluetape4k.clinic.appointment.messaging

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** wire schema validation과 registry compatibility readiness를 분리하는 port입니다. */
interface AppointmentSchemaRegistry {
    val subject: String

    fun validate(schemaVersion: Int)

    fun readiness(): AppointmentSchemaReadiness
}

/** registry가 설정되지 않은 local/test 환경에서도 schema resource를 검증합니다. */
class StaticAppointmentSchemaRegistry(
    override val subject: String = DEFAULT_SUBJECT,
    private val supportedVersions: Set<Int> = setOf(AppointmentEventEnvelope.CURRENT_SCHEMA_VERSION),
    private val resourceExists: () -> Boolean = {
        StaticAppointmentSchemaRegistry::class.java.classLoader
            .getResource(SCHEMA_RESOURCE) != null
    },
) : AppointmentSchemaRegistry {

    init {
        require(subject.length in 1..128) { "schema subject must be bounded" }
        require(subject.matches(SUBJECT_PATTERN)) { "schema subject is not canonical" }
        require(supportedVersions.isNotEmpty()) { "supported schema versions must not be empty" }
    }

    override fun validate(schemaVersion: Int) {
        require(schemaVersion in supportedVersions) { "Unsupported appointment schemaVersion" }
        require(resourceExists()) { "appointment JSON Schema resource is unavailable" }
    }

    override fun readiness(): AppointmentSchemaReadiness {
        val localValid = supportedVersions.contains(AppointmentEventEnvelope.CURRENT_SCHEMA_VERSION) && resourceExists()
        return AppointmentSchemaReadiness(
            subject = subject,
            localSchemaValid = localValid,
            registryReachable = true,
            compatibilityLevel = AppointmentSchemaReadiness.EXPECTED_COMPATIBILITY,
        )
    }

    companion object {
        const val DEFAULT_SUBJECT = "appointment-events-value"
        const val SCHEMA_RESOURCE = "schemas/appointment-event-envelope-v1.schema.json"
        private val SUBJECT_PATTERN = Regex("^[A-Za-z0-9._-]{1,128}$")
    }
}

/** 운영 Schema Registry의 subject compatibility를 startup에서 fail-closed로 확인합니다. */
class HttpAppointmentSchemaRegistry(
    override val subject: String = StaticAppointmentSchemaRegistry.DEFAULT_SUBJECT,
    private val compatibilityReader: () -> String,
    private val local: StaticAppointmentSchemaRegistry = StaticAppointmentSchemaRegistry(subject),
) : AppointmentSchemaRegistry {

    override fun validate(schemaVersion: Int) {
        local.validate(schemaVersion)
        val readiness = readiness()
        require(readiness.ready) { "Schema Registry compatibility is unavailable" }
    }

    override fun readiness(): AppointmentSchemaReadiness {
        val localReadiness = local.readiness()
        val compatibility = try {
            compatibilityReader().trim().uppercase()
        } catch (_: Exception) {
            return localReadiness.copy(
                registryReachable = false,
                compatibilityLevel = "UNAVAILABLE",
            )
        }
        return localReadiness.copy(
            registryReachable = compatibility.isNotBlank(),
            compatibilityLevel = compatibility,
        )
    }
}

/** Confluent-compatible `/config/{subject}` endpoint을 읽는 bounded JDK client입니다. */
class JdkSchemaRegistryCompatibilityReader(
    baseUri: URI,
    private val subject: String,
    private val timeout: Duration = Duration.ofSeconds(2),
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(timeout).build(),
) : () -> String {
    private val endpoint = URI.create("${baseUri.toString().trimEnd('/')}/config/$subject")

    init {
        require(subject.matches(Regex("^[A-Za-z0-9._-]{1,128}$"))) {
            "schema subject is not canonical"
        }
        require(!timeout.isNegative && !timeout.isZero) { "schema registry timeout must be positive" }
    }

    override fun invoke(): String {
        val request = HttpRequest.newBuilder(endpoint)
            .timeout(timeout)
            .header("Accept", "application/vnd.schemaregistry.v1+json")
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        require(response.statusCode() in 200..299) { "schema registry returned HTTP ${response.statusCode()}" }
        val match = COMPATIBILITY_PATTERN.find(response.body())
            ?: throw IllegalArgumentException("schema registry compatibility is missing")
        return match.groupValues[1]
    }

    companion object {
        private val COMPATIBILITY_PATTERN = Regex("\\\"compatibilityLevel\\\"\\s*:\\s*\\\"([A-Za-z_]+)\\\"")
    }
}
