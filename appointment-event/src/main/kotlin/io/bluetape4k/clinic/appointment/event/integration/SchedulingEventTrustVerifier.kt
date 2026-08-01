package io.bluetape4k.clinic.appointment.event.integration

import io.bluetape4k.clinic.appointment.event.profile.PatientSchedulingAssessmentChanged
import io.bluetape4k.clinic.appointment.event.profile.PatientSchedulingAssessmentChangedHasher
import io.bluetape4k.clinic.appointment.model.plan.BookingPreferenceSnapshot
import io.bluetape4k.clinic.appointment.model.plan.ExecutionTreatment
import io.bluetape4k.clinic.appointment.model.plan.MigrationMapping
import io.bluetape4k.clinic.appointment.model.plan.PackageExecutionSnapshot
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration

fun interface SchedulingEventSignatureVerifier {
    fun verify(envelope: UntrustedSchedulingEventEnvelope<*>): Boolean
}

class SchedulingTrustException(
    val reasonCode: String,
) : RuntimeException(reasonCode)

class SchedulingEventTrustVerifier(
    private val signatureVerifier: SchedulingEventSignatureVerifier,
    private val allowedProducers: Set<String>,
    private val allowedKeyIds: Set<String>,
    private val allowedAlgorithms: Set<String>,
    private val expectedIssuer: String,
    private val expectedAudience: String,
    private val replayWindow: Duration,
    private val clock: Clock,
) {
    init {
        require(allowedProducers.isNotEmpty()) { "allowedProducers must not be empty" }
        require(allowedKeyIds.isNotEmpty()) { "allowedKeyIds must not be empty" }
        require(allowedAlgorithms.isNotEmpty()) { "allowedAlgorithms must not be empty" }
        require(!replayWindow.isZero && !replayWindow.isNegative) { "replayWindow must be positive" }
    }

    fun verify(
        envelope: UntrustedSchedulingEventEnvelope<PurchaseCompletedEvent>,
    ): TrustedSchedulingEventEnvelope<PurchaseCompletedEvent> {
        PurchaseEventBounds.validateEnvelopeMetadata(envelope)
        trust(envelope.eventType == "PurchaseCompleted", "EVENT_TYPE_NOT_ALLOWED")
        trust(envelope.producer in allowedProducers, "PRODUCER_NOT_ALLOWED")
        trust(envelope.keyId in allowedKeyIds, "KEY_NOT_ALLOWED")
        trust(envelope.algorithm in allowedAlgorithms, "ALGORITHM_NOT_ALLOWED")
        trust(envelope.issuer == expectedIssuer, "ISSUER_NOT_ALLOWED")
        trust(envelope.audience == expectedAudience, "AUDIENCE_NOT_ALLOWED")
        val now = clock.instant()
        trust(!envelope.occurredAt.isBefore(now.minus(replayWindow)), "REPLAY_WINDOW_EXCEEDED")
        trust(!envelope.occurredAt.isAfter(now.plusSeconds(30)), "EVENT_FROM_FUTURE")
        trust(envelope.payloadHash == PurchaseCompletedPayloadHasher.hash(envelope.payload), "PAYLOAD_HASH_MISMATCH")
        trust(signatureVerifier.verify(envelope), "SIGNATURE_INVALID")
        return envelope.trusted()
    }

    /**
     * 고정 allowlist의 실행 BOM event만 trusted envelope로 승격합니다.
     *
     * @param envelope raw payload에서 strict decoding한 schema version 1 envelope입니다.
     * @return metadata, replay window, canonical hash, 서명을 모두 통과한 envelope입니다.
     * @throws SchedulingTrustException 허용된 신뢰 계약 중 하나라도 실패하면 발생합니다.
     */
    fun verifyPackageExecution(
        envelope: UntrustedSchedulingEventEnvelope<PackageExecutionEvent>,
    ): TrustedSchedulingEventEnvelope<PackageExecutionEvent> {
        try {
            PackageExecutionEventBounds.validate(envelope)
        } catch (_: IllegalArgumentException) {
            throw SchedulingTrustException("PAYLOAD_CONTRACT_INVALID")
        }
        trust(envelope.eventType == "PackageExecutionPlanned", "EVENT_TYPE_NOT_ALLOWED")
        trust(envelope.schemaVersion == 1, "SCHEMA_VERSION_NOT_ALLOWED")
        trust(envelope.producer in allowedProducers, "PRODUCER_NOT_ALLOWED")
        trust(envelope.keyId in allowedKeyIds, "KEY_NOT_ALLOWED")
        trust(envelope.algorithm in allowedAlgorithms, "ALGORITHM_NOT_ALLOWED")
        trust(envelope.issuer == expectedIssuer, "ISSUER_NOT_ALLOWED")
        trust(envelope.audience == expectedAudience, "AUDIENCE_NOT_ALLOWED")
        val now = clock.instant()
        trust(!envelope.occurredAt.isBefore(now.minus(replayWindow)), "REPLAY_WINDOW_EXCEEDED")
        trust(!envelope.occurredAt.isAfter(now.plusSeconds(30)), "EVENT_FROM_FUTURE")
        trust(envelope.payloadHash == PackageExecutionPayloadHasher.hash(envelope.payload), "PAYLOAD_HASH_MISMATCH")
        trust(signatureVerifier.verify(envelope), "SIGNATURE_INVALID")
        return envelope.trusted()
    }

    /**
     * 상품 version 전환 승인 fact를 trusted envelope로 승격합니다.
     *
     * 상품서비스가 발행한 최신 정의를 예약서비스가 그대로 실행하려면 raw transport에서
     * 받은 event type, schema, producer, key, issuer/audience, replay window, canonical
     * hash, signature가 모두 맞아야 합니다. 이 검증을 통과하지 못한 payload는 Plan
     * revision 계산에 절대 들어가면 안 됩니다.
     */
    fun verifyProductVersionMigration(
        envelope: UntrustedSchedulingEventEnvelope<ProductVersionMigrationApprovedEvent>,
    ): TrustedSchedulingEventEnvelope<ProductVersionMigrationApprovedEvent> {
        try {
            ExternalFactEventBounds.validateProductVersionMigration(envelope)
        } catch (_: IllegalArgumentException) {
            throw SchedulingTrustException("PAYLOAD_CONTRACT_INVALID")
        }
        verifyCommonEnvelope(envelope, eventType = "ProductVersionMigrationApproved", schemaVersion = 1)
        trust(
            envelope.payloadHash == ProductVersionMigrationPayloadHasher.hash(envelope.payload),
            "PAYLOAD_HASH_MISMATCH",
        )
        trust(signatureVerifier.verify(envelope), "SIGNATURE_INVALID")
        return envelope.trusted()
    }

    /**
     * 고객 일정 변경 거부 fact를 trusted envelope로 승격합니다.
     *
     * 이 fact는 확정 예약을 직접 바꾸는 command가 아니라 CRM handoff와 운영 예외 생성의
     * 근거입니다. 그래서 상품 전환 승인과 동일한 transport 신뢰 검증을 적용하고,
     * payload subject와 reason code도 allowlist로 제한합니다.
     */
    fun verifyMigrationRescheduleDeclined(
        envelope: UntrustedSchedulingEventEnvelope<ProductVersionMigrationRescheduleDeclinedEvent>,
    ): TrustedSchedulingEventEnvelope<ProductVersionMigrationRescheduleDeclinedEvent> {
        try {
            ExternalFactEventBounds.validateMigrationRescheduleDeclined(envelope)
        } catch (_: IllegalArgumentException) {
            throw SchedulingTrustException("PAYLOAD_CONTRACT_INVALID")
        }
        verifyCommonEnvelope(envelope, eventType = "ProductVersionMigrationRescheduleDeclined", schemaVersion = 1)
        trust(
            envelope.payloadHash == ProductVersionMigrationRescheduleDeclinedPayloadHasher.hash(envelope.payload),
            "PAYLOAD_HASH_MISMATCH",
        )
        trust(signatureVerifier.verify(envelope), "SIGNATURE_INVALID")
        return envelope.trusted()
    }

    /**
     * 임상·환불 소유 서비스의 진료 이행 fact를 trusted envelope로 승격합니다.
     *
     * 부분 이행, 장비 장애, 환불은 예약 Plan의 미래 의무를 크게 바꾸므로 handler 앞에서
     * producer와 canonical payload를 고정합니다. 완료·잔여 진료 정의의 길이와 identifier
     * 경계도 여기서 먼저 확인해 DB나 planner 계층으로 오염된 값을 넘기지 않습니다.
     */
    fun verifyTreatmentFulfillment(
        envelope: UntrustedSchedulingEventEnvelope<TreatmentFulfillmentEvent>,
    ): TrustedSchedulingEventEnvelope<TreatmentFulfillmentEvent> {
        try {
            ExternalFactEventBounds.validateTreatmentFulfillment(envelope)
        } catch (_: IllegalArgumentException) {
            throw SchedulingTrustException("PAYLOAD_CONTRACT_INVALID")
        }
        verifyCommonEnvelope(envelope, eventType = "TreatmentFulfillmentRecorded", schemaVersion = 1)
        val earliestAcceptedFactAt = clock.instant().minus(replayWindow)
        envelope.payload.facts.forEach { fact ->
            trust(!fact.occurredAt.isBefore(earliestAcceptedFactAt), "FACT_REPLAY_WINDOW_EXCEEDED")
            trust(!fact.occurredAt.isAfter(envelope.occurredAt), "FACT_FROM_FUTURE")
        }
        trust(envelope.payloadHash == TreatmentFulfillmentPayloadHasher.hash(envelope.payload), "PAYLOAD_HASH_MISMATCH")
        trust(signatureVerifier.verify(envelope), "SIGNATURE_INVALID")
        return envelope.trusted()
    }

    /**
     * CRM의 최소 프로필 변경 신호를 trusted envelope로 승격합니다.
     *
     * profile 본문이나 점수는 계약에 없으며, transport metadata와 canonical hash,
     * signature가 모두 맞는 신호만 이후 fingerprint·clinic scope 검증으로 넘깁니다.
     */
    fun verifyProfileAssessment(
        envelope: UntrustedSchedulingEventEnvelope<PatientSchedulingAssessmentChanged>,
    ): TrustedSchedulingEventEnvelope<PatientSchedulingAssessmentChanged> {
        try {
            ProfileAssessmentEventBounds.validateEnvelopeMetadata(envelope)
        } catch (_: IllegalArgumentException) {
            throw SchedulingTrustException("PAYLOAD_CONTRACT_INVALID")
        }
        verifyCommonEnvelope(
            envelope,
            eventType = "PatientSchedulingAssessmentChanged",
            schemaVersion = 1,
        )
        trust(envelope.payload.eventId == envelope.eventId, "ROUTING_METADATA_MISMATCH")
        trust(envelope.payload.occurredAt == envelope.occurredAt, "ROUTING_METADATA_MISMATCH")
        trust(
            envelope.payloadHash == PatientSchedulingAssessmentChangedHasher.hash(envelope.payload),
            "PAYLOAD_HASH_MISMATCH",
        )
        trust(signatureVerifier.verify(envelope), "SIGNATURE_INVALID")
        return envelope.trusted()
    }

    /**
     * 출석 신뢰도 정책용 최소 event를 trusted envelope로 승격합니다.
     *
     * payload는 회원 서비스의 opaque member ID와 bounded attribution만 포함하며, producer가
     * 보낸 이름·전화번호·상담 원문은 예약서비스 신뢰 경계를 통과할 수 없습니다.
     */
    fun verifyBookingReliability(
        envelope: UntrustedSchedulingEventEnvelope<BookingReliabilitySignalEvent>,
    ): TrustedSchedulingEventEnvelope<BookingReliabilitySignalEvent> {
        try {
            BookingReliabilityEventBounds.validate(envelope)
        } catch (_: IllegalArgumentException) {
            throw SchedulingTrustException("PAYLOAD_CONTRACT_INVALID")
        }
        verifyCommonEnvelope(
            envelope,
            eventType = "BookingReliabilitySignalRecorded",
            schemaVersion = 1,
        )
        trust(
            envelope.payloadHash == BookingReliabilitySignalPayloadHasher.hash(envelope.payload),
            "PAYLOAD_HASH_MISMATCH",
        )
        trust(signatureVerifier.verify(envelope), "SIGNATURE_INVALID")
        return envelope.trusted()
    }

    private fun verifyCommonEnvelope(
        envelope: UntrustedSchedulingEventEnvelope<*>,
        eventType: String,
        schemaVersion: Int,
    ) {
        trust(envelope.eventType == eventType, "EVENT_TYPE_NOT_ALLOWED")
        trust(envelope.schemaVersion == schemaVersion, "SCHEMA_VERSION_NOT_ALLOWED")
        trust(envelope.producer in allowedProducers, "PRODUCER_NOT_ALLOWED")
        trust(envelope.keyId in allowedKeyIds, "KEY_NOT_ALLOWED")
        trust(envelope.algorithm in allowedAlgorithms, "ALGORITHM_NOT_ALLOWED")
        trust(envelope.issuer == expectedIssuer, "ISSUER_NOT_ALLOWED")
        trust(envelope.audience == expectedAudience, "AUDIENCE_NOT_ALLOWED")
        val now = clock.instant()
        trust(!envelope.occurredAt.isBefore(now.minus(replayWindow)), "REPLAY_WINDOW_EXCEEDED")
        trust(!envelope.occurredAt.isAfter(now.plusSeconds(30)), "EVENT_FROM_FUTURE")
    }

    private fun trust(condition: Boolean, reasonCode: String) {
        if (!condition) throw SchedulingTrustException(reasonCode)
    }
}

