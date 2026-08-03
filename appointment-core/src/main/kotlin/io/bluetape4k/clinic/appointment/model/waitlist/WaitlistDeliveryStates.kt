package io.bluetape4k.clinic.appointment.model.waitlist

/**
 * vacancy delivery worker가 처리하는 durable job lifecycle입니다.
 */
enum class VacancyJobState {
    /** worker가 claim할 수 있는 준비 상태입니다. */
    READY,

    /** lease owner가 배정되어 처리 중인 상태입니다. */
    PROCESSING,

    /** 후보에게 offer가 발행된 terminal 상태입니다. */
    OFFERED,

    /** 매칭 가능한 후보가 없어 닫힌 terminal 상태입니다. */
    NO_CANDIDATE,

    /** vacancy deadline이 지나 닫힌 terminal 상태입니다. */
    EXPIRED,

    /** 재시도 한도를 넘거나 복구 불가능한 오류로 닫힌 terminal 상태입니다. */
    FAILED,
}

/**
 * idempotent waitlist command record의 저장 상태입니다.
 */
enum class WaitlistCommandState {
    /** command handler가 결과를 아직 확정하지 않았습니다. */
    PROCESSING,

    /** command 결과 digest와 응답 참조가 확정됐습니다. */
    SUCCEEDED,

    /** command가 stable failure code로 종료됐습니다. */
    FAILED,
}

/**
 * waitlist delivery policy version의 publication lifecycle입니다.
 */
enum class WaitlistPolicyState {
    /** 작성 중인 immutable draft version입니다. */
    DRAFT,

    /** 새 vacancy와 command validation에 사용되는 active version입니다. */
    ACTIVE,

    /** 더 이상 선택되지 않는 historical version입니다. */
    RETIRED,
}
