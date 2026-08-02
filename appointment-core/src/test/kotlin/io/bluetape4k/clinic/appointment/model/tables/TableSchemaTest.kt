package io.bluetape4k.clinic.appointment.model.tables

import io.bluetape4k.assertions.shouldContainAll
import io.bluetape4k.clinic.appointment.test.AbstractExposedTest
import io.bluetape4k.clinic.appointment.test.TestDB
import io.bluetape4k.clinic.appointment.test.withTables
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class TableSchemaTest : AbstractExposedTest() {

    private val allTables = arrayOf(
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
        BookingReliabilityEvents,
        BookingReliabilityDecisions,
        BookingReliabilityOverrides,
        BookingReliabilityReevaluationJobs,
        ProfileReevaluationHeads,
        ProfileReevaluationJobs,
        ProfileReevaluationOutcomes,
        WaitlistEntries,
        WaitlistOffers,
        WaitlistCapacityHolds,
        WaitlistOfferEvents,
    )

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `should create all tables without errors`(testDB: TestDB) {
        withTables(testDB, *allTables) {
            // withTables 가 테이블 생성을 처리
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `should drop and recreate all tables`(testDB: TestDB) {
        withTables(testDB, *allTables) {
            SchemaUtils.drop(*allTables)
            SchemaUtils.create(*allTables)
        }
    }

    @org.junit.jupiter.api.Test
    fun `waitlist tables declare the same safety checks as V18`() {
        val constraints = listOf(
            WaitlistEntries to setOf(
                "ck_waitlist_entry_status",
                "ck_waitlist_entry_date_range",
                "ck_waitlist_entry_time_range",
                "ck_waitlist_entry_member_opaque",
            ),
            WaitlistOffers to setOf(
                "ck_waitlist_offer_status",
                "ck_waitlist_offer_time_range",
                "ck_waitlist_offer_expiry",
                "ck_waitlist_offer_units",
                "ck_waitlist_offer_policy_hash",
                "ck_waitlist_offer_evaluation_digest",
            ),
            WaitlistCapacityHolds to setOf(
                "ck_waitlist_capacity_hold_status",
                "ck_waitlist_capacity_hold_time_range",
                "ck_waitlist_capacity_hold_units",
            ),
            WaitlistOfferEvents to setOf(
                "ck_waitlist_offer_event_version",
                "ck_waitlist_offer_event_actor_ref",
                "ck_waitlist_offer_event_correlation_id",
            ),
        )

        withTables(
            TestDB.H2,
            Clinics,
            Doctors,
            TreatmentTypes,
            ResourceCapacityBuckets,
            WaitlistEntries,
            WaitlistOffers,
            WaitlistCapacityHolds,
            WaitlistOfferEvents,
        ) {
            constraints.forEach { (table, expected) ->
                table.checkConstraints().map { it.checkName }.toSet() shouldContainAll expected
            }
        }
    }
}
