package io.bluetape4k.clinic.appointment.api.config

/**
 * 스케줄링 정책 명령 거절을 표현하는 정제된 애플리케이션 예외이다.
 *
 * [detail]은 서버 내부 진단용 도메인 설명이다. 공개 응답은 이 값을 반사하지 않고
 * [SchedulingPolicyErrorCode.safeMessage]를 사용한다. 그래도 로그에 기록될 수 있으므로
 * 원본 JSON, idempotency key 또는 digest, JWT 데이터, actor claim, SQL 문장, 포착된
 * 예외 메시지는 절대 포함하지 않는다. 기계가 의존하는 안정적인 오류 계약은 [errorCode]이다.
 *
 * @property errorCode 외부에 공개되는 안정적인 정책 오류 코드.
 * @property detail 이 발생 건에 대한 내부 진단 설명. 1..500자의 비밀 없는 문장이어야 한다.
 */
class SchedulingPolicyApiException(
    val errorCode: SchedulingPolicyErrorCode,
    val detail: String,
) : RuntimeException(detail) {
    init {
        require(detail.isNotBlank() && detail.length <= 500) {
            "detail must contain 1..500 customer-safe characters"
        }
    }
}
