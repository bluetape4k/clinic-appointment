package io.bluetape4k.clinic.appointment.api.commitment

import io.bluetape4k.clinic.appointment.model.catalog.CatalogProjectionStatus
import io.bluetape4k.clinic.appointment.model.commitment.AppointmentItemDraft
import io.bluetape4k.clinic.appointment.model.commitment.ConsentDecisionType
import io.bluetape4k.clinic.appointment.model.commitment.ResourceAllocationDraft
import io.bluetape4k.clinic.appointment.model.commitment.ResourceAllocationMode
import io.bluetape4k.clinic.appointment.model.commitment.ResourceType
import io.bluetape4k.clinic.appointment.model.dto.AppointmentVisitIdentityDraft
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
import io.bluetape4k.clinic.appointment.event.integration.SchedulingOutboxEvents
import io.bluetape4k.clinic.appointment.repository.AppointmentCommandIdempotencyRepository
import io.bluetape4k.clinic.appointment.repository.AppointmentCommitmentRepository
import io.bluetape4k.clinic.appointment.repository.AppointmentItemRepository
import io.bluetape4k.clinic.appointment.repository.AppointmentRepository
import io.bluetape4k.clinic.appointment.repository.ResourceAllocationRepository
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.lang.reflect.InvocationTargetException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * canonical Gatling 실행에서 commitment command의 실제 Exposed DB 경로를 검증하는 fixture입니다.
 *
 * 각 HTTP 요청은 새 H2 database와 production table 객체를 만들고 Java reflection으로
 * `AppointmentCommitmentCommandService.confirmDirectAppointment`를 호출합니다. Kotlin Gatling
 * source set은 main의 `internal` command 타입을 직접 컴파일 참조할 수 없으므로 reflection이
 * JVM public bytecode 경계에서 command를 생성합니다. 호출 대상은 production service와
 * repository 그대로이며, fixture는 schema·seed·probe 판정만 담당합니다.
 */
internal class VisitCommitmentGatlingFixture {
    fun run(caseName: String): VisitCommitmentProbeResult =
        when (caseName) {
            "exclusive-overlap" -> exclusiveOverlap()
            "capacity-exhaustion" -> capacityExhaustion()
            "multi-lock" -> multiLock()
            "idempotency-replay" -> idempotencyReplay()
            else -> VisitCommitmentProbeResult(caseName, verified = false, evidence = "unknown-case")
        }

    private fun exclusiveOverlap(): VisitCommitmentProbeResult =
        withIsolatedInvoker("exclusive-overlap") { invoker, clinic ->
            val first = invoker.confirmDirect(clinic, "exclusive-winner", "doctor-exclusive")
            val second = invoker.confirmDirect(clinic, "exclusive-loser", "doctor-exclusive")
            VisitCommitmentProbeResult(
                caseName = "exclusive-overlap",
                verified = first.success && second.errorCode == "RESOURCE_CONFLICT",
                evidence = "winnerProposal=${first.proposalId};loser=${second.errorCode}",
            )
        }

    private fun capacityExhaustion(): VisitCommitmentProbeResult =
        withIsolatedInvoker("capacity-exhaustion") { invoker, clinic ->
            val first =
                invoker.confirmCapacityBucket(
                    clinic = clinic,
                    key = "capacity-winner",
                    bucketResourceId = "bucket-main",
                    practitionerResourceId = "doctor-capacity-winner",
                    capacityUnits = 2,
                    maximumCapacity = 3,
                )
            val second =
                invoker.confirmCapacityBucket(
                    clinic = clinic,
                    key = "capacity-loser",
                    bucketResourceId = "bucket-main",
                    practitionerResourceId = "doctor-capacity-loser",
                    capacityUnits = 2,
                    maximumCapacity = 3,
                )
            VisitCommitmentProbeResult(
                caseName = "capacity-exhaustion",
                verified = first.success && second.errorCode == "RESOURCE_CONFLICT",
                evidence = "winnerProposal=${first.proposalId};bucket=${second.errorCode}",
            )
        }

    private fun multiLock(): VisitCommitmentProbeResult =
        withIsolatedInvoker("multi-lock") { invoker, clinic ->
            val result = invoker.confirmMultiLock(clinic, "multi-lock", "doctor-multi")
            VisitCommitmentProbeResult(
                caseName = "multi-lock",
                verified = result.success && result.allocationCount == 3,
                evidence = "proposal=${result.proposalId};allocations=${result.allocationCount}",
            )
        }