/**
 * CRM 프로필 변경 신호가 canonical hash, 암호화, 격리 저장소에 도달하기 전에 적용하는
 * 구조·크기 경계입니다. 프로필 본문은 받지 않으며 비식별 참조도 bounded 값만 허용합니다.
 */
internal object ProfileAssessmentEventBounds {
    private const val MAX_IDENTIFIER_LENGTH = 160
    private const val MAX_REFERENCE_LENGTH = 512
    private const val MAX_SIGNATURE_LENGTH = 1_024
    private const val MAX_CANONICAL_PAYLOAD_BYTES = 16 * 1_024
    private val identifier = Regex("[A-Za-z0-9][A-Za-z0-9._:/-]*")
    private val sha256 = Regex("[0-9a-f]{64}")

    fun validateEnvelopeMetadata(
        envelope: UntrustedSchedulingEventEnvelope<PatientSchedulingAssessmentChanged>,
    ) {
        listOf(
            envelope.eventId,
            envelope.eventType,
            envelope.producer,
            envelope.issuer,
            envelope.audience,
            envelope.keyId,
            envelope.algorithm,
            envelope.correlationId,
        ).forEach(::boundedIdentifier)
        require(envelope.payloadHash.matches(sha256)) {
            "payloadHash must be lowercase SHA-256"
        }
        require(envelope.schemaVersion > 0) { "schemaVersion must be positive" }
        require(envelope.signature.length in 1..MAX_SIGNATURE_LENGTH) {
            "signature length is invalid"
        }

        val payload = envelope.payload
        boundedIdentifier(payload.eventId)
        require(payload.tenantGroupId > 0) { "tenantGroupId must be positive" }
        require(payload.clinicId > 0) { "clinicId must be positive" }
        require(payload.patientReferenceFingerprint.matches(sha256)) {
            "patientReferenceFingerprint must be lowercase SHA-256"
        }
        require(payload.profileRevision > 0) { "profileRevision must be positive" }
        require(payload.assessmentRef.length in 1..MAX_REFERENCE_LENGTH) {
            "assessmentRef length is invalid"
        }
        require(payload.assessmentHash.matches(sha256)) {
            "assessmentHash must be lowercase SHA-256"
        }
        require(
            PatientSchedulingAssessmentChangedHasher.canonicalBytes(payload).size <=
                MAX_CANONICAL_PAYLOAD_BYTES,
        ) {
            "profile assessment payload is too large"
        }
    }

