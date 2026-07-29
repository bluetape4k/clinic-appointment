package io.bluetape4k.clinic.appointment.event.integration

import io.bluetape4k.clinic.appointment.model.plan.MigrationMapping
import io.bluetape4k.clinic.appointment.model.plan.PackageExecutionSnapshot
import io.bluetape4k.support.requireNotBlank
import java.io.Serializable
import java.security.MessageDigest
import java.time.Instant

/**
 * 상품서비스와 상담 절차가 승인한 기존 구매의 상품 version 전환 사실입니다.
 *
 * 예약서비스는 이 event로 상품 계약을 수정하지 않습니다. [mappings]과
 * [targetExecutionSnapshot]을 검증한 뒤, 이미 완료된 진료는 과거 revision에 그대로
 * 두고 미래 진료만 동일 Plan의 새 immutable revision으로 만듭니다.
 *
 * @property sourceAggregateId 상품서비스 migration aggregate의 안정적인 식별자입니다.
 * @property sourceAggregateVersion producer 안에서 단조 증가하는 양수 version입니다.
 * @property tenantGroupId SaaS tenant 경계이며 [clinicId]와 함께 Plan lookup 범위를
 * 결정합니다.
 * @property sourcePurchaseAuthority 구매 ID 소유 authority입니다.
 * @property sourcePurchaseId 기존 Plan을 만든 구매 식별자입니다. 상품 version 전환은
 * 새 구매가 아니므로 새 Plan을 만들지 않습니다.
 * @property migrationId 고객 동의 subject와 전환표를 결합하는 안정적인 전환 ID입니다.
 * @property fromProductVersionId 처리 직전 활성 revision의 정확한 상품 version입니다.
 * @property toProductVersionId 승인된 목표 version입니다.
 * @property mappings 모든 미완료 source를 정확히 한 번 설명하는 명시적 BOM 대응표입니다.
 * @property mappingHash [mappings]의 canonical SHA-256이며 [consent]와 같아야 합니다.
 * @property consent migration, from/to version, mapping hash 전체에 대한 고객 동의
 * 증거입니다. 일정 변경 동의를 대신하지 않습니다.
 * @property targetExecutionSnapshot 목표 상품 version의 완전 전개된 실행 BOM입니다.
 */
data class ProductVersionMigrationApprovedEvent(
    val sourceAggregateId: String,
    val sourceAggregateVersion: Long,
    val tenantGroupId: Long,
    val clinicId: Long,
    val sourcePurchaseAuthority: String,
    val sourcePurchaseId: String,
    val migrationId: String,
    val fromProductVersionId: String,
    val toProductVersionId: String,
    val mappings: List<MigrationMapping>,
    val mappingHash: String,
    val consent: ProductVersionMigrationConsentEvidence,
    val targetExecutionSnapshot: PackageExecutionSnapshot,
) : Serializable {
    init {
        sourceAggregateId.requireNotBlank("sourceAggregateId")
        require(sourceAggregateVersion > 0) { "sourceAggregateVersion must be positive" }
        require(tenantGroupId > 0) { "tenantGroupId must be positive" }
        require(clinicId > 0) { "clinicId must be positive" }
        sourcePurchaseAuthority.requireNotBlank("sourcePurchaseAuthority")
        sourcePurchaseId.requireNotBlank("sourcePurchaseId")
        migrationId.requireNotBlank("migrationId")
        fromProductVersionId.requireNotBlank("fromProductVersionId")
        toProductVersionId.requireNotBlank("toProductVersionId")
        require(fromProductVersionId != toProductVersionId) {
            "product migration must change the product version"
        }
        require(mappings.isNotEmpty()) { "mappings must not be empty" }
        require(mappingHash.matches(LOWERCASE_SHA_256)) {
            "mappingHash must be lowercase SHA-256"
        }
    }

    companion object {
        private const val serialVersionUID = 1L
        private val LOWERCASE_SHA_256 = Regex("[0-9a-f]{64}")
    }
}

/**
 * 상품 version 전환 자체에 대한 최소 동의 증거입니다.
 *
 * 원문 서명이나 상담 기록을 복제하지 않고 비가역 [evidenceReferenceHash]만
 * 저장·전달합니다.
 * 예약 날짜 변경에는 별도의 appointment proposal 동의가 필요합니다.
 */
data class ProductVersionMigrationConsentEvidence(
    val migrationId: String,
    val fromProductVersionId: String,
    val toProductVersionId: String,
    val mappingHash: String,
    val consentedAt: Instant,
    val evidenceType: ProductVersionMigrationConsentEvidenceType,
    val evidenceReferenceHash: String,
) : Serializable {
    init {
        migrationId.requireNotBlank("migrationId")
        fromProductVersionId.requireNotBlank("fromProductVersionId")
        toProductVersionId.requireNotBlank("toProductVersionId")
        require(mappingHash.matches(LOWERCASE_SHA_256)) {
            "mappingHash must be lowercase SHA-256"
        }
        require(evidenceReferenceHash.matches(LOWERCASE_SHA_256)) {
            "evidenceReferenceHash must be lowercase SHA-256"
        }
    }

    companion object {
        private const val serialVersionUID = 1L
        private val LOWERCASE_SHA_256 = Regex("[0-9a-f]{64}")
    }
}

