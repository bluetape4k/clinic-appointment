package io.bluetape4k.clinic.appointment.repository

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.clinic.appointment.model.commitment.AppointmentCommitment
import io.bluetape4k.clinic.appointment.model.commitment.AppointmentCommitmentStatus
import io.bluetape4k.clinic.appointment.model.commitment.AppointmentItemDraft
import io.bluetape4k.clinic.appointment.model.commitment.AppointmentOrigin
import io.bluetape4k.clinic.appointment.model.commitment.AppointmentProposalDraft
import io.bluetape4k.clinic.appointment.model.dto.AppointmentItemAppendScope
import io.bluetape4k.clinic.appointment.model.dto.AppointmentPlanRevisionAggregateRecord
import io.bluetape4k.clinic.appointment.model.dto.PlanRevisionTreatmentRecord
import io.bluetape4k.clinic.appointment.model.plan.AppointmentPlanRevision
import io.bluetape4k.clinic.appointment.model.plan.AppointmentPlanStatus
import io.bluetape4k.clinic.appointment.model.plan.PlanTreatmentStatus
import io.bluetape4k.clinic.appointment.model.tables.AppointmentItems
import io.bluetape4k.clinic.appointment.model.tables.AppointmentPlans
import io.bluetape4k.clinic.appointment.model.tables.PlanRevisionTreatments
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.StatementContext
import org.jetbrains.exposed.v1.core.statements.StatementInterceptor
import org.jetbrains.exposed.v1.core.statements.api.PreparedStatementApi
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

class AppointmentItemRepositoryTest {
    private val itemRepository = AppointmentItemRepository()
    private val commitmentRepository = AppointmentCommitmentRepository()
    private val revisionRepository = AppointmentPlanRevisionRepository()

    @Test
    fun `proposal에 여러 plan-linked item을 immutable snapshot으로 append한다`() {
        withCommitmentTables { seed ->
            val revision = revisionRepository.append(revisionAggregate(seed.planId))
            val proposalId = appendProposal(seed.appointmentId)

            val saved =
                itemRepository.appendValidated(
                    scope = scope(seed, proposalId),
                    items = revision.treatments.map { it.toDraft(revision.revision.id) },
                )

            saved.map { it.treatmentKey } shouldBeEqualTo listOf("care", "follow-up")
            saved.map { it.proposalId }.toSet() shouldBeEqualTo setOf(proposalId)
            saved.first().detailedTreatmentCodes shouldBeEqualTo listOf("CARE_A", "CARE_B")
            saved.first().preparationMinutes shouldBeEqualTo 10
            saved.first().treatmentMinutes shouldBeEqualTo 30
            saved.first().recoveryMinutes shouldBeEqualTo 20
        }
    }

    @Test
    fun `대형 패키지 item도 treatment scope를 한 번 조회하고 한 번에 저장한다`() {
        withCommitmentTables { seed ->
            val revision = revisionRepository.append(largeRevisionAggregate(seed.planId, treatmentCount = 100))
            val proposalId = appendProposal(seed.appointmentId)
            val counter = AppointmentItemStatementCounter()
            registerInterceptor(counter)

            val saved =
                itemRepository.appendValidated(
                    scope = scope(seed, proposalId),
                    items = revision.treatments.map { it.toDraft(revision.revision.id) },
                )

            saved.size shouldBeEqualTo 100
            counter.treatmentScopeSelectStatements.get() shouldBeEqualTo 1
            counter.itemInsertStatements.get() shouldBeEqualTo 1
        }
    }

    @Test
    fun `다른 환자 plan revision item은 같은 appointment proposal에 append할 수 없다`() {
        withCommitmentTables { seed ->
            val otherPlanId = appendPlanWithPatientFingerprint(seed.planId, "x".repeat(64))
            val otherRevision = revisionRepository.append(revisionAggregate(otherPlanId))
            val proposalId = appendProposal(seed.appointmentId)

            assertFailsWith<IllegalArgumentException> {
                itemRepository.appendValidated(
                    scope = scope(seed, proposalId),
                    items = listOf(otherRevision.treatments.first().toDraft(otherRevision.revision.id)),
                )
            }
        }
    }

