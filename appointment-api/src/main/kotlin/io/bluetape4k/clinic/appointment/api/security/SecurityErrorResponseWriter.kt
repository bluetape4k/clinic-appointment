package io.bluetape4k.clinic.appointment.api.security

import io.bluetape4k.clinic.appointment.api.config.PlanFoundationError
import jakarta.servlet.http.HttpServletResponse
import java.util.UUID

/**
 * controller가 요청을 처리하기 전에 authentication 또는 authorization이 실패했을 때 사용하는
 * 안정적인 privacy-safe JSON 계약을 작성한다.
 *
 * security failure는 의도적으로 공개 [PlanFoundationError] code와 message만 노출한다.
 * raw JWT, claim 값, parser exception text, signing detail, authorization rule은 이 응답에
 * 절대 복사하면 안 된다.
 *
 * correlation identifier는 [CorrelationIdFilter]가 수립한다. header와 JSON body는 정확히
 * 같은 값을 가져야 하므로, 운영자는 민감한 authentication material 없이 고객의 오류 보고를
 * 서버 감사 기록과 연결할 수 있다. UUID fallback은 정상 filter chain 밖에서 직접 호출된 경우에만
 * 존재하며, 사용 시에도 이 불변식을 보존하기 위해 response header에 함께 기록한다.
 */
object SecurityErrorResponseWriter {
    /**
     * terminal security error response 하나를 작성한다.
     *
     * 이 메서드는 response serialization만 소유하며 request를 authenticate, authorize, log,
     * retry하지 않는다. 따라서 [error]는 이미 분류된 public error contract여야 하고,
     * exception message가 아니어야 한다.
     *
     * @param response 이 호출로 status, media type, correlation header, JSON body가 완성되는 servlet response.
     * @param error 비민감 message를 가진 public error classification.
     */
    fun write(response: HttpServletResponse, error: PlanFoundationError) {
        val correlationId = response.getHeader(CorrelationIdFilter.HEADER_NAME)
            ?.takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString().also {
                response.setHeader(CorrelationIdFilter.HEADER_NAME, it)
            }

        response.status = error.status.value()
        response.contentType = "application/json"
        response.characterEncoding = Charsets.UTF_8.name()
        response.writer.write(
            """{"success":false,"data":null,"error":"${escapeJson(error.safeMessage)}","errorCode":"${escapeJson(error.code)}","correlationId":"${escapeJson(correlationId)}"}"""
        )
    }

    /**
     * 길이가 제한된 공개 response 값을 JSON string literal로 escape한다.
     *
     * 이 helper는 general JSON API가 아니라 serializer-local helper로 의도적으로 제한한다.
     * 향후 공개 오류 문구가 response shape를 깨거나 추가 field를 주입하지 못하게 하기 위해서다.
     * authentication input은 이 메서드에 절대 전달하면 안 된다.
     */
    private fun escapeJson(value: String): String = buildString(value.length) {
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) {
                    append("\\u")
                    append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
            }
        }
    }
}
