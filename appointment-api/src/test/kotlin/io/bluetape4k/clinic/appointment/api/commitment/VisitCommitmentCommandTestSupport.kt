package io.bluetape4k.clinic.appointment.api.commitment

import io.bluetape4k.clinic.appointment.event.integration.SchedulingOutboxEvents
import io.bluetape4k.clinic.appointment.model.catalog.CatalogProjectionStatus
import io.bluetape4k.clinic.appointment.model.commitment.AppointmentItemDraft
import io.bluetape4k.clinic.appointment.model.commitment.ConsentDecisionType
import io.bluetape4k.clinic.appointment.model.commitment.ResourceAllocationDraft
import io.bluetape4k.clinic.appointment.model.commitment.ResourceAllocationMode
import io.bluetape4k.clinic.appointment.model.commitment.ResourceType
import io.bluetape4k.clinic.appointment.model.dto.AppointmentCommandResultRecord
import io.bluetape4k.clinic.appointment.model.dto.AppointmentCommitmentRecord
import io.bluetape4k.clinic.appointment.model.dto.AppointmentProposalRecord
import io.bluetape4k.clinic.appointment.model.dto.ResourceAllocationRecord
import io.bluetape4k.clinic.appointment.model.dto.ResourceAllocationRequest
import io.bluetape4k.clinic.appointment.model.identity.MemberId
import io.bluetape4k.clinic.appointment.model.plan.AppointmentPlanStatus
import io.bluetape4k.clinic.appointment.model.plan.PlanTreatmentStatus
import io.bluetape4k.clinic.appointment.model.policy.AdminBookingMode
import io.bluetape4k.clinic.appointment.model.tables.AppointmentAuditEvents
import io.bluetape4k.clinic.appointment.model.tables.AppointmentCancellationDetails
import io.bluetape4k.clinic.appointment.model.tables.AppointmentCommandIdempotencies
import io.bluetape4k.clinic.appointment.model.tables.AppointmentCommitments
import io.bluetape4k.clinic.appointment.model.tables.AppointmentItems
import io.bluetape4k.clinic.appointment.model.tables.AppointmentPlanRevisions
import io.bluetape4k.clinic.appointment.model.tables.AppointmentPlans
import io.bluetape4k.clinic.appointment.model.tables.AppointmentProposals
import io.bluetape4k.clinic.appointment.model.tables.Appointments
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.ConsentDecisions
import io.bluetape4k.clinic.appointment.model.tables.ConsultationTopics
import io.bluetape4k.clinic.appointment.model.tables.Doctors
import io.bluetape4k.clinic.appointment.model.tables.Equipments
import io.bluetape4k.clinic.appointment.model.tables.PlanRevisionTreatments
import io.bluetape4k.clinic.appointment.model.tables.ProductCatalogProjections
import io.bluetape4k.clinic.appointment.model.tables.ResourceAllocations
import io.bluetape4k.clinic.appointment.model.tables.ResourceCapacityBuckets
import io.bluetape4k.clinic.appointment.model.tables.TenantGroups
import io.bluetape4k.clinic.appointment.model.tables.TreatmentTypes
import io.bluetape4k.clinic.appointment.model.tables.WaitlistCapacityHolds
import io.bluetape4k.clinic.appointment.model.tables.WaitlistEntries
import io.bluetape4k.clinic.appointment.model.tables.WaitlistOfferEvents
import io.bluetape4k.clinic.appointment.model.tables.WaitlistOffers
import io.bluetape4k.clinic.appointment.repository.AppointmentCommitmentRepository
import io.bluetape4k.clinic.appointment.repository.ResourceAllocationRepository
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * commitment command 테스트가 공유하는 Exposed 스키마와 업무 fixture입니다.
 *
 * 각 테스트는 독립 H2 database를 사용하고 [BeforeEach]에서 production table 객체로
 * 스키마를 구성합니다. PostgreSQL 동시성 테스트는 같은 초기화 계약을 override하여
 * bluetape4k singleton container에 적용합니다.
 */
internal abstract class VisitCommitmentCommandTestSupport {
    protected lateinit var database: Database
    protected lateinit var clinic: ClinicFixture

