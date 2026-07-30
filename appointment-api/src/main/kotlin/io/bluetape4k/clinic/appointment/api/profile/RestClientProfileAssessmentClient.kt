package io.bluetape4k.clinic.appointment.api.profile

import io.bluetape4k.clinic.appointment.service.StrictJsonPayloadDecoder
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.util.UriComponentsBuilder
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.IDN
import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException
import java.net.http.HttpClient
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.util.Locale
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger

fun interface ProfileAssessmentAddressResolver {
    @Throws(UnknownHostException::class)
    fun resolve(host: String): List<InetAddress>

    companion object {
        val DNS = ProfileAssessmentAddressResolver { host ->
            InetAddress.getAllByName(host).toList()
        }
    }
}

/**
 * profile assessment client의 낮은 cardinality 관측값입니다.
 */
class ProfileAssessmentClientMetrics(
    private val registry: MeterRegistry,
) {
    private val inFlight = AtomicInteger()

    init {
        Gauge.builder(IN_FLIGHT, inFlight) { value -> value.get().toDouble() }
            .register(registry)
    }

    fun acquired() {
        inFlight.incrementAndGet()
    }

    fun released() {
        inFlight.decrementAndGet()
    }

    fun record(result: ProfileAssessmentMetricResult) {
        Counter.builder(REQUESTS)
            .tag("result", result.value)
            .register(registry)
            .increment()
    }

    companion object {
        const val IN_FLIGHT = "clinic.profile.assessment.inflight"
        const val REQUESTS = "clinic.profile.assessment.requests"
    }
}

enum class ProfileAssessmentMetricResult(
    val value: String,
) {
    SUCCESS("success"),
    SATURATED("saturated"),
    TIMEOUT("timeout"),
    UPSTREAM("upstream"),
    AUTHENTICATION("authentication"),
    TERMINAL("terminal"),
}

/**
 * Spring [RestClient]로 CRM의 최소 assessment projection을 일시적으로 조회합니다.
 *
 * endpoint는 HTTPS 고정 host allowlist와 public address 검증을 매 요청 전에 통과해야
 * 합니다. JDK HTTP client는 redirect를 따르지 않으며, 응답은 byte 상한 안에서 strict
 * schema로만 decode합니다.
 */
