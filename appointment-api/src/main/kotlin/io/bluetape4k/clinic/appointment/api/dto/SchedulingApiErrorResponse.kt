package io.bluetape4k.clinic.appointment.api.dto

import com.fasterxml.jackson.annotation.JsonInclude

/**
 * 스케줄링 foundation API가 사용하는 안정적이고 privacy-safe한 오류 envelope이다.
 *
 * @property success 이 envelope에서는 항상 `false`이다.
 * @property data 호환성 유지를 위해 남겨둔 필드이며 오류 응답에서는 항상 `null`이다.
 * @property error parser, token, claim, payload, stack, 내부 식별자 세부사항이 없는
 * 고객 안전 메시지.
 * @property errorCode 기계가 해석할 수 있는 안정적인 오류 코드.
 * @property correlationId Security 및 controller 처리 전에 수립된, 길이가 제한된
 * 요청 추적 ID.
 * @property retryable 동일한 의도를 수정하지 않고 다시 시도했을 때 성공 가능성이
 * 있는지 나타낸다. `null`은 기존 카탈로그/플랜 응답 모양을 보존하기 위한 값이다.
 * 정책 handler는 항상 명시적인 `true` 또는 `false`를 내려서, 호출자가 서버 안내에
 * 따른 재시도와 의도 변경이 필요한 거절을 구분할 수 있게 한다.
 * @property action 선택적 고객/운영자 복구 안내 문구. `null`이면 JSON에서 생략된다.
 * 닫힌 오류 registry에서 선택된 값이어야 하며, 요청 값, 내부 운영 지시,
 * 예외 세부사항을 포함하지 않아야 한다.
 */
data class SchedulingApiErrorResponse(
    val success: Boolean = false,
    val data: Any? = null,
    val error: String,
    val errorCode: String,
    val correlationId: String,
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    val retryable: Boolean? = null,
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    val action: String? = null,
)
