package io.bluetape4k.clinic.appointment.event.integration

import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.javatime.timestamp
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.io.Serializable
import java.time.Instant

/**
 * 신뢰성 검증에 실패했거나 이 서비스에 존재하지 않는 tenant/clinic 범위를
 * 주장한 envelope의 terminal rejection 저장소입니다.
 *
 * 주장된 tenant·clinic 식별자에는 의도적으로 foreign key를 두지 않습니다.
 * 신뢰할 수 없거나 알 수 없는 범위 때문에 poison event를 내구성 있게
 * 수집하지 못하는 일이 없어야 합니다.
 */
object UntrustedSchedulingEventRejections : LongIdTable("scheduling_untrusted_event_rejections") {
    val eventId = varchar("event_id", 128).uniqueIndex("uq_untrusted_rejection_event_id")
    val eventType = varchar("event_type", 128)
    val producer = varchar("producer", 128)
    val sourceAuthority = varchar("source_authority", 128)
    val sourceAggregateId = varchar("source_aggregate_id", 128)
    val sourceAggregateVersion = long("source_aggregate_version")
    val claimedTenantGroupId = long("claimed_tenant_group_id")
    val claimedClinicId = long("claimed_clinic_id")
    val schemaVersion = integer("schema_version")
    val correlationId = varchar("correlation_id", 128)
    val reasonCode = varchar("reason_code", 128)
    val envelopeHash = varchar("envelope_hash", 64)
    val detectedAt = timestamp("detected_at")

    init {
        index("idx_untrusted_rejection_detected", false, detectedAt, reasonCode)
        index(
            "idx_untrusted_rejection_claimed_scope",
            false,
            claimedTenantGroupId,
            claimedClinicId,
            detectedAt,
        )
    }
}

data class UntrustedEventRejection(
    val eventId: String,
    val eventType: String,
    val producer: String,
    val sourceAuthority: String,
    val sourceAggregateId: String,
    val sourceAggregateVersion: Long,
    val claimedTenantGroupId: Long,
    val claimedClinicId: Long,
    val schemaVersion: Int,
    val correlationId: String,
    val reasonCode: String,
    val envelopeHash: String,
    val detectedAt: Instant,
): Serializable {
    private companion object {
        const val serialVersionUID: Long = 1L
    }
}

class UntrustedSchedulingEventRejectionRepository {

    fun record(rejection: UntrustedEventRejection): Long {
        listOf(
            rejection.eventId,
            rejection.eventType,
            rejection.producer,
            rejection.sourceAuthority,
            rejection.sourceAggregateId,
            rejection.correlationId,
        ).forEach {
            require(it.matches(identifierPattern)) { "rejection identifier must be bounded and safe" }
        }
        require(rejection.sourceAggregateVersion > 0)
        require(rejection.claimedTenantGroupId > 0)
        require(rejection.claimedClinicId > 0)
        require(rejection.schemaVersion > 0)
        require(rejection.reasonCode.matches(reasonCodePattern)) { "reasonCode must be a bounded code" }
        require(rejection.envelopeHash.matches(sha256Pattern)) {
            "envelopeHash must be lowercase SHA-256"
        }
        return UntrustedSchedulingEventRejections.insertAndGetId {
            it[eventId] = rejection.eventId
            it[eventType] = rejection.eventType
            it[producer] = rejection.producer
            it[sourceAuthority] = rejection.sourceAuthority
            it[sourceAggregateId] = rejection.sourceAggregateId
            it[sourceAggregateVersion] = rejection.sourceAggregateVersion
            it[claimedTenantGroupId] = rejection.claimedTenantGroupId
            it[claimedClinicId] = rejection.claimedClinicId
            it[schemaVersion] = rejection.schemaVersion
            it[correlationId] = rejection.correlationId
            it[reasonCode] = rejection.reasonCode
            it[envelopeHash] = rejection.envelopeHash
            it[detectedAt] = rejection.detectedAt
        }.value
    }

    fun exists(eventId: String): Boolean =
        UntrustedSchedulingEventRejections
            .selectAll()
            .where { UntrustedSchedulingEventRejections.eventId eq eventId }
            .limit(1)
            .any()

    private companion object {
        val identifierPattern = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
        val reasonCodePattern = Regex("[A-Z][A-Z0-9_]{1,127}")
        val sha256Pattern = Regex("[0-9a-f]{64}")
    }
}
