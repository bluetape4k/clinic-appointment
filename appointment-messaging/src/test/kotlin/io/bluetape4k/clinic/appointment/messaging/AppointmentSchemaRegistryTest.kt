package io.bluetape4k.clinic.appointment.messaging

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URI
import java.time.Duration
import java.util.Base64

class AppointmentSchemaRegistryTest {

    @Test
    fun `static registry accepts current schema and reports ready`() {
        val registry = StaticAppointmentSchemaRegistry()

        registry.validate(AppointmentEventEnvelope.CURRENT_SCHEMA_VERSION)
        registry.readiness().ready.shouldBeTrue()
        registry.readiness().compatibilityLevel shouldBeEqualTo "BACKWARD_TRANSITIVE"
    }

    @Test
    fun `static registry rejects unsupported schema version`() {
        val registry = StaticAppointmentSchemaRegistry()

        assertFailsWith<IllegalArgumentException> {
            registry.validate(AppointmentEventEnvelope.CURRENT_SCHEMA_VERSION + 1)
        }
    }

    @Test
    fun `remote compatibility gate fails closed when registry is unavailable or incompatible`() {
        val unavailable = HttpAppointmentSchemaRegistry(
            subject = "appointment-events-value",
            compatibilityReader = { throw IllegalStateException("registry unavailable") },
        )
        val incompatible = HttpAppointmentSchemaRegistry(
            subject = "appointment-events-value",
            compatibilityReader = { "NONE" },
        )

        unavailable.readiness().ready.shouldBeFalse()
        incompatible.readiness().ready.shouldBeFalse()
        incompatible.readiness().subject shouldBeEqualTo "appointment-events-value"
    }

    @Test
    fun `consumer identity and replay request reject unbounded or unapproved input`() {
        assertFailsWith<IllegalArgumentException> { AppointmentLogicalConsumerId("") }
        assertFailsWith<IllegalArgumentException> { AppointmentLogicalStreamId(" ") }
        assertFailsWith<IllegalArgumentException> {
            AppointmentReplayRequest(
                identity = AppointmentConsumerIdentity(
                    consumerId = AppointmentLogicalConsumerId("notification"),
                    streamId = AppointmentLogicalStreamId("appointment-events"),
                ),
                tenantGroupId = 1,
                clinicId = 1,
                approver = "",
                fromOffset = 0,
                toOffset = 1,
                dryRun = false,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            AppointmentReplayRequest(
                identity = AppointmentConsumerIdentity(
                    consumerId = AppointmentLogicalConsumerId("notification"),
                    streamId = AppointmentLogicalStreamId("appointment-events"),
                ),
                tenantGroupId = 1,
                clinicId = 1,
                approver = "operator",
                fromOffset = 2,
                toOffset = 1,
                dryRun = true,
            )
        }
    }

    @Test
    fun `jdk reader requests the exact subject endpoint with basic authentication`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var requestPath: String? = null
        var authorization: String? = null
        server.createContext("/registry/config/appointment-events-value") { exchange ->
            requestPath = exchange.requestURI.path
            authorization = exchange.requestHeaders.getFirst("Authorization")
            val body = "{\"compatibilityLevel\":\"BACKWARD_TRANSITIVE\"}".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val credentials = AppointmentSchemaRegistryBasicCredentials("registry-user", "registry-pass")
            val reader = JdkSchemaRegistryCompatibilityReader(
                baseUri = URI("http://127.0.0.1:${server.address.port}/registry"),
                subject = "appointment-events-value",
                timeout = Duration.ofSeconds(2),
                credentials = credentials,
            )

            reader() shouldBeEqualTo "BACKWARD_TRANSITIVE"
            requestPath shouldBeEqualTo "/registry/config/appointment-events-value"
            authorization shouldBeEqualTo "Basic " + Base64.getEncoder().encodeToString(
                "registry-user:registry-pass".toByteArray(),
            )
            credentials.toString().contains("registry-pass").shouldBeFalse()
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `jdk reader rejects non loopback http and uri userinfo`() {
        assertFailsWith<IllegalArgumentException> {
            JdkSchemaRegistryCompatibilityReader(
                baseUri = URI("http://registry.example.com"),
                subject = "appointment-events-value",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            JdkSchemaRegistryCompatibilityReader(
                baseUri = URI("https://user:pass@registry.example.com"),
                subject = "appointment-events-value",
            )
        }
    }
}
