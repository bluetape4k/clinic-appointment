package io.bluetape4k.clinic.appointment.api.profile

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.reflect.full.memberProperties

class RestClientProfileAssessmentClientTest {

    private val fixtures = CopyOnWriteArrayList<ProfileAssessmentHttpFixture>()

    @AfterEach
    fun closeFixtures() {
        fixtures.forEach(ProfileAssessmentHttpFixture::close)
    }

    @Test
    fun `assessment 계약은 예약 계산에 필요한 비식별 필드만 가진다`() {
        assertEquals(
            setOf(
                "tenantGroupId",
                "clinicId",
                "patientReferenceFingerprint",
                "profileRevision",
                "assessmentReference",
                "assessmentHash",
                "eligibleServiceCodes",
                "requiredResourceTags",
                "allowedTimeWindows",
            ),
            ProfileSchedulingAssessment::class.memberProperties.map { it.name }.toSet(),
        )
        assertEquals(
            setOf("startAt", "endAt"),
            AllowedTimeWindow::class.memberProperties.map { it.name }.toSet(),
        )
    }

    @Test
    fun `서비스 코드와 시간 구간 collection은 크기와 원소 형식을 제한한다`() {
        val base = ProfileSchedulingAssessment(
            tenantGroupId = 1L,
            clinicId = 41L,
            patientReferenceFingerprint = "a".repeat(64),
            profileRevision = 7L,
            assessmentReference = "assessment ref 7",
            assessmentHash = "b".repeat(64),
            eligibleServiceCodes = setOf("LASER"),
            requiredResourceTags = setOf("LASER_ROOM"),
            allowedTimeWindows = emptyList(),
        )

        assertThrows(IllegalArgumentException::class.java) {
            base.copy(eligibleServiceCodes = (1..65).map { "SERVICE_$it" }.toSet())
        }
        assertThrows(IllegalArgumentException::class.java) {
            base.copy(requiredResourceTags = setOf("resource tag with spaces"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            base.copy(
                allowedTimeWindows = List(65) { index ->
                    AllowedTimeWindow(
                        startAt = java.time.Instant.EPOCH.plusSeconds(index * 2L),
                        endAt = java.time.Instant.EPOCH.plusSeconds(index * 2L + 1L),
                    )
                },
            )
        }
    }

    @Test
    fun `opaque assessment reference를 한 path segment로 인코딩하고 strict 응답을 반환한다`() {
        val fixture = fixture()
        fixture.respond(200, assessmentJson())
        val registry = SimpleMeterRegistry()
        val client = client(fixture, registry = registry)

        val result = client.fetch(request())

        assertEquals(7L, result.profileRevision)
        assertEquals(setOf("LASER", "FOLLOW_UP"), result.eligibleServiceCodes)
        assertEquals("/assessments/assessment%20ref%207", fixture.rawPaths.single())
        assertEquals("a".repeat(64), fixture.header("X-Patient-Reference-Fingerprint"))
        assertEquals("7", fixture.header("X-Profile-Revision"))
        assertEquals("correlation-7", fixture.header("X-Correlation-Id"))
        assertEquals(1.0, registry.find(ProfileAssessmentClientMetrics.REQUESTS)
            .tag("result", "success")
            .counter()!!
            .count())
    }

    @Test
    fun `unknown field와 identity 불일치는 원문 없이 자동 재시도하지 않는다`() {
        val fixture = fixture()
        val client = client(fixture)
        val marker = "sensitive-diagnosis-marker"
        fixture.respond(200, assessmentJson(extraField = ",\"diagnosis\":\"$marker\""))

        val schemaFailure = failure { client.fetch(request()) }

        assertEquals(ProfileAssessmentFailureCode.SCHEMA_INVALID, schemaFailure.code)
        assertFalse(schemaFailure.retryable)
        assertFalse(schemaFailure.message!!.contains(marker))

        listOf(
            assessmentJson(tenantGroupId = 2L),
            assessmentJson(clinicId = 42L),
            assessmentJson(fingerprint = "c".repeat(64)),
            assessmentJson(revision = 8L),
            assessmentJson(reference = "different-reference"),
            assessmentJson(hash = "d".repeat(64)),
        ).forEach { mismatched ->
            fixture.respond(200, mismatched)
            val mismatch = failure { client.fetch(request()) }
            assertEquals(ProfileAssessmentFailureCode.RESPONSE_IDENTITY_MISMATCH, mismatch.code)
            assertFalse(mismatch.retryable)
        }
    }

    @Test
    fun `timeout 5xx와 인증 인프라 장애만 기술 재시도로 분류한다`() {
        val fixture = fixture()
        val client = client(fixture, readTimeout = Duration.ofMillis(40))

        fixture.respond(503, """{"ignored":"body-marker"}""")
        failure { client.fetch(request()) }.also {
            assertEquals(ProfileAssessmentFailureCode.UPSTREAM_UNAVAILABLE, it.code)
            assertTrue(it.retryable)
            assertFalse(it.message!!.contains("body-marker"))
        }

        fixture.respond(401, """{"token":"credential-marker"}""")
        failure { client.fetch(request()) }.also {
            assertEquals(ProfileAssessmentFailureCode.AUTHENTICATION_INFRASTRUCTURE_UNAVAILABLE, it.code)
            assertTrue(it.retryable)
            assertFalse(it.message!!.contains("credential-marker"))
        }

        fixture.respond(200, assessmentJson(), delay = Duration.ofMillis(200))
        failure { client.fetch(request()) }.also {
            assertEquals(ProfileAssessmentFailureCode.TIMEOUT, it.code)
            assertTrue(it.retryable)
        }
    }

    @Test
    fun `HTTPS 고정 host와 public address가 아닌 endpoint를 거부한다`() {
        val publicAddress = InetAddress.getByName("93.184.216.34")
        val publicResolver = ProfileAssessmentAddressResolver { listOf(publicAddress) }

        assertThrows(IllegalArgumentException::class.java) {
            productionClient(
                baseUrl = URI("http://crm.example.test/assessments"),
                resolver = publicResolver,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            productionClient(
                baseUrl = URI("https://other.example.test/assessments"),
                resolver = publicResolver,
            )
        }
        listOf("127.0.0.1", "10.0.0.8", "169.254.10.20", "100.64.0.1", "fc00::1").forEach { address ->
            assertThrows(IllegalArgumentException::class.java) {
                productionClient(
                    baseUrl = URI("https://crm.example.test/assessments"),
                    resolver = ProfileAssessmentAddressResolver {
                        listOf(InetAddress.getByName(address))
                    },
                )
            }
        }
    }

    @Test
    fun `DNS가 private address로 바뀌면 연결 전에 terminal security failure로 중단한다`() {
        val calls = AtomicInteger()
        val resolver = ProfileAssessmentAddressResolver {
            if (calls.getAndIncrement() == 0) {
                listOf(InetAddress.getByName("93.184.216.34"))
            } else {
                listOf(InetAddress.getByName("127.0.0.1"))
            }
        }
        val client = productionClient(
            baseUrl = URI("https://crm.example.test/assessments"),
            resolver = resolver,
        )

        val failure = failure { client.fetch(request()) }

        assertEquals(ProfileAssessmentFailureCode.ENDPOINT_ADDRESS_REJECTED, failure.code)
        assertFalse(failure.retryable)
    }

    @Test
    fun `absolute path traversal encoded slash와 redirect를 따르지 않는다`() {
        val fixture = fixture()
        val client = client(fixture)
        listOf(
            "https://evil.example/assessment",
            "..",
            "a/b",
            "a%2Fb",
            "a%5Cb",
        ).forEach { reference ->
            val rejected = failure {
                client.fetch(request().copy(assessmentReference = reference))
            }
            assertEquals(ProfileAssessmentFailureCode.ASSESSMENT_REFERENCE_INVALID, rejected.code)
            assertFalse(rejected.retryable)
        }
        assertEquals(0, fixture.requestCount.get())

        fixture.redirect("/redirect-target")
        val redirected = failure { client.fetch(request()) }
        assertEquals(ProfileAssessmentFailureCode.REDIRECT_REJECTED, redirected.code)
        assertFalse(redirected.retryable)
        assertEquals(0, fixture.redirectTargetCount.get())
    }

    @Test
    fun `응답 byte 상한을 넘으면 body를 보존하지 않고 terminal contract failure로 끝낸다`() {
        val fixture = fixture()
        val marker = "private-profile-body-"
        fixture.respond(
            200,
            """{"padding":"${marker.repeat(100)}"}""",
        )
        val client = client(fixture, maxResponseBytes = 256)

        val rejected = failure { client.fetch(request()) }

        assertEquals(ProfileAssessmentFailureCode.RESPONSE_TOO_LARGE, rejected.code)
        assertFalse(rejected.retryable)
        assertFalse(rejected.message!!.contains(marker))
    }

    private fun productionClient(
        baseUrl: URI,
        resolver: ProfileAssessmentAddressResolver,
    ) = RestClientProfileAssessmentClient(
        baseUrl = baseUrl,
        allowedHosts = setOf("crm.example.test"),
        connectTimeout = Duration.ofMillis(100),
        readTimeout = Duration.ofMillis(100),
        maxResponseBytes = 16 * 1024,
        maxConcurrency = 2,
        meterRegistry = SimpleMeterRegistry(),
        addressResolver = resolver,
        allowUnsafeTestEndpoint = false,
    )

    private fun client(
        fixture: ProfileAssessmentHttpFixture,
        registry: SimpleMeterRegistry = SimpleMeterRegistry(),
        readTimeout: Duration = Duration.ofSeconds(1),
        maxResponseBytes: Int = 16 * 1024,
        maxConcurrency: Int = 2,
    ) = RestClientProfileAssessmentClient(
        baseUrl = fixture.baseUri,
        allowedHosts = setOf("127.0.0.1"),
        connectTimeout = Duration.ofSeconds(1),
        readTimeout = readTimeout,
        maxResponseBytes = maxResponseBytes,
        maxConcurrency = maxConcurrency,
        meterRegistry = registry,
        addressResolver = ProfileAssessmentAddressResolver {
            listOf(InetAddress.getLoopbackAddress())
        },
        allowUnsafeTestEndpoint = true,
    )

    private fun fixture() = ProfileAssessmentHttpFixture().also(fixtures::add)

    private fun failure(block: () -> Unit): ProfileAssessmentException =
        assertThrows(ProfileAssessmentException::class.java, block)
}

internal class ProfileAssessmentHttpFixture : AutoCloseable {
    private val handler = AtomicReference<(HttpExchange) -> Unit> {
        it.sendResponseHeaders(500, -1)
        it.close()
    }
    private val executor = Executors.newCachedThreadPool()
    private val server = HttpServer.create(
        InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
        0,
    )
    val requestCount = AtomicInteger()
    val redirectTargetCount = AtomicInteger()
    val rawPaths = CopyOnWriteArrayList<String>()
    val headers = CopyOnWriteArrayList<Map<String, List<String>>>()
    val baseUri: URI

    init {
        server.executor = executor
        server.createContext("/assessments") { exchange ->
            requestCount.incrementAndGet()
            rawPaths += exchange.requestURI.rawPath
            headers += exchange.requestHeaders.toMap()
            handler.get().invoke(exchange)
        }
        server.createContext("/redirect-target") { exchange ->
            redirectTargetCount.incrementAndGet()
            exchange.sendResponseHeaders(204, -1)
            exchange.close()
        }
        server.start()
        baseUri = URI("http://127.0.0.1:${server.address.port}/assessments")
    }

    fun respond(status: Int, body: String, delay: Duration = Duration.ZERO) {
        handler.set { exchange ->
            if (!delay.isZero) Thread.sleep(delay)
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "application/json")
            runCatching {
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            exchange.close()
        }
    }

    fun redirect(location: String) {
        handler.set { exchange ->
            exchange.responseHeaders.set("Location", location)
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
    }

    fun handle(block: (HttpExchange) -> Unit) {
        handler.set(block)
    }

    fun header(name: String): String? =
        headers.single()
            .entries
            .firstOrNull { (key, _) -> key.equals(name, ignoreCase = true) }
            ?.value
            ?.single()

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }
}

internal fun request() = FetchProfileAssessment(
    tenantGroupId = 1L,
    clinicId = 41L,
    patientReferenceFingerprint = "a".repeat(64),
    profileRevision = 7L,
    assessmentReference = "assessment ref 7",
    assessmentHash = "b".repeat(64),
    correlationId = "correlation-7",
)

internal fun assessmentJson(
    tenantGroupId: Long = 1L,
    clinicId: Long = 41L,
    fingerprint: String = "a".repeat(64),
    revision: Long = 7L,
    reference: String = "assessment ref 7",
    hash: String = "b".repeat(64),
    extraField: String = "",
): String =
    """
    {
      "tenantGroupId": $tenantGroupId,
      "clinicId": $clinicId,
      "patientReferenceFingerprint": "$fingerprint",
      "profileRevision": $revision,
      "assessmentReference": "$reference",
      "assessmentHash": "$hash",
      "eligibleServiceCodes": ["LASER", "FOLLOW_UP"],
      "requiredResourceTags": ["LASER_ROOM"],
      "allowedTimeWindows": [
        {
          "startAt": "2026-08-01T00:00:00Z",
          "endAt": "2026-08-31T23:59:59Z"
        }
      ]
      $extraField
    }
    """.trimIndent()
