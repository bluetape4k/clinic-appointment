package io.bluetape4k.clinic.appointment.repository

import io.bluetape4k.clinic.appointment.model.dto.CancellationHistoryBoundary
import io.bluetape4k.clinic.appointment.model.dto.PatientCancellationHistoryPage
import io.bluetape4k.clinic.appointment.model.dto.PatientCancellationHistoryRecord
import io.bluetape4k.clinic.appointment.model.tables.AppointmentCancellationDetails
import io.bluetape4k.clinic.appointment.model.tables.AppointmentCommitments
import io.bluetape4k.clinic.appointment.model.tables.AppointmentItems
import io.bluetape4k.clinic.appointment.model.tables.AppointmentPlanRevisions
import io.bluetape4k.clinic.appointment.model.tables.AppointmentPlans
import io.bluetape4k.clinic.appointment.model.tables.AppointmentProposals
import io.bluetape4k.clinic.appointment.model.tables.Appointments
import io.bluetape4k.clinic.appointment.model.tables.PlanRevisionTreatments
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Join
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll

/** 같은 patient scope에서 keyset cursor의 anchor가 사라졌음을 나타냅니다. */
class CancellationHistoryAnchorMissingException : IllegalStateException("cancellation history cursor anchor is missing")

/** 호출자가 소유한 read-only transaction에서 환자 취소 이력을 keyset 조회합니다. */
class AppointmentCancellationHistoryRepository {
    companion object {
        const val MAX_PAGE_SIZE = 50
        private const val METADATA_ROW_LIMIT = 400
        private const val MAX_METADATA_ROWS_PER_DETAIL = 8
        private val FINGERPRINT = Regex("[0-9a-f]{64}")
    }

    /**
     * tenant와 patient fingerprint를 index 선두 조건으로 사용해 최신순 page를 읽습니다.
     * legacy null fingerprint row는 이 경계에서 의도적으로 제외됩니다.
     */
    fun findPage(
        tenantGroupId: Long,
        patientScopeFingerprint: String,
        boundary: CancellationHistoryBoundary?,
        limit: Int,
    ): PatientCancellationHistoryPage {
        require(tenantGroupId > 0) { "tenantGroupId must be positive" }
        require(FINGERPRINT.matches(patientScopeFingerprint)) {
            "patientScopeFingerprint must be lowercase SHA-256"
        }
        require(limit in 1..MAX_PAGE_SIZE) { "limit must be between 1 and $MAX_PAGE_SIZE" }

        boundary?.let { cursorBoundary ->
            val anchorExists = AppointmentCancellationDetails
                .select(AppointmentCancellationDetails.id)
                .where {
                    (AppointmentCancellationDetails.tenantGroupId eq tenantGroupId) and
                        (AppointmentCancellationDetails.patientScopeFingerprint eq patientScopeFingerprint) and
                        (AppointmentCancellationDetails.occurredAt eq cursorBoundary.occurredAt) and
                        (AppointmentCancellationDetails.id eq cursorBoundary.detailId)
                }
                .limit(1)
                .any()
            if (!anchorExists) throw CancellationHistoryAnchorMissingException()
        }

        val pageJoin =
            Join(
                AppointmentCancellationDetails,
                AppointmentProposals,
                JoinType.INNER,
                AppointmentCancellationDetails.proposalId,
                AppointmentProposals.id,
            )
                .join(
                    AppointmentCommitments,
                    JoinType.INNER,
                    AppointmentCancellationDetails.commitmentId,
                    AppointmentCommitments.id,
                )
                .join(
                    Appointments,
                    JoinType.INNER,
                    AppointmentCancellationDetails.appointmentId,
                    Appointments.id,
                )
        val pageRows =
            pageJoin
                .select(
                    AppointmentCancellationDetails.id,
                    AppointmentCancellationDetails.tenantGroupId,
                    AppointmentCancellationDetails.clinicId,
                    AppointmentCancellationDetails.appointmentId,
                    AppointmentCancellationDetails.commitmentId,
                    AppointmentCancellationDetails.proposalId,
                    AppointmentCancellationDetails.patientScopeFingerprint,
                    AppointmentCancellationDetails.reasonCode,
                    AppointmentCancellationDetails.reasonDetail,
                    AppointmentCancellationDetails.fromCommitmentStatus,
                    AppointmentCancellationDetails.actorRole,
                    AppointmentCancellationDetails.occurredAt,
                    AppointmentCommitments.status,
                    AppointmentProposals.proposedStartAt,
                    AppointmentProposals.proposedEndAt,
                ).where {
                    (AppointmentCancellationDetails.tenantGroupId eq tenantGroupId) and
                        (AppointmentCancellationDetails.patientScopeFingerprint eq patientScopeFingerprint) and
                        (boundary?.let {
                            (AppointmentCancellationDetails.occurredAt less it.occurredAt) or
                                ((AppointmentCancellationDetails.occurredAt eq it.occurredAt) and
                                    (AppointmentCancellationDetails.id less it.detailId))
                        } ?: org.jetbrains.exposed.v1.core.Op.TRUE)
                }
                .orderBy(
                    AppointmentCancellationDetails.occurredAt to SortOrder.DESC,
                    AppointmentCancellationDetails.id to SortOrder.DESC,
                )
                .limit(limit + 1)
                .toList()

        val hasNext = pageRows.size > limit
        val rows = if (hasNext) pageRows.take(limit) else pageRows
        val metadata = loadMetadata(rows.map { it[AppointmentCancellationDetails.proposalId] })
        return PatientCancellationHistoryPage(
            entries = rows.map { row ->
                val scopeFingerprint = checkNotNull(row[AppointmentCancellationDetails.patientScopeFingerprint]) {
                    "patientScopeFingerprint must be present for a history row"
                }
                val proposalId = row[AppointmentCancellationDetails.proposalId].value
                val detailMetadata = metadata.values[proposalId]
                PatientCancellationHistoryRecord(
                    detailId = row[AppointmentCancellationDetails.id].value,
                    tenantGroupId = row[AppointmentCancellationDetails.tenantGroupId].value,
                    clinicId = row[AppointmentCancellationDetails.clinicId].value,
                    appointmentId = row[AppointmentCancellationDetails.appointmentId].value,
                    commitmentId = row[AppointmentCancellationDetails.commitmentId].value,
                    proposalId = row[AppointmentCancellationDetails.proposalId].value,
                    patientScopeFingerprint = scopeFingerprint,
                    reasonCode = row[AppointmentCancellationDetails.reasonCode],
                    reasonDetail = row[AppointmentCancellationDetails.reasonDetail],
                    fromCommitmentStatus = row[AppointmentCancellationDetails.fromCommitmentStatus],
                    toCommitmentStatus = row[AppointmentCommitments.status],
                    actorRole = row[AppointmentCancellationDetails.actorRole],
                    occurredAt = row[AppointmentCancellationDetails.occurredAt],
                    visitStartAt = row[AppointmentProposals.proposedStartAt],
                    visitEndAt = row[AppointmentProposals.proposedEndAt],
                    productName = detailMetadata?.productNames?.singleOrNull(),
                    sessionNumber = detailMetadata?.itemSequences?.singleOrNull()?.takeIf { it > 0 },
                    totalSessions = detailMetadata?.itemSequences?.maxOrNull()?.takeIf { it > 0 },
                )
            },
            hasNext = hasNext,
            metadataAmbiguousCount = metadata.ambiguousProposalIds.size,
        )
    }

