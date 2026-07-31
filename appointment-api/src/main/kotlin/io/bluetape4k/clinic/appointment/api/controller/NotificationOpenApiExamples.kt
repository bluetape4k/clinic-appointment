package io.bluetape4k.clinic.appointment.api.controller

/** OpenAPI에 게시하는 개인정보 비포함 알림·회원 식별 예시입니다. */
internal object NotificationOpenApiExamples {
    const val MEMBER_ID_REQUIRED =
        """{"success":false,"data":null,"error":"A verified member identifier is required for this appointment.","errorCode":"MEMBER_ID_REQUIRED","correlationId":"corr_example_01","retryable":false,"action":"Select a member and retry the appointment request."}"""
    const val MEMBER_NOT_FOUND =
        """{"success":false,"data":null,"error":"The selected member was not found.","errorCode":"MEMBER_NOT_FOUND","correlationId":"corr_example_01","retryable":false,"action":"Use the latest member search result and retry."}"""
    const val MEMBER_SCOPE_MISMATCH =
        """{"success":false,"data":null,"error":"The selected member is not available in this appointment scope.","errorCode":"MEMBER_SCOPE_MISMATCH","correlationId":"corr_example_01","retryable":false,"action":"Verify the clinic scope and selected member."}"""
    const val MEMBER_REFERENCE_AMBIGUOUS =
        """{"success":false,"data":null,"error":"The appointment member reference is ambiguous.","errorCode":"MEMBER_REFERENCE_AMBIGUOUS","correlationId":"corr_example_01","retryable":false,"action":"Correct the Plan or member mapping before retrying."}"""
    const val MEMBER_DIRECTORY_UNAVAILABLE =
        """{"success":false,"data":null,"error":"The member directory is temporarily unavailable.","errorCode":"MEMBER_DIRECTORY_UNAVAILABLE","correlationId":"corr_example_01","retryable":true,"action":"Retry with the same idempotency key after the Retry-After interval."}"""
    const val NOTIFICATION_OPERATION_UNAVAILABLE =
        """{"success":false,"data":null,"error":"Notification operation is temporarily unavailable.","errorCode":"NOTIFICATION_OPERATION_UNAVAILABLE","correlationId":"corr_example_01","retryable":true,"action":"Retry after the Retry-After interval or verify notification operation wiring."}"""
    const val EXHAUSTED_STATUS =
        """{"success":true,"data":{"status":"EXHAUSTED","reasonCode":"PROVIDER_RETRY_EXHAUSTED","nextAttemptAt":null,"exhaustedAt":"2026-08-01T00:00:00Z","recommendedAction":"CONTACT_NOTIFICATION_SUPPORT","patientVisible":false},"error":null}"""
    const val RE_NOTIFY_DRY_RUN_REQUEST =
        """{"appointmentIds":[101,102],"generation":"incident-20260801-01","platformApproval":{"authority":"notification-platform","reference":"approval-01"},"clinicApproval":{"authority":"clinic-operations","reference":"approval-02"},"dryRun":true}"""
    const val RE_NOTIFY_DRY_RUN_RESPONSE =
        """{"success":true,"data":{"generation":"incident-20260801-01","dryRun":true,"requestedCount":2,"acceptedCount":1,"skippedCount":1,"skippedReasons":{"ALREADY_SENT":1}},"error":null}"""
}