    @BeforeEach
    fun setUpCommitmentCommandDatabase() {
        database = createDatabase()
        transaction(database) {
// PostgreSQL V22는 portable Exposed 메타데이터와 컬럼 순서가 다른 partial ready
// index를 의도적으로 사용한다. 앞선 Flyway integration test가 해당 테이블을 이미
// 설치했다면 SchemaUtils에 테이블 조정을 요청할 때 중복 CREATE INDEX가 발생한다.
// 마이그레이션된 테이블을 재사용하고 이 fixture는 나머지 테이블만 생성한다.
// 빈 데이터베이스에서는 여전히 일반 Exposed bootstrap 경로를 따른다.
            val tablesToCreate =
                if (tableReadable(SchedulingOutboxEvents.tableName)) {
                    TABLES.filterNot { it == SchedulingOutboxEvents }.toTypedArray()
                } else {
                    TABLES
                }
            SchemaUtils.createMissingTablesAndColumns(*tablesToCreate)
            TABLES.reversed().forEach(Table::deleteAll)
            clinic = seedClinic()
        }
    }

    private fun tableReadable(tableName: String): Boolean =
        listOf(tableName, tableName.uppercase()).distinct().any { candidate ->
            val jdbcConnection =
                TransactionManager.current().connection.connection as java.sql.Connection
            jdbcConnection.metaData
                .getTables(null, null, candidate, arrayOf("TABLE"))
                .use { rows -> rows.next() }
        }

    /**
     * singleton PostgreSQL·MySQL을 사용하는 하위 테스트가 다음 테스트 클래스에 Plan과
     * 상품 projection FK를 남기지 않도록 모든 commitment fixture를 역의존 순서로
     * 정리합니다.
     *
     * 개별 H2 테스트에서는 database 자체가 격리되어 있지만 동일 계약을 적용해
     * database 종류에 따라 테스트 수명주기가 달라지지 않게 합니다.
     *
     * fixture 정리와 하위 클래스의 연결 자원 해제를 하나의 lifecycle callback에서
     * 순서대로 실행합니다. JUnit은 하위 클래스의 [AfterEach]를 상위 클래스보다 먼저
     * 호출하므로 각 클래스가 독립 callback을 가지면 connection pool이 먼저 닫힐 수
     * 있습니다. 따라서 반드시 database 정리를 마친 뒤 [afterDatabaseCleanup]으로
     * 하위 클래스 자원을 해제합니다.
     */
    @AfterEach
    fun tearDownCommitmentCommandDatabase() {
        try {
            transaction(database) {
                TABLES.reversed().forEach(Table::deleteAll)
            }
        } finally {
            afterDatabaseCleanup()
        }
    }

    /**
     * 모든 commitment fixture를 정리한 뒤 하위 테스트가 소유한 database 연결 자원을
     * 해제합니다.
     *
     * HikariCP처럼 [createDatabase]에서 생성한 자원이 있다면 이 hook에서 닫아야
     * 합니다. 별도의 [AfterEach]를 선언하면 JUnit 상속 실행 순서 때문에 fixture
     * 정리보다 먼저 자원이 닫힐 수 있습니다.
     */
    protected open fun afterDatabaseCleanup() = Unit

    /** 테스트 종류에 맞는 database 연결을 반환합니다. */
    protected open fun createDatabase(): Database =
        Database.connect(
            url =
                "jdbc:h2:mem:task6_${System.nanoTime()};" +
                    "MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver",
        )

    /** 운영 서비스와 같은 retry 경계를 사용하되 테스트에서는 실제 대기를 생략합니다. */
    protected fun commandService(
        clock: Clock = CLOCK,
        retryDelay: (Long) -> Unit = {},
    ): AppointmentCommitmentCommandService =
        AppointmentCommitmentCommandService(
            database = database,
            clock = clock,
            retryDelay = retryDelay,
        )

    /** 병원 직접 확정으로 이후 변경 시나리오의 기존 예약을 준비합니다. */
    protected fun confirmDirect(
        service: AppointmentCommitmentCommandService,
        key: String,
        resourceId: String = "doctor-${clinic.doctorId}",
    ): AppointmentCommitmentCommandResult =
        service.confirmDirectAppointment(
            DirectAppointmentConfirmationCommand(
                context = commandContext(key),
                identity = appointmentIdentity(key),
                proposal = proposalInput(revision = 1L, resourceId = resourceId),
                expiresAt = ACTIVE_EXPIRY,
                representativeTreatmentName = "미백 치료",
                projectionTarget = confirmedProjectionTarget(resourceId),
                policyDecision = directConfirmationPolicyDecision(),
                consent = acceptedConsent(key),
            ),
        )

