package io.bluetape4k.clinic.appointment.api.config

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
        private const val READ_LIMIT_BYTES = CatalogDefinitionValidator.MAX_PAYLOAD_BYTES + 1
        private val catalogSyncPath = Regex(
            "^/api/[^/]+/clinics/[^/]+/catalog-sources/[^/]+/catalog-products/[^/]+/versions/[^/]+$"
        )
    }

    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        request.method != HttpMethod.PUT.name() || !catalogSyncPath.matches(request.requestURI)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        if (request.contentLengthLong > CatalogDefinitionValidator.MAX_PAYLOAD_BYTES) {
            rejectPayload(response)
            return
        }

        val body = request.inputStream.readNBytes(READ_LIMIT_BYTES)
        if (body.size > CatalogDefinitionValidator.MAX_PAYLOAD_BYTES) {
            rejectPayload(response)
            return
        }

        filterChain.doFilter(CachedBodyRequest(request, body), response)
    }

    private fun rejectPayload(response: HttpServletResponse) {
        val error = PlanFoundationError.PAYLOAD_TOO_LARGE
        val correlationId = UUID.randomUUID().toString()
        log.warn { "Catalog sync payload exceeded ${CatalogDefinitionValidator.MAX_PAYLOAD_BYTES} bytes" }
        response.status = error.status.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = StandardCharsets.UTF_8.name()
        response.writer.write(
            """
            {"success":false,"data":null,"error":"${error.safeMessage}","errorCode":"${error.code}","correlationId":"$correlationId"}
            """.trimIndent()
        )
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
