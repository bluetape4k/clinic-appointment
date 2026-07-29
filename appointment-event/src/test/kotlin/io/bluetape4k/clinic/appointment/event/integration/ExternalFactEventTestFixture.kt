package io.bluetape4k.clinic.appointment.event.integration

import io.bluetape4k.clinic.appointment.model.catalog.CatalogProjectionStatus
import io.bluetape4k.clinic.appointment.model.dto.AppointmentPlanRevisionAggregateRecord
import io.bluetape4k.clinic.appointment.model.dto.PlanRevisionDependencyRecord
import io.bluetape4k.clinic.appointment.model.dto.PlanRevisionTreatmentRecord
import io.bluetape4k.clinic.appointment.model.plan.AppointmentPlanRevision
import io.bluetape4k.clinic.appointment.model.plan.AppointmentPlanStatus
import io.bluetape4k.clinic.appointment.model.plan.ComponentVersionRef
import io.bluetape4k.clinic.appointment.model.plan.ExecutionDependency
import io.bluetape4k.clinic.appointment.model.plan.ExecutionDependencyType
import io.bluetape4k.clinic.appointment.model.plan.ExecutionTreatment
import io.bluetape4k.clinic.appointment.model.plan.PackageExecutionSnapshot
import io.bluetape4k.clinic.appointment.model.plan.PlanTreatmentStatus
import io.bluetape4k.clinic.appointment.model.tables.AppointmentOperationalExceptions
import io.bluetape4k.clinic.appointment.model.tables.AppointmentPlanRevisions
import io.bluetape4k.clinic.appointment.model.tables.AppointmentPlans
import io.bluetape4k.clinic.appointment.model.tables.Appointments
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.PlanRevisionDependencies
import io.bluetape4k.clinic.appointment.model.tables.PlanRevisionGroupingConstraints
import io.bluetape4k.clinic.appointment.model.tables.PlanRevisionTreatments
import io.bluetape4k.clinic.appointment.model.tables.PlannedTreatments
import io.bluetape4k.clinic.appointment.model.tables.ProductCatalogProjections
import io.bluetape4k.clinic.appointment.model.tables.TenantGroups
import io.bluetape4k.clinic.appointment.model.tables.TreatmentDependencies
import io.bluetape4k.clinic.appointment.repository.AppointmentPlanRevisionRepository
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Instant

