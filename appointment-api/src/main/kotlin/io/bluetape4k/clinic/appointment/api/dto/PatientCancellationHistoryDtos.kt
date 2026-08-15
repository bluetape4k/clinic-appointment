package io.bluetape4k.clinic.appointment.api.dto

import com.fasterxml.jackson.annotation.JsonInclude

/** 환자 취소 이력 조회 query입니다. raw cursor는 public request query에만 존재합니다. */
data class PatientCancellationHistoryQuery(
    val cursor: String? = null,
    val limit: Int = 20,
)

/** 환자에게 노출하는 한 건의 취소 이력입니다. 내부 ID와 fingerprint는 포함하지 않습니다. */
data class PatientCancellationHistoryEntryResponse(
    val appointmentRef: String,
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    val productName: String?,
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    val sessionNumber: Int?,
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    val totalSessions: Int?,
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    val visitStartAt: String?,
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    val visitEndAt: String?,
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    val fromStatus: String?,
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    val fromStatusLabel: String?,
    val toStatus: String,
    val toStatusLabel: String,
    val reasonCode: String,
    val reasonLabel: String,
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    val reasonDetail: String?,
    val actorRole: String,
    val actorLabel: String,
    val occurredAt: String,
)

/** 환자 취소 이력 page의 raw JSON body입니다. */
data class PatientCancellationHistoryPageResponse(
    val limit: Int,
    val entries: List<PatientCancellationHistoryEntryResponse>,
    val nextCursor: String?,
)
