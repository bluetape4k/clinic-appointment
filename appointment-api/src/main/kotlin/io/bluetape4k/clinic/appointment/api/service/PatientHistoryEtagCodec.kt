package io.bluetape4k.clinic.appointment.api.service

import io.bluetape4k.clinic.appointment.model.dto.PatientCancellationHistoryRecord
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.Normalizer

/** 환자 취소 이력 page의 strong ETag canonical serializer입니다. */
class PatientHistoryEtagCodec {
    fun strongTag(
        limit: Int,
        requestedCursor: String?,
        entries: List<PatientHistoryEtagEntry>,
        nextCursor: String?,
    ): String {
        require(limit in 1..50)
        require(entries.size <= 50)
        val bytes = ByteArrayOutputStream()
        putLong(bytes, limit.toLong())
        putNullable(bytes, requestedCursor)
        putNullable(bytes, nextCursor)
        putLong(bytes, entries.size.toLong())
        entries.forEach { entry ->
            putString(bytes, entry.appointmentRef)
            putNullable(bytes, entry.productName)
            putNullable(bytes, entry.sessionNumber?.toString())
            putNullable(bytes, entry.totalSessions?.toString())
            putNullable(bytes, entry.visitStartAt)
            putNullable(bytes, entry.visitEndAt)
            putNullable(bytes, entry.fromStatus)
            putNullable(bytes, entry.fromStatusLabel)
            putString(bytes, entry.toStatus)
            putString(bytes, entry.toStatusLabel)
            putString(bytes, entry.reasonCode)
            putString(bytes, entry.reasonLabel)
            putNullable(bytes, entry.reasonDetail)
            putString(bytes, entry.actorRole)
            putString(bytes, entry.actorLabel)
            putString(bytes, entry.occurredAt)
        }
        require(bytes.size() <= MAX_CANONICAL_BYTES) { "patient history response is too large" }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())
        return "\"sha256:${digest.joinToString("") { "%02x".format(it) }}\""
    }

    fun isStrongTag(value: String?): Boolean =
        value != null && STRONG_ETAG.matches(value) && value.length <= 128

    companion object {
        private const val MAX_CANONICAL_BYTES = 256 * 1024
        private val STRONG_ETAG = Regex("\\\"sha256:[0-9a-f]{64}\\\"")
    }

    private fun putNullable(out: ByteArrayOutputStream, value: String?) {
        out.write(if (value == null) 0 else 1)
        if (value != null) putString(out, value)
    }

    private fun putString(out: ByteArrayOutputStream, value: String) {
        val bytes = Normalizer.normalize(value, Normalizer.Form.NFC).toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= 65_535) { "canonical field is too large" }
        out.write(ByteBuffer.allocate(4).putInt(bytes.size).array())
        out.write(bytes)
    }

    private fun putLong(out: ByteArrayOutputStream, value: Long) {
        out.write(ByteBuffer.allocate(8).putLong(value).array())
    }
}

/** ETag codec 입력을 API DTO와 분리한 canonical entry입니다. */
data class PatientHistoryEtagEntry(
    val appointmentRef: String,
    val productName: String?,
    val sessionNumber: Int?,
    val totalSessions: Int?,
    val visitStartAt: String?,
    val visitEndAt: String?,
    val fromStatus: String?,
    val fromStatusLabel: String?,
    val toStatus: String,
    val toStatusLabel: String,
    val reasonCode: String,
    val reasonLabel: String,
    val reasonDetail: String?,
    val actorRole: String,
    val actorLabel: String,
    val occurredAt: String,
) {
    companion object {
        fun from(
            record: PatientCancellationHistoryRecord,
            appointmentRef: String,
            fromStatusLabel: String?,
            toStatusLabel: String,
            reasonLabel: String,
            actorLabel: String,
        ): PatientHistoryEtagEntry = PatientHistoryEtagEntry(
            appointmentRef = appointmentRef,
            productName = record.productName,
            sessionNumber = record.sessionNumber,
            totalSessions = record.totalSessions,
            visitStartAt = record.visitStartAt.toString(),
            visitEndAt = record.visitEndAt.toString(),
            fromStatus = record.fromCommitmentStatus?.name,
            fromStatusLabel = fromStatusLabel,
            toStatus = record.toCommitmentStatus.name,
            toStatusLabel = toStatusLabel,
            reasonCode = record.reasonCode,
            reasonLabel = reasonLabel,
            reasonDetail = record.reasonDetail,
            actorRole = record.actorRole,
            actorLabel = actorLabel,
            occurredAt = record.occurredAt.toString(),
        )
    }
}
