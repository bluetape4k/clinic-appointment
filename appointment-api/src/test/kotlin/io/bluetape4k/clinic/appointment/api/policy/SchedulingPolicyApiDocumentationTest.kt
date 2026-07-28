package io.bluetape4k.clinic.appointment.api.policy

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.clinic.appointment.model.policy.PolicyScope
import io.bluetape4k.clinic.appointment.model.policy.SchedulingPolicyKind
import io.bluetape4k.clinic.appointment.service.SchedulingPolicyPayloadCodec
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.nio.file.Path

/**
 * 사용자 문서의 booking-policy draft 예제가 실제 strict payload codec과 동기화됐는지 검증한다.
 *
 * 문서의 marker 안에 있는 JSON은 단순 표시 예제가 아니라 복사해 호출할 수 있는 wire
 * 계약이다. 필드명, 초 단위 duration, enum 값 또는 schema version이 구현과 어긋나면
 * 이 테스트가 문서 변경과 같은 빌드에서 실패한다.
 */
class SchedulingPolicyApiDocumentationTest {

    private val mapper = JsonMapper.builder()
        .addModule(KotlinModule.Builder().build())
        .build()
    private val payloadCodec = SchedulingPolicyPayloadCodec()

    @Test
    fun `documented booking draft examples decode with the production strict codec`() {
        val contract = schedulingPolicyContractPath().toFile().readText()
        val examples = BOOKING_DRAFT_PATTERN.findAll(contract)
            .map { match -> match.groupValues[1] }
            .toList()

        examples.size shouldBeEqualTo 3
        examples.forEach { example ->
            val request = mapper.readTree(example)
            val kind = SchedulingPolicyKind.valueOf(request.path("kind").asText())
            val schemaVersion = request.path("schemaVersion").asInt()
            val payloadJson = mapper.writeValueAsString(request.path("payload"))

            payloadCodec.decode(
                kind = kind,
                scope = PolicyScope.TENANT_DEFAULT,
                schemaVersion = schemaVersion,
                json = payloadJson,
            )
        }
    }

    private fun schedulingPolicyContractPath(): Path =
        listOf(
            Path.of("docs/api/scheduling-policy.md"),
            Path.of("../docs/api/scheduling-policy.md"),
        ).first { path -> path.toFile().isFile }

    private companion object {
        val BOOKING_DRAFT_PATTERN = Regex(
            """<!-- booking-draft-example:start -->\s*```json\s*(.*?)\s*```\s*<!-- booking-draft-example:end -->""",
            RegexOption.DOT_MATCHES_ALL,
        )
    }
}
