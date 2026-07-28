package io.bluetape4k.clinic.appointment.api.config

import io.bluetape4k.clinic.appointment.api.security.CorrelationIdFilter
import io.bluetape4k.clinic.appointment.service.CatalogDefinitionValidator
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.UUID

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
class CatalogPayloadSizeFilter : OncePerRequestFilter() {

    companion object : KLogging() {
        private const val POLICY_ENVELOPE_OVERHEAD_BYTES = 16 * 1_024
        private val catalogSyncPath = Regex(
            "^/api/[^/]+/clinics/[^/]+/catalog-sources/[^/]+/catalog-products/[^/]+/versions/[^/]+$"
        )
        private val policyWritePath = Regex(
            "^/api/[^/]+/admin/(?:clinics/[^/]+/)?scheduling-policies/(?:drafts|[^/]+/(?:validate|preview|approve|schedule|activate|retire)|activation-commands/[^/]+/replay)$"
        )
    }

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val catalogRequest =
            request.method == HttpMethod.PUT.name() && catalogSyncPath.matches(request.requestURI)
        val policyRequest =
            request.method == HttpMethod.POST.name() && policyWritePath.matches(request.requestURI)
        return !catalogRequest && !policyRequest
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val maximumBytes = request.maximumBodyBytes()
        if (request.contentLengthLong > maximumBytes) {
            rejectPayload(request, response)
            return
        }

        val body = request.inputStream.readNBytes(maximumBytes + 1)
        if (body.size > maximumBytes) {
            rejectPayload(request, response)
            return
        }

        filterChain.doFilter(CachedBodyRequest(request, body), response)
    }

    /**
     * 정책 draft envelope는 payload 외 CAS/audit 필드를 포함하므로 제한된 overhead를 허용한다.
     *
     * strict payload codec이 내부 payload 자체의 256KiB 상한을 다시 검사한다. 이 filter의
     * envelope 상한은 Jackson materialization 전에 과대 요청을 끊기 위한 transport 방어다.
     */
    private fun HttpServletRequest.maximumBodyBytes(): Int =
        if (policyWritePath.matches(requestURI)) {
            CatalogDefinitionValidator.MAX_PAYLOAD_BYTES + POLICY_ENVELOPE_OVERHEAD_BYTES
        } else {
            CatalogDefinitionValidator.MAX_PAYLOAD_BYTES
        }

    private fun rejectPayload(request: HttpServletRequest, response: HttpServletResponse) {
        val correlationId = response.getHeader(CorrelationIdFilter.HEADER_NAME)
            ?.takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString().also {
                response.setHeader(CorrelationIdFilter.HEADER_NAME, it)
            }
        val policyRequest = policyWritePath.matches(request.requestURI)
        log.warn {
            if (policyRequest) {
                "Scheduling policy request exceeded the bounded HTTP envelope"
            } else {
                "Catalog sync payload exceeded ${CatalogDefinitionValidator.MAX_PAYLOAD_BYTES} bytes"
            }
        }
        response.status = if (policyRequest) {
            SchedulingPolicyErrorCode.POLICY_PAYLOAD_INVALID.httpStatus.value()
        } else {
            PlanFoundationError.PAYLOAD_TOO_LARGE.status.value()
        }
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = StandardCharsets.UTF_8.name()
        if (policyRequest) {
            val error = SchedulingPolicyErrorCode.POLICY_PAYLOAD_INVALID
            response.writer.write(
                """{"success":false,"data":null,"error":"${error.safeMessage}","errorCode":"${error.name}","correlationId":"$correlationId","retryable":false,"action":"${error.action}"}"""
            )
        } else {
            val error = PlanFoundationError.PAYLOAD_TOO_LARGE
            response.writer.write(
                """{"success":false,"data":null,"error":"${error.safeMessage}","errorCode":"${error.code}","correlationId":"$correlationId"}"""
            )
        }
    }

    private class CachedBodyRequest(
        request: HttpServletRequest,
        private val body: ByteArray,
    ) : HttpServletRequestWrapper(request) {

        override fun getInputStream(): ServletInputStream =
            CachedBodyInputStream(ByteArrayInputStream(body))

        override fun getReader(): BufferedReader =
            BufferedReader(InputStreamReader(inputStream, characterEncoding ?: StandardCharsets.UTF_8.name()))
    }

    private class CachedBodyInputStream(
        private val delegate: ByteArrayInputStream,
    ) : ServletInputStream() {

        override fun isFinished(): Boolean = delegate.available() == 0

        override fun isReady(): Boolean = true

        override fun setReadListener(readListener: ReadListener?) {
            if (readListener == null) {
                return
            }
            if (isFinished) {
                readListener.onAllDataRead()
            } else {
                readListener.onDataAvailable()
            }
        }

        override fun read(): Int = delegate.read()

        override fun read(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ): Int = delegate.read(bytes, offset, length)
    }
}
