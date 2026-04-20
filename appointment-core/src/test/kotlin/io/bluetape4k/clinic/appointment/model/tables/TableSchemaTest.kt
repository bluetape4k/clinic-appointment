package io.bluetape4k.clinic.appointment.model.tables

import io.bluetape4k.clinic.appointment.test.AbstractExposedTest
import io.bluetape4k.clinic.appointment.test.TestDB
import io.bluetape4k.clinic.appointment.test.withTables
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class TableSchemaTest : AbstractExposedTest() {

    private val allTables = arrayOf(
        Holidays,
        Clinics,
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
        AppointmentNotes,
        AppointmentStateHistory,
        RescheduleCandidates
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
}
