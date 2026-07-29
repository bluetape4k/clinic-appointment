package io.bluetape4k.clinic.appointment.api.commitment

import io.bluetape4k.clinic.appointment.event.integration.QuarantineAuditAction
import io.bluetape4k.clinic.appointment.event.integration.QuarantineStatus
import io.bluetape4k.clinic.appointment.event.integration.SchedulingInboxEvents
import io.bluetape4k.clinic.appointment.event.integration.SchedulingInboxStatus
import io.bluetape4k.clinic.appointment.event.integration.SchedulingOutboxEvents
import io.bluetape4k.clinic.appointment.event.integration.SchedulingOutboxStatus
import io.bluetape4k.clinic.appointment.event.integration.SchedulingQuarantineAuditEvents
import io.bluetape4k.clinic.appointment.event.integration.SchedulingQuarantineEvents
import io.bluetape4k.clinic.appointment.model.tables.AppointmentCommandIdempotencies
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.TenantGroups
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.io.Serializable
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * commitment 관련 운영 record를 tenant·clinic 단위의 작은 batch로 정리합니다.
 *
 * - 처리 완료 inbox와 command idempotency는 30일 후 삭제합니다.
 * - 전달 완료 outbox는 7일 후 삭제하며 PENDING/FAILED row는 보존합니다.
 * - 해결된 quarantine은 90일 후 암호화 payload만 만료하고 metadata와 append-only
 *   audit을 보존합니다.
 * - `QUARANTINED` inbox, 열린 quarantine, legal hold는 기간과 관계없이 보존합니다.
 *
 * 각 호출은 하나의 database transaction을 소유합니다. 경계 시각과 같은 row는 다음
 * 실행까지 보존하고, cutoff보다 엄격히 오래된 row만 처리합니다.
 *
 * @property database cleanup transaction을 실행할 Exposed database입니다.
 * @property clock 모든 retention cutoff와 audit 시각의 단일 UTC 기준입니다.
 * @property batchSizePerTenant 한 호출에서 종류별로 처리할 최대 row 수입니다.
 * @property mutationObserver 후보 조회와 조건부 update 사이 경쟁을 재현하는 테스트·진단 hook입니다.
 */
