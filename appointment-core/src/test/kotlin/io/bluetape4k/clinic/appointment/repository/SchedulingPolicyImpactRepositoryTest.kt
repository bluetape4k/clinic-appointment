package io.bluetape4k.clinic.appointment.repository

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.clinic.appointment.model.dto.PolicyScopeRef
import io.bluetape4k.clinic.appointment.model.catalog.CatalogProjectionStatus
import io.bluetape4k.clinic.appointment.model.plan.AppointmentPlanStatus
import io.bluetape4k.clinic.appointment.model.plan.PlannedTreatmentStatus
import io.bluetape4k.clinic.appointment.model.policy.PolicyScope
import io.bluetape4k.clinic.appointment.model.tables.*
import io.bluetape4k.clinic.appointment.statemachine.AppointmentState
import io.bluetape4k.clinic.appointment.test.AbstractExposedTest
import io.bluetape4k.clinic.appointment.test.TestDB
import io.bluetape4k.clinic.appointment.test.withTables
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * 정책 영향도 조회가 테넌트·병원 경계를 넘지 않고 항상 제한된 keyset page만 반환하는지 검증한다.
 *
 * 이 테스트는 appointment/plan payload를 읽는 API가 생기지 않도록 반환 타입을 key projection으로
 * 고정한다. 존재하지 않거나 다른 테넌트에 속한 병원은 빈 page로 처리하고, 설정된 최대 5,000행을
 * 넘는 요청은 SQL 실행 전에 거부해야 한다.
 */
class SchedulingPolicyImpactRepositoryTest : AbstractExposedTest() {

