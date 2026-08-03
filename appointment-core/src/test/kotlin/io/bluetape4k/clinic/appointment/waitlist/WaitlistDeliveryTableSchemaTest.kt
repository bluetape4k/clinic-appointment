package io.bluetape4k.clinic.appointment.waitlist

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
                "vacancy_generation",
                "active_vacancy_key",
                "lease_owner",
                "lease_expires_at",
            )
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
    }
}