    private fun boundedIdentifier(value: String) {
        require(value.length in 1..MAX_IDENTIFIER_LENGTH) {
            "identifier length is invalid"
        }
        require(identifier.matches(value)) { "identifier contains unsafe characters" }
    }
}

/**
 * 고객 일정 변경 거부 payload의 canonical SHA-256입니다.
 *
 * serializer field 순서나 DTO `toString()`에 의존하지 않고 replay/hash conflict를
 * 구분하기 위해 length-framed 값을 사용합니다.
 */
internal object ProductVersionMigrationRescheduleDeclinedPayloadHasher {
    fun hash(event: ProductVersionMigrationRescheduleDeclinedEvent): String =
        MessageDigest.getInstance("SHA-256")
            .digest(
                CanonicalFrameWriter().apply {
                    string("sourceAggregateId", event.sourceAggregateId)
                    long("sourceAggregateVersion", event.sourceAggregateVersion)
                    long("tenantGroupId", event.tenantGroupId)
                    long("clinicId", event.clinicId)
                    string("sourcePurchaseAuthority", event.sourcePurchaseAuthority)
                    string("sourcePurchaseId", event.sourcePurchaseId)
                    string("migrationId", event.migrationId)
                    string("appointmentId", event.appointmentId?.toString())
                    string("reasonCode", event.reasonCode)
                }.toByteArray(),
            )
            .joinToString("") { byte -> "%02x".format(byte) }
}

