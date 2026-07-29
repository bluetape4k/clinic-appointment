package io.bluetape4k.clinic.appointment.event.integration

import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.error
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * raw payload를 해석하기 전에 broker header에서 얻는 외부 fact routing 정보입니다.
 *
 * payload mapping이나 signature 검증이 실패해도 encrypted quarantine을 만들 수 있도록
 * source scope를 raw JSON 밖의 고정 transport header로 받습니다. trusted payload와
 * 값이 다르면 `ROUTING_METADATA_MISMATCH`로 격리합니다.
 *
 * @property sourceAuthority source aggregate를 소유한 구매·임상·환불 서비스의
 * 안정 식별자입니다.
 * @property sourceAggregateId 해당 authority stream에서 version 순서를 공유하는 aggregate id입니다.
 * @property sourceAggregateVersion 1부터 증가하는 외부 aggregate version입니다.
 * @property tenantGroupId quarantine과 Plan 조회를 격리하는 SaaS tenant 식별자입니다.
 * @property clinicId 같은 tenant 안에서 예약 정책과 Plan을 소유하는 병원 식별자입니다.
 */
data class ExternalFactRoutingMetadata(
    val sourceAuthority: String,
    val sourceAggregateId: String,
    val sourceAggregateVersion: Long,
    val tenantGroupId: Long,
    val clinicId: Long,
) {
    init {
        require(sourceAuthority.isNotEmpty()) { "sourceAuthority must not be empty" }
        require(sourceAggregateId.isNotEmpty()) { "sourceAggregateId must not be empty" }
        require(sourceAggregateVersion > 0) { "sourceAggregateVersion must be positive" }
        require(tenantGroupId > 0) { "tenantGroupId must be positive" }
        require(clinicId > 0) { "clinicId must be positive" }
    }
}

/**
 * raw envelope metadata, 관측 routing, raw JSON bytes를 하나의 증거로 보호합니다.
 *
 * @param envelope 아직 trust 검증되지 않은 transport envelope입니다.
 * @param rawPayload strict decode 전의 정확한 UTF-8 JSON bytes입니다.
 * @param routing broker header에서 관측한 source·tenant·clinic routing입니다.
 */
interface RawExternalFactEnvelopeProtector {
    fun protect(
        envelope: UntrustedSchedulingEventEnvelope<*>,
        rawPayload: ByteArray,
        routing: ExternalFactRoutingMetadata,
    ): ProtectedQuarantineEnvelope

    /**
     * transport 상한을 초과한 원문을 암호화·보관하지 않고 전체 원문 hash만 계산합니다.
     *
     * 구현은 원문 크기에 비례하는 추가 배열을 만들지 않아야 합니다.
     */
    fun hashOnly(
        envelope: UntrustedSchedulingEventEnvelope<*>,
        rawPayload: ByteArray,
        routing: ExternalFactRoutingMetadata,
    ): ProtectedQuarantineEnvelope
}

/**
 * 외부 fact raw envelope를 AES-GCM으로 보호하는 production 구현입니다.
 *
 * canonical plaintext는 bounded envelope metadata, 관측 routing, 정확한 raw JSON
 * bytes를 결합합니다. 상한을 넘는 metadata는 전체 길이와 SHA-256으로 대체합니다.
 * plaintext는 저장하거나 로그에 남기지 않고 evidence hash와 randomized ciphertext만
 * 반환합니다.
 *
 * @param encryptionKey 128/192/256-bit AES key 원문입니다. 생성 시 방어적으로 복사합니다.
 * @param keyId 암호문 복호화 key를 찾기 위한 비밀이 아닌 rotation 식별자입니다.
 */