    /** 현재 확정 proposal과 active allocation을 caller transaction에서 다시 읽습니다. */
    protected fun currentConfirmation(appointmentId: Long): CurrentConfirmation =
        transaction(database) {
            val commitment =
                checkNotNull(
                    AppointmentCommitmentRepository().findByAppointmentId(appointmentId),
                ) {
                    "fixture commitment must exist"
                }
            val proposalId =
                checkNotNull(commitment.confirmedProposalId) {
                    "fixture commitment must be confirmed"
                }
            CurrentConfirmation(
                commitment = commitment,
                proposal =
                    checkNotNull(
                        AppointmentCommitmentRepository().findProposal(commitment.id, proposalId),
                    ) {
                        "fixture confirmed proposal must exist"
                    },
                allocations = ResourceAllocationRepository().findByProposal(proposalId),
            )
        }

    protected fun commandContext(
        key: String,
        commandHash: String = "c".repeat(64),
        tenantGroupId: Long = TENANT_ID,
        clinicId: Long = clinic.clinicId,
    ): CommitmentCommandContext =
        CommitmentCommandContext(
            tenantGroupId = tenantGroupId,
            clinicId = clinicId,
            actorScopeHash = "a".repeat(64),
            actorAuditRef = "actor:masked",
            idempotencyKeyHash =
                MessageDigest
                    .getInstance("SHA-256")
                    .digest(key.toByteArray(StandardCharsets.UTF_8))
                    .joinToString("") { "%02x".format(it) },
            commandHash = commandHash,
            correlationId = "correlation-$key",
        )

    protected fun proposalInput(
        revision: Long,
        resourceId: String,
        startsAt: Instant = PROPOSAL_START,
        supersedesProposalId: Long? = null,
        allocationMode: ResourceAllocationMode = ResourceAllocationMode.EXCLUSIVE,
        capacityUnits: Int = 1,
        maximumCapacity: Int = 1,
        practitionerResourceId: String = resourceId,
        includeConsultationItem: Boolean = false,
        consultationResourceId: String = "equipment-consultation",
    ): VisitProposalInput {
        val endsAt = startsAt.plusSeconds(3_600)
        val treatmentKey = "whitening"
        return VisitProposalInput(
            revision = revision,
            startsAt = startsAt,
            endsAt = endsAt,
            items =
                buildList {
                    add(
                        AppointmentItemDraft(
                            planRevisionId = clinic.planRevisionId,
                            treatmentKey = treatmentKey,
                            representativeTreatmentName = "미백 치료",
                            detailedTreatmentCodes = listOf("WHITENING"),
                            preparationMinutes = 10,
                            treatmentMinutes = 40,
                            recoveryMinutes = 10,
                        ),
                    )
                    if (includeConsultationItem) {
                        add(
                            AppointmentItemDraft(
                                planRevisionId = clinic.planRevisionId,
                                treatmentKey = "consultation",
                                representativeTreatmentName = "사후 상담",
                                detailedTreatmentCodes = listOf("CONSULTATION"),
                                preparationMinutes = 0,
                                treatmentMinutes = 20,
                                recoveryMinutes = 0,
                            ),
                        )
                    }
                },
            resourceRequests =
                buildList {
                    if (allocationMode == ResourceAllocationMode.CAPACITY_BUCKET) {
                        add(
                            ResourceAllocationRequest(
                                allocation =
                                    ResourceAllocationDraft(
                                        resourceType = ResourceType.PRACTITIONER,
                                        resourceId = practitionerResourceId,
                                        startsAt = startsAt,
                                        endsAt = endsAt,
                                        capacityUnits = 1,
                                        allocationMode = ResourceAllocationMode.EXCLUSIVE,
                                        appointmentItemKey = treatmentKey,
                                    ),
                                maximumCapacity = 1,
                            ),
                        )
                    }
                    add(
                        ResourceAllocationRequest(
                            allocation =
                                ResourceAllocationDraft(
                                    resourceType =
                                        if (allocationMode == ResourceAllocationMode.CAPACITY_BUCKET) {
                                            ResourceType.CAPACITY_BUCKET
                                        } else {
                                            ResourceType.PRACTITIONER
                                        },
                                    resourceId = resourceId,
                                    startsAt = startsAt,
                                    endsAt = endsAt,
                                    capacityUnits = capacityUnits,
                                    maximumCapacity = maximumCapacity,
                                    allocationMode = allocationMode,
                                    appointmentItemKey = treatmentKey,
                                ),
                            maximumCapacity = maximumCapacity,
                        ),
                    )
                    if (includeConsultationItem) {
                        add(
                            ResourceAllocationRequest(
                                allocation =
                                    ResourceAllocationDraft(
                                        resourceType = ResourceType.EQUIPMENT,
                                        resourceId = consultationResourceId,
                                        startsAt = startsAt,
                                        endsAt = endsAt,
                                        capacityUnits = 1,
                                        allocationMode = ResourceAllocationMode.EXCLUSIVE,
                                        appointmentItemKey = "consultation",
                                    ),
                                maximumCapacity = 1,
                            ),
                        )
                    }
                },
            policySnapshotId = 7L,
            supersedesProposalId = supersedesProposalId,
        )
    }

