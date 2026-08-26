package io.bluetape4k.clinic.appointment.waitlist

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContainAll
import io.bluetape4k.clinic.appointment.model.tables.BookingBenefitGrants
import io.bluetape4k.clinic.appointment.model.tables.BookingRestrictions
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.DisruptionRecoveryCredits
import io.bluetape4k.clinic.appointment.model.tables.TenantGroups
import io.bluetape4k.clinic.appointment.model.tables.WaitlistCommandRecords
import io.bluetape4k.clinic.appointment.model.tables.WaitlistPolicyEvents
import io.bluetape4k.clinic.appointment.model.tables.WaitlistPolicyVersions
import io.bluetape4k.clinic.appointment.model.tables.WaitlistVacancyJobs
import io.bluetape4k.clinic.appointment.test.AbstractExposedTest
import io.bluetape4k.clinic.appointment.test.TestDB
import io.bluetape4k.clinic.appointment.test.withDb
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** Waitlist delivery V19 table의 additive schema 이름과 핵심 semantic column을 고정합니다. */
class WaitlistDeliveryTableSchemaTest : AbstractExposedTest() {

    @BeforeEach
    fun resetTables() {
        withDb(TestDB.H2) {
            SchemaUtils.createMissingTablesAndColumns(TenantGroups, Clinics)
            SchemaUtils.createMissingTablesAndColumns(*deliveryTables)
            deliveryTables.reversed().forEach { it.deleteAll() }
        }
    }

    @Test
    fun `V19 table names are fixed scheduling schema names`() {
        withDb(TestDB.H2) {
            SchemaUtils.createMissingTablesAndColumns(TenantGroups, Clinics)
            SchemaUtils.createMissingTablesAndColumns(*deliveryTables)

            WaitlistPolicyVersions.tableName shouldBeEqualTo "scheduling_waitlist_policy_versions"
            WaitlistPolicyEvents.tableName shouldBeEqualTo "scheduling_waitlist_policy_events"
            BookingRestrictions.tableName shouldBeEqualTo "scheduling_booking_restrictions"
            DisruptionRecoveryCredits.tableName shouldBeEqualTo "scheduling_disruption_recovery_credits"
            BookingBenefitGrants.tableName shouldBeEqualTo "scheduling_booking_benefit_grants"
            WaitlistVacancyJobs.tableName shouldBeEqualTo "scheduling_waitlist_vacancy_jobs"
            WaitlistCommandRecords.tableName shouldBeEqualTo "scheduling_waitlist_command_records"
        }
    }

    @Test
    fun `vacancy jobs retain generation active key and lease fencing columns`() {
        withDb(TestDB.H2) {
            SchemaUtils.createMissingTablesAndColumns(TenantGroups, Clinics)
            SchemaUtils.createMissingTablesAndColumns(WaitlistVacancyJobs)

            WaitlistVacancyJobs.columns.map { it.name }.toSet() shouldContainAll setOf(
                "vacancy_key",
                "vacancy_generation",
                "active_vacancy_key",
                "source_appointment_id",
                "source_transition_id",
                "resource_type",
                "resource_id",
                "capacity_units",
                "maximum_capacity",
                "treatment_type_id",
                "doctor_id",
                "attempt",
                "lease_owner",
                "lease_version",
                "lease_expires_at",
                "fence_epoch",
                "fence_sequence",
                "version",
            )
            WaitlistVacancyJobs.fenceEpoch.defaultValueFun?.invoke() shouldBeEqualTo 0L
            WaitlistVacancyJobs.fenceSequence.defaultValueFun?.invoke() shouldBeEqualTo 0L
            WaitlistVacancyJobs.hasUniqueIndex("uq_waitlist_vacancy_active") shouldBeEqualTo true
            WaitlistVacancyJobs.hasUniqueIndex(
                "tenant_group_id",
                "clinic_id",
                "vacancy_key",
                "vacancy_generation",
            ) shouldBeEqualTo true
            WaitlistVacancyJobs.hasUniqueIndex(
                "tenant_group_id",
                "clinic_id",
                "source_appointment_id",
                "source_transition_id",
            ) shouldBeEqualTo true
        }
    }

    @Test
    fun `fencing token compares epoch before sequence and rejects negative values`() {
        val previous = WaitlistFencingToken(epoch = 4L, sequence = 9L)

        WaitlistFencingToken(epoch = 4L, sequence = 10L)
            .isStrictlyGreaterThan(previous) shouldBeEqualTo true
        WaitlistFencingToken(epoch = 5L, sequence = 0L)
            .isStrictlyGreaterThan(previous) shouldBeEqualTo true
        WaitlistFencingToken(epoch = 4L, sequence = 9L)
            .isStrictlyGreaterThan(previous) shouldBeEqualTo false
        assertFailsWith<IllegalArgumentException> {
            WaitlistFencingToken(epoch = -1L, sequence = 0L)
        }
    }