class VisitCommitmentRetentionService(
    private val database: Database,
    private val clock: Clock = Clock.systemUTC(),
    private val batchSizePerTenant: Int = 500,
    private val mutationObserver: VisitCommitmentRetentionMutationObserver =
        VisitCommitmentRetentionMutationObserver.NOOP,
) {

    init {
        require(batchSizePerTenant in 1..5_000) { "batchSizePerTenant must be between 1 and 5000" }
    }

    /**
     * 한 tenant·clinic의 보존 기한을 현재 [Clock] 기준으로 한 batch만 적용합니다.
     *
     * 양수 scope만 허용하며, 삭제·payload 만료·감사 기록은 하나의 transaction에서
     * 원자적으로 처리합니다. 반환 ID는 운영 로그용이 아니라 테스트와 호출자가 실제
     * 변경 건수를 계산하기 위한 값입니다.
     */
    fun cleanupTenant(
        tenantGroupId: Long,
        clinicId: Long,
    ): VisitCommitmentRetentionResult {
        require(tenantGroupId > 0L && clinicId > 0L) { "tenantGroupId and clinicId must be positive" }
        val now = clock.instant()
        return transaction(database) {
            val idempotencyIds = selectIdempotencies(tenantGroupId, clinicId, now.minus(INBOX_RETENTION))
            val inboxIds = selectInbox(tenantGroupId, clinicId, now.minus(INBOX_RETENTION))
            val outboxIds = selectOutbox(tenantGroupId, clinicId, now.minus(OUTBOX_RETENTION))
            val quarantineIds = selectQuarantine(tenantGroupId, clinicId, now.minus(QUARANTINE_RETENTION))

            if (idempotencyIds.isNotEmpty()) {
                AppointmentCommandIdempotencies.deleteWhere {
                    AppointmentCommandIdempotencies.id inList idempotencyIds
                }
            }
            if (inboxIds.isNotEmpty()) {
                SchedulingInboxEvents.deleteWhere { SchedulingInboxEvents.id inList inboxIds }
            }
            if (outboxIds.isNotEmpty()) {
                SchedulingOutboxEvents.deleteWhere { SchedulingOutboxEvents.id inList outboxIds }
            }
            val expiredQuarantineIds = buildList {
                quarantineIds.forEach { quarantineId ->
                    mutationObserver.beforeExpireQuarantine(quarantineId)
                    val updated = SchedulingQuarantineEvents.update({
                        (SchedulingQuarantineEvents.id eq quarantineId) and
                            (SchedulingQuarantineEvents.legalHold eq false) and
                            (SchedulingQuarantineEvents.status inList RESOLVED_QUARANTINE_STATUSES) and
                            SchedulingQuarantineEvents.resolvedAt.isNotNull() and
                            (SchedulingQuarantineEvents.resolvedAt less now.minus(QUARANTINE_RETENTION)) and
                            SchedulingQuarantineEvents.encryptedOriginalEnvelope.isNotNull()
                    }) {
                        it[encryptedOriginalEnvelope] = null
                        it[status] = QuarantineStatus.PAYLOAD_EXPIRED
                    }
                    if (updated == 1) {
                        SchedulingQuarantineAuditEvents.insert {
                            it[SchedulingQuarantineAuditEvents.quarantineId] = quarantineId
                            it[action] = QuarantineAuditAction.PAYLOAD_EXPIRED
                            it[actor] = RETENTION_ACTOR
                            it[reason] = RETENTION_REASON
                            it[beforeStatus] = null
                            it[afterStatus] = QuarantineStatus.PAYLOAD_EXPIRED
                            it[createdAt] = now
                        }
                        add(quarantineId)
                    }
                }
            }
            VisitCommitmentRetentionResult(idempotencyIds, inboxIds, outboxIds, expiredQuarantineIds)
        }
    }

    private fun selectIdempotencies(tenantId: Long, clinicId: Long, cutoff: Instant): List<Long> =
        AppointmentCommandIdempotencies.selectAll()
            .where {
                (AppointmentCommandIdempotencies.tenantGroupId eq EntityID(tenantId, TenantGroups)) and
                    (AppointmentCommandIdempotencies.clinicId eq EntityID(clinicId, Clinics)) and
                    (AppointmentCommandIdempotencies.createdAt less cutoff)
            }
            .orderBy(AppointmentCommandIdempotencies.createdAt, SortOrder.ASC)
            .limit(batchSizePerTenant)
            .map { it[AppointmentCommandIdempotencies.id].value }

    private fun selectInbox(tenantId: Long, clinicId: Long, cutoff: Instant): List<Long> =
        SchedulingInboxEvents.selectAll()
            .where {
                (SchedulingInboxEvents.tenantGroupId eq EntityID(tenantId, TenantGroups)) and
                    (SchedulingInboxEvents.clinicId eq EntityID(clinicId, Clinics)) and
                    (SchedulingInboxEvents.status eq SchedulingInboxStatus.PROCESSED) and
                    (SchedulingInboxEvents.receivedAt less cutoff)
            }
            .orderBy(SchedulingInboxEvents.receivedAt, SortOrder.ASC)
            .limit(batchSizePerTenant)
            .map { it[SchedulingInboxEvents.id].value }

    private fun selectOutbox(tenantId: Long, clinicId: Long, cutoff: Instant): List<Long> =
        SchedulingOutboxEvents.selectAll()
            .where {
                (SchedulingOutboxEvents.tenantGroupId eq EntityID(tenantId, TenantGroups)) and
                    (SchedulingOutboxEvents.clinicId eq EntityID(clinicId, Clinics)) and
                    (SchedulingOutboxEvents.status eq SchedulingOutboxStatus.PUBLISHED) and
                    (SchedulingOutboxEvents.publishedAt less cutoff)
            }
            .orderBy(SchedulingOutboxEvents.publishedAt, SortOrder.ASC)
            .limit(batchSizePerTenant)
            .map { it[SchedulingOutboxEvents.id].value }

    private fun selectQuarantine(tenantId: Long, clinicId: Long, cutoff: Instant): List<Long> =
        SchedulingQuarantineEvents.selectAll()
            .where {
                (SchedulingQuarantineEvents.tenantGroupId eq EntityID(tenantId, TenantGroups)) and
                    (SchedulingQuarantineEvents.clinicId eq EntityID(clinicId, Clinics)) and
                    (SchedulingQuarantineEvents.legalHold eq false) and
                    (SchedulingQuarantineEvents.status inList RESOLVED_QUARANTINE_STATUSES) and
                    SchedulingQuarantineEvents.encryptedOriginalEnvelope.isNotNull() and
                    SchedulingQuarantineEvents.resolvedAt.isNotNull() and
                    (SchedulingQuarantineEvents.resolvedAt less cutoff)
            }
            .orderBy(
                SchedulingQuarantineEvents.resolvedAt to SortOrder.ASC,
                SchedulingQuarantineEvents.id to SortOrder.ASC,
            )
            .limit(batchSizePerTenant)
            .map { it[SchedulingQuarantineEvents.id].value }

    private companion object {
        val INBOX_RETENTION: Duration = Duration.ofDays(30)
        val OUTBOX_RETENTION: Duration = Duration.ofDays(7)
        val QUARANTINE_RETENTION: Duration = Duration.ofDays(90)
        val RESOLVED_QUARANTINE_STATUSES =
            listOf(QuarantineStatus.RELEASE_APPROVED, QuarantineStatus.RELEASE_DENIED)
        const val RETENTION_ACTOR = "reservation-retention-job"
        const val RETENTION_REASON = "approved-retention-policy"
    }
}

/**
 * retention 후보 선택과 조건부 update 사이의 경쟁을 재현하는 진단 hook입니다.
 *
 * production 기본값은 no-op입니다. 테스트는 이 지점에서 legal hold나 상태를 변경해
 * compare-and-set이 audit 없이 안전하게 0건 처리하는지 검증할 수 있습니다.
 */
fun interface VisitCommitmentRetentionMutationObserver {
    /** quarantine payload 조건부 만료 직전에 호출되는 테스트 진단 지점입니다. */
    fun beforeExpireQuarantine(quarantineId: Long)

    companion object {
        val NOOP = VisitCommitmentRetentionMutationObserver { }
    }
}

/**
 * 한 tenant·clinic cleanup 실행에서 실제로 변경된 row ID입니다.
 *
 * quarantine ID는 metadata row 삭제가 아니라 암호화 payload 만료를 의미합니다.
 *
 * @property deletedIdempotencyIds 보존 기한을 지난 command idempotency row ID입니다.
 * @property deletedInboxIds 처리 완료 후 보존 기한을 지난 inbox row ID입니다.
 * @property deletedOutboxIds 전달 완료 후 보존 기한을 지난 outbox row ID입니다.
 * @property expiredQuarantinePayloadIds metadata는 유지하고 암호화 payload만 만료한 quarantine ID입니다.
 */
data class VisitCommitmentRetentionResult(
    val deletedIdempotencyIds: List<Long>,
    val deletedInboxIds: List<Long>,
    val deletedOutboxIds: List<Long>,
    val expiredQuarantinePayloadIds: List<Long>,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