object PurchaseCompletedPayloadHasher {
    fun hash(event: PurchaseCompletedEvent): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(canonicalBytes(event))
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    internal fun canonicalBytes(event: PurchaseCompletedEvent): ByteArray {
        return CanonicalFrameWriter().apply {
            string("sourceAggregateId", event.sourceAggregateId)
            long("sourceAggregateVersion", event.sourceAggregateVersion)
            long("tenantGroupId", event.tenantGroupId)
            long("clinicId", event.clinicId)
            string("sourcePurchaseAuthority", event.sourcePurchaseAuthority)
            string("sourcePurchaseId", event.sourcePurchaseId)
            string("patientReferenceToken", event.patientReferenceToken)
            string("catalogSourceAuthority", event.catalogSourceAuthority)
            string("productId", event.productId)
            long("catalogVersion", event.catalogVersion)
            bookingPreference("bookingPreference", event.bookingPreference)
        }.toByteArray()
    }
}

internal class CanonicalFrameWriter {
    private val out = ByteArrayOutputStream()

    fun string(name: String, value: String?) {
        frame(name, "string", value?.toByteArray(StandardCharsets.UTF_8))
    }

    fun int(name: String, value: Int) {
        frame(name, "int", value.toString().toByteArray(StandardCharsets.UTF_8))
    }

    fun long(name: String, value: Long) {
        frame(name, "long", value.toString().toByteArray(StandardCharsets.UTF_8))
    }

    fun boolean(name: String, value: Boolean) {
        frame(name, "boolean", value.toString().toByteArray(StandardCharsets.UTF_8))
    }

    fun instant(name: String, value: java.time.Instant) {
        frame(name, "instant", value.toString().toByteArray(StandardCharsets.UTF_8))
    }