internal class ExternalFactEventTestFixture(
    databaseName: String,
) {
    val now: Instant = Instant.parse("2026-07-29T12:00:00Z")
    val tenantGroupId: Long = 1L
    val clinicId: Long
    val planId: Long
    val initialRevisionId: Long

    init {
        Database.connect(
            "jdbc:h2:mem:${databaseName}_${System.nanoTime()};DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver",
        )
        var seededClinicId = 0L
        var seededPlanId = 0L
        var seededRevisionId = 0L
        transaction {
            SchemaUtils.createMissingTablesAndColumns(
                TenantGroups,
                Clinics,
                ProductCatalogProjections,
                AppointmentPlans,
                PlannedTreatments,
                TreatmentDependencies,
                Appointments,
                AppointmentPlanRevisions,
                PlanRevisionTreatments,
                PlanRevisionDependencies,
                PlanRevisionGroupingConstraints,
                AppointmentOperationalExceptions,
                SchedulingInboxEvents,
                SchedulingOutboxEvents,
                UntrustedSchedulingEventRejections,
                SchedulingQuarantineEvents,
                SchedulingQuarantineAuditEvents,
            )
            AppointmentOperationalExceptions.deleteAll()
            SchedulingQuarantineAuditEvents.deleteAll()
            SchedulingQuarantineEvents.deleteAll()
            UntrustedSchedulingEventRejections.deleteAll()
            SchedulingOutboxEvents.deleteAll()
            SchedulingInboxEvents.deleteAll()
            PlanRevisionGroupingConstraints.deleteAll()
            PlanRevisionDependencies.deleteAll()
            PlanRevisionTreatments.deleteAll()
            AppointmentPlanRevisions.deleteAll()
            Appointments.deleteAll()
            TreatmentDependencies.deleteAll()
            PlannedTreatments.deleteAll()
            AppointmentPlans.deleteAll()
            ProductCatalogProjections.deleteAll()
            Clinics.deleteAll()
            TenantGroups.deleteAll()

            TenantGroups.insert {
                it[id] = EntityID(tenantGroupId, TenantGroups)
                it[tenantCode] = "tenant-task8"
                it[displayName] = "Task 8 Tenant"
                it[active] = true
            }
            seededClinicId = Clinics.insertAndGetId {
                it[tenantGroupId] = EntityID(this@ExternalFactEventTestFixture.tenantGroupId, TenantGroups)
                it[name] = "Task 8 Clinic"
            }.value
            val catalogProjectionId = ProductCatalogProjections.insertAndGetId {
                it[tenantGroupId] = EntityID(this@ExternalFactEventTestFixture.tenantGroupId, TenantGroups)
                it[clinicId] = EntityID(seededClinicId, Clinics)
                it[sourceAuthority] = "product-service"
                it[productId] = "package-product"
                it[catalogVersion] = 1L
                it[productName] = "패키지 상품"
                it[schemaVersion] = 1
                it[sourceUpdatedAt] = now.minusSeconds(3_600)
                it[status] = CatalogProjectionStatus.ACTIVE
                it[payloadHash] = "a".repeat(64)
            }.value
            seededPlanId = AppointmentPlans.insertAndGetId {
                it[tenantGroupId] = EntityID(this@ExternalFactEventTestFixture.tenantGroupId, TenantGroups)
                it[clinicId] = EntityID(seededClinicId, Clinics)
                it[AppointmentPlans.catalogProjectionId] =
                    EntityID(catalogProjectionId, ProductCatalogProjections)
                it[sourcePurchaseAuthority] = "purchase-service"
                it[sourcePurchaseId] = "purchase-100"
                it[patientReferenceCiphertext] = "encrypted-patient"
                it[patientReferenceKeyId] = "key-1"
                it[patientReferenceFingerprint] = "fingerprint-1"
                it[catalogSourceAuthority] = "product-service"
                it[productId] = "package-product"
                it[catalogVersion] = 1L
                it[catalogPayloadHash] = "a".repeat(64)
                it[productName] = "패키지 상품"
                it[bookingPreferenceType] = "NOT_PROVIDED"
                it[bookingPreferencePayload] = "{}"
                it[status] = AppointmentPlanStatus.PARTIALLY_FULFILLED
            }.value
            seededRevisionId = AppointmentPlanRevisionRepository().append(
                AppointmentPlanRevisionAggregateRecord(
                    revision = AppointmentPlanRevision(
                        planId = seededPlanId,
                        revision = 1L,
                        productVersionId = "product-v1",
                        snapshotHash = "1".repeat(64),
                        active = true,
                    ),
                    treatments = listOf(
                        treatmentRecord("completed", PlanTreatmentStatus.COMPLETED),
                        treatmentRecord("future-old", PlanTreatmentStatus.PENDING),
                        treatmentRecord("independent", PlanTreatmentStatus.PENDING),
                    ),
                    dependencies = listOf(
                        PlanRevisionDependencyRecord(
                            predecessorTreatmentKey = "future-old",
                            successorTreatmentKey = "blocked-next",
                            type = ExecutionDependencyType.BLOCKING,
                            minimumIntervalDays = 7,
                            preferredIntervalDays = 14,
                            maximumIntervalDays = 21,
                        ),
                        PlanRevisionDependencyRecord(
                            predecessorTreatmentKey = "future-old",
                            successorTreatmentKey = "independent",
                            type = ExecutionDependencyType.NON_BLOCKING,
                            minimumIntervalDays = 0,
                            preferredIntervalDays = null,
                            maximumIntervalDays = null,
                        ),
                    ),
                    groupingConstraints = emptyList(),
                ).copy(
                    treatments = listOf(
                        treatmentRecord("completed", PlanTreatmentStatus.COMPLETED),
                        treatmentRecord("future-old", PlanTreatmentStatus.PENDING),
                        treatmentRecord("blocked-next", PlanTreatmentStatus.PENDING),
                        treatmentRecord("independent", PlanTreatmentStatus.PENDING),
                    ),
                )
            ).revision.id
        }
        clinicId = seededClinicId
        planId = seededPlanId
        initialRevisionId = seededRevisionId
    }

    fun targetSnapshot(
        treatmentKeys: List<String> = listOf("future-new", "blocked-new", "independent"),
        dependencies: List<ExecutionDependency> = listOf(
            ExecutionDependency("future-new", "blocked-new", ExecutionDependencyType.BLOCKING, 7, 14, 21),
            ExecutionDependency("future-new", "independent", ExecutionDependencyType.NON_BLOCKING),
        ),
    ): PackageExecutionSnapshot =
        PackageExecutionSnapshot(
            packageProductId = "package-product",
            packageProductVersionId = "product-v2",
            selectedComponentVersions = listOf(ComponentVersionRef("component", "component-v2")),
            expandedTreatmentItems = treatmentKeys.mapIndexed { index, key ->
                executionTreatment(key, index + 1)
            },
            executionDependencies = dependencies,
            visitGroupingConstraints = emptyList(),
            snapshotHash = "2".repeat(64),
        )

    fun executionTreatment(
        treatmentKey: String,
        sequence: Int = 1,
        detailedCodes: List<String> = listOf("CODE-A", "CODE-B"),
    ): ExecutionTreatment =
        ExecutionTreatment(
            treatmentKey = treatmentKey,
            componentProductId = "component",
            componentProductVersionId = "component-v2",
            sourceBomItemId = "bom-$treatmentKey",
            sequence = sequence,
            representativeTreatmentName = "대표 진료",
            detailedTreatmentCodes = detailedCodes,
            preparationMinutes = 5,
            treatmentMinutes = 20,
            recoveryMinutes = 5,
            practitionerQualifications = listOf("DOCTOR"),
            equipmentTypes = listOf("LASER"),
            spaceCapabilities = listOf("ROOM"),
        )

    fun treatmentRecord(
        treatmentKey: String,
        status: PlanTreatmentStatus,
    ): PlanRevisionTreatmentRecord =
        PlanRevisionTreatmentRecord(
            treatmentKey = treatmentKey,
            componentProductId = "component",
            componentProductVersionId = "component-v1",
            productVersionId = "product-v1",
            status = status,
            sourceBomItemId = "bom-$treatmentKey",
            sequence = 1,
            representativeTreatmentName = "대표 진료",
            detailedTreatmentCodes = listOf("CODE-A", "CODE-B"),
            preparationMinutes = 5,
            treatmentMinutes = 20,
            recoveryMinutes = 5,
            practitionerQualifications = listOf("DOCTOR"),
            equipmentTypes = listOf("LASER"),
            spaceCapabilities = listOf("ROOM"),
        )
}