/**
 * 예약서비스가 인정하는 상품 version 전환 동의 증거의 고정 allowlist입니다.
 *
 * enum 밖의 임의 문자열은 신뢰 경계에서 역직렬화되지 않습니다. 실제 원문과 접근
 * 제어는 동의 소유 서비스가 담당하고, 예약서비스는 비가역 reference hash만 보존합니다.
 */
enum class ProductVersionMigrationConsentEvidenceType {
    DIGITAL_SIGNATURE,
    WRITTEN_CONSENT,
    RECORDED_CALL,
}

/**
 * 고객이 상품 전환 뒤 제안된 새 일정을 거부한 내부 사실입니다.
 *
 * 기존 확정 예약을 취소하거나 변경하라는 command가 아닙니다. 운영 예외와 CRM handoff만
 * 생성해 상담팀이 별도 조정하도록 합니다.
 */
data class ProductVersionMigrationRescheduleDeclinedEvent(
    val sourceAggregateId: String,
    val sourceAggregateVersion: Long,
    val tenantGroupId: Long,
    val clinicId: Long,
    val sourcePurchaseAuthority: String,
    val sourcePurchaseId: String,
    val migrationId: String,
    val appointmentId: Long?,
    val reasonCode: String,
) : Serializable {
    init {
        sourceAggregateId.requireNotBlank("sourceAggregateId")
        require(sourceAggregateVersion > 0) { "sourceAggregateVersion must be positive" }
        require(tenantGroupId > 0) { "tenantGroupId must be positive" }
        require(clinicId > 0) { "clinicId must be positive" }
        sourcePurchaseAuthority.requireNotBlank("sourcePurchaseAuthority")
        sourcePurchaseId.requireNotBlank("sourcePurchaseId")
        migrationId.requireNotBlank("migrationId")
        appointmentId?.let { require(it > 0) { "appointmentId must be positive" } }
        require(reasonCode == CUSTOMER_DECLINED_RESCHEDULE_REASON) {
            "reasonCode must identify customer-declined rescheduling"
        }
    }

    companion object {
        private const val serialVersionUID = 1L
        const val CUSTOMER_DECLINED_RESCHEDULE_REASON = "CUSTOMER_DECLINED_RESCHEDULE"
    }
}

/**
 * serializer 설정이나 collection iteration 순서에 의존하지 않는 migration payload hasher입니다.
 */
object ProductVersionMigrationPayloadHasher {
    /** mapping 목록의 선언 순서와 각 source key의 정렬된 값을 SHA-256으로 계산합니다. */
    fun mappingHash(mappings: List<MigrationMapping>): String =
        sha256(
            CanonicalFrameWriter().apply {
                int("mappings.size", mappings.size)
                mappings.forEachIndexed { index, mapping ->
                    string("mappings[$index].type", mapping.type.name)
                    val sources = mapping.sourceTreatmentKeys.sorted()
                    int("mappings[$index].sources.size", sources.size)
                    sources.forEachIndexed { sourceIndex, source ->
                        string("mappings[$index].sources[$sourceIndex]", source)
                    }
                    int("mappings[$index].targets.size", mapping.targets.size)
                    mapping.targets.forEachIndexed { targetIndex, target ->
                        string("mappings[$index].targets[$targetIndex]", target.treatmentKey)
                    }
                }
            }.toByteArray(),
        )

    /**
     * source scope, consent subject, mapping, 목표 실행 BOM 전체를 포함한 SHA-256입니다.
     */
    fun hash(event: ProductVersionMigrationApprovedEvent): String {
        val snapshotHash = PackageExecutionPayloadHasher.hash(
            PackageExecutionEvent(
                sourceAggregateId = event.sourceAggregateId,
                sourceAggregateVersion = event.sourceAggregateVersion,
                tenantGroupId = event.tenantGroupId,
                clinicId = event.clinicId,
                sourcePurchaseAuthority = event.sourcePurchaseAuthority,
                sourcePurchaseId = event.sourcePurchaseId,
                executionSnapshot = event.targetExecutionSnapshot,
            ),
        )
        return sha256(
            CanonicalFrameWriter().apply {
                string("sourceAggregateId", event.sourceAggregateId)
                long("sourceAggregateVersion", event.sourceAggregateVersion)
                long("tenantGroupId", event.tenantGroupId)
                long("clinicId", event.clinicId)
                string("sourcePurchaseAuthority", event.sourcePurchaseAuthority)
                string("sourcePurchaseId", event.sourcePurchaseId)
                string("migrationId", event.migrationId)
                string("fromProductVersionId", event.fromProductVersionId)
                string("toProductVersionId", event.toProductVersionId)
                string("mappingHash", event.mappingHash)
                string("calculatedMappingHash", mappingHash(event.mappings))
                string("consent.migrationId", event.consent.migrationId)
                string("consent.fromProductVersionId", event.consent.fromProductVersionId)
                string("consent.toProductVersionId", event.consent.toProductVersionId)
                string("consent.mappingHash", event.consent.mappingHash)
                string("consent.consentedAt", event.consent.consentedAt.toString())
                string("consent.evidenceType", event.consent.evidenceType.name)
                string("consent.evidenceReferenceHash", event.consent.evidenceReferenceHash)
                string("targetExecutionSnapshotHash", snapshotHash)
            }.toByteArray(),
        )
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }
}