    fun bookingPreference(name: String, value: BookingPreferenceSnapshot) {
        when (value) {
            is BookingPreferenceSnapshot.ExactDateTime -> {
                string("$name.type", "EXACT_DATE_TIME")
                string("$name.originalLocalDateTime", value.originalLocalDateTime.toString())
                string("$name.originalOffset", value.originalOffset.toString())
                string("$name.zoneId", value.zoneId.id)
                instant("$name.normalizedInstant", value.normalizedInstant)
            }
            is BookingPreferenceSnapshot.DateRange -> {
                string("$name.type", "DATE_RANGE")
                string("$name.startDate", value.startDate.toString())
                string("$name.endDate", value.endDate.toString())
                string("$name.zoneId", value.zoneId.id)
            }
            is BookingPreferenceSnapshot.PreferredWeekdaysAndWindows -> {
                string("$name.type", "PREFERRED_WEEKDAYS_AND_WINDOWS")
                int("$name.weekdays.size", value.weekdays.size)
                value.weekdays.forEachIndexed { index, weekday ->
                    string("$name.weekdays[$index]", weekday.name)
                }
                int("$name.localTimeWindows.size", value.localTimeWindows.size)
                value.localTimeWindows.forEachIndexed { index, window ->
                    string("$name.localTimeWindows[$index].start", window.start.toString())
                    string("$name.localTimeWindows[$index].end", window.end.toString())
                }
                string("$name.zoneId", value.zoneId.id)
            }
            BookingPreferenceSnapshot.NotProvided -> string("$name.type", "NOT_PROVIDED")
        }
    }

    fun toByteArray(): ByteArray = out.toByteArray()

    private fun frame(
        name: String,
        type: String,
        value: ByteArray?,
    ) {
        writeAscii(name)
        out.write(0)
        writeAscii(type)
        out.write(0)
        if (value == null) {
            writeAscii("-1")
        } else {
            writeAscii(value.size.toString())
            out.write(0)
            out.write(value)
        }
        out.write(0)
    }

    private fun writeAscii(value: String) {
        out.write(value.toByteArray(StandardCharsets.US_ASCII))
    }
}

internal object PurchaseEventBounds {
    private const val MAX_IDENTIFIER_LENGTH = 128
    private const val MAX_TOKEN_LENGTH = 2_048
    private const val MAX_ZONE_ID_LENGTH = 128
    private const val MAX_TIME_WINDOWS = 32
    private const val MAX_CANONICAL_PAYLOAD_BYTES = 32 * 1_024
    private val identifier = Regex("[A-Za-z0-9][A-Za-z0-9._:-]*")

    fun validate(envelope: TrustedSchedulingEventEnvelope<PurchaseCompletedEvent>) {
        validateMetadata(
            envelope.eventId,
            envelope.eventType,
            envelope.producer,
            envelope.issuer,
            envelope.audience,
            envelope.keyId,
            envelope.algorithm,
            envelope.correlationId,
            envelope.payloadHash,
            envelope.schemaVersion,
        )
        validatePayload(envelope.payload)
    }

    fun validateEnvelopeMetadata(envelope: UntrustedSchedulingEventEnvelope<PurchaseCompletedEvent>) {
        validateMetadata(
            envelope.eventId,
            envelope.eventType,
            envelope.producer,
            envelope.issuer,
            envelope.audience,
            envelope.keyId,
            envelope.algorithm,
            envelope.correlationId,
            envelope.payloadHash,
            envelope.schemaVersion,
        )
        require(envelope.signature.length <= 1_024) { "signature is too long" }
        validatePayload(envelope.payload)
    }

    private fun validateMetadata(
        eventId: String,
        eventType: String,
        producer: String,
        issuer: String,
        audience: String,
        keyId: String,
        algorithm: String,
        correlationId: String,
        payloadHash: String,
        schemaVersion: Int,
    ) {
        listOf(eventId, eventType, producer, issuer, audience, keyId, algorithm, correlationId)
            .forEach(::boundedIdentifier)
        require(payloadHash.matches(Regex("[0-9a-f]{64}"))) { "payloadHash must be lowercase SHA-256" }
        require(schemaVersion > 0) { "schemaVersion must be positive" }
    }

    private fun validatePayload(payload: PurchaseCompletedEvent) {
        boundedIdentifier(payload.sourceAggregateId)
        boundedIdentifier(payload.sourcePurchaseAuthority)
        boundedIdentifier(payload.sourcePurchaseId)
        boundedIdentifier(payload.catalogSourceAuthority)
        boundedIdentifier(payload.productId)
        require(payload.sourceAggregateVersion > 0) { "sourceAggregateVersion must be positive" }
        require(payload.tenantGroupId > 0) { "tenantGroupId must be positive" }
        require(payload.clinicId > 0) { "clinicId must be positive" }
        require(payload.catalogVersion > 0) { "catalogVersion must be positive" }
        require(payload.patientReferenceToken.isNotBlank()) { "patientReferenceToken must not be blank" }
        require(payload.patientReferenceToken.length <= MAX_TOKEN_LENGTH) { "patientReferenceToken is too long" }
        validateBookingPreference(payload.bookingPreference)
        require(PurchaseCompletedPayloadHasher.canonicalBytes(payload).size <= MAX_CANONICAL_PAYLOAD_BYTES) {
            "purchase payload is too large"
        }
    }