    protected fun appointmentIdentity(key: String) =
        AppointmentVisitIdentity(
            patientName = "Patient $key",
            patientPhone = "010-0000-0000",
            memberId = MemberId("patient-$key"),
            patientReferenceFingerprint = clinic.patientReferenceFingerprint,
        )

    protected fun confirmedProjectionTarget(practitionerResourceId: String = "doctor-${clinic.doctorId}") =
        ConfirmedAppointmentProjectionTarget(
            doctorId = clinic.doctorId,
            treatmentTypeId = clinic.treatmentTypeId,
            practitionerResourceId = practitionerResourceId,
        )

    /** 직접 확정을 허용하는 유효 정책 snapshot fixture입니다. */
    protected fun directConfirmationPolicyDecision(
        policySnapshotId: Long = 7L,
        adminBookingMode: AdminBookingMode =
            AdminBookingMode.DIRECT_CONFIRM_WITH_CONSENT_EVIDENCE,
        allowedEvidenceTypes: Set<String> = setOf("SIGNED_FORM"),
        maximumEvidenceAge: Duration = Duration.ofDays(30),
        termsHashRequired: Boolean = true,
        requiredTermsHash: String? = "d".repeat(64),
    ) = DirectConfirmationPolicyDecision(
        policySnapshotId = policySnapshotId,
        policySnapshotHash = "f".repeat(64),
        adminBookingMode = adminBookingMode,
        allowedEvidenceTypes = allowedEvidenceTypes,
        maximumEvidenceAge = maximumEvidenceAge,
        termsHashRequired = termsHashRequired,
        requiredTermsHash = requiredTermsHash,
    )

    protected fun acceptedConsent(key: String) =
        ProposalConsentEvidence(
            decision = ConsentDecisionType.ACCEPTED,
            evidenceType = "SIGNED_FORM",
            evidenceAuthority = "customer-app",
            evidenceId = "consent-$key",
            evidenceHash = "e".repeat(64),
            decidedAt = NOW,
            termsHash = "d".repeat(64),
            actorRef = "patient:masked",
        )

    protected fun declinedConsent(key: String) =
        ProposalConsentEvidence(
            decision = ConsentDecisionType.DECLINED,
            evidenceType = "SIGNED_FORM",
            evidenceAuthority = "customer-app",
            evidenceId = "consent-$key",
            evidenceHash = "e".repeat(64),
            decidedAt = NOW,
            termsHash = "d".repeat(64),
            actorRef = "patient:masked",
        )

    /** production 멱등 응답과 같은 canonical hash를 가진 durable 결과 fixture입니다. */
    protected fun commandResultRecord(
        result: AppointmentCommitmentCommandResult,
        responseHash: String = responseHash(result.commitment, result.proposal),
    ) = AppointmentCommandResultRecord(
        resultType = "APPOINTMENT_PROPOSAL",
        resultId = result.proposal.id,
        commitment = result.commitment,
        proposal = result.proposal,
        responseHash = responseHash,
    )

