package io.bluetape4k.clinic.appointment.event.integration

import io.bluetape4k.clinic.appointment.model.plan.PackageExecutionSnapshot
import java.io.Serializable
import java.security.MessageDigest

/**
 * 구매 당시 확정된 실행 BOM을 예약 Plan revision으로 전달하는 integration event입니다.
 *
 * @property sourceAggregateId 구매 aggregate의 안정적인 ID입니다.
 * @property sourceAggregateVersion 같은 구매에서 1부터 단조 증가하는 source version입니다.
 * @property tenantGroupId SaaS tenant 경계, [clinicId]는 병원 경계입니다.
 * @property sourcePurchaseAuthority 구매 ID를 소유하는 authority입니다.
 * @property sourcePurchaseId authority 안에서 안정적인 구매 ID입니다.
 * @property executionSnapshot 단일 상품도 구성 상품 quantity 1로 정규화된 불변 실행 계약입니다.
 */
data class PackageExecutionEvent(
    val sourceAggregateId: String,
    val sourceAggregateVersion: Long,
    val tenantGroupId: Long,
    val clinicId: Long,
    val sourcePurchaseAuthority: String,
    val sourcePurchaseId: String,
    val executionSnapshot: PackageExecutionSnapshot,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * class 이름이나 serializer default typing에 의존하지 않는 실행 BOM canonical hasher입니다.
 */
object PackageExecutionPayloadHasher {
    /**
     * 실행 BOM의 전체 provenance와 실행 제약을 canonical frame으로 직렬화해 SHA-256을 계산합니다.
     *
     * @param event 신뢰 검증할 schema version 1 payload입니다.
     * @return 소문자 64자리 SHA-256 hex digest입니다.
     */
    fun hash(event: PackageExecutionEvent): String =
        MessageDigest.getInstance("SHA-256")
            .digest(canonicalBytes(event))
            .joinToString("") { byte -> "%02x".format(byte) }

    internal fun canonicalBytes(event: PackageExecutionEvent): ByteArray =
        CanonicalFrameWriter().apply {
            string("sourceAggregateId", event.sourceAggregateId)
            long("sourceAggregateVersion", event.sourceAggregateVersion)
            long("tenantGroupId", event.tenantGroupId)
            long("clinicId", event.clinicId)
            string("sourcePurchaseAuthority", event.sourcePurchaseAuthority)
            string("sourcePurchaseId", event.sourcePurchaseId)
            val snapshot = event.executionSnapshot
            string("snapshot.packageProductId", snapshot.packageProductId)
            string("snapshot.packageProductVersionId", snapshot.packageProductVersionId)
            string("snapshot.snapshotHash", snapshot.snapshotHash)
            int("snapshot.components.size", snapshot.selectedComponentVersions.size)
            snapshot.selectedComponentVersions.forEachIndexed { index, component ->
                string("snapshot.components[$index].productId", component.componentProductId)
                string("snapshot.components[$index].versionId", component.componentProductVersionId)
                int("snapshot.components[$index].quantity", component.quantity)
                string("snapshot.components[$index].selectionGroupId", component.selectionGroupId)
            }
            int("snapshot.selections.size", snapshot.componentSelections.size)
            snapshot.componentSelections.forEachIndexed { index, selection ->
                string("snapshot.selections[$index].id", selection.selectionGroupId)
                int("snapshot.selections[$index].candidateCount", selection.candidateCount)
                int("snapshot.selections[$index].requiredCount", selection.requiredSelectionCount)
            }
            int("snapshot.treatments.size", snapshot.expandedTreatmentItems.size)
            snapshot.expandedTreatmentItems.forEachIndexed { index, treatment ->
                string("snapshot.treatments[$index].key", treatment.treatmentKey)
                string("snapshot.treatments[$index].productId", treatment.componentProductId)
                string("snapshot.treatments[$index].versionId", treatment.componentProductVersionId)
                string("snapshot.treatments[$index].bomItemId", treatment.sourceBomItemId)
                int("snapshot.treatments[$index].sequence", treatment.sequence)
                string("snapshot.treatments[$index].name", treatment.representativeTreatmentName)
                stringList("snapshot.treatments[$index].codes", treatment.detailedTreatmentCodes)
                int("snapshot.treatments[$index].preparation", treatment.preparationMinutes)
                int("snapshot.treatments[$index].treatment", treatment.treatmentMinutes)
                int("snapshot.treatments[$index].recovery", treatment.recoveryMinutes)
                stringList("snapshot.treatments[$index].practitioners", treatment.practitionerQualifications)
                stringList("snapshot.treatments[$index].equipment", treatment.equipmentTypes)
                stringList("snapshot.treatments[$index].spaces", treatment.spaceCapabilities)
            }
            int("snapshot.dependencies.size", snapshot.executionDependencies.size)
            snapshot.executionDependencies.forEachIndexed { index, dependency ->
                string("snapshot.dependencies[$index].predecessor", dependency.predecessorTreatmentKey)
                string("snapshot.dependencies[$index].successor", dependency.successorTreatmentKey)
                string("snapshot.dependencies[$index].type", dependency.type.name)
                int("snapshot.dependencies[$index].minimum", dependency.minimumIntervalDays)
                string("snapshot.dependencies[$index].preferred", dependency.preferredIntervalDays?.toString())
                string("snapshot.dependencies[$index].maximum", dependency.maximumIntervalDays?.toString())
            }
            int("snapshot.grouping.size", snapshot.visitGroupingConstraints.size)
            snapshot.visitGroupingConstraints.forEachIndexed { index, grouping ->
                string("snapshot.grouping[$index].first", grouping.firstTreatmentKey)
                string("snapshot.grouping[$index].second", grouping.secondTreatmentKey)
                string("snapshot.grouping[$index].type", grouping.type.name)
            }
        }.toByteArray()

    private fun CanonicalFrameWriter.stringList(name: String, values: List<String>) {
        int("$name.size", values.size)
        values.forEachIndexed { index, value -> string("$name[$index]", value) }
    }
}

/**
 * 실행 BOM envelope와 DB 영속 필드의 길이·문자 집합 경계를 검증합니다.
 *
 * transport 크기와 JSON 깊이는 [VisitPlanningEventIngress]가 raw byte에서 먼저
 * 확인합니다. 이 객체는 역직렬화된 DTO가 inbox 및 Plan revision의 고정 길이
 * column을 넘겨 SQL 예외로 빠지지 않도록 trusted 승격 전에 구조 경계를 확인합니다.
 */
internal object PackageExecutionEventBounds {
    private const val MAX_IDENTIFIER_LENGTH = 128
    private const val MAX_TREATMENT_NAME_LENGTH = 256
    private const val MAX_COLLECTION_SIZE = 1_000
    private val identifier = Regex("[A-Za-z0-9][A-Za-z0-9._:-]*")
    private val sha256 = Regex("[0-9a-f]{64}")

    /** trusted handler 진입 시 방어적으로 envelope와 payload 경계를 다시 확인합니다. */
    fun validate(envelope: TrustedSchedulingEventEnvelope<PackageExecutionEvent>) {
        validateMetadata(
            eventId = envelope.eventId,
            eventType = envelope.eventType,
            producer = envelope.producer,
            issuer = envelope.issuer,
            audience = envelope.audience,
            keyId = envelope.keyId,
            algorithm = envelope.algorithm,
            correlationId = envelope.correlationId,
            payloadHash = envelope.payloadHash,
            schemaVersion = envelope.schemaVersion,
        )
        validatePayload(envelope.payload)
    }

    /** untrusted DTO를 trusted envelope로 승격하기 전에 metadata와 payload 경계를 확인합니다. */
    fun validate(envelope: UntrustedSchedulingEventEnvelope<PackageExecutionEvent>) {
        validateMetadata(
            eventId = envelope.eventId,
            eventType = envelope.eventType,
            producer = envelope.producer,
            issuer = envelope.issuer,
            audience = envelope.audience,
            keyId = envelope.keyId,
            algorithm = envelope.algorithm,
            correlationId = envelope.correlationId,
            payloadHash = envelope.payloadHash,
            schemaVersion = envelope.schemaVersion,
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
        require(payloadHash.matches(sha256)) { "payloadHash must be lowercase SHA-256" }
        require(schemaVersion > 0) { "schemaVersion must be positive" }
    }

    private fun validatePayload(payload: PackageExecutionEvent) {
        boundedIdentifier(payload.sourceAggregateId)
        boundedIdentifier(payload.sourcePurchaseAuthority)
        boundedIdentifier(payload.sourcePurchaseId)
        require(payload.sourceAggregateVersion > 0) { "sourceAggregateVersion must be positive" }
        require(payload.tenantGroupId > 0) { "tenantGroupId must be positive" }
        require(payload.clinicId > 0) { "clinicId must be positive" }

        val snapshot = payload.executionSnapshot
        boundedIdentifier(snapshot.packageProductId)
        boundedIdentifier(snapshot.packageProductVersionId)
        require(snapshot.snapshotHash.matches(sha256)) { "snapshotHash must be lowercase SHA-256" }
        require(snapshot.selectedComponentVersions.size <= MAX_COLLECTION_SIZE) {
            "too many selected component versions"
        }
        snapshot.selectedComponentVersions.forEach { component ->
            boundedIdentifier(component.componentProductId)
            boundedIdentifier(component.componentProductVersionId)
            component.selectionGroupId?.let(::boundedIdentifier)
        }
        require(snapshot.componentSelections.size <= MAX_COLLECTION_SIZE) {
            "too many component selections"
        }
        snapshot.componentSelections.forEach { selection ->
            boundedIdentifier(selection.selectionGroupId)
        }
        require(snapshot.expandedTreatmentItems.size in 1..MAX_COLLECTION_SIZE) {
            "expandedTreatmentItems size is invalid"
        }
        snapshot.expandedTreatmentItems.forEach { treatment ->
            boundedIdentifier(treatment.treatmentKey)
            boundedIdentifier(treatment.componentProductId)
            boundedIdentifier(treatment.componentProductVersionId)
            boundedIdentifier(treatment.sourceBomItemId)
            require(treatment.representativeTreatmentName.length in 1..MAX_TREATMENT_NAME_LENGTH) {
                "representativeTreatmentName is invalid"
            }
            require(treatment.detailedTreatmentCodes.size <= MAX_COLLECTION_SIZE) {
                "too many detailed treatment codes"
            }
            treatment.detailedTreatmentCodes.forEach(::boundedIdentifier)
            require(treatment.practitionerQualifications.size <= MAX_COLLECTION_SIZE) {
                "too many practitioner qualifications"
            }
            treatment.practitionerQualifications.forEach(::boundedIdentifier)
            require(treatment.equipmentTypes.size <= MAX_COLLECTION_SIZE) {
                "too many equipment types"
            }
            treatment.equipmentTypes.forEach(::boundedIdentifier)
            require(treatment.spaceCapabilities.size <= MAX_COLLECTION_SIZE) {
                "too many space capabilities"
            }
            treatment.spaceCapabilities.forEach(::boundedIdentifier)
        }
        require(snapshot.executionDependencies.size <= MAX_COLLECTION_SIZE) {
            "too many execution dependencies"
        }
        snapshot.executionDependencies.forEach { dependency ->
            boundedIdentifier(dependency.predecessorTreatmentKey)
            boundedIdentifier(dependency.successorTreatmentKey)
        }
        require(snapshot.visitGroupingConstraints.size <= MAX_COLLECTION_SIZE) {
            "too many visit grouping constraints"
        }
        snapshot.visitGroupingConstraints.forEach { grouping ->
            boundedIdentifier(grouping.firstTreatmentKey)
            boundedIdentifier(grouping.secondTreatmentKey)
        }
    }

    private fun boundedIdentifier(value: String) {
        require(value.length in 1..MAX_IDENTIFIER_LENGTH && identifier.matches(value)) {
            "identifier is invalid"
        }
    }
}