class AesGcmRawExternalFactEnvelopeProtector(
    encryptionKey: ByteArray,
    private val keyId: String,
) : RawExternalFactEnvelopeProtector {
    private val encryptionKey: SecretKey = SecretKeySpec(encryptionKey.copyOf(), "AES")

    init {
        require(encryptionKey.size in setOf(16, 24, 32)) {
            "AES encryption key must be 16, 24, or 32 bytes"
        }
        require(keyId.matches(SAFE_IDENTIFIER)) { "keyId contains unsafe characters" }
    }

    override fun protect(
        envelope: UntrustedSchedulingEventEnvelope<*>,
        rawPayload: ByteArray,
        routing: ExternalFactRoutingMetadata,
    ): ProtectedQuarantineEnvelope {
        require(rawPayload.size <= MAX_EXTERNAL_FACT_PAYLOAD_BYTES) {
            "raw external fact payload exceeds the encryptable transport limit"
        }
        val metadata = canonicalMetadata(envelope, rawPayload.size, routing)
        val plaintext = metadata + rawPayload
        val envelopeHash = evidenceHash(metadata, rawPayload)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey)
        cipher.updateAAD(
            "appointment-external-fact\u0000${aadComponent(envelope.eventId)}\u0000${
                aadComponent(envelope.eventType)
            }"
                .toByteArray(StandardCharsets.UTF_8),
        )
        val encrypted = cipher.doFinal(plaintext)
        return ProtectedQuarantineEnvelope(
            ciphertext = Base64.getEncoder().encodeToString(cipher.iv + encrypted),
            keyId = keyId,
            envelopeHash = envelopeHash,
        )
    }

    override fun hashOnly(
        envelope: UntrustedSchedulingEventEnvelope<*>,
        rawPayload: ByteArray,
        routing: ExternalFactRoutingMetadata,
    ): ProtectedQuarantineEnvelope {
        require(rawPayload.size > MAX_EXTERNAL_FACT_PAYLOAD_BYTES) {
            "hash-only evidence is reserved for oversized external fact payloads"
        }
        val metadata = canonicalMetadata(envelope, rawPayload.size, routing)
        return ProtectedQuarantineEnvelope(
            ciphertext = null,
            keyId = keyId,
            envelopeHash = evidenceHash(metadata, rawPayload),
        )
    }

    private fun canonicalMetadata(
        envelope: UntrustedSchedulingEventEnvelope<*>,
        rawPayloadSize: Int,
        routing: ExternalFactRoutingMetadata,
    ): ByteArray =
        CanonicalFrameWriter().apply {
            boundedString("eventId", envelope.eventId, MAX_IDENTIFIER_LENGTH)
            boundedString("eventType", envelope.eventType, MAX_IDENTIFIER_LENGTH)
            instant("occurredAt", envelope.occurredAt)
            instant("receivedAt", envelope.receivedAt)
            boundedString("producer", envelope.producer, MAX_IDENTIFIER_LENGTH)
            boundedString("issuer", envelope.issuer, MAX_IDENTIFIER_LENGTH)
            boundedString("audience", envelope.audience, MAX_IDENTIFIER_LENGTH)
            boundedString("keyId", envelope.keyId, MAX_IDENTIFIER_LENGTH)
            boundedString("algorithm", envelope.algorithm, MAX_IDENTIFIER_LENGTH)
            int("schemaVersion", envelope.schemaVersion)
            boundedString("correlationId", envelope.correlationId, MAX_IDENTIFIER_LENGTH)
            boundedString("payloadHash", envelope.payloadHash, MAX_SHA256_LENGTH)
            boundedString("signature", envelope.signature, MAX_SIGNATURE_LENGTH)
            boundedString("routing.sourceAuthority", routing.sourceAuthority, MAX_IDENTIFIER_LENGTH)
            boundedString("routing.sourceAggregateId", routing.sourceAggregateId, MAX_IDENTIFIER_LENGTH)
            long("routing.sourceAggregateVersion", routing.sourceAggregateVersion)
            long("routing.tenantGroupId", routing.tenantGroupId)
            long("routing.clinicId", routing.clinicId)
            int("rawPayload.size", rawPayloadSize)
        }.toByteArray()

    private fun evidenceHash(metadata: ByteArray, rawPayload: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .apply {
                update(metadata)
                update(rawPayload)
            }
            .digest()
            .joinToString("") { byte -> "%02x".format(byte) }

    /**
     * 허용 상한을 넘은 metadata는 전체 길이와 SHA-256만 암호화 frame에 넣습니다.
     *
     * exact raw JSON은 그대로 보호하면서 공격자가 만든 거대 header가 quarantine
     * ciphertext 상한을 다시 초과하지 않게 합니다.
     */
    private fun CanonicalFrameWriter.boundedString(
        name: String,
        value: String,
        maximumLength: Int,
    ) {
        int("$name.length", value.length)
        if (value.length <= maximumLength) {
            string(name, value)
        } else {
            string("$name.sampleHash", boundedSampleHash(value))
        }
    }

    private fun aadComponent(value: String): String =
        if (value.length <= MAX_IDENTIFIER_LENGTH && SAFE_IDENTIFIER.matches(value)) {
            value
        } else {
            "invalid:${value.length}:${boundedSampleHash(value)}"
        }

    /**
     * 공격자가 만든 거대 header를 전체 byte 배열로 복제하지 않고 고정 길이 표본만 hash합니다.
     */
    private fun boundedSampleHash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        DigestOutputStream(OutputStream.nullOutputStream(), digest).use { digestStream ->
            OutputStreamWriter(digestStream, StandardCharsets.UTF_8).use { writer ->
                writer.write(value, 0, minOf(value.length, MAX_METADATA_SAMPLE_LENGTH))
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private companion object {
        const val MAX_IDENTIFIER_LENGTH = 128
        const val MAX_SHA256_LENGTH = 64
        const val MAX_SIGNATURE_LENGTH = 1_024
        const val MAX_METADATA_SAMPLE_LENGTH = 256
        val SAFE_IDENTIFIER = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
    }
}

/** 상품 version 전환 stream의 gap authority proof를 조회합니다. */
fun interface ProductVersionMigrationProofProvider {
    /**
     * 조회가 불가능하거나 contiguous version이면 `null`을 반환하고 예외를 던지지 않습니다.
     */
    fun obtain(
        producer: String,
        event: ProductVersionMigrationApprovedEvent,
    ): SourceAuthorityVersionProof?
}

/** 고객 일정 거부 stream의 gap authority proof를 조회합니다. */
fun interface MigrationDeclineProofProvider {
    /**
     * 조회가 불가능하거나 contiguous version이면 `null`을 반환하고 예외를 던지지 않습니다.
     */
    fun obtain(
        producer: String,
        event: ProductVersionMigrationRescheduleDeclinedEvent,
    ): SourceAuthorityVersionProof?
}

/** 완료·부분 이행·환불 stream의 gap authority proof를 조회합니다. */
fun interface TreatmentFulfillmentProofProvider {
    /**
     * 조회가 불가능하거나 contiguous version이면 `null`을 반환하고 예외를 던지지 않습니다.
     */
    fun obtain(
        producer: String,
        event: TreatmentFulfillmentEvent,
    ): SourceAuthorityVersionProof?
}

/**
 * 외부 fact consumer 경계에서 최종 수렴 상태와 reason code를 기록합니다.
 *
 * handler 내부 metric과 별도로 proof 조회 실패처럼 handler mutation 전에 끝나는
 * 경로도 같은 cardinality의 `(result, reason)` label로 관측하기 위한 port입니다.
 */
fun interface ExternalFactMetrics {
    fun record(result: String, reason: String?)

    companion object {
        val NOOP = ExternalFactMetrics { _, _ -> }
    }
}

/**
 * 외부 fact consumer의 유일한 production mutation 진입점입니다.
 *
 * 한 호출에서 raw 원문 보호, strict decoding, trust 검증, transport routing 대조,
 * source authority proof 조회, handler 실행을 순서대로 수행합니다. decode/trust/routing
 * 실패는 handler를 호출하지 않고 FK 없는 terminal rejection에 관측 routing과
 * evidence hash를 남깁니다. 존재가 확인된 tenant·clinic scope에는 encrypted
 * quarantine과 append-only 감사 row도 함께 기록합니다. Task 8 handler mutation
 * 메서드는 module-internal이므로 다른 모듈의 broker/controller wiring이 이 경계를
 * 우회할 수 없습니다.
 */
class ExternalFactEventConsumer(
    private val ingress: ExternalFactEventIngress,
    private val rawEnvelopeProtector: RawExternalFactEnvelopeProtector,
    private val quarantineRepository: SchedulingQuarantineRepository,
    private val rejectionRepository: UntrustedSchedulingEventRejectionRepository,
    private val migrationProofProvider: ProductVersionMigrationProofProvider,
    private val declineProofProvider: MigrationDeclineProofProvider,
    private val fulfillmentProofProvider: TreatmentFulfillmentProofProvider,
    private val migrationHandler: ProductVersionMigrationHandler,
    private val fulfillmentHandler: TreatmentFulfillmentHandler,
    private val clock: Clock,
    private val quarantineRetention: Duration = Duration.ofDays(30),
    private val metrics: ExternalFactMetrics = ExternalFactMetrics.NOOP,
) {
    /**
     * 고객 동의가 증명된 상품 version 전환을 동일 Plan의 새 revision으로 반영합니다.
     *
     * @return 생성, replay, gap 대기, 격리 중 최종 처리 상태입니다.
     */
    fun acceptProductVersionMigration(
        rawEnvelope: UntrustedSchedulingEventEnvelope<*>,
        rawPayload: ByteArray,
        routing: ExternalFactRoutingMetadata,
    ): PurchaseHandleResult =
        accept(
            rawEnvelope,
            rawPayload,
            routing,
            ingress::verifyProductVersionMigration,
            migrationProofProvider::obtain,
            migrationHandler::stageAuthorityUnavailable,
        ) { trusted, protected, proof ->
            migrationHandler.handle(trusted, protected, proof)
        }

    /**
     * 전환 뒤 고객의 확정 일정 변경 거부를 운영 예외와 CRM handoff로 기록합니다.
     *
     * 이 호출은 기존 확정 예약을 직접 변경하거나 취소하지 않습니다.
     */
    fun acceptMigrationRescheduleDeclined(
        rawEnvelope: UntrustedSchedulingEventEnvelope<*>,
        rawPayload: ByteArray,
        routing: ExternalFactRoutingMetadata,
    ): PurchaseHandleResult =
        accept(
            rawEnvelope,
            rawPayload,
            routing,
            ingress::verifyMigrationRescheduleDeclined,
            declineProofProvider::obtain,
            migrationHandler::stageDeclineAuthorityUnavailable,
        ) { trusted, protected, proof ->
            migrationHandler.handleRescheduleDeclined(trusted, protected, proof)
        }

    /**
     * 진료 완료·부분 이행·자원 장애·환불 사실을 미래 예약 가능 의무에 투영합니다.
     *
     * 완료 provenance는 기존 revision에 보존하며 후속 변경은 새 revision으로만 기록합니다.
     */
    fun acceptTreatmentFulfillment(
        rawEnvelope: UntrustedSchedulingEventEnvelope<*>,
        rawPayload: ByteArray,
        routing: ExternalFactRoutingMetadata,
    ): PurchaseHandleResult =
        accept(
            rawEnvelope,
            rawPayload,
            routing,
            ingress::verifyTreatmentFulfillment,
            fulfillmentProofProvider::obtain,
            fulfillmentHandler::stageAuthorityUnavailable,
        ) { trusted, protected, proof ->
            fulfillmentHandler.handle(trusted, protected, proof)
        }

    private fun <T> accept(
        rawEnvelope: UntrustedSchedulingEventEnvelope<*>,
        rawPayload: ByteArray,
        routing: ExternalFactRoutingMetadata,
        verify: (UntrustedSchedulingEventEnvelope<*>, ByteArray) -> TrustedSchedulingEventEnvelope<T>,
        proofProvider: (String, T) -> SourceAuthorityVersionProof?,
        stageAuthorityUnavailable: (
            TrustedSchedulingEventEnvelope<T>,
            SourceAuthorityFailureReason,
            ProtectedQuarantineEnvelope,
        ) -> PurchaseHandleResult,
        handle: (
            TrustedSchedulingEventEnvelope<T>,
            ProtectedQuarantineEnvelope,
            SourceAuthorityVersionProof?,
        ) -> PurchaseHandleResult,
    ): PurchaseHandleResult {
        val protected =
            if (rawPayload.size > MAX_EXTERNAL_FACT_PAYLOAD_BYTES) {
                rawEnvelopeProtector.hashOnly(rawEnvelope, rawPayload, routing)
            } else {
                rawEnvelopeProtector.protect(rawEnvelope, rawPayload, routing)
            }
        val trusted = try {
            verify(rawEnvelope, rawPayload)
        } catch (failure: SchedulingTrustException) {
            return record(reject(rawEnvelope, routing, protected, failure.reasonCode))
        }
        if (!routing.matches(trusted.payload)) {
            return record(reject(
                rawEnvelope = rawEnvelope,
                observedRouting = routing,
                protected = protected,
                reasonCode = "ROUTING_METADATA_MISMATCH",
                quarantineRouting = trusted.payload.routingMetadata(),
            ))
        }
        val proof = try {
            proofProvider(trusted.producer, trusted.payload)
        } catch (failure: SourceAuthorityUnavailableException) {
            return record(
                stageAuthorityUnavailable(
                    trusted,
                    failure.failureReason,
                    protected,
                )
            )
        }
        return record(handle(trusted, protected, proof))
    }

    private fun record(result: PurchaseHandleResult): PurchaseHandleResult {
        try {
            metrics.record(result.status.name, result.reasonCode)
        } catch (failure: Exception) {
            log.error(failure) { "External fact metric recording failed" }
        }
        return result
    }

    private fun reject(
        rawEnvelope: UntrustedSchedulingEventEnvelope<*>,
        observedRouting: ExternalFactRoutingMetadata,
        protected: ProtectedQuarantineEnvelope,
        reasonCode: String,
        quarantineRouting: ExternalFactRoutingMetadata? = observedRouting,
    ): PurchaseHandleResult {
        val quarantineEventId = safeIdentifier("event", rawEnvelope.eventId)
        val handled = try {
            transaction {
                if (
                    rejectionRepository.exists(quarantineEventId) ||
                    quarantineRepository.findByEventId(quarantineEventId) != null
                ) {
                    return@transaction PurchaseHandleResult(
                        PurchaseHandleStatus.DUPLICATE,
                        "EVENT_ALREADY_TERMINAL",
                    )
                }
                val detectedAt = clock.instant()
                rejectionRepository.record(
                    UntrustedEventRejection(
                        eventId = quarantineEventId,
                        eventType = safeIdentifier("type", rawEnvelope.eventType),
                        producer = safeIdentifier("producer", rawEnvelope.producer),
                        sourceAuthority = safeIdentifier("authority", observedRouting.sourceAuthority),
                        sourceAggregateId = safeIdentifier("aggregate", observedRouting.sourceAggregateId),
                        sourceAggregateVersion = observedRouting.sourceAggregateVersion,
                        claimedTenantGroupId = observedRouting.tenantGroupId,
                        claimedClinicId = observedRouting.clinicId,
                        schemaVersion = rawEnvelope.schemaVersion.coerceAtLeast(1),
                        correlationId = safeIdentifier("correlation", rawEnvelope.correlationId),
                        reasonCode = reasonCode,
                        envelopeHash = protected.envelopeHash,
                        detectedAt = detectedAt,
                    ),
                )
                quarantineRouting
                    ?.takeIf(::routingScopeExists)
                    ?.let { storageRouting ->
                        quarantineRepository.recordDetected(
                            QuarantineDetection(
                                eventId = quarantineEventId,
                                eventType = safeIdentifier("type", rawEnvelope.eventType),
                                protectedEnvelope = protected,
                                producer = safeIdentifier("producer", rawEnvelope.producer),
                                sourceAuthority = safeIdentifier("authority", storageRouting.sourceAuthority),
                                schemaVersion = rawEnvelope.schemaVersion.coerceAtLeast(1),
                                sourceAggregateId = safeIdentifier("aggregate", storageRouting.sourceAggregateId),
                                sourceAggregateVersion = storageRouting.sourceAggregateVersion,
                                tenantGroupId = storageRouting.tenantGroupId,
                                clinicId = storageRouting.clinicId,
                                reasonCode = reasonCode,
                                detectedAt = detectedAt,
                                correlationId = safeIdentifier("correlation", rawEnvelope.correlationId),
                                retentionClass = QuarantineRetentionClass.STANDARD,
                                payloadExpiresAt = detectedAt.plus(quarantineRetention),
                            ),
                        )
                    }
                PurchaseHandleResult(PurchaseHandleStatus.QUARANTINED, reasonCode)
            }
        } catch (failure: ExposedSQLException) {
            if (!failure.sqlState.startsWith("23")) throw failure
            transaction {
                check(
                    rejectionRepository.exists(quarantineEventId) ||
                        quarantineRepository.findByEventId(quarantineEventId) != null,
                ) {
                    "external fact rejection constraint conflict could not be classified"
                }
                PurchaseHandleResult(PurchaseHandleStatus.DUPLICATE, "EVENT_RACE_CONVERGED")
            }
        }
        return handled
    }

    /**
     * tenant-neutral rejection과 별도로 암호화 quarantine을 저장할 수 있는 scope인지 확인합니다.
     */
    private fun routingScopeExists(routing: ExternalFactRoutingMetadata): Boolean =
        Clinics
            .selectAll()
            .where {
                (Clinics.id eq routing.clinicId) and
                    (Clinics.tenantGroupId eq routing.tenantGroupId)
            }
            .limit(1)
            .any()

    /*
     * 아래 식별자 정규화는 tenant-neutral rejection row와 quarantine index에만 적용합니다.
     * 원래 transport metadata와 observed routing은 protected envelope hash의 입력입니다.
     */

    /**
     * 잘못된 transport metadata도 원문을 노출하지 않고 quarantine 식별자로 바꿉니다.
     *
     * 정상 identifier는 그대로 유지해 운영 검색성을 보존하고, 형식이 잘못된 값만
     * SHA-256 기반의 결정적 대체값으로 바꿉니다. 원래 값은 encrypted envelope 안에만
     * 남으므로 PII나 제어문자가 index·audit column으로 유출되지 않습니다.
     */
    private fun safeIdentifier(prefix: String, value: String): String =
        if (SAFE_IDENTIFIER.matches(value)) {
            value
        } else {
            "$prefix-invalid-${value.length}-${boundedIdentifierSampleHash(value)}"
        }

    /**
     * index용 대체 식별자는 최대 256자 표본만 읽어 pre-trust CPU·allocation을 제한합니다.
     */
    private fun boundedIdentifierSampleHash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        DigestOutputStream(OutputStream.nullOutputStream(), digest).use { digestStream ->
            OutputStreamWriter(digestStream, StandardCharsets.UTF_8).use { writer ->
                writer.write(value, 0, minOf(value.length, MAX_IDENTIFIER_SAMPLE_LENGTH))
            }
        }
        return digest.digest()
            .joinToString("") { byte -> "%02x".format(byte) }
            .take(32)
    }

    private fun <T> ExternalFactRoutingMetadata.matches(payload: T): Boolean =
        when (payload) {
            is ProductVersionMigrationApprovedEvent ->
                matches(
                    payload.sourcePurchaseAuthority,
                    payload.sourceAggregateId,
                    payload.sourceAggregateVersion,
                    payload.tenantGroupId,
                    payload.clinicId,
                )
            is ProductVersionMigrationRescheduleDeclinedEvent ->
                matches(
                    payload.sourcePurchaseAuthority,
                    payload.sourceAggregateId,
                    payload.sourceAggregateVersion,
                    payload.tenantGroupId,
                    payload.clinicId,
                )
            is TreatmentFulfillmentEvent ->
                matches(
                    payload.sourcePurchaseAuthority,
                    payload.sourceAggregateId,
                    payload.sourceAggregateVersion,
                    payload.tenantGroupId,
                    payload.clinicId,
                )
            else -> false
        }

    /**
     * trust 검증을 통과한 payload의 권위 scope를 quarantine FK와 운영 조회 기준으로 사용합니다.
     *
     * broker routing이 위조되거나 오래된 경우 그 값을 그대로 저장하면 존재하지 않는
     * tenant/clinic FK 때문에 증거 기록 자체가 실패할 수 있습니다.
     */
    private fun <T> T.routingMetadata(): ExternalFactRoutingMetadata =
        when (this) {
            is ProductVersionMigrationApprovedEvent ->
                ExternalFactRoutingMetadata(
                    sourcePurchaseAuthority,
                    sourceAggregateId,
                    sourceAggregateVersion,
                    tenantGroupId,
                    clinicId,
                )
            is ProductVersionMigrationRescheduleDeclinedEvent ->
                ExternalFactRoutingMetadata(
                    sourcePurchaseAuthority,
                    sourceAggregateId,
                    sourceAggregateVersion,
                    tenantGroupId,
                    clinicId,
                )
            is TreatmentFulfillmentEvent ->
                ExternalFactRoutingMetadata(
                    sourcePurchaseAuthority,
                    sourceAggregateId,
                    sourceAggregateVersion,
                    tenantGroupId,
                    clinicId,
                )
            else -> error("unsupported external fact payload type: ${this?.let { it::class.simpleName }}")
        }

    private fun ExternalFactRoutingMetadata.matches(
        sourceAuthority: String,
        sourceAggregateId: String,
        sourceAggregateVersion: Long,
        tenantGroupId: Long,
        clinicId: Long,
    ): Boolean =
        this.sourceAuthority == sourceAuthority &&
            this.sourceAggregateId == sourceAggregateId &&
            this.sourceAggregateVersion == sourceAggregateVersion &&
            this.tenantGroupId == tenantGroupId &&
            this.clinicId == clinicId

    private companion object : KLogging() {
        const val MAX_IDENTIFIER_SAMPLE_LENGTH = 256
        val SAFE_IDENTIFIER = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
    }
}