    private fun responseHash(
        commitment: AppointmentCommitmentRecord,
        proposal: AppointmentProposalRecord,
    ): String {
        val canonical =
            listOf(
                commitment.id,
                commitment.appointmentId,
                commitment.status,
                commitment.origin,
                commitment.confirmedProposalId,
                commitment.effectivePolicySnapshotId,
                commitment.version,
                commitment.bookingReliabilityStamp?.decisionId,
                commitment.bookingReliabilityStamp?.policyVersionId,
                commitment.bookingReliabilityStamp?.policyHash,
                commitment.bookingReliabilityStamp?.evaluationDigest,
                commitment.bookingReliabilityStamp?.expiresAt,
                proposal.id,
                proposal.commitmentId,
                proposal.revision,
                proposal.proposedStartAt,
                proposal.proposedEndAt,
                proposal.expiresAt,
                proposal.expiredAt,
                proposal.representativeTreatmentName,
                proposal.proposalHash,
                proposal.policySnapshotId,
                proposal.supersedesProposalId,
                proposal.createdByActor,
                proposal.bookingReliabilityStamp?.decisionId,
                proposal.bookingReliabilityStamp?.policyVersionId,
                proposal.bookingReliabilityStamp?.policyHash,
                proposal.bookingReliabilityStamp?.evaluationDigest,
                proposal.bookingReliabilityStamp?.expiresAt,
            ).joinToString(separator = "|") { it?.toString().orEmpty() }
        return MessageDigest
            .getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString(separator = "") { "%02x".format(it) }
    }

    /** 같은 tenant 아래 별도 병원을 만들어 cross-clinic mutation 방어를 검증합니다. */
    protected fun seedAdditionalClinic(suffix: String): ClinicFixture =
        transaction(database) {
            insertClinic(
                name = "Task 6 Clinic $suffix",
                doctorName = "Task 6 Doctor $suffix",
            )
        }

    private fun seedClinic(): ClinicFixture {
        TenantGroups.insert {
            it[id] = EntityID(TENANT_ID, TenantGroups)
            it[tenantCode] = "tenant-task6"
            it[displayName] = "Task 6 Tenant"
            it[active] = true
        }
        return insertClinic(name = "Task 6 Clinic", doctorName = "Task 6 Doctor")
    }

    /** 현재 transaction의 Task 6 tenant에 병원과 projection용 FK를 함께 생성합니다. */
    private fun insertClinic(
        name: String,
        doctorName: String,
    ): ClinicFixture {
        val clinicId =
            Clinics
                .insertAndGetId {
                    it[tenantGroupId] = EntityID(TENANT_ID, TenantGroups)
                    it[Clinics.name] = name
                    it[timezone] = "Asia/Seoul"
                }.value
        val doctorId =
            Doctors
                .insertAndGetId {
                    it[Doctors.clinicId] = clinicId
                    it[Doctors.name] = doctorName
                }.value
        val treatmentTypeId =
            TreatmentTypes
                .insertAndGetId {
                    it[TreatmentTypes.clinicId] = clinicId
                    it[TreatmentTypes.name] = "미백 치료"
                    it[TreatmentTypes.defaultDurationMinutes] = 60
                }.value
        val patientReferenceFingerprint = PATIENT_REFERENCE_FINGERPRINT
        val catalogId =
            ProductCatalogProjections
                .insertAndGetId {
                    it[tenantGroupId] = TENANT_ID
                    it[ProductCatalogProjections.clinicId] = clinicId
                    it[sourceAuthority] = "product-service"
                    it[productId] = "whitening-$clinicId"
                    it[catalogVersion] = 1L
                    it[productName] = "미백 패키지"
                    it[schemaVersion] = 1
                    it[sourceUpdatedAt] = NOW
                    it[status] = CatalogProjectionStatus.ACTIVE
                    it[payloadHash] = "a".repeat(64)
                }.value
        val planId =
            AppointmentPlans
                .insertAndGetId {
                    it[tenantGroupId] = TENANT_ID
                    it[AppointmentPlans.clinicId] = clinicId
                    it[catalogProjectionId] = catalogId
                    it[sourcePurchaseAuthority] = "purchase-service"
                    it[sourcePurchaseId] = "purchase-$clinicId"
                    it[patientReferenceCiphertext] = "encrypted-patient-reference"
                    it[patientReferenceKeyId] = "test-key"
                    it[AppointmentPlans.patientReferenceFingerprint] = patientReferenceFingerprint
                    it[catalogSourceAuthority] = "product-service"
                    it[productId] = "whitening-$clinicId"
                    it[catalogVersion] = 1L
                    it[catalogPayloadHash] = "a".repeat(64)
                    it[productName] = "미백 패키지"
                    it[bookingPreferenceType] = "NOT_PROVIDED"
                    it[bookingPreferencePayload] = "{}"
                    it[status] = AppointmentPlanStatus.ACTIVE
                }.value
        val planRevisionId =
            AppointmentPlanRevisions
                .insertAndGetId {
                    it[AppointmentPlanRevisions.planId] = planId
                    it[revision] = 1L
                    it[productVersionId] = "whitening-v1"
                    it[snapshotHash] = "b".repeat(64)
                    it[active] = true
                }.value
        PlanRevisionTreatments.insert {
            it[PlanRevisionTreatments.planRevisionId] = planRevisionId
            it[treatmentKey] = "whitening"
            it[componentProductId] = "whitening-component"
            it[componentProductVersionId] = "whitening-component-v1"
            it[productVersionId] = "whitening-v1"
            it[status] = PlanTreatmentStatus.PENDING
            it[sourceBomItemId] = "bom-whitening"
            it[sequence] = 1
            it[representativeTreatmentName] = "미백 치료"
            it[detailedTreatmentCodesPayload] = "[\"WHITENING\"]"
            it[preparationMinutes] = 10
            it[treatmentMinutes] = 40
            it[recoveryMinutes] = 10
            it[practitionerQualificationsPayload] = "[\"DOCTOR\"]"
            it[equipmentTypesPayload] = "[]"
            it[spaceCapabilitiesPayload] = "[]"
        }
        PlanRevisionTreatments.insert {
            it[PlanRevisionTreatments.planRevisionId] = planRevisionId
            it[treatmentKey] = "consultation"
            it[componentProductId] = "consultation-component"
            it[componentProductVersionId] = "consultation-component-v1"
            it[productVersionId] = "whitening-v1"
            it[status] = PlanTreatmentStatus.PENDING
            it[sourceBomItemId] = "bom-consultation"
            it[sequence] = 2
            it[representativeTreatmentName] = "사후 상담"
            it[detailedTreatmentCodesPayload] = "[\"CONSULTATION\"]"
            it[preparationMinutes] = 0
            it[treatmentMinutes] = 20
            it[recoveryMinutes] = 0
            it[practitionerQualificationsPayload] = "[\"DOCTOR\"]"
            it[equipmentTypesPayload] = "[\"CONSULTATION_DEVICE\"]"
            it[spaceCapabilitiesPayload] = "[]"
        }
        return ClinicFixture(
            clinicId = clinicId,
            doctorId = doctorId,
            treatmentTypeId = treatmentTypeId,
            planId = planId,
            planRevisionId = planRevisionId,
            patientReferenceFingerprint = patientReferenceFingerprint,
        )
    }

