package io.bluetape4k.clinic.appointment.api.config

/**
 * 요청 URI가 공개 scheduling-policy tenant/clinic route family에 정확히 속하는지 판별한다.
 *
 * 인증·인가는 Spring Security matcher와 application service가 수행한다. 이 함수는 tenant
 * routing 또는 예외 처리에서 정책 전용 안정 오류 envelope를 선택할 때만 사용한다. 단순
 * substring 검사는 앞으로 무관한 경로 이름이 우연히 `scheduling-policies`를 포함할 때
 * 오류 계약을 잘못 바꿀 수 있으므로 `/api/{tenant}/admin` 아래의 두 공개 route shape만
 * 허용한다.
 *
 * @param requestUri query string을 제외한 servlet request URI.
 */
internal fun isSchedulingPolicyRequestPath(requestUri: String): Boolean =
    SCHEDULING_POLICY_REQUEST_PATH.matches(requestUri)

private val SCHEDULING_POLICY_REQUEST_PATH = Regex(
    "^/api/[^/]+/admin/(?:clinics/[^/]+/)?scheduling-policies(?:/.*)?$"
)