    private fun idempotencyReplay(): VisitCommitmentProbeResult =
        withIsolatedInvoker("idempotency-replay") { invoker, clinic ->
            val first = invoker.confirmDirect(clinic, "same-idempotency-key", "doctor-replay")
            val replay = invoker.confirmDirect(clinic, "same-idempotency-key", "doctor-replay")
            VisitCommitmentProbeResult(
                caseName = "idempotency-replay",
                verified =
                    first.success &&
                        replay.success &&
                        !first.replay &&
                        replay.replay &&
                        first.proposalId == replay.proposalId &&
                        first.commitmentId == replay.commitmentId,
                evidence =
                    "firstProposal=${first.proposalId};replayProposal=${replay.proposalId};" +
                        "replay=${replay.replay}",
            )
        }

    private fun withIsolatedInvoker(
        suffix: String,
        block: (VisitCommitmentCommandInvoker, GatlingClinicFixture) -> VisitCommitmentProbeResult,
    ): VisitCommitmentProbeResult {
        val database =
            Database.connect(
                url =
                    "jdbc:h2:mem:gatling_${suffix}_${System.nanoTime()};" +
                        "MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver",
            )
        val clinic = initialize(database)
        return block(VisitCommitmentCommandInvoker(database), clinic)
    }

    /**
     * PostgreSQL Gatling fixture가 production outbox table을 그대로 생성할 수 있게 한다.
     * H2 probe는 기존 최소 DDL을 유지하고, PostgreSQL은 [SchedulingOutboxEvents]의 전체
     * column/index 계약을 사용한다.
     */
    internal fun initialize(
        database: Database,
        useProductionOutboxSchema: Boolean = false,
    ): GatlingClinicFixture =
        transaction(database) {
            SchemaUtils.createMissingTablesAndColumns(*TABLES)
            if (useProductionOutboxSchema) {
                SchemaUtils.createMissingTablesAndColumns(SchedulingOutboxEvents)
            } else {
                createSchedulingOutboxEventsTable()
            }
            seedClinic()
        }

    private fun createSchedulingOutboxEventsTable() {
        TransactionManager.current().exec(
            """
            create table if not exists scheduling_outbox_events (
                id bigint generated by default as identity primary key,
                event_id varchar(128) not null unique,
                causation_event_id varchar(128),
                correlation_id varchar(128) not null,
                event_type varchar(128) not null,
                tenant_group_id bigint not null,
                clinic_id bigint,
                plan_id bigint,
                aggregate_type varchar(64),
                aggregate_id varchar(160),
                schema_version integer not null,
                payload_json clob not null,
                status varchar(32) not null,
                attempt_count integer not null default 0,
                next_attempt_at timestamp,
                created_at timestamp default current_timestamp,
                published_at timestamp
            )
            """.trimIndent(),
            explicitStatementType = StatementType.CREATE,
        )
    }

    private fun seedClinic(): GatlingClinicFixture {
        TenantGroups.insert {
            it[id] = EntityID(TENANT_ID, TenantGroups)
            it[tenantCode] = "tenant-gatling"
            it[displayName] = "Gatling Tenant"
            it[active] = true
        }
        val clinicId =
            Clinics
                .insertAndGetId {
                    it[tenantGroupId] = EntityID(TENANT_ID, TenantGroups)
                    it[name] = "Gatling Clinic"
                    it[timezone] = "Asia/Seoul"
                }.value
        val doctorId =
            Doctors
                .insertAndGetId {
                    it[Doctors.clinicId] = clinicId
                    it[name] = "Gatling Doctor"
                }.value
        val treatmentTypeId =
            TreatmentTypes
                .insertAndGetId {
                    it[TreatmentTypes.clinicId] = clinicId
                    it[name] = "미백 치료"
                    it[defaultDurationMinutes] = 60
                }.value
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
                    it[patientReferenceKeyId] = "gatling-key"
                    it[patientReferenceFingerprint] = PATIENT_REFERENCE_FINGERPRINT
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
        insertPlanTreatment(planRevisionId, "whitening", 1, "미백 치료", "[\"WHITENING\"]", "[]", "[]")
        insertPlanTreatment(
            planRevisionId,
            "consultation",
            2,
            "사후 상담",
            "[\"CONSULTATION\"]",
            "[\"CONSULTATION_DEVICE\"]",
            "[\"OPERATORY\"]",
        )
        return GatlingClinicFixture(
            clinicId = clinicId,
            doctorId = doctorId,
            treatmentTypeId = treatmentTypeId,
            planRevisionId = planRevisionId,
            patientReferenceFingerprint = PATIENT_REFERENCE_FINGERPRINT,
        )
    }