    private fun validateBookingPreference(preference: BookingPreferenceSnapshot) {
        when (preference) {
            is BookingPreferenceSnapshot.ExactDateTime -> {
                require(preference.zoneId.id.length <= MAX_ZONE_ID_LENGTH) { "zoneId is too long" }
                val validOffsets = preference.zoneId.rules.getValidOffsets(preference.originalLocalDateTime)
                require(preference.originalOffset in validOffsets) {
                    "originalOffset is invalid for originalLocalDateTime and zoneId"
                }
                require(
                    preference.normalizedInstant ==
                        preference.originalLocalDateTime.toInstant(preference.originalOffset),
                ) { "normalizedInstant is inconsistent with the original local date-time" }
            }

            is BookingPreferenceSnapshot.DateRange -> {
                require(preference.zoneId.id.length <= MAX_ZONE_ID_LENGTH) { "zoneId is too long" }
                require(preference.startDate <= preference.endDate) { "date range is reversed" }
            }

            is BookingPreferenceSnapshot.PreferredWeekdaysAndWindows -> {
                require(preference.zoneId.id.length <= MAX_ZONE_ID_LENGTH) { "zoneId is too long" }
                require(preference.weekdays.isNotEmpty()) { "weekdays must not be empty" }
                require(preference.weekdays.size <= 7) { "too many weekdays" }
                require(preference.weekdays.distinct().size == preference.weekdays.size) {
                    "weekdays must not contain duplicates"
                }
                require(preference.localTimeWindows.isNotEmpty()) { "localTimeWindows must not be empty" }
                require(preference.localTimeWindows.size <= MAX_TIME_WINDOWS) { "too many localTimeWindows" }
                require(preference.localTimeWindows.distinct().size == preference.localTimeWindows.size) {
                    "localTimeWindows must not contain duplicates"
                }
            }

            BookingPreferenceSnapshot.NotProvided -> Unit
        }
    }

    private fun boundedIdentifier(value: String) {
        require(value.length in 1..MAX_IDENTIFIER_LENGTH) { "identifier length is invalid" }
        require(identifier.matches(value)) { "identifier contains unsafe characters" }
    }
}

/**
 * 외부 fact event 3종이 trusted handler에 도달하기 전 지켜야 하는 공통 구조 경계입니다.
 *
 * 이 검증은 JSON transport 크기와 깊이를 다루는 [ExternalFactEventIngress] 이후,
 * canonical hash와 signature 검증 직전에 실행됩니다. 목적은 예약서비스가 소유하지 않는
 * 상품·임상·환불 사실을 처리하더라도 inbox, quarantine, Plan revision column 경계를
 * 넘는 값이나 의미 없는 식별자를 내부 도메인으로 흘려보내지 않는 것입니다.
 */
internal object ExternalFactEventBounds {
    private const val MAX_IDENTIFIER_LENGTH = 128
    private const val MAX_SIGNATURE_LENGTH = 1_024
    private const val MAX_FACTS = 1_000
    private const val MAX_MAPPINGS = 1_000
    private const val MAX_TREATMENTS = 1_000
    private const val MAX_TREATMENT_NAME_LENGTH = 256
    private const val MAX_REASON_LENGTH = 128
    private const val MAX_CANONICAL_PAYLOAD_BYTES = 256 * 1_024
    private val identifier = Regex("[A-Za-z0-9][A-Za-z0-9._:-]*")
    private val sha256 = Regex("[0-9a-f]{64}")

    fun validateProductVersionMigration(
        envelope: UntrustedSchedulingEventEnvelope<ProductVersionMigrationApprovedEvent>,
    ) {
        validateMetadata(envelope)
        val payload = envelope.payload
        validateSourceScope(
            sourceAggregateId = payload.sourceAggregateId,
            sourceAggregateVersion = payload.sourceAggregateVersion,
            tenantGroupId = payload.tenantGroupId,
            clinicId = payload.clinicId,
            sourcePurchaseAuthority = payload.sourcePurchaseAuthority,
            sourcePurchaseId = payload.sourcePurchaseId,
        )
        boundedIdentifier(payload.migrationId)
        boundedIdentifier(payload.fromProductVersionId)
        boundedIdentifier(payload.toProductVersionId)
        require(payload.fromProductVersionId != payload.toProductVersionId) {
            "product migration must change the product version"
        }
        require(payload.mappings.size in 1..MAX_MAPPINGS) { "mappings size is invalid" }
        payload.mappings.forEach(::validateMapping)
        require(payload.mappingHash.matches(sha256)) { "mappingHash must be lowercase SHA-256" }
        require(payload.mappingHash == ProductVersionMigrationPayloadHasher.mappingHash(payload.mappings)) {
            "mappingHash does not match mappings"
        }
        validateConsent(payload)
        validateSnapshot(payload.targetExecutionSnapshot)
        require(ProductVersionMigrationPayloadHasher.hash(payload).length == 64) {
            "migration payload hash is invalid"
        }
    }

