package io.bluetape4k.clinic.appointment.repository

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.clinic.appointment.model.dto.AppointmentPlanRevisionAggregateRecord
import io.bluetape4k.clinic.appointment.model.dto.PlanRevisionDependencyRecord
import io.bluetape4k.clinic.appointment.model.dto.PlanRevisionGroupingConstraintRecord
import io.bluetape4k.clinic.appointment.model.dto.PlanRevisionTreatmentRecord
import io.bluetape4k.clinic.appointment.model.plan.AppointmentPlanRevision
import io.bluetape4k.clinic.appointment.model.plan.ExecutionDependencyType
import io.bluetape4k.clinic.appointment.model.plan.PlanTreatmentStatus
import io.bluetape4k.clinic.appointment.model.plan.VisitGroupingType
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.junit.jupiter.api.Test

class AppointmentPlanRevisionRepositoryTest {

    private val repository = AppointmentPlanRevisionRepository()

    @Test
    fun `revision과 child를 저장하고 expected active revision CAS로 활성화한다`() {
        withCommitmentTables { seed ->
            val first = repository.append(aggregate(seed.planId, 1L, active = true, "v1"))
            val second = repository.append(aggregate(seed.planId, 2L, active = false, "v2"))

            repository.activate(seed.planId, first.revision.id, second.revision.id).shouldBeTrue()
            repository.activate(seed.planId, first.revision.id, second.revision.id).shouldBeFalse()

            val active = repository.findActive(seed.planId).shouldNotBeNull()
            active.revision.id shouldBeEqualTo second.revision.id
            active.treatments.size shouldBeEqualTo 2
            active.treatments.map { it.productVersionId }.toSet() shouldBeEqualTo setOf("v2")
            active.treatments.first().treatmentMinutes shouldBeEqualTo 30
            active.treatments.first().equipmentTypes shouldBeEqualTo listOf("LASER")
            active.dependencies.single().type shouldBeEqualTo ExecutionDependencyType.BLOCKING
            active.dependencies.single().minimumIntervalDays shouldBeEqualTo 7
            active.groupingConstraints.single().type shouldBeEqualTo VisitGroupingType.MUST_SEPARATE_VISIT
            active.groupingConstraints.single().firstTreatmentKey shouldBeEqualTo "care"
            active.groupingConstraints.single().secondTreatmentKey shouldBeEqualTo "follow-up"
        }
    }

    @Test
    fun `같은 plan revision 번호는 중복될 수 없다`() {
        withCommitmentTables { seed ->
            repository.append(aggregate(seed.planId, 1L, active = true, "v1"))
            assertFailsWith<ExposedSQLException> {
                repository.append(aggregate(seed.planId, 1L, active = false, "v2"))
            }
        }
    }

    private fun aggregate(
        planId: Long,
        revision: Long,
        active: Boolean,
        productVersionId: String,
    ) = AppointmentPlanRevisionAggregateRecord(
        revision = AppointmentPlanRevision(
            planId = planId,
            revision = revision,
            productVersionId = productVersionId,
            snapshotHash = revision.toString().repeat(64).take(64),
            active = active,
        ),
        treatments = listOf(
            PlanRevisionTreatmentRecord(
                treatmentKey = "care",
                componentProductId = "component",
                componentProductVersionId = productVersionId,
                productVersionId = productVersionId,
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
                componentProductVersionId = productVersionId,
                productVersionId = productVersionId,
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
        dependencies = listOf(
            PlanRevisionDependencyRecord(
                predecessorTreatmentKey = "care",
                successorTreatmentKey = "follow-up",
                type = ExecutionDependencyType.BLOCKING,
                minimumIntervalDays = 7,
                preferredIntervalDays = 14,
                maximumIntervalDays = 21,
            ),
        ),
        groupingConstraints = listOf(
            PlanRevisionGroupingConstraintRecord(
                firstTreatmentKey = "follow-up",
                secondTreatmentKey = "care",
                type = VisitGroupingType.MUST_SEPARATE_VISIT,
            ),
        ),
    )
}