    protected companion object {
        val NOW: Instant = Instant.parse("2026-08-01T00:00:00Z")
        val CLOCK: Clock = Clock.fixed(NOW, ZoneOffset.UTC)
        val PROPOSAL_START: Instant = Instant.parse("2026-08-10T01:00:00Z")
        val ACTIVE_EXPIRY: Instant = Instant.parse("2026-08-09T00:00:00Z")
        const val TENANT_ID: Long = 1L
        const val PATIENT_REFERENCE_FINGERPRINT: String =
            "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"

        val TABLES: Array<Table> =
            arrayOf(
                TenantGroups,
                Clinics,
                Doctors,
                TreatmentTypes,
                Equipments,
                ConsultationTopics,
                Appointments,
                ProductCatalogProjections,
                AppointmentPlans,
                AppointmentPlanRevisions,
                PlanRevisionTreatments,
                AppointmentCommitments,
                AppointmentProposals,
                AppointmentItems,
                ConsentDecisions,
                ResourceCapacityBuckets,
                ResourceAllocations,
                WaitlistEntries,
                WaitlistOffers,
                WaitlistCapacityHolds,
                WaitlistOfferEvents,
                AppointmentCommandIdempotencies,
                AppointmentAuditEvents,
                AppointmentCancellationDetails,
                SchedulingOutboxEvents,
            )
    }
}

/**
 * 테스트 병원의 legacy projection 외래 키입니다.
 */
internal class ClinicFixture(
    val clinicId: Long,
    val doctorId: Long,
    val treatmentTypeId: Long,
    val planId: Long,
    val planRevisionId: Long,
    val patientReferenceFingerprint: String,
)

/**
 * 확정 상태와 그 proposal, allocation을 한 transaction에서 읽은 검증 snapshot입니다.
 */
internal class CurrentConfirmation(
    val commitment: AppointmentCommitmentRecord,
    val proposal: AppointmentProposalRecord,
    val allocations: List<ResourceAllocationRecord>,
)
