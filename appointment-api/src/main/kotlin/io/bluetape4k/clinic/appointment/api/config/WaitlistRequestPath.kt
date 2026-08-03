package io.bluetape4k.clinic.appointment.api.config

/** waitlist staff API의 공개 route family를 정확히 식별합니다. */
internal fun isWaitlistRequestPath(requestUri: String): Boolean =
    Regex(
        "^/api/[^/]+/clinics/[^/]+/waitlist(?:/.*)?$",
    ).matches(requestUri)
