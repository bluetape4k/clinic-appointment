package io.bluetape4k.clinic.appointment.repository

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.clinic.appointment.model.dto.AppointmentPlanAggregateRecord
import io.bluetape4k.clinic.appointment.model.dto.AppointmentPlanRecord
import io.bluetape4k.clinic.appointment.model.dto.PlannedTreatmentKey
import io.bluetape4k.clinic.appointment.model.dto.PlannedTreatmentRecord
import io.bluetape4k.clinic.appointment.model.dto.ProductCatalogProjectionRecord
import io.bluetape4k.clinic.appointment.model.dto.TreatmentDependencyRecord
import io.bluetape4k.clinic.appointment.model.plan.AppointmentPlanStatus
import io.bluetape4k.clinic.appointment.model.plan.BookingPreferenceSnapshot
import io.bluetape4k.clinic.appointment.model.plan.PlannedTreatmentStatus
import io.bluetape4k.clinic.appointment.model.tables.AppointmentPlans
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.PlannedTreatments
import io.bluetape4k.clinic.appointment.model.tables.ProductCatalogBomDependencies
import io.bluetape4k.clinic.appointment.model.tables.ProductCatalogBomItems
import io.bluetape4k.clinic.appointment.model.tables.ProductCatalogProjections
import io.bluetape4k.clinic.appointment.model.tables.TreatmentDependencies
import io.bluetape4k.clinic.appointment.model.tables.TenantGroups
import io.bluetape4k.clinic.appointment.test.AbstractExposedTest
import io.bluetape4k.clinic.appointment.test.TestDB
import io.bluetape4k.clinic.appointment.test.withTables
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class AppointmentPlanRepositoryTest : AbstractExposedTest() {
    private val catalogRepository = ProductCatalogRepository()
    private val repository = AppointmentPlanRepository()

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `round trips plan children and fails closed across tenant and clinic scopes`(testDB: TestDB) {
        withPlanTables(testDB) {
            val clinicId = createClinic("Plan Clinic")
            val catalogId = saveCatalog(clinicId)
            val aggregate = planAggregate(clinicId, catalogId)

            val saved = repository.saveAggregate(aggregate)
            val planId = saved.plan.id.shouldNotBeNull()
            val found = repository.findByIdAndTenantClinic(planId, 1L, clinicId)

            found.shouldNotBeNull()
            found.plan.patientReferenceCiphertext shouldBeEqualTo "ciphertext"
            found.plan.patientReferenceFingerprint shouldBeEqualTo "f".repeat(64)
            found.treatments.map { it.key } shouldBeEqualTo listOf(
                PlannedTreatmentKey("laser", 1),
                PlannedTreatmentKey("care", 1),
            )
            found.treatments.first().detailedTreatmentCodes shouldBeEqualTo listOf("laser-a")
            found.treatments.first().representativeTreatmentName shouldBeEqualTo "Treatment laser"
            found.treatments.first().durationMinutes shouldBeEqualTo 30
            found.treatments.first().sequenceNo shouldBeEqualTo 1
            found.treatments.first().practitionerQualifications shouldBeEqualTo listOf("doctor")
            found.treatments.first().equipmentTypes shouldBeEqualTo listOf("laser")
            found.treatments.first().roomTypes shouldBeEqualTo listOf("room")
            found.dependencies.single().minimumIntervalDays shouldBeEqualTo 1

            repository.findByIdAndTenantClinic(planId, 2L, clinicId).shouldBeNull()
            repository.findByIdAndTenantClinic(planId, 1L, clinicId + 1).shouldBeNull()
            repository.findBySourcePurchaseAndTenantClinic(
                "commerce",
                "purchase-1",
                2L,
                clinicId,
            ).shouldBeNull()
            repository.findBySourcePurchaseAndTenantClinic(
                "commerce",
                "purchase-1",
                1L,
                clinicId + 1,
            ).shouldBeNull()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `source purchase is globally unique and catalog deletion is restricted after plan creation`(testDB: TestDB) {
        withPlanTables(testDB) {
            val clinicId = createClinic("First Clinic")
            val otherTenantId = 2L
            TenantGroups.insert {
                it[id] = EntityID(otherTenantId, TenantGroups)
                it[tenantCode] = "tenant-2"
                it[displayName] = "Tenant 2"
            }
            val otherClinicId = createClinic("Second Clinic", tenantId = otherTenantId)
            val catalogId = saveCatalog(clinicId)
            val otherCatalogId = saveCatalog(
                otherClinicId,
                tenantGroupId = otherTenantId,
                productId = "product-2",
            )

            repository.saveAggregate(planAggregate(clinicId, catalogId))

            assertFailsWith<IllegalArgumentException> {
                repository.saveAggregate(
                    planAggregate(clinicId, catalogId).copy(
                        plan = planAggregate(clinicId, catalogId).plan.copy(
                            catalogPayloadHash = "b".repeat(64),
                        )
                    )
                )
            }
            assertFailsWith<ExposedSQLException> {
                val otherAggregate = planAggregate(otherClinicId, otherCatalogId)
                repository.saveAggregate(
                    otherAggregate.copy(
                        plan = otherAggregate.plan.copy(
                            tenantGroupId = otherTenantId,
                            productId = "product-2",
                        )
                    )
                )
            }
            assertFailsWith<ExposedSQLException> {
                catalogRepository.deleteProjection(catalogId)
            }
        }
    }

    private fun withPlanTables(
        testDB: TestDB,
        statement: org.jetbrains.exposed.v1.jdbc.JdbcTransaction.() -> Unit,
    ) = withTables(
        testDB,
        Clinics,
        ProductCatalogProjections,
        ProductCatalogBomItems,
        ProductCatalogBomDependencies,
        AppointmentPlans,
        PlannedTreatments,
        TreatmentDependencies,
    ) { statement() }

    private fun org.jetbrains.exposed.v1.jdbc.JdbcTransaction.createClinic(
        nameValue: String,
        tenantId: Long = 1L,
    ): Long =
        Clinics.insertAndGetId {
            it[tenantGroupId] = tenantId
            it[name] = nameValue
        }.value

    private fun saveCatalog(
        clinicId: Long,
        tenantGroupId: Long = 1L,
        productId: String = "product-1",
    ): Long {
        val source = ProductCatalogRepositoryTestSource.record(tenantGroupId, clinicId, productId)
        return catalogRepository.saveAggregate(source).id.shouldNotBeNull()
    }

    private fun planAggregate(
        clinicId: Long,
        catalogProjectionId: Long,
    ): AppointmentPlanAggregateRecord {
        val bookingPreference = BookingPreferenceSnapshot.ExactDateTime(
            originalLocalDateTime = LocalDateTime.of(2026, 8, 1, 10, 0),
            originalOffset = ZoneOffset.ofHours(9),
            zoneId = ZoneId.of("Asia/Seoul"),
            normalizedInstant = Instant.parse("2026-08-01T01:00:00Z"),
        )
        return AppointmentPlanAggregateRecord(
            plan = AppointmentPlanRecord(
                tenantGroupId = 1L,
                clinicId = clinicId,
                catalogProjectionId = catalogProjectionId,
                sourcePurchaseAuthority = "commerce",
                sourcePurchaseId = "purchase-1",
                patientReferenceCiphertext = "ciphertext",
                patientReferenceKeyId = "key-1",
                patientReferenceFingerprint = "f".repeat(64),
                productId = "product-1",
                catalogVersion = 7L,
                catalogPayloadHash = "a".repeat(64),
                productName = "Laser package",
                bookingPreference = bookingPreference,
                status = AppointmentPlanStatus.ACTIVE,
            ),
            treatments = listOf(
                plannedTreatment("laser", 1, 0, listOf("laser-a"), listOf("laser")),
                plannedTreatment("care", 1, 1, listOf("care"), emptyList()),
            ),
            dependencies = listOf(
                TreatmentDependencyRecord(
                    predecessor = PlannedTreatmentKey("laser", 1),
                    successor = PlannedTreatmentKey("care", 1),
                    minimumIntervalDays = 1,
                    preferredIntervalDays = 2,
                    maximumIntervalDays = 3,
                )
            ),
        )
    }

    private fun plannedTreatment(
        bomItemId: String,
        sequenceNo: Int,
        bomOrder: Int,
        codes: List<String>,
        equipmentTypes: List<String>,
    ) = PlannedTreatmentRecord(
        bomItemId = bomItemId,
        sequenceNo = sequenceNo,
        bomOrder = bomOrder,
        representativeTreatmentName = "Treatment $bomItemId",
        detailedTreatmentCodes = codes,
        durationMinutes = 30,
        minimumIntervalDays = 1,
        preferredIntervalDays = 7,
        maximumIntervalDays = 14,
        practitionerQualifications = listOf("doctor"),
        equipmentTypes = equipmentTypes,
        roomTypes = listOf("room"),
        earliestStartAt = null,
        latestStartAt = null,
        status = PlannedTreatmentStatus.PLANNED,
    )
}

private object ProductCatalogRepositoryTestSource {
    fun record(
        tenantGroupId: Long,
        clinicId: Long,
        productId: String,
    ): ProductCatalogProjectionRecord {
        val definition = io.bluetape4k.clinic.appointment.model.catalog.ProductCatalogDefinition(
            tenantGroupId = tenantGroupId,
            clinicId = clinicId,
            sourceAuthority = "product-catalog",
            productId = productId,
            catalogVersion = 7L,
            productName = "Laser package",
            schemaVersion = 1,
            sourceUpdatedAt = Instant.parse("2026-07-26T00:00:00Z"),
            items = listOf(
                io.bluetape4k.clinic.appointment.model.catalog.CatalogBomItem(
                    bomItemId = "laser",
                    representativeTreatmentName = "Laser",
                    detailedTreatmentCodes = listOf("laser-a"),
                    repeatCount = 1,
                    durationMinutes = 30,
                    minimumIntervalDays = 1,
                    preferredIntervalDays = 7,
                    maximumIntervalDays = 14,
                    practitionerQualifications = listOf("doctor"),
                    equipmentTypes = listOf("laser"),
                    roomTypes = listOf("room"),
                )
            ),
            dependencies = emptyList(),
            initialBookingRule = null,
        )
        return ProductCatalogProjectionRecord(definition = definition, payloadHash = "a".repeat(64))
    }
}