    private fun insertPlanTreatment(
        planRevisionId: Long,
        treatmentKey: String,
        sequence: Int,
        name: String,
        codes: String,
        equipmentTypes: String,
        spaceCapabilities: String,
    ) {
        PlanRevisionTreatments.insert {
            it[PlanRevisionTreatments.planRevisionId] = planRevisionId
            it[PlanRevisionTreatments.treatmentKey] = treatmentKey
            it[componentProductId] = "$treatmentKey-component"
            it[componentProductVersionId] = "$treatmentKey-component-v1"
            it[productVersionId] = "whitening-v1"
            it[status] = PlanTreatmentStatus.PENDING
            it[sourceBomItemId] = "bom-$treatmentKey"
            it[PlanRevisionTreatments.sequence] = sequence
            it[representativeTreatmentName] = name
            it[detailedTreatmentCodesPayload] = codes
            it[preparationMinutes] = if (treatmentKey == "whitening") 10 else 0
            it[treatmentMinutes] = if (treatmentKey == "whitening") 40 else 20
            it[recoveryMinutes] = if (treatmentKey == "whitening") 10 else 0
            it[practitionerQualificationsPayload] = "[\"DOCTOR\"]"
            it[equipmentTypesPayload] = equipmentTypes
            it[spaceCapabilitiesPayload] = spaceCapabilities
        }
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-01T00:00:00Z")
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
                AppointmentCommandIdempotencies,
                AppointmentAuditEvents,
                AppointmentCancellationDetails,
                WaitlistEntries,
                WaitlistOffers,
                WaitlistCapacityHolds,
                WaitlistOfferEvents,
            )
    }
}

/** Gatling HTTP 응답으로 직렬화할 commitment probe 결과입니다. */
internal data class VisitCommitmentProbeResult(
    val caseName: String,
    val verified: Boolean,
    val evidence: String,
)

/** Gatling fixture가 seed한 clinic과 Plan revision FK입니다. */
internal data class GatlingClinicFixture(
    val clinicId: Long,
    val doctorId: Long,
    val treatmentTypeId: Long,
    val planRevisionId: Long,
    val patientReferenceFingerprint: String,
)

/**
 * main source set의 `internal` commitment command API를 reflection으로 호출합니다.
 *
 * 새 fake model을 만들지 않고 `AppointmentCommitmentCommandService` 인스턴스와 command 객체만
 * JVM 생성자로 구성합니다. 성공 뒤 allocation count는 같은 H2 database의 production
 * [ResourceAllocationRepository]로 다시 읽어 multi-lock 증거를 고정합니다.
 */
