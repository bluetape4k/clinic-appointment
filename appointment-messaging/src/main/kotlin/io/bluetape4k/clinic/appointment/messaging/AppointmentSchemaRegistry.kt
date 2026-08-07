package io.bluetape4k.clinic.appointment.messaging

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.time.Duration

/** Spring configuration에서 registry secret을 직접 보관하지 않도록 하는 resolver port입니다. */
fun interface AppointmentSchemaRegistryCredentialResolver {
    fun resolve(reference: String): AppointmentSchemaRegistryBasicCredentials
}

/** Basic credential은 request 생성 시에만 사용하며, 문자열 표현은 항상 redacted 됩니다. */
class AppointmentSchemaRegistryBasicCredentials(
    val username: String,
    password: String,
) {
    private val password: String = password

    init {
        require(username.isNotBlank() && username.length <= 256) {
            "schema registry username must be bounded"
        }
        require(password.isNotEmpty() && password.length <= 1024) {
            "schema registry password must be bounded"
        }
    }

    fun authorizationHeader(): String =
        "Basic " + Base64.getEncoder().encodeToString(
            "$username:$password".toByteArray(StandardCharsets.UTF_8),
        )

    override fun toString(): String = "AppointmentSchemaRegistryBasicCredentials(username=$username, password=[REDACTED])"
}

/** wire schema validation과 registry compatibility readiness를 분리하는 port입니다. */
interface AppointmentSchemaRegistry {
    val subject: String

    fun validate(schemaVersion: Int)

    fun readiness(): AppointmentSchemaReadiness
}

/** registry HTTP/transport 장애처럼 record를 영구 거부하면 안 되는 오류입니다. */
class AppointmentSchemaRegistryUnavailableException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** local schema 또는 remote compatibility가 영구적으로 계약을 위반한 오류입니다. */
class AppointmentSchemaRegistryUnsupportedException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

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
        if (schemaVersion !in supportedVersions) {
            throw AppointmentSchemaRegistryUnsupportedException("Unsupported appointment schemaVersion")
        }
        if (!resourceExists()) {
            throw AppointmentSchemaRegistryUnsupportedException("appointment JSON Schema resource is unavailable")
        }
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
        if (!readiness.registryReachable) {
            throw AppointmentSchemaRegistryUnavailableException("Schema Registry compatibility endpoint is unavailable")
        }
        if (readiness.compatibilityLevel != AppointmentSchemaReadiness.EXPECTED_COMPATIBILITY) {
            throw AppointmentSchemaRegistryUnsupportedException("Schema Registry compatibility is not supported")
        }
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
    private val credentials: AppointmentSchemaRegistryBasicCredentials? = null,
) : () -> String {
    private val endpoint = URI.create("${baseUri.toString().trimEnd('/')}/config/$subject")

    init {
        require(subject.matches(Regex("^[A-Za-z0-9._-]{1,128}$"))) {
            "schema subject is not canonical"
        }
        require(!timeout.isNegative && !timeout.isZero) { "schema registry timeout must be positive" }
        require(baseUri.userInfo == null) { "schema registry URI must not contain userinfo" }
        require(baseUri.query == null && baseUri.fragment == null) {
            "schema registry URI must not contain query or fragment"
        }
        require(baseUri.scheme.equals("https", ignoreCase = true) || baseUri.isLoopbackHttp()) {
            "schema registry URI must use HTTPS or loopback HTTP"
        }
    }

    override fun invoke(): String {
        val builder = HttpRequest.newBuilder(endpoint)
            .timeout(timeout)
            .header("Accept", "application/vnd.schemaregistry.v1+json")
        credentials?.let { builder.header("Authorization", it.authorizationHeader()) }
        val request = builder.GET().build()
        val response = try {
            client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        } catch (failure: Exception) {
            throw AppointmentSchemaRegistryUnavailableException("schema registry request failed", failure)
        }
        if (response.statusCode() !in 200..299) {
            throw AppointmentSchemaRegistryUnavailableException("schema registry request failed")
        }
        val body = response.body().use { input ->
            val output = ByteArrayOutputStream(MAX_RESPONSE_BYTES)
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                require(total <= MAX_RESPONSE_BYTES) { "schema registry response is too large" }
                output.write(buffer, 0, read)
            }
            output.toString(StandardCharsets.UTF_8)
        }
        val match = COMPATIBILITY_PATTERN.find(body)
            ?: throw AppointmentSchemaRegistryUnavailableException("schema registry compatibility is missing")
        return match.groupValues[1]
    }

    private fun URI.isLoopbackHttp(): Boolean =
        scheme.equals("http", ignoreCase = true) &&
            host?.lowercase() in setOf("localhost", "127.0.0.1", "[::1]", "::1")

    companion object {
        private const val MAX_RESPONSE_BYTES = 64 * 1024
        private val COMPATIBILITY_PATTERN = Regex("\\\"compatibilityLevel\\\"\\s*:\\s*\\\"([A-Za-z_]+)\\\"")
    }
}