    /** page에 포함된 proposal만 대상으로 상품·회차 snapshot을 단일 batch로 읽습니다. */
    private fun loadMetadata(proposalIds: List<EntityID<Long>>): MetadataBatch {
        if (proposalIds.isEmpty()) return MetadataBatch(emptyMap(), emptySet())

        val metadataJoin =
            Join(
                AppointmentItems,
                PlanRevisionTreatments,
                JoinType.INNER,
                AppointmentItems.planRevisionId,
                PlanRevisionTreatments.planRevisionId,
                additionalConstraint = {
                    AppointmentItems.treatmentKey eq PlanRevisionTreatments.treatmentKey
                },
            )
                .join(
                    AppointmentPlanRevisions,
                    JoinType.INNER,
                    AppointmentItems.planRevisionId,
                    AppointmentPlanRevisions.id,
                )
                .join(
                    AppointmentPlans,
                    JoinType.INNER,
                    AppointmentPlanRevisions.planId,
                    AppointmentPlans.id,
                )

        val metadataRows =
            metadataJoin
                .select(
                    AppointmentItems.proposalId,
                    AppointmentItems.treatmentKey,
                    PlanRevisionTreatments.treatmentKey,
                    PlanRevisionTreatments.sequence,
                    AppointmentPlans.productName,
                )
                .where { AppointmentItems.proposalId inList proposalIds }
                .limit(METADATA_ROW_LIMIT + 1)
                .toList()

        val grouped = metadataRows.groupBy { it[AppointmentItems.proposalId].value }
        val perDetailOverflow = grouped
            .filterValues { it.size > MAX_METADATA_ROWS_PER_DETAIL }
            .keys
        // A page has at most 50 details and each detail is bounded to 8 rows. If
        // the 401st row exists, the bounded query may have cut through a detail;
        // fail closed for every proposal not proven complete instead of returning
        // a guessed product/session value.
        val ambiguousProposalIds = if (metadataRows.size > METADATA_ROW_LIMIT) {
            proposalIds.map { it.value }.toSet()
        } else {
            perDetailOverflow
        }
        val values = grouped
            .filterKeys { it !in ambiguousProposalIds }
            .mapValues { (_, values) ->
                CancellationMetadata(
                    productNames = values.map { it[AppointmentPlans.productName] }.toSet(),
                    itemSequences = values
                        .filter { it[AppointmentItems.treatmentKey] == it[PlanRevisionTreatments.treatmentKey] }
                        .map { it[PlanRevisionTreatments.sequence] }
                        .toSet(),
                )
            }
        return MetadataBatch(values, ambiguousProposalIds)
    }

    private data class MetadataBatch(
        val values: Map<Long, CancellationMetadata>,
        val ambiguousProposalIds: Set<Long>,
    )

    private data class CancellationMetadata(
        val productNames: Set<String>,
        val itemSequences: Set<Int>,
    )

}
