package io.bluetape4k.clinic.appointment.service

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.clinic.appointment.model.catalog.CatalogBomDependency
import io.bluetape4k.clinic.appointment.model.catalog.CatalogBomItem
import io.bluetape4k.clinic.appointment.model.catalog.ProductCatalogDefinition
import io.bluetape4k.clinic.appointment.model.dto.PlannedTreatmentKey
import io.bluetape4k.clinic.appointment.model.dto.ProductCatalogProjectionRecord
import io.bluetape4k.clinic.appointment.model.plan.BookingPreferenceSnapshot
import org.junit.jupiter.api.Test
import java.time.Instant

class AppointmentPlanFactoryTest {

    private val factory = AppointmentPlanFactory()

    @Test
    fun `expands repeated treatments and materializes default dependency occurrences`() {
        val preference = BookingPreferenceSnapshot.DateRange(
            startDate = java.time.LocalDate.parse("2026-08-10"),
            endDate = java.time.LocalDate.parse("2026-08-20"),
            zoneId = java.time.ZoneId.of("Asia/Seoul"),
        )

        val aggregate = factory.create(catalog(), input(preference))

        aggregate.plan.bookingPreference shouldBeEqualTo preference
        aggregate.plan.catalogVersion shouldBeEqualTo 7L
        aggregate.plan.catalogPayloadHash shouldBeEqualTo "a".repeat(64)
        aggregate.treatments.map { it.key } shouldBeEqualTo listOf(
            PlannedTreatmentKey("laser", 1),
            PlannedTreatmentKey("laser", 2),
            PlannedTreatmentKey("laser", 3),
            PlannedTreatmentKey("care", 1),
        )
        aggregate.treatments.map { it.representativeTreatmentName } shouldBeEqualTo
            listOf("Laser", "Laser", "Laser", "After care")
        aggregate.dependencies.single().predecessor shouldBeEqualTo PlannedTreatmentKey("laser", 3)
        aggregate.dependencies.single().successor shouldBeEqualTo PlannedTreatmentKey("care", 1)
        aggregate.treatments.all { it.earliestStartAt == null && it.latestStartAt == null } shouldBeEqualTo true
    }

    @Test
    fun `preserves explicit pairwise occurrence mappings`() {
        val dependencies = (1..3).map { sequence ->
            CatalogBomDependency(
                predecessorBomItemId = "laser",
                predecessorSequenceNo = sequence,
                successorBomItemId = "care",
                successorSequenceNo = sequence,
                minimumIntervalDays = 1,
                preferredIntervalDays = 2,
                maximumIntervalDays = 3,
            )
        }
        val base = catalog()
        val pairwiseCatalog = base.copy(
            definition = base.definition.copy(
                items = base.definition.items.map { item ->
                    if (item.bomItemId == "care") item.copy(repeatCount = 3) else item
                },
                dependencies = dependencies,
            )
        )

        val aggregate = factory.create(pairwiseCatalog, input(BookingPreferenceSnapshot.NotProvided))

        aggregate.dependencies.map { it.predecessor to it.successor } shouldBeEqualTo (1..3).map { sequence ->
            PlannedTreatmentKey("laser", sequence) to PlannedTreatmentKey("care", sequence)
        }
    }

    private fun input(preference: BookingPreferenceSnapshot) = AppointmentPlanFactoryInput(
        sourcePurchaseAuthority = "commerce",
        sourcePurchaseId = "purchase-1",
        patientReferenceCiphertext = "ciphertext",
        patientReferenceKeyId = "key-1",
        patientReferenceFingerprint = "fingerprint",
        bookingPreference = preference,
    )

    private fun catalog() = ProductCatalogProjectionRecord(
        id = 77L,
        definition = ProductCatalogDefinition(
            tenantGroupId = 10L,
            clinicId = 21L,
            sourceAuthority = "product-catalog",
            productId = "laser-care",
            catalogVersion = 7L,
            productName = "Laser Care",
            schemaVersion = 1,
            sourceUpdatedAt = Instant.parse("2026-07-26T05:00:00Z"),
            items = listOf(
                CatalogBomItem(
                    bomItemId = "laser",
                    representativeTreatmentName = "Laser",
                    detailedTreatmentCodes = listOf("LASER"),
                    repeatCount = 3,
                    durationMinutes = 30,
                    minimumIntervalDays = 21,
                    preferredIntervalDays = 28,
                    maximumIntervalDays = 42,
                    practitionerQualifications = listOf("DERMATOLOGIST"),
                    equipmentTypes = listOf("LASER_A"),
                    roomTypes = listOf("PROCEDURE"),
                ),
                CatalogBomItem(
                    bomItemId = "care",
                    representativeTreatmentName = "After care",
                    detailedTreatmentCodes = listOf("CARE"),
                    repeatCount = 1,
                    durationMinutes = 20,
                    minimumIntervalDays = null,
                    preferredIntervalDays = null,
                    maximumIntervalDays = null,
                    practitionerQualifications = listOf("NURSE"),
                    equipmentTypes = emptyList(),
                    roomTypes = listOf("PROCEDURE"),
                ),
            ),
            dependencies = listOf(
                CatalogBomDependency(
                    predecessorBomItemId = "laser",
                    successorBomItemId = "care",
                    minimumIntervalDays = 1,
                    preferredIntervalDays = 2,
                    maximumIntervalDays = 3,
                )
            ),
            initialBookingRule = null,
        ),
        payloadHash = "a".repeat(64),
    )
}