    fun validateMigrationRescheduleDeclined(
        envelope: UntrustedSchedulingEventEnvelope<ProductVersionMigrationRescheduleDeclinedEvent>,
    ) {
        validateMetadata(envelope)
        val payload = envelope.payload
        validateSourceScope(
            sourceAggregateId = payload.sourceAggregateId,
            sourceAggregateVersion = payload.sourceAggregateVersion,
            tenantGroupId = payload.tenantGroupId,
            clinicId = payload.clinicId,
            sourcePurchaseAuthority = payload.sourcePurchaseAuthority,
            sourcePurchaseId = payload.sourcePurchaseId,
        )
        boundedIdentifier(payload.migrationId)
        payload.appointmentId?.let { require(it > 0) { "appointmentId must be positive" } }
        require(payload.reasonCode == "CUSTOMER_DECLINED_RESCHEDULE") {
            "reasonCode is not allowed"
        }
        require(ProductVersionMigrationRescheduleDeclinedPayloadHasher.hash(payload).length == 64) {
            "decline payload hash is invalid"
        }
    }

    fun validateTreatmentFulfillment(
        envelope: UntrustedSchedulingEventEnvelope<TreatmentFulfillmentEvent>,
    ) {
        validateMetadata(envelope)
        val payload = envelope.payload
        validateSourceScope(
            sourceAggregateId = payload.sourceAggregateId,
            sourceAggregateVersion = payload.sourceAggregateVersion,
            tenantGroupId = payload.tenantGroupId,
            clinicId = payload.clinicId,
            sourcePurchaseAuthority = payload.sourcePurchaseAuthority,
            sourcePurchaseId = payload.sourcePurchaseId,
        )
        require(payload.facts.size in 1..MAX_FACTS) { "facts size is invalid" }
        require(payload.facts.map { it.treatmentKey }.distinct().size == payload.facts.size) {
            "facts must contain unique treatmentKey values"
        }
        payload.facts.forEach { fact ->
            boundedIdentifier(fact.treatmentKey)
            fact.reasonCode?.let {
                require(it.length in 1..MAX_REASON_LENGTH && identifier.matches(it)) {
                    "reasonCode is invalid"
                }
            }
            fact.completedTreatment?.let(::validateTreatment)
            fact.remainingTreatment?.let(::validateTreatment)
        }
        require(TreatmentFulfillmentPayloadHasher.canonicalBytes(payload).size <= MAX_CANONICAL_PAYLOAD_BYTES) {
            "fulfillment payload is too large"
        }
    }

    private fun validateMetadata(envelope: UntrustedSchedulingEventEnvelope<*>) {
        listOf(
            envelope.eventId,
            envelope.eventType,
            envelope.producer,
            envelope.issuer,
            envelope.audience,
            envelope.keyId,
            envelope.algorithm,
            envelope.correlationId,
        ).forEach(::boundedIdentifier)
        require(envelope.payloadHash.matches(sha256)) { "payloadHash must be lowercase SHA-256" }
        require(envelope.schemaVersion > 0) { "schemaVersion must be positive" }
        require(envelope.signature.length in 1..MAX_SIGNATURE_LENGTH) { "signature length is invalid" }
    }

    private fun validateSourceScope(
        sourceAggregateId: String,
        sourceAggregateVersion: Long,
        tenantGroupId: Long,
        clinicId: Long,
        sourcePurchaseAuthority: String,
        sourcePurchaseId: String,
    ) {
        boundedIdentifier(sourceAggregateId)
        require(sourceAggregateVersion > 0) { "sourceAggregateVersion must be positive" }
        require(tenantGroupId > 0) { "tenantGroupId must be positive" }
        require(clinicId > 0) { "clinicId must be positive" }
        boundedIdentifier(sourcePurchaseAuthority)
        boundedIdentifier(sourcePurchaseId)
    }

    private fun validateMapping(mapping: MigrationMapping) {
        require(mapping.sourceTreatmentKeys.size <= MAX_TREATMENTS) { "too many source treatments" }
        mapping.sourceTreatmentKeys.forEach(::boundedIdentifier)
        require(mapping.targets.size <= MAX_TREATMENTS) { "too many target treatments" }
        mapping.targets.forEach { target -> boundedIdentifier(target.treatmentKey) }
    }

    private fun validateConsent(payload: ProductVersionMigrationApprovedEvent) {
        val consent = payload.consent
        require(consent.migrationId == payload.migrationId) { "consent migrationId mismatch" }
        require(consent.fromProductVersionId == payload.fromProductVersionId) {
            "consent fromProductVersionId mismatch"
        }
        require(consent.toProductVersionId == payload.toProductVersionId) { "consent toProductVersionId mismatch" }
        require(consent.mappingHash == payload.mappingHash) { "consent mappingHash mismatch" }
        require(consent.evidenceReferenceHash.matches(sha256)) {
            "consent evidenceReferenceHash must be lowercase SHA-256"
        }
    }

