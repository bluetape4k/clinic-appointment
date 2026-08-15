package io.bluetape4k.clinic.appointment.model.tables

import io.bluetape4k.assertions.shouldContainAll
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.clinic.appointment.test.AbstractExposedTest
import io.bluetape4k.clinic.appointment.test.TestDB
import io.bluetape4k.clinic.appointment.test.withDb
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.junit.jupiter.api.Test

/** Issue #305 V28 cancellation snapshot columns/index 계약을 고정한다. */
class AppointmentCancellationDetailsSchemaTest : AbstractExposedTest() {

    @Test
    fun `cancellation detail retains nullable status and patient scope snapshot`() {
        withDb(TestDB.H2) {
            SchemaUtils.createMissingTablesAndColumns(
                TenantGroups,
                Clinics,
                Appointments,
                AppointmentCommitments,
                AppointmentProposals,
                AppointmentCancellationDetails,
            )

            AppointmentCancellationDetails.columns.map { it.name }.toSet() shouldContainAll setOf(
                "from_commitment_status",
                "patient_scope_fingerprint",
            )
            AppointmentCancellationDetails.indices.any { index ->
                index.columns.map { it.name } ==
                    listOf("tenant_group_id", "patient_scope_fingerprint", "occurred_at", "id")
            }.shouldBeTrue()
        }
    }
}
