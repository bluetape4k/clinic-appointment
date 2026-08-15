package io.bluetape4k.clinic.appointment.model.tables

import io.bluetape4k.assertions.shouldContainAll
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.clinic.appointment.test.AbstractExposedTest
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test

/** Issue #305 V28 cancellation snapshot columns/index 계약을 고정한다. */
class AppointmentCancellationDetailsSchemaTest : AbstractExposedTest() {

    @Test
    fun `cancellation detail retains nullable status and patient scope snapshot`() {
        // 이 schema-only 검사는 공유 regular-v2 H2를 오염시키지 않도록 독립 DB를 사용한다.
        val database = Database.connect(
            "jdbc:h2:mem:appointment-cancellation-details-schema-${System.nanoTime()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver",
        )
        transaction(database) {
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