    @Test
    fun `item draft가 revision treatment snapshot과 다르면 append를 거부한다`() {
        withCommitmentTables { seed ->
            val revision = revisionRepository.append(revisionAggregate(seed.planId))
            val proposalId = appendProposal(seed.appointmentId)
            val source = revision.treatments.first()
            val tampered =
                AppointmentItemDraft(
                    planRevisionId = revision.revision.id,
                    treatmentKey = source.treatmentKey,
                    representativeTreatmentName = source.representativeTreatmentName,
                    detailedTreatmentCodes = source.detailedTreatmentCodes,
                    preparationMinutes = source.preparationMinutes,
                    treatmentMinutes = 31,
                    recoveryMinutes = source.recoveryMinutes,
                )

            assertFailsWith<IllegalArgumentException> {
                itemRepository.appendValidated(
                    scope = scope(seed, proposalId),
                    items = listOf(tampered),
                )
            }
        }
    }

    private fun appendProposal(appointmentId: Long): Long {
        val commitment =
            commitmentRepository.create(
                AppointmentCommitment(
                    appointmentId = appointmentId,
                    status = AppointmentCommitmentStatus.PROPOSED,
                    origin = AppointmentOrigin.CLINIC,
                    confirmedProposalId = null,
                    effectivePolicySnapshotId = 7L,
                    version = 1L,
                ),
            )
        val draft =
            AppointmentProposalDraft(
                appointmentId = appointmentId,
                revision = 1L,
                startsAt = Instant.parse("2026-08-10T01:00:00Z"),
                endsAt = Instant.parse("2026-08-10T02:00:00Z"),
                items = emptyList(),
                allocations = emptyList(),
                policySnapshotId = 7L,
                supersedesProposalId = null,
            )
        return commitmentRepository
            .appendProposal(
                commitmentId = commitment.id,
                draft = draft,
                proposalHash = "p".repeat(64),
                expiresAt = draft.startsAt.minusSeconds(60),
                representativeTreatmentName = "복합 진료",
                createdByActor = "clinic",
            ).id
    }

    private fun scope(
        seed: CommitmentSeed,
        proposalId: Long,
    ) = AppointmentItemAppendScope(
        appointmentId = seed.appointmentId,
        proposalId = proposalId,
        tenantGroupId = 1L,
        clinicId = seed.clinicId,
        patientReferenceFingerprint = "f".repeat(64),
    )

    private fun appendPlanWithPatientFingerprint(
        sourcePlanId: Long,
        patientReferenceFingerprint: String,
    ): Long {
        val source =
            AppointmentPlans
                .selectAll()
                .where { AppointmentPlans.id eq sourcePlanId }
                .single()
        return AppointmentPlans
            .insertAndGetId {
                it[tenantGroupId] = source[AppointmentPlans.tenantGroupId]
                it[clinicId] = source[AppointmentPlans.clinicId]
                it[catalogProjectionId] = source[AppointmentPlans.catalogProjectionId]
                it[sourcePurchaseAuthority] = "purchase-service"
                it[sourcePurchaseId] = "purchase-other"
                it[patientReferenceCiphertext] = "other-ciphertext"
                it[patientReferenceKeyId] = "key-1"
                it[AppointmentPlans.patientReferenceFingerprint] = patientReferenceFingerprint
                it[catalogSourceAuthority] = source[AppointmentPlans.catalogSourceAuthority]
                it[productId] = source[AppointmentPlans.productId]
                it[catalogVersion] = source[AppointmentPlans.catalogVersion]
                it[catalogPayloadHash] = source[AppointmentPlans.catalogPayloadHash]
                it[productName] = source[AppointmentPlans.productName]
                it[bookingPreferenceType] = source[AppointmentPlans.bookingPreferenceType]
                it[bookingPreferencePayload] = source[AppointmentPlans.bookingPreferencePayload]
                it[status] = AppointmentPlanStatus.ACTIVE
            }.value
    }