    private val repository = SchedulingPolicyImpactRepository()

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `missing scoped clinic returns an empty bounded page without probing work tables`(testDB: TestDB) {
        withImpactTables(testDB) {
            val page = repository.scanFutureWork(
                scope = PolicyScopeRef(
                    tenantGroupId = 77L,
                    scope = PolicyScope.CLINIC_OVERRIDE,
                    clinicId = 41L,
                ),
                horizonFrom = Instant.parse("2026-07-27T00:00:00Z"),
                horizonUntil = Instant.parse("2026-08-27T00:00:00Z"),
                after = null,
                limit = 5_000,
            )

            page.items shouldBeEqualTo emptyList()
            page.nextCursor.shouldBeNull()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `tenant scope without clinics is empty while invalid horizon and page size are rejected`(testDB: TestDB) {
        withImpactTables(testDB) {
            val clinicScope = PolicyScopeRef(
                tenantGroupId = 77L,
                scope = PolicyScope.CLINIC_OVERRIDE,
                clinicId = 41L,
            )
            val tenantPage = repository.scanFutureWork(
                scope = PolicyScopeRef(77L, PolicyScope.TENANT_DEFAULT),
                horizonFrom = Instant.parse("2026-07-27T00:00:00Z"),
                horizonUntil = Instant.parse("2026-08-27T00:00:00Z"),
                after = null,
                limit = 100,
            )
            tenantPage.items shouldBeEqualTo emptyList()
            tenantPage.nextCursor.shouldBeNull()

            assertFailsWith<IllegalArgumentException> {
                repository.scanFutureWork(
                    scope = clinicScope,
                    horizonFrom = Instant.parse("2026-08-27T00:00:00Z"),
                    horizonUntil = Instant.parse("2026-07-27T00:00:00Z"),
                    after = null,
                    limit = 100,
                )
            }
            assertFailsWith<IllegalArgumentException> {
                repository.scanFutureWork(
                    scope = clinicScope,
                    horizonFrom = Instant.parse("2026-07-27T00:00:00Z"),
                    horizonUntil = Instant.parse("2026-08-27T00:00:00Z"),
                    after = null,
                    limit = 5_001,
                )
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `sparse tenant scan checkpoints after a bounded clinic batch`(testDB: TestDB) {
        withImpactTables(testDB) {
            val clinicIds = (1..101).map { sequence ->
                Clinics.insertAndGetId {
                    it[tenantGroupId] = EntityID(1L, TenantGroups)
                    it[name] = "Sparse Clinic $sequence"
                    it[timezone] = "Asia/Seoul"
                }.value
            }
            val scope = PolicyScopeRef(1L, PolicyScope.TENANT_DEFAULT)
            val horizonFrom = Instant.parse("2026-07-27T00:00:00Z")
            val horizonUntil = Instant.parse("2026-08-27T00:00:00Z")

            val first = repository.scanFutureWork(
                scope = scope,
                horizonFrom = horizonFrom,
                horizonUntil = horizonUntil,
                after = null,
                limit = 5_000,
            )

            first.items shouldBeEqualTo emptyList()
            val boundary = first.nextCursor.shouldNotBeNull()
            boundary.clinicId shouldBeEqualTo clinicIds[99]
            boundary.isClinicBoundary shouldBeEqualTo true

            val second = repository.scanFutureWork(
                scope = scope,
                horizonFrom = horizonFrom,
                horizonUntil = horizonUntil,
                after = boundary,
                limit = 5_000,
            )

            second.items shouldBeEqualTo emptyList()
            second.nextCursor.shouldBeNull()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `appointment and active plan treatment partitions resume with an exclusive durable cursor`(testDB: TestDB) {
        withImpactTables(testDB) {
            val clinicId = Clinics.insertAndGetId {
                it[tenantGroupId] = EntityID(1L, TenantGroups)
                it[name] = "Seoul Clinic"
                it[timezone] = "Asia/Seoul"
            }
            val doctorId = Doctors.insertAndGetId {
                it[Doctors.clinicId] = clinicId
                it[name] = "Dr. Preview"
            }
            val treatmentTypeId = TreatmentTypes.insertAndGetId {
                it[TreatmentTypes.clinicId] = clinicId
                it[name] = "Preview Treatment"
                it[defaultDurationMinutes] = 30
            }
            val appointmentId = Appointments.insertAndGetId {
                it[Appointments.clinicId] = clinicId
                it[Appointments.doctorId] = doctorId
                it[Appointments.treatmentTypeId] = treatmentTypeId
                it[patientName] = "bounded-projection-only"
                it[appointmentDate] = LocalDate.of(2026, 7, 28)
                it[startTime] = LocalTime.of(9, 0)
                it[endTime] = LocalTime.of(9, 30)
                it[status] = AppointmentState.CONFIRMED
            }
            val catalogId = ProductCatalogProjections.insertAndGetId {
                it[tenantGroupId] = EntityID(1L, TenantGroups)
                it[ProductCatalogProjections.clinicId] = clinicId
                it[sourceAuthority] = "catalog"
                it[productId] = "product-preview"
                it[catalogVersion] = 1L
                it[productName] = "Preview Product"
                it[schemaVersion] = 1
                it[sourceUpdatedAt] = Instant.parse("2026-07-26T00:00:00Z")
                it[status] = CatalogProjectionStatus.ACTIVE
                it[payloadHash] = "a".repeat(64)
            }
            val planId = AppointmentPlans.insertAndGetId {
                it[tenantGroupId] = EntityID(1L, TenantGroups)
                it[AppointmentPlans.clinicId] = clinicId
                it[catalogProjectionId] = catalogId
                it[sourcePurchaseAuthority] = "commerce"
                it[sourcePurchaseId] = "purchase-preview"
                it[patientReferenceCiphertext] = "ciphertext"
                it[patientReferenceKeyId] = "key-preview"
                it[patientReferenceFingerprint] = "f".repeat(64)
                it[catalogSourceAuthority] = "catalog"
                it[productId] = "product-preview"
                it[catalogVersion] = 1L
                it[catalogPayloadHash] = "a".repeat(64)
                it[productName] = "Preview Product"
                it[bookingPreferenceType] = "NONE"
                it[bookingPreferencePayload] = "{}"
                it[status] = AppointmentPlanStatus.ACTIVE
            }
            val treatmentId = PlannedTreatments.insertAndGetId {
                it[PlannedTreatments.planId] = planId
                it[bomItemId] = "care"
                it[sequenceNo] = 1
                it[bomOrder] = 0
                it[representativeTreatmentName] = "Care"
                it[detailedTreatmentCodesJson] = "[\"care\"]"
                it[durationMinutes] = 30
                it[practitionerQualificationsJson] = "[]"
                it[equipmentTypesJson] = "[]"
                it[roomTypesJson] = "[]"
                it[earliestStartAt] = Instant.parse("2026-07-29T00:00:00Z")
                it[status] = PlannedTreatmentStatus.PLANNED
            }
            val cancelledPlanId = AppointmentPlans.insertAndGetId {
                it[tenantGroupId] = EntityID(1L, TenantGroups)
                it[AppointmentPlans.clinicId] = clinicId
                it[catalogProjectionId] = catalogId
                it[sourcePurchaseAuthority] = "commerce"
                it[sourcePurchaseId] = "purchase-cancelled-preview"
                it[patientReferenceCiphertext] = "cancelled-ciphertext"
                it[patientReferenceKeyId] = "key-cancelled-preview"
                it[patientReferenceFingerprint] = "c".repeat(64)
                it[catalogSourceAuthority] = "catalog"
                it[productId] = "product-preview"
                it[catalogVersion] = 1L
                it[catalogPayloadHash] = "a".repeat(64)
                it[productName] = "Cancelled Preview Product"
                it[bookingPreferenceType] = "NONE"
                it[bookingPreferencePayload] = "{}"
                it[status] = AppointmentPlanStatus.CANCELLED
            }
            PlannedTreatments.insertAndGetId {
                it[PlannedTreatments.planId] = cancelledPlanId
                it[bomItemId] = "cancelled-care"
                it[sequenceNo] = 1
                it[bomOrder] = 0
                it[representativeTreatmentName] = "Cancelled Care"
                it[detailedTreatmentCodesJson] = "[\"cancelled-care\"]"
                it[durationMinutes] = 30
                it[practitionerQualificationsJson] = "[]"
                it[equipmentTypesJson] = "[]"
                it[roomTypesJson] = "[]"
                it[earliestStartAt] = Instant.parse("2026-07-28T12:00:00Z")
                it[status] = PlannedTreatmentStatus.PLANNED
            }
            val scope = PolicyScopeRef(1L, PolicyScope.CLINIC_OVERRIDE, clinicId.value)
            val horizonFrom = Instant.parse("2026-07-27T00:00:00Z")
            val horizonUntil = Instant.parse("2026-07-30T00:00:00Z")

            val first = repository.scanFutureWork(scope, horizonFrom, horizonUntil, after = null, limit = 1)
            first.items.single() shouldBeEqualTo PolicyImpactKey(
                clinicId = clinicId.value,
                scheduledAt = Instant.parse("2026-07-28T00:00:00Z"),
                aggregateType = PolicyImpactAggregateType.APPOINTMENT,
                aggregateId = appointmentId.value.toString(),
            )

            val second = repository.scanFutureWork(
                scope,
                horizonFrom,
                horizonUntil,
                after = first.nextCursor,
                limit = 1,
            )
            second.items.single() shouldBeEqualTo PolicyImpactKey(
                clinicId = clinicId.value,
                scheduledAt = Instant.parse("2026-07-29T00:00:00Z"),
                aggregateType = PolicyImpactAggregateType.PLANNED_TREATMENT,
                aggregateId = treatmentId.value.toString(),
            )

            val exhausted = repository.scanFutureWork(
                scope,
                horizonFrom,
                horizonUntil,
                after = second.nextCursor,
                limit = 1,
            )
            exhausted.items shouldBeEqualTo emptyList()
            exhausted.nextCursor.shouldBeNull()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `tenant preview resumes across clinics without flattening different timezones`(testDB: TestDB) {
        withImpactTables(testDB) {
            val firstClinicId = Clinics.insertAndGetId {
                it[tenantGroupId] = EntityID(1L, TenantGroups)
                it[name] = "Los Angeles Clinic"
                it[timezone] = "America/Los_Angeles"
            }
            val secondClinicId = Clinics.insertAndGetId {
                it[tenantGroupId] = EntityID(1L, TenantGroups)
                it[name] = "Seoul Clinic"
                it[timezone] = "Asia/Seoul"
            }

            fun insertAppointment(
                clinicId: EntityID<Long>,
                suffix: String,
            ): Long {
                val doctorId = Doctors.insertAndGetId {
                    it[Doctors.clinicId] = clinicId
                    it[name] = "Dr. $suffix"
                }
                val treatmentTypeId = TreatmentTypes.insertAndGetId {
                    it[TreatmentTypes.clinicId] = clinicId
                    it[name] = "Treatment $suffix"
                    it[defaultDurationMinutes] = 30
                }
                return Appointments.insertAndGetId {
                    it[Appointments.clinicId] = clinicId
                    it[Appointments.doctorId] = doctorId
                    it[Appointments.treatmentTypeId] = treatmentTypeId
                    it[patientName] = "tenant-preview-$suffix"
                    it[appointmentDate] = LocalDate.of(2026, 7, 28)
                    it[startTime] = LocalTime.of(9, 0)
                    it[endTime] = LocalTime.of(9, 30)
                    it[status] = AppointmentState.CONFIRMED
                }.value
            }

            val firstAppointmentId = insertAppointment(firstClinicId, "la")
            val secondAppointmentId = insertAppointment(secondClinicId, "seoul")
            val scope = PolicyScopeRef(1L, PolicyScope.TENANT_DEFAULT)
            val horizonFrom = Instant.parse("2026-07-27T00:00:00Z")
            val horizonUntil = Instant.parse("2026-07-29T00:00:00Z")

            val first = repository.scanFutureWork(scope, horizonFrom, horizonUntil, after = null, limit = 1)
            first.items.single() shouldBeEqualTo PolicyImpactKey(
                clinicId = firstClinicId.value,
                scheduledAt = Instant.parse("2026-07-28T16:00:00Z"),
                aggregateType = PolicyImpactAggregateType.APPOINTMENT,
                aggregateId = firstAppointmentId.toString(),
            )

            val second = repository.scanFutureWork(scope, horizonFrom, horizonUntil, first.nextCursor, limit = 1)
            second.items.single() shouldBeEqualTo PolicyImpactKey(
                clinicId = secondClinicId.value,
                scheduledAt = Instant.parse("2026-07-28T00:00:00Z"),
                aggregateType = PolicyImpactAggregateType.APPOINTMENT,
                aggregateId = secondAppointmentId.toString(),
            )

            val exhausted = repository.scanFutureWork(scope, horizonFrom, horizonUntil, second.nextCursor, limit = 1)
            exhausted.items shouldBeEqualTo emptyList()
            exhausted.nextCursor.shouldBeNull()
        }
    }

    /**
     * 공유 dialect DB의 모든 FK 자식까지 함께 소유해 test 종료 시 schema를 완전히 회수한다.
     *
     * 영향도 조회 자체는 appointment/plan projection만 읽지만, 일부 선행 테스트가 생성한
     * clinic/tenant FK 자식 schema가 남아 있으면 부분 table 집합의 DDL drop이 거절된다.
     * 따라서 repository 테스트는 전체 schema graph를 명시해 다음 테스트에 DDL 의존성을
     * 남기지 않는다.
     */
    private fun withImpactTables(
        testDB: TestDB,
        statement: org.jetbrains.exposed.v1.jdbc.JdbcTransaction.() -> Unit,
    ) = withTables(testDB, *ALL_SCHEMA_TABLES) { statement() }

    private companion object {
        val ALL_SCHEMA_TABLES = arrayOf(
            TenantGroups,
            Clinics,
            Holidays,
            ClinicDefaultBreakTimes,
            OperatingHoursTable,
            BreakTimes,
            ClinicClosures,
            Doctors,
            DoctorSchedules,
            DoctorAbsences,
            Equipments,
            EquipmentUnavailabilities,
            EquipmentUnavailabilityExceptions,
            TreatmentTypes,
            TreatmentEquipments,
            ConsultationTopics,
            Appointments,
            AppointmentIdempotencies,
            AppointmentNotes,
            AppointmentStateHistory,
            RescheduleCandidates,
            ProductCatalogProjections,
            ProductCatalogBomItems,
            ProductCatalogBomDependencies,
            AppointmentPlans,
            PlannedTreatments,
            TreatmentDependencies,
            SchedulingPolicyDefinitions,
            SchedulingPolicyApprovals,
            SchedulingPolicyScopeHeads,
            EffectiveSchedulingPolicySnapshots,
            SchedulingPolicyActivationCommands,
            SchedulingPolicyPreviewJobs,
        )
    }
}