internal class VisitCommitmentCommandInvoker(
    private val database: Database,
) {
    private val serviceClass =
        Class.forName("io.bluetape4k.clinic.appointment.api.commitment.AppointmentCommitmentCommandService")
    private val contextClass =
        Class.forName("io.bluetape4k.clinic.appointment.api.commitment.CommitmentCommandContext")
    private val proposalInputClass =
        Class.forName("io.bluetape4k.clinic.appointment.api.commitment.VisitProposalInput")
    private val projectionTargetClass =
        Class.forName("io.bluetape4k.clinic.appointment.api.commitment.ConfirmedAppointmentProjectionTarget")
    private val policyDecisionClass =
        Class.forName("io.bluetape4k.clinic.appointment.api.commitment.DirectConfirmationPolicyDecision")
    private val consentClass =
        Class.forName("io.bluetape4k.clinic.appointment.api.commitment.ProposalConsentEvidence")
    private val directCommandClass =
        Class.forName("io.bluetape4k.clinic.appointment.api.commitment.DirectAppointmentConfirmationCommand")
    private val cancelCommandClass =
        Class.forName("io.bluetape4k.clinic.appointment.api.commitment.CancelAppointmentCommand")
    private val notificationWriter =
        Class.forName(
            "io.bluetape4k.clinic.appointment.api.commitment." +
                "AppointmentCommitmentCommandService\$NoopAppointmentNotificationWriter",
        ).getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
    private val bookingEligibilityGate =
        Class.forName("io.bluetape4k.clinic.appointment.api.commitment.BookingEligibilityGate")
            .getDeclaredField("Companion").get(null).let { companion ->
                companion.javaClass.getMethod("disabled").invoke(companion)
            }
    private val contextConstructor = contextClass.constructors.single { it.parameterCount == 8 }
    private val service =
        serviceClass
            .constructors
            .single { it.parameterCount == 13 }
            .newInstance(
                database,
                CLOCK,
                3,
                25L,
                { _: Long -> Unit },
                { _: Int -> 0L },
                AppointmentRepository(),
                AppointmentCommitmentRepository(),
                AppointmentItemRepository(),
                ResourceAllocationRepository(),
                AppointmentCommandIdempotencyRepository(),
                notificationWriter,
                bookingEligibilityGate,
            )

    fun confirmDirect(
        clinic: GatlingClinicFixture,
        key: String,
        practitionerResourceId: String,
    ): CommandOutcome =
        confirm(
            clinic = clinic,
            key = key,
            proposal =
                proposalInput(
                    clinic = clinic,
                    resourceId = practitionerResourceId,
                    practitionerResourceId = practitionerResourceId,
                    allocationMode = ResourceAllocationMode.EXCLUSIVE,
                    capacityUnits = 1,
                    maximumCapacity = 1,
                    includeEquipment = false,
                    includeTreatmentSpace = false,
                ),
        )

    fun confirmCapacityBucket(
        clinic: GatlingClinicFixture,
        key: String,
        bucketResourceId: String,
        practitionerResourceId: String,
        capacityUnits: Int,
        maximumCapacity: Int,
    ): CommandOutcome =
        confirm(
            clinic = clinic,
            key = key,
            proposal =
                proposalInput(
                    clinic = clinic,
                    resourceId = bucketResourceId,
                    practitionerResourceId = practitionerResourceId,
                    allocationMode = ResourceAllocationMode.CAPACITY_BUCKET,
                    capacityUnits = capacityUnits,
                    maximumCapacity = maximumCapacity,
                    includeEquipment = false,
                    includeTreatmentSpace = false,
                ),
        )

    fun confirmMultiLock(
        clinic: GatlingClinicFixture,
        key: String,
        practitionerResourceId: String,
    ): CommandOutcome =
        confirm(
            clinic = clinic,
            key = key,
            proposal =
                proposalInput(
                    clinic = clinic,
                    resourceId = practitionerResourceId,
                    practitionerResourceId = practitionerResourceId,
                    allocationMode = ResourceAllocationMode.EXCLUSIVE,
                    capacityUnits = 1,
                    maximumCapacity = 1,
                    includeEquipment = true,
                    includeTreatmentSpace = true,
                ),
        )

    /**
     * production cancel command를 reflection 경계에서 호출한다.
     *
     * [source]의 immutable appointment/proposal snapshot을 사용하므로 benchmark가 stale
     * `expectedVersion`이나 hash를 임의로 다시 계산하지 않는다.
     */
    fun cancel(
        clinic: GatlingClinicFixture,
        source: CommandOutcome,
        key: String,
        actorRole: String,
        reasonDetail: String? = null,
    ): CommandOutcome {
        require(source.success) { "cancel source commitment must be successful" }
        return try {
            val result =
                serviceClass
                    .getMethod("cancelAppointment", cancelCommandClass)
                    .invoke(
                        service,
                        cancelCommandClass
                            .constructors
                            .single { it.parameterCount == 7 }
                            .newInstance(
                                commandContext(clinic, key, actorRole),
                                source.appointmentId,
                                source.proposalId,
                                source.version,
                                source.proposalHash,
                                "CUSTOMER_REQUEST",
                                reasonDetail,
                            ),
                    )
            val commitment = result.javaClass.getMethod("getCommitment").invoke(result)
            val proposalRecord = result.javaClass.getMethod("getProposal").invoke(result)
            val appointmentId = commitment.javaClass.getMethod("getAppointmentId").invoke(commitment) as Long
            val commitmentId = commitment.javaClass.getMethod("getId").invoke(commitment) as Long
            val proposalId = proposalRecord.javaClass.getMethod("getId").invoke(proposalRecord) as Long
            val version = commitment.javaClass.getMethod("getVersion").invoke(commitment) as Long
            val proposalHash = proposalRecord.javaClass.getMethod("getProposalHash").invoke(proposalRecord) as String
            val replay = result.javaClass.getMethod("getIdempotentReplay").invoke(result) as Boolean
            CommandOutcome.success(
                appointmentId = appointmentId,
                commitmentId = commitmentId,
                proposalId = proposalId,
                version = version,
                proposalHash = proposalHash,
                replay = replay,
                allocationCount = 0,
            )
        } catch (failure: InvocationTargetException) {
            val target = failure.targetException
            val code =
                if (target.javaClass.simpleName == "AppointmentCommitmentCommandException") {
                    target.javaClass.getMethod("getCode").invoke(target).toString()
                } else {
                    throw target
                }
            CommandOutcome.rejected(code)
        }
    }

    private fun confirm(
        clinic: GatlingClinicFixture,
        key: String,
        proposal: Any,
    ): CommandOutcome =
        try {
            val result =
                serviceClass
                    .getMethod("confirmDirectAppointment", directCommandClass)
                    .invoke(
                        service,
                        directCommandClass
                            .constructors
                            .single()
                            .newInstance(
                                commandContext(clinic, key),
                                appointmentIdentity(clinic, key),
                                proposal,
                                ACTIVE_EXPIRY,
                                "미백 치료",
                                projectionTarget(clinic, selectedPractitioner(proposal)),
                                policyDecision(),
                                acceptedConsent(key),
                            ),
                    )
            val commitment = result.javaClass.getMethod("getCommitment").invoke(result)
            val proposalRecord = result.javaClass.getMethod("getProposal").invoke(result)
            val appointmentId = commitment.javaClass.getMethod("getAppointmentId").invoke(commitment) as Long
            val commitmentId = commitment.javaClass.getMethod("getId").invoke(commitment) as Long
            val proposalId = proposalRecord.javaClass.getMethod("getId").invoke(proposalRecord) as Long
            val version = commitment.javaClass.getMethod("getVersion").invoke(commitment) as Long
            val proposalHash = proposalRecord.javaClass.getMethod("getProposalHash").invoke(proposalRecord) as String
            val replay = result.javaClass.getMethod("getIdempotentReplay").invoke(result) as Boolean
            val allocationCount =
                transaction(database) {
                    ResourceAllocationRepository().findByProposal(proposalId).size
                }
            CommandOutcome.success(
                appointmentId = appointmentId,
                commitmentId = commitmentId,
                proposalId = proposalId,
                version = version,
                proposalHash = proposalHash,
                replay = replay,
                allocationCount = allocationCount,
            )
        } catch (failure: InvocationTargetException) {
            val target = failure.targetException
            val code =
                if (target.javaClass.simpleName == "AppointmentCommitmentCommandException") {
                    target.javaClass.getMethod("getCode").invoke(target).toString()
                } else {
                    throw target
                }
            CommandOutcome.rejected(code)
        }

    private fun proposalInput(
        clinic: GatlingClinicFixture,
        resourceId: String,
        practitionerResourceId: String,
        allocationMode: ResourceAllocationMode,
        capacityUnits: Int,
        maximumCapacity: Int,
        includeEquipment: Boolean,
        includeTreatmentSpace: Boolean,
    ): Any {
        val requests =
            buildList {
                if (allocationMode == ResourceAllocationMode.CAPACITY_BUCKET) {
                    add(exclusiveRequest(ResourceType.PRACTITIONER, practitionerResourceId, "whitening"))
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
                                startsAt = PROPOSAL_START,
                                endsAt = PROPOSAL_START.plusSeconds(3_600),
                                capacityUnits = capacityUnits,
                                maximumCapacity = maximumCapacity,
                                allocationMode = allocationMode,
                                appointmentItemKey = "whitening",
                            ),
                        maximumCapacity = maximumCapacity,
                    ),
                )
                if (includeEquipment) {
                    add(exclusiveRequest(ResourceType.EQUIPMENT, "equipment-consultation", "consultation"))
                }
                if (includeTreatmentSpace) {
                    add(exclusiveRequest(ResourceType.TREATMENT_SPACE, "space-treatment-a", "whitening"))
                }
            }
        return proposalInputClass
            .constructors
            .single()
            .newInstance(
                1L,
                PROPOSAL_START,
                PROPOSAL_START.plusSeconds(3_600),
                proposalItems(clinic.planRevisionId),
                requests,
                POLICY_SNAPSHOT_ID,
                null,
            )
    }

    private fun proposalItems(planRevisionId: Long): List<AppointmentItemDraft> =
        listOf(
            AppointmentItemDraft(planRevisionId, "whitening", "미백 치료", listOf("WHITENING"), 10, 40, 10),
            AppointmentItemDraft(planRevisionId, "consultation", "사후 상담", listOf("CONSULTATION"), 0, 20, 0),
        )

    private fun exclusiveRequest(
        resourceType: ResourceType,
        resourceId: String,
        itemKey: String,
    ): ResourceAllocationRequest =
        ResourceAllocationRequest(
            allocation =
                ResourceAllocationDraft(
                    resourceType = resourceType,
                    resourceId = resourceId,
                    startsAt = PROPOSAL_START,
                    endsAt = PROPOSAL_START.plusSeconds(3_600),
                    capacityUnits = 1,
                    allocationMode = ResourceAllocationMode.EXCLUSIVE,
                    appointmentItemKey = itemKey,
                ),
            maximumCapacity = 1,
        )

    private fun commandContext(
        clinic: GatlingClinicFixture,
        key: String,
        actorRole: String = "UNKNOWN",
    ): Any =
        contextConstructor
            .newInstance(
                TENANT_ID,
                clinic.clinicId,
                "a".repeat(64),
                "actor:masked",
                actorRole,
                sha256(key),
                "c".repeat(64),
                "gatling-$key",
            )

    private fun appointmentIdentity(
        clinic: GatlingClinicFixture,
        key: String,
    ): AppointmentVisitIdentityDraft =
        AppointmentVisitIdentityDraft(
            patientName = "Patient $key",
            patientPhone = "010-0000-0000",
            memberId = MemberId("patient-$key"),
            patientReferenceFingerprint = clinic.patientReferenceFingerprint,
        )

    private fun projectionTarget(
        clinic: GatlingClinicFixture,
        practitionerResourceId: String,
    ): Any =
        projectionTargetClass
            .constructors
            .single()
            .newInstance(clinic.doctorId, clinic.treatmentTypeId, practitionerResourceId)

    private fun policyDecision(): Any =
        policyDecisionClass
            .constructors
            .single()
            .newInstance(
                POLICY_SNAPSHOT_ID,
                "f".repeat(64),
                AdminBookingMode.DIRECT_CONFIRM_WITH_CONSENT_EVIDENCE,
                setOf("SIGNED_FORM"),
                Duration.ofDays(30),
                true,
                "d".repeat(64),
            )

    private fun acceptedConsent(key: String): Any =
        consentClass
            .constructors
            .single()
            .newInstance(
                ConsentDecisionType.ACCEPTED,
                "SIGNED_FORM",
                "customer-app",
                "consent-$key",
                "e".repeat(64),
                NOW,
                "d".repeat(64),
                "patient:masked",
            )

    private fun selectedPractitioner(proposal: Any): String {
        @Suppress("UNCHECKED_CAST")
        val requests = proposal.javaClass.getMethod("getResourceRequests").invoke(proposal) as List<ResourceAllocationRequest>
        return requests
            .map(ResourceAllocationRequest::allocation)
            .single { it.resourceType == ResourceType.PRACTITIONER }
            .resourceId
    }

    private fun sha256(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString(separator = "") { "%02x".format(it) }

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-01T00:00:00Z")
        val CLOCK: Clock = Clock.fixed(NOW, ZoneOffset.UTC)
        val PROPOSAL_START: Instant = Instant.parse("2026-08-10T01:00:00Z")
        val ACTIVE_EXPIRY: Instant = Instant.parse("2026-08-09T00:00:00Z")
        const val TENANT_ID: Long = 1L
        const val POLICY_SNAPSHOT_ID: Long = 7L
    }
}

/** reflection 호출 결과를 Gatling probe가 검증하기 쉬운 값으로 축약합니다. */
internal data class CommandOutcome(
    val appointmentId: Long,
    val success: Boolean,
    val commitmentId: Long,
    val proposalId: Long,
    val version: Long,
    val proposalHash: String,
    val replay: Boolean,
    val allocationCount: Int,
    val errorCode: String?,
) {
    companion object {
        fun success(
            appointmentId: Long,
            commitmentId: Long,
            proposalId: Long,
            version: Long,
            proposalHash: String,
            replay: Boolean,
            allocationCount: Int,
        ): CommandOutcome =
            CommandOutcome(
                appointmentId = appointmentId,
                success = true,
                commitmentId = commitmentId,
                proposalId = proposalId,
                version = version,
                proposalHash = proposalHash,
                replay = replay,
                allocationCount = allocationCount,
                errorCode = null,
            )

        fun rejected(errorCode: String): CommandOutcome =
            CommandOutcome(
                appointmentId = -1L,
                success = false,
                commitmentId = -1L,
                proposalId = -1L,
                version = -1L,
                proposalHash = "",
                replay = false,
                allocationCount = 0,
                errorCode = errorCode,
            )
    }
}
