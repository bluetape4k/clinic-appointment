package io.bluetape4k.clinic.appointment.api.config

import io.bluetape4k.clinic.appointment.event.AppointmentEventLogs
import io.bluetape4k.clinic.appointment.model.tables.AppointmentNotes
import io.bluetape4k.clinic.appointment.model.tables.AppointmentStateHistory
import io.bluetape4k.clinic.appointment.model.tables.Appointments
import io.bluetape4k.clinic.appointment.model.tables.BreakTimes
import io.bluetape4k.clinic.appointment.model.tables.ClinicClosures
import io.bluetape4k.clinic.appointment.model.tables.ClinicDefaultBreakTimes
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.ConsultationTopics
import io.bluetape4k.clinic.appointment.model.tables.DoctorAbsences
import io.bluetape4k.clinic.appointment.model.tables.DoctorSchedules
import io.bluetape4k.clinic.appointment.model.tables.Doctors
import io.bluetape4k.clinic.appointment.model.tables.EquipmentUnavailabilities
import io.bluetape4k.clinic.appointment.model.tables.EquipmentUnavailabilityExceptions
import io.bluetape4k.clinic.appointment.model.tables.Equipments
import io.bluetape4k.clinic.appointment.model.tables.Holidays
import io.bluetape4k.clinic.appointment.model.tables.OperatingHoursTable
import io.bluetape4k.clinic.appointment.model.tables.RescheduleCandidates
import io.bluetape4k.clinic.appointment.model.tables.TreatmentEquipments
import io.bluetape4k.clinic.appointment.model.tables.TreatmentTypes
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.core.annotation.Order

/**
 * 데이터베이스 스키마 초기화 설정.
 *
 * dev/test 프로파일에서만 활성화됩니다.
 * Exposed SchemaUtils로 in-memory 스키마를 생성합니다.
 *
 * prod에서는 Flyway SQL 마이그레이션이 스키마를 관리합니다.
 */
@Configuration(proxyBeanMethods = false)
@Profile("dev", "test")
@ConditionalOnProperty(name = ["spring.flyway.enabled"], havingValue = "false", matchIfMissing = true)
class SchemaInitConfig {
    /**
     * Exposed SchemaUtils로 스키마 생성 (dev/test 전용).
     */
    @Bean
    @Order(1)
    fun schemaInitializer(): ApplicationRunner =
        ApplicationRunner {
            transaction {
                SchemaUtils.create(
                    Clinics,
                    OperatingHoursTable,
                    ClinicDefaultBreakTimes,
                    BreakTimes,
                    ClinicClosures,
                    Doctors,
                    DoctorSchedules,
                    DoctorAbsences,
                    TreatmentTypes,
                    Equipments,
                    TreatmentEquipments,
                    EquipmentUnavailabilities,
                    EquipmentUnavailabilityExceptions,
                    ConsultationTopics,
                    Holidays,
                    Appointments,
                    AppointmentNotes,
                    AppointmentStateHistory,
                    RescheduleCandidates,
                    AppointmentEventLogs
                )
            }
        }
}