    @Test
    fun `policy versions retain generation digest policy document and audit authority`() {
        withDb(TestDB.H2) {
            SchemaUtils.createMissingTablesAndColumns(TenantGroups, Clinics)
            SchemaUtils.createMissingTablesAndColumns(WaitlistPolicyVersions)

            WaitlistPolicyVersions.columns.map { it.name }.toSet() shouldContainAll setOf(
                "tenant_group_id",
                "clinic_id",
                "generation",
                "policy_version",
                "policy_digest",
                "urgency_weight",
                "recovery_weight",
                "benefit_weight",
                "reliability_weight",
                "waiting_age_weight",
                "slot_fit_weight",
                "canonical_policy_json",
                "status",
                "effective_from",
                "effective_until",
                "created_by",
                "created_at",
                "retired_by",
                "retired_at",
            )
            WaitlistPolicyVersions.hasUniqueIndex(
                "tenant_group_id",
                "clinic_id",
                "generation",
            ) shouldBeEqualTo true
            WaitlistPolicyVersions.hasCheckConstraint("ck_waitlist_policy_weights_bounded") shouldBeEqualTo true
        }
    }

    @Test
    fun `policy events retain actor correlation generation transition and reason columns`() {
        withDb(TestDB.H2) {
            SchemaUtils.createMissingTablesAndColumns(TenantGroups, Clinics)
            SchemaUtils.createMissingTablesAndColumns(WaitlistPolicyEvents)

            WaitlistPolicyEvents.columns.map { it.name }.toSet() shouldContainAll setOf(
                "tenant_group_id",
                "clinic_id",
                "policy_version",
                "event_type",
                "actor_ref",
                "correlation_id",
                "from_generation",
                "to_generation",
                "reason_code",
                "event_digest",
                "occurred_at",
            )
        }
    }

    @Test
    fun `booking adjustment tables retain actor reversal approval and cap columns`() {
        withDb(TestDB.H2) {
            SchemaUtils.createMissingTablesAndColumns(TenantGroups, Clinics)
            SchemaUtils.createMissingTablesAndColumns(
                BookingRestrictions,
                DisruptionRecoveryCredits,
                BookingBenefitGrants,
            )

            BookingRestrictions.columns.map { it.name }.toSet() shouldContainAll setOf(
                "member_id",
                "evidence_digest",
                "policy_version",
                "restriction_mode",
                "actor_ref",
                "reason_code",
                "starts_at",
                "expires_at",
                "released_by",
                "released_at",
                "reversal_version",
            )
            DisruptionRecoveryCredits.columns.map { it.name }.toSet() shouldContainAll setOf(
                "source_appointment_id",
                "credit_digest",
                "priority_boost",
                "granted_by",
                "reversed_by",
                "reversed_at",
                "reversal_version",
            )
            DisruptionRecoveryCredits.hasUniqueIndex("uq_disruption_recovery_credit") shouldBeEqualTo true
            BookingBenefitGrants.columns.map { it.name }.toSet() shouldContainAll setOf(
                "approval_reference",
                "benefit_cap",
                "grant_digest",
                "policy_version",
                "revoked_by",
                "revoked_at",
                "revoke_version",
            )
            BookingBenefitGrants.hasUniqueIndex("uq_booking_benefit_grant") shouldBeEqualTo true
        }
    }

    @Test
    fun `command records keep scoped hmac idempotency columns`() {
        withDb(TestDB.H2) {
            SchemaUtils.createMissingTablesAndColumns(TenantGroups, Clinics)
            SchemaUtils.createMissingTablesAndColumns(WaitlistCommandRecords)

            WaitlistCommandRecords.columns.map { it.name }.toSet() shouldContainAll setOf(
                "tenant_group_id",
                "clinic_id",
                "command_type",
                "key_digest",
                "request_digest",
                "status",
                "expires_at",
            )
            WaitlistCommandRecords.hasUniqueIndex("uq_waitlist_command_idempotency") shouldBeEqualTo true
        }
    }

    private companion object {
        private val deliveryTables = arrayOf(
            WaitlistPolicyVersions,
            WaitlistPolicyEvents,
            BookingRestrictions,
            DisruptionRecoveryCredits,
            BookingBenefitGrants,
            WaitlistVacancyJobs,
            WaitlistCommandRecords,
        )

        private fun Table.hasUniqueIndex(vararg columnNames: String): Boolean =
            indices.any { index ->
                index.unique && index.columns.map { it.name } == columnNames.toList()
            }

        private fun Table.hasUniqueIndex(indexName: String): Boolean =
            indices.any { index ->
                index.unique && index.customName == indexName
            }

        private fun Table.hasCheckConstraint(name: String): Boolean =
            checkConstraints().any { it.checkName.equals(name, ignoreCase = true) }
    }
}
