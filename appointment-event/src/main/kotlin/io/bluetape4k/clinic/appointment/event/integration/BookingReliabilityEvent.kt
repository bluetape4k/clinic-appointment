package io.bluetape4k.clinic.appointment.event.integration

import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityEventSource
import io.bluetape4k.support.requireNotBlank
import java.io.Serializable
import java.security.MessageDigest
import java.time.Instant

/**
 * 신뢰된 출석 결과를 예약 신뢰성 원장으로 넘기는 최소 event입니다.
 *
 * 회원 식별자는 회원 서비스가 발급한 opaque [memberId]만 받습니다. 이름, 전화번호,
 * 자유 입력 사유, 원문 payload는 이 계약과 원장에 존재하지 않습니다.
 */
data class BookingReliabilitySignalEvent(
    val sourceAuthority: String,
    val sourceAggregateId: String,
    val sourceVersion: Long,
    val tenantGroupId: Long,
    val clinicId: Long,
    val memberId: String,
    val eventId: String,
    val appointmentId: Long,
    val signalType: BookingReliabilitySignalType,
    val responsibility: BookingReliabilityResponsibility,
    val scheduledStartAt: Instant,
    val occurredAt: Instant,
    val source: BookingReliabilityEventSource = BookingReliabilityEventSource.APPOINTMENT,
) : Serializable {
    init {
        sourceAuthority.requireNotBlank("sourceAuthority")
        sourceAggregateId.requireNotBlank("sourceAggregateId")
        require(sourceVersion > 0) { "sourceVersion must be positive" }
        require(tenantGroupId > 0) { "tenantGroupId must be positive" }
        require(clinicId > 0) { "clinicId must be positive" }
        require(memberId.matches(OPAQUE_IDENTIFIER)) { "memberId must be an opaque bounded identifier" }
        require(eventId.matches(OPAQUE_IDENTIFIER)) { "eventId must be an opaque bounded identifier" }
        require(appointmentId > 0) { "appointmentId must be positive" }
        require(!scheduledStartAt.isBefore(occurredAt)) {
            "scheduledStartAt must not be before occurredAt"
        }
    }

    companion object {
        private const val serialVersionUID = 1L
        internal val OPAQUE_IDENTIFIER = Regex("[A-Za-z0-9][A-Za-z0-9._:/-]{0,159}")
    }
}

/** 예약 신뢰성 계산에 들어가는 객관적 출석 결과 종류입니다. */
enum class BookingReliabilitySignalType {
    NO_SHOW_RECORDED,
    LATE_CANCELLATION_RECORDED,
}

/** 고객 책임 여부를 bounded attribution으로 표현합니다. */
enum class BookingReliabilityResponsibility {
    PATIENT_RESPONSIBLE,
    CLINIC_RESPONSIBLE,
    OPERATIONAL_EXCEPTION,
    DATA_CORRECTION,
    UNKNOWN,
}

/** strict ingress payload의 deterministic SHA-256입니다. */
object BookingReliabilitySignalPayloadHasher {
    fun hash(event: BookingReliabilitySignalEvent): String =
        MessageDigest.getInstance("SHA-256")
            .digest(canonicalBytes(event))
            .joinToString("") { byte -> "%02x".format(byte) }

    internal fun canonicalBytes(event: BookingReliabilitySignalEvent): ByteArray =
        CanonicalFrameWriter().apply {
            string("sourceAuthority", event.sourceAuthority)
            string("sourceAggregateId", event.sourceAggregateId)
            long("sourceVersion", event.sourceVersion)
            long("tenantGroupId", event.tenantGroupId)
            long("clinicId", event.clinicId)
            string("memberId", event.memberId)
            string("eventId", event.eventId)
            long("appointmentId", event.appointmentId)
            string("signalType", event.signalType.name)
            string("responsibility", event.responsibility.name)
            instant("scheduledStartAt", event.scheduledStartAt)
            instant("occurredAt", event.occurredAt)
            string("source", event.source.name)
        }.toByteArray()
}