class RestClientProfileAssessmentClient internal constructor(
    private val baseUrl: URI,
    allowedHosts: Set<String>,
    connectTimeout: Duration,
    readTimeout: Duration,
    private val maxResponseBytes: Int,
    maxConcurrency: Int,
    meterRegistry: MeterRegistry,
    private val addressResolver: ProfileAssessmentAddressResolver,
    private val allowUnsafeTestEndpoint: Boolean,
    private val decoder: StrictJsonPayloadDecoder = StrictJsonPayloadDecoder(),
) : ProfileAssessmentClient {
    private val endpointPolicy = ProfileAssessmentEndpointPolicy(
        baseUrl = baseUrl,
        allowedHosts = allowedHosts,
        addressResolver = addressResolver,
        allowUnsafeTestEndpoint = allowUnsafeTestEndpoint,
    )
    private val permits = Semaphore(maxConcurrency, true)
    private val metrics = ProfileAssessmentClientMetrics(meterRegistry)
    private val restClient: RestClient

    init {
        require(!connectTimeout.isNegative && !connectTimeout.isZero) {
            "connectTimeout must be positive"
        }
        require(!readTimeout.isNegative && !readTimeout.isZero) {
            "readTimeout must be positive"
        }
        require(maxResponseBytes in 256..MAX_RESPONSE_BYTES) {
            "maxResponseBytes must be between 256 and $MAX_RESPONSE_BYTES"
        }
        require(maxConcurrency > 0) { "maxConcurrency must be positive" }
        endpointPolicy.validateAtStartup()

        val httpClient = HttpClient.newBuilder()
            .connectTimeout(connectTimeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
        val requestFactory = JdkClientHttpRequestFactory(httpClient).apply {
            setReadTimeout(readTimeout)
        }
        restClient = RestClient.builder()
            .requestFactory(requestFactory)
            .build()
    }

    constructor(
        baseUrl: URI,
        allowedHosts: Set<String>,
        connectTimeout: Duration,
        readTimeout: Duration,
        maxResponseBytes: Int,
        maxConcurrency: Int,
        meterRegistry: MeterRegistry,
        addressResolver: ProfileAssessmentAddressResolver = ProfileAssessmentAddressResolver.DNS,
    ) : this(
        baseUrl = baseUrl,
        allowedHosts = allowedHosts,
        connectTimeout = connectTimeout,
        readTimeout = readTimeout,
        maxResponseBytes = maxResponseBytes,
        maxConcurrency = maxConcurrency,
        meterRegistry = meterRegistry,
        addressResolver = addressResolver,
        allowUnsafeTestEndpoint = false,
    )

    override fun fetch(request: FetchProfileAssessment): ProfileSchedulingAssessment {
        if (!permits.tryAcquire()) {
            metrics.record(ProfileAssessmentMetricResult.SATURATED)
            throw ProfileAssessmentException(ProfileAssessmentFailureCode.CONCURRENCY_SATURATED)
        }
        metrics.acquired()
        return try {
            endpointPolicy.validateBeforeRequest()
            val assessmentUri = assessmentUri(request.assessmentReference)
            val assessment = exchange(assessmentUri, request)
            verifyIdentity(request, assessment)
            metrics.record(ProfileAssessmentMetricResult.SUCCESS)
            assessment
        } catch (failure: ProfileAssessmentException) {
            metrics.record(failure.code.metricResult())
            throw failure
        } catch (failure: ResourceAccessException) {
            val code = if (failure.hasTimeoutCause()) {
                ProfileAssessmentFailureCode.TIMEOUT
            } else {
                ProfileAssessmentFailureCode.UPSTREAM_UNAVAILABLE
            }
            metrics.record(code.metricResult())
            throw ProfileAssessmentException(code)
        } catch (_: IOException) {
            metrics.record(ProfileAssessmentMetricResult.UPSTREAM)
            throw ProfileAssessmentException(ProfileAssessmentFailureCode.UPSTREAM_UNAVAILABLE)
        } finally {
            metrics.released()
            permits.release()
        }
    }

    private fun exchange(
        uri: URI,
        request: FetchProfileAssessment,
    ): ProfileSchedulingAssessment =
        restClient.get()
            .uri(uri)
            .accept(MediaType.APPLICATION_JSON)
            .header(PATIENT_FINGERPRINT_HEADER, request.patientReferenceFingerprint)
            .header(PROFILE_REVISION_HEADER, request.profileRevision.toString())
            .header(CORRELATION_ID_HEADER, request.correlationId)
            .exchangeForRequiredValue { _, response ->
                when (response.statusCode.value()) {
                    in 200..299 -> decodeResponse(response)
                    in 300..399 ->
                        throw ProfileAssessmentException(ProfileAssessmentFailureCode.REDIRECT_REJECTED)
                    401, 403 ->
                        throw ProfileAssessmentException(
                            ProfileAssessmentFailureCode.AUTHENTICATION_INFRASTRUCTURE_UNAVAILABLE,
                        )
                    408, 425, 429 ->
                        throw ProfileAssessmentException(ProfileAssessmentFailureCode.UPSTREAM_UNAVAILABLE)
                    in 500..599 ->
                        throw ProfileAssessmentException(ProfileAssessmentFailureCode.UPSTREAM_UNAVAILABLE)
                    else ->
                        throw ProfileAssessmentException(ProfileAssessmentFailureCode.HTTP_CONTRACT_REJECTED)
                }
            }

    private fun decodeResponse(
        response: RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse,
    ): ProfileSchedulingAssessment {
        val contentType = response.headers.contentType
        if (contentType == null || !MediaType.APPLICATION_JSON.isCompatibleWith(contentType)) {
            throw ProfileAssessmentException(ProfileAssessmentFailureCode.RESPONSE_CONTENT_TYPE_INVALID)
        }
        val declaredLength = response.headers.contentLength
        if (declaredLength > maxResponseBytes) {
            throw ProfileAssessmentException(ProfileAssessmentFailureCode.RESPONSE_TOO_LARGE)
        }
        val bytes = response.body.use { body -> body.readBounded(maxResponseBytes) }
        return try {
            decoder.decode(bytes, ProfileSchedulingAssessment::class.java)
        } catch (_: IllegalArgumentException) {
            throw ProfileAssessmentException(ProfileAssessmentFailureCode.SCHEMA_INVALID)
        }
    }

    private fun verifyIdentity(
        request: FetchProfileAssessment,
        assessment: ProfileSchedulingAssessment,
    ) {
        val matches = assessment.tenantGroupId == request.tenantGroupId &&
            assessment.clinicId == request.clinicId &&
            assessment.patientReferenceFingerprint == request.patientReferenceFingerprint &&
            assessment.profileRevision == request.profileRevision &&
            assessment.assessmentReference == request.assessmentReference &&
            assessment.assessmentHash == request.assessmentHash
        if (!matches) {
            throw ProfileAssessmentException(ProfileAssessmentFailureCode.RESPONSE_IDENTITY_MISMATCH)
        }
    }

    private fun assessmentUri(reference: String): URI {
        val lower = reference.lowercase(Locale.ROOT)
        val invalid = reference.isBlank() ||
            reference.length > MAX_ASSESSMENT_REFERENCE_LENGTH ||
            reference.any(Char::isISOControl) ||
            reference.contains('/') ||
            reference.contains('\\') ||
            reference.contains("..") ||
            ABSOLUTE_URI.matches(reference) ||
            "%2f" in lower ||
            "%5c" in lower ||
            "%2e" in lower
        if (invalid) {
            throw ProfileAssessmentException(ProfileAssessmentFailureCode.ASSESSMENT_REFERENCE_INVALID)
        }
        return UriComponentsBuilder.fromUri(baseUrl)
            .pathSegment(reference)
            .build()
            .encode()
            .toUri()
    }

    private fun InputStream.readBounded(maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(maxBytes, BUFFER_SIZE))
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) {
                throw ProfileAssessmentException(ProfileAssessmentFailureCode.RESPONSE_TOO_LARGE)
            }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun Throwable.hasTimeoutCause(): Boolean {
        var current: Throwable? = this
        while (current != null) {
            if (current is HttpTimeoutException || current is java.net.SocketTimeoutException) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private fun ProfileAssessmentFailureCode.metricResult(): ProfileAssessmentMetricResult =
        when (this) {
            ProfileAssessmentFailureCode.CONCURRENCY_SATURATED ->
                ProfileAssessmentMetricResult.SATURATED
            ProfileAssessmentFailureCode.TIMEOUT -> ProfileAssessmentMetricResult.TIMEOUT
            ProfileAssessmentFailureCode.UPSTREAM_UNAVAILABLE -> ProfileAssessmentMetricResult.UPSTREAM
            ProfileAssessmentFailureCode.AUTHENTICATION_INFRASTRUCTURE_UNAVAILABLE ->
                ProfileAssessmentMetricResult.AUTHENTICATION
            else -> ProfileAssessmentMetricResult.TERMINAL
        }

    private companion object {
        const val MAX_RESPONSE_BYTES = 1024 * 1024
        const val MAX_ASSESSMENT_REFERENCE_LENGTH = 512
        const val BUFFER_SIZE = 8 * 1024
        const val PATIENT_FINGERPRINT_HEADER = "X-Patient-Reference-Fingerprint"
        const val PROFILE_REVISION_HEADER = "X-Profile-Revision"
        const val CORRELATION_ID_HEADER = "X-Correlation-Id"
        val ABSOLUTE_URI = Regex("[A-Za-z][A-Za-z0-9+.-]*://.*")
    }
}

private class ProfileAssessmentEndpointPolicy(
    private val baseUrl: URI,
    allowedHosts: Set<String>,
    private val addressResolver: ProfileAssessmentAddressResolver,
    private val allowUnsafeTestEndpoint: Boolean,
) {
    private val allowedHosts = allowedHosts.map(::normalizeHost).toSet()
    private val host: String

    init {
        require(this.allowedHosts.isNotEmpty()) { "allowedHosts must not be empty" }
        require(baseUrl.userInfo == null) { "assessment baseUrl must not contain user-info" }
        require(baseUrl.query == null) { "assessment baseUrl must not contain a query" }
        require(baseUrl.fragment == null) { "assessment baseUrl must not contain a fragment" }
        require(baseUrl.rawPath.orEmpty().lowercase(Locale.ROOT).let { path ->
            ".." !in path && "%2f" !in path && "%5c" !in path && "%2e" !in path
        }) {
            "assessment baseUrl path is unsafe"
        }
        if (allowUnsafeTestEndpoint) {
            require(baseUrl.scheme.equals("http", ignoreCase = true)) {
                "test assessment baseUrl must use HTTP"
            }
        } else {
            require(baseUrl.scheme.equals("https", ignoreCase = true)) {
                "assessment baseUrl must use HTTPS"
            }
            require(baseUrl.port == -1 || baseUrl.port == 443) {
                "assessment baseUrl must use the default HTTPS port"
            }
        }
        host = normalizeHost(requireNotNull(baseUrl.host) { "assessment baseUrl must have a host" })
        require(host in this.allowedHosts) { "assessment baseUrl host is not allowlisted" }
    }

    fun validateAtStartup() {
        if (allowUnsafeTestEndpoint) return
        val addresses = try {
            addressResolver.resolve(host)
        } catch (_: UnknownHostException) {
            throw IllegalArgumentException("assessment baseUrl host cannot be resolved")
        }
        require(addresses.isNotEmpty() && addresses.none(::isRejectedAddress)) {
            "assessment baseUrl resolved to a prohibited address"
        }
    }

    fun validateBeforeRequest() {
        if (allowUnsafeTestEndpoint) return
        val addresses = try {
            addressResolver.resolve(host)
        } catch (_: Exception) {
            throw ProfileAssessmentException(ProfileAssessmentFailureCode.UPSTREAM_UNAVAILABLE)
        }
        if (addresses.isEmpty() || addresses.any(::isRejectedAddress)) {
            throw ProfileAssessmentException(ProfileAssessmentFailureCode.ENDPOINT_ADDRESS_REJECTED)
        }
    }

    private fun normalizeHost(value: String): String =
        IDN.toASCII(value.trim().removeSuffix("."))
            .lowercase(Locale.ROOT)

    private fun isRejectedAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress ||
            address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress ||
            address.isMulticastAddress
        ) {
            return true
        }
        val bytes = address.address
        if (bytes.size == 4) {
            val first = bytes[0].toInt() and 0xff
            val second = bytes[1].toInt() and 0xff
            return first == 0 ||
                first >= 224 ||
                (first == 100 && second in 64..127)
        }
        if (bytes.size == 16) {
            val first = bytes[0].toInt() and 0xff
            return first and 0xfe == 0xfc
        }
        return true
    }
}