    private fun revisionAggregate(planId: Long) =
        AppointmentPlanRevisionAggregateRecord(
            revision =
                AppointmentPlanRevision(
                    planId = planId,
                    revision = 1L,
                    productVersionId = "v1",
                    snapshotHash = "r".repeat(64),
                    active = true,
                ),
            treatments =
                listOf(
                    PlanRevisionTreatmentRecord(
                        treatmentKey = "care",
                        componentProductId = "component",
                        componentProductVersionId = "v1",
                        productVersionId = "v1",
                        status = PlanTreatmentStatus.PENDING,
                        sourceBomItemId = "bom-care",
                        sequence = 1,
                        representativeTreatmentName = "대표 진료",
                        detailedTreatmentCodes = listOf("CARE_A", "CARE_B"),
                        preparationMinutes = 10,
                        treatmentMinutes = 30,
                        recoveryMinutes = 20,
                        practitionerQualifications = listOf("DOCTOR"),
                        equipmentTypes = listOf("LASER"),
                        spaceCapabilities = listOf("LASER_ROOM"),
                    ),
                    PlanRevisionTreatmentRecord(
                        treatmentKey = "follow-up",
                        componentProductId = "component",
                        componentProductVersionId = "v1",
                        productVersionId = "v1",
                        status = PlanTreatmentStatus.PENDING,
                        sourceBomItemId = "bom-follow-up",
                        sequence = 2,
                        representativeTreatmentName = "후속 진료",
                        detailedTreatmentCodes = listOf("FOLLOW_UP"),
                        preparationMinutes = 0,
                        treatmentMinutes = 15,
                        recoveryMinutes = 0,
                        practitionerQualifications = listOf("DOCTOR"),
                        equipmentTypes = emptyList(),
                        spaceCapabilities = listOf("CONSULT_ROOM"),
                    ),
                ),
            dependencies = emptyList(),
            groupingConstraints = emptyList(),
        )

    private fun largeRevisionAggregate(
        planId: Long,
        treatmentCount: Int,
    ) = revisionAggregate(planId).copy(
        treatments =
            (1..treatmentCount).map { sequence ->
                PlanRevisionTreatmentRecord(
                    treatmentKey = "care-$sequence",
                    componentProductId = "component-$sequence",
                    componentProductVersionId = "v1",
                    productVersionId = "v1",
                    status = PlanTreatmentStatus.PENDING,
                    sourceBomItemId = "bom-care-$sequence",
                    sequence = sequence,
                    representativeTreatmentName = "패키지 진료 $sequence",
                    detailedTreatmentCodes = listOf("CARE_$sequence"),
                    preparationMinutes = 5,
                    treatmentMinutes = 20,
                    recoveryMinutes = 10,
                    practitionerQualifications = listOf("DOCTOR"),
                    equipmentTypes = listOf("LASER"),
                    spaceCapabilities = listOf("LASER_ROOM"),
                )
            },
    )

    private fun PlanRevisionTreatmentRecord.toDraft(planRevisionId: Long) =
        AppointmentItemDraft(
            planRevisionId = planRevisionId,
            treatmentKey = treatmentKey,
            representativeTreatmentName = representativeTreatmentName,
            detailedTreatmentCodes = detailedTreatmentCodes,
            preparationMinutes = preparationMinutes,
            treatmentMinutes = treatmentMinutes,
            recoveryMinutes = recoveryMinutes,
        )

    /**
     * item 수가 증가해도 immutable treatment 검증과 insert SQL 실행 횟수가 증가하지
     * 않는다는 repository 성능 계약을 관찰합니다.
     */
    private class AppointmentItemStatementCounter : StatementInterceptor {
        val treatmentScopeSelectStatements = AtomicInteger(0)
        val itemInsertStatements = AtomicInteger(0)

        override fun afterExecution(
            transaction: Transaction,
            contexts: List<StatementContext>,
            executedStatement: PreparedStatementApi,
        ) {
            val sql = contexts.firstOrNull()?.sql(transaction)?.lowercase() ?: return
            when {
                sql.startsWith("select") && PlanRevisionTreatments.tableName in sql ->
                    treatmentScopeSelectStatements.incrementAndGet()
                sql.startsWith("insert") && AppointmentItems.tableName in sql ->
                    itemInsertStatements.incrementAndGet()
            }
        }
    }
}