    private fun validateSnapshot(snapshot: PackageExecutionSnapshot) {
        boundedIdentifier(snapshot.packageProductId)
        boundedIdentifier(snapshot.packageProductVersionId)
        require(snapshot.snapshotHash.matches(sha256)) { "snapshotHash must be lowercase SHA-256" }
        require(snapshot.selectedComponentVersions.size <= MAX_TREATMENTS) { "too many component versions" }
        snapshot.selectedComponentVersions.forEach { component ->
            boundedIdentifier(component.componentProductId)
            boundedIdentifier(component.componentProductVersionId)
            component.selectionGroupId?.let(::boundedIdentifier)
        }
        require(snapshot.expandedTreatmentItems.size in 1..MAX_TREATMENTS) {
            "expandedTreatmentItems size is invalid"
        }
        snapshot.expandedTreatmentItems.forEach(::validateTreatment)
        snapshot.executionDependencies.forEach { dependency ->
            boundedIdentifier(dependency.predecessorTreatmentKey)
            boundedIdentifier(dependency.successorTreatmentKey)
        }
        snapshot.visitGroupingConstraints.forEach { grouping ->
            boundedIdentifier(grouping.firstTreatmentKey)
            boundedIdentifier(grouping.secondTreatmentKey)
        }
    }

    private fun validateTreatment(treatment: ExecutionTreatment) {
        boundedIdentifier(treatment.treatmentKey)
        boundedIdentifier(treatment.componentProductId)
        boundedIdentifier(treatment.componentProductVersionId)
        boundedIdentifier(treatment.sourceBomItemId)
        require(treatment.representativeTreatmentName.length in 1..MAX_TREATMENT_NAME_LENGTH) {
            "representativeTreatmentName is invalid"
        }
        require(treatment.detailedTreatmentCodes.size <= MAX_TREATMENTS) { "too many detailed treatment codes" }
        treatment.detailedTreatmentCodes.forEach(::boundedIdentifier)
        require(treatment.practitionerQualifications.size <= MAX_TREATMENTS) { "too many practitioner qualifications" }
        treatment.practitionerQualifications.forEach(::boundedIdentifier)
        require(treatment.equipmentTypes.size <= MAX_TREATMENTS) { "too many equipment types" }
        treatment.equipmentTypes.forEach(::boundedIdentifier)
        require(treatment.spaceCapabilities.size <= MAX_TREATMENTS) { "too many space capabilities" }
        treatment.spaceCapabilities.forEach(::boundedIdentifier)
    }

    private fun boundedIdentifier(value: String) {
        require(value.length in 1..MAX_IDENTIFIER_LENGTH && identifier.matches(value)) {
            "identifier is invalid"
        }
    }

}

/** 예약 신뢰도 event가 내부 table 경계를 넘기 전 적용하는 구조·PII 방지 경계입니다. */
internal object BookingReliabilityEventBounds {
    private const val MAX_IDENTIFIER_LENGTH = 128
    private const val MAX_SIGNATURE_LENGTH = 1_024
    private const val MAX_CANONICAL_PAYLOAD_BYTES = 16 * 1_024
    private val identifier = Regex("[A-Za-z0-9][A-Za-z0-9._:-]*")
    private val sha256 = Regex("[0-9a-f]{64}")

    fun validate(envelope: UntrustedSchedulingEventEnvelope<BookingReliabilitySignalEvent>) {
        listOf(
            envelope.eventId,
            envelope.eventType,
            envelope.producer,
            envelope.issuer,
            envelope.audience,
            envelope.keyId,
            envelope.algorithm,
            envelope.correlationId,
        ).forEach(::boundedIdentifier)
        require(envelope.payloadHash.matches(sha256)) { "payloadHash must be lowercase SHA-256" }
        require(envelope.schemaVersion > 0) { "schemaVersion must be positive" }
        require(envelope.signature.length in 1..MAX_SIGNATURE_LENGTH) { "signature length is invalid" }

        val payload = envelope.payload
        require(payload.eventId == envelope.eventId) { "eventId does not match envelope" }
        require(payload.occurredAt == envelope.occurredAt) { "occurredAt does not match envelope" }
        boundedIdentifier(payload.sourceAuthority)
        boundedIdentifier(payload.sourceAggregateId)
        require(payload.sourceVersion > 0) { "sourceVersion must be positive" }
        require(payload.tenantGroupId > 0) { "tenantGroupId must be positive" }
        require(payload.clinicId > 0) { "clinicId must be positive" }
        boundedIdentifier(payload.memberId)
        boundedIdentifier(payload.eventId)
        require(payload.appointmentId > 0) { "appointmentId must be positive" }
        require(BookingReliabilitySignalPayloadHasher.canonicalBytes(payload).size <= MAX_CANONICAL_PAYLOAD_BYTES) {
            "booking reliability payload is too large"
        }
    }

    private fun boundedIdentifier(value: String) {
        require(value.length in 1..MAX_IDENTIFIER_LENGTH && identifier.matches(value)) {
            "identifier is invalid"
        }
    }
}
