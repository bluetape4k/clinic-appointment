package io.bluetape4k.clinic.appointment.api.service

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.clinic.appointment.model.tables.AppointmentCancellationDetails
import io.bluetape4k.clinic.appointment.model.tables.AppointmentCommitments
import io.bluetape4k.clinic.appointment.model.tables.AppointmentItems
import io.bluetape4k.clinic.appointment.model.tables.AppointmentPlanRevisions
import io.bluetape4k.clinic.appointment.model.tables.AppointmentPlans
import io.bluetape4k.clinic.appointment.model.tables.AppointmentProposals
import io.bluetape4k.clinic.appointment.model.tables.Appointments
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.Doctors
import io.bluetape4k.clinic.appointment.model.tables.PlanRevisionTreatments
import io.bluetape4k.clinic.appointment.model.tables.ProductCatalogProjections
import io.bluetape4k.clinic.appointment.model.tables.TenantGroups
import io.bluetape4k.clinic.appointment.model.tables.TreatmentTypes
import io.mockk.mockk
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test

class PatientHistoryReadinessTest {
    @Test
    fun `writer version fence keeps endpoint unavailable before every replica is v2`() {
        val readiness = DatabasePatientHistoryReadiness(
            database = mockk(),
            registry = object : PatientHistoryTokenRegistry {
                override fun get(key: String): PatientHistoryTokenEntry? = null
                override fun putIfAbsent(key: String, entry: PatientHistoryTokenEntry): PatientHistoryTokenEntry = entry
                override fun isReady(): Boolean = true
            },
            writerVersionProvider = PatientHistoryWriterVersionProvider { 1 },
        )

        val failure = assertFailsWith<PatientHistoryApiException> { readiness.requireReady() }

        failure.error shouldBeEqualTo PatientHistoryApiError.UNAVAILABLE
    }

    @Test
    fun `sixty second probe caches a ready result and fail closes after registry becomes unavailable`() {
        val database = Database.connect(
            "jdbc:h2:mem:patient_history_readiness_${System.nanoTime()};DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
            driver = "org.h2.Driver",
        )
        transaction(database) {
            SchemaUtils.createMissingTablesAndColumns(
                TenantGroups,
                Clinics,
                Doctors,
                TreatmentTypes,
                Appointments,
                AppointmentCommitments,
                AppointmentProposals,
                ProductCatalogProjections,
                AppointmentPlans,
                AppointmentPlanRevisions,
                PlanRevisionTreatments,
                AppointmentItems,
                AppointmentCancellationDetails,
            )
        }

        var now = 0L
        var registryReady = true
        val readiness = DatabasePatientHistoryReadiness(
            database = database,
            registry = object : PatientHistoryTokenRegistry {
                override fun get(key: String): PatientHistoryTokenEntry? = null
                override fun putIfAbsent(
                    key: String,
                    entry: PatientHistoryTokenEntry,
                ): PatientHistoryTokenEntry = entry

                override fun isReady(): Boolean = registryReady
            },
            writerVersionProvider = PatientHistoryWriterVersionProvider { 2 },
            nanoTime = { now },
        )

        readiness.requireReady()
        registryReady = false
        readiness.requireReady()

        now += 60_000_000_001L
        val failure = assertFailsWith<PatientHistoryApiException> { readiness.requireReady() }
        failure.error shouldBeEqualTo PatientHistoryApiError.UNAVAILABLE

        registryReady = true
        readiness.scheduledProbe()
        registryReady.shouldBeTrue()
        readiness.requireReady()
    }
}
