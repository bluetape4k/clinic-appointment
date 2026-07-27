package io.bluetape4k.clinic.appointment.api.config

import io.bluetape4k.clinic.appointment.event.AppointmentEventLogs
import io.bluetape4k.clinic.appointment.event.integration.SchedulingInboxEvents
import io.bluetape4k.clinic.appointment.event.integration.SchedulingOutboxEvents
import io.bluetape4k.clinic.appointment.event.integration.SchedulingQuarantineAuditEvents
import io.bluetape4k.clinic.appointment.event.integration.SchedulingQuarantineEvents
import io.bluetape4k.clinic.appointment.event.integration.UntrustedSchedulingEventRejections
import io.bluetape4k.clinic.appointment.model.tables.AppointmentIdempotencies
import io.bluetape4k.clinic.appointment.model.tables.AppointmentNotes
import io.bluetape4k.clinic.appointment.model.tables.AppointmentStateHistory
import io.bluetape4k.clinic.appointment.model.tables.Appointments
import io.bluetape4k.clinic.appointment.model.tables.AppointmentPlans
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
import io.bluetape4k.clinic.appointment.model.tables.EffectiveSchedulingPolicySnapshots
import io.bluetape4k.clinic.appointment.model.tables.Holidays
import io.bluetape4k.clinic.appointment.model.tables.OperatingHoursTable
import io.bluetape4k.clinic.appointment.model.tables.PlannedTreatments
import io.bluetape4k.clinic.appointment.model.tables.ProductCatalogBomDependencies
import io.bluetape4k.clinic.appointment.model.tables.ProductCatalogBomItems
import io.bluetape4k.clinic.appointment.model.tables.ProductCatalogProjections
import io.bluetape4k.clinic.appointment.model.tables.RescheduleCandidates
import io.bluetape4k.clinic.appointment.model.tables.SchedulingPolicyActivationCommands
import io.bluetape4k.clinic.appointment.model.tables.SchedulingPolicyApprovals
import io.bluetape4k.clinic.appointment.model.tables.SchedulingPolicyDefinitions
import io.bluetape4k.clinic.appointment.model.tables.SchedulingPolicyPreviewJobs
import io.bluetape4k.clinic.appointment.model.tables.SchedulingPolicyScopeHeads
import io.bluetape4k.clinic.appointment.model.tables.TenantGroups
import io.bluetape4k.clinic.appointment.model.tables.TreatmentEquipments
import io.bluetape4k.clinic.appointment.model.tables.TreatmentTypes
import io.bluetape4k.clinic.appointment.model.tables.TreatmentDependencies
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.core.annotation.Order

/**
 * Creates the complete Exposed schema for local development and tests.
 *
 * This configuration is deliberately disabled when Flyway owns the schema. The
 * table list therefore mirrors the latest Flyway migration, including policy
 * definitions, approval evidence, effective snapshots, activation commands,
 * and preview jobs. Keeping both paths aligned prevents local tests from
 * accepting a schema that production migrations cannot create.
 *
 * Production environments must use Flyway instead of this initializer.
 */
@Configuration(proxyBeanMethods = false)
@Profile("dev", "test")
@ConditionalOnProperty(name = ["spring.flyway.enabled"], havingValue = "false", matchIfMissing = true)
class SchemaInitConfig {
    /**
     * Creates all application tables in dependency order for dev/test profiles.
     *
     * Policy definition rows precede approval and execution tables because
     * approval evidence references a definition. Runtime policy heads and
     * effective snapshots are registered before activation/preview work tables
     * so a fresh in-memory database exposes the same persistence surface as V9.
     */
    @Bean
    @Order(1)
    fun schemaInitializer(): ApplicationRunner =
        ApplicationRunner {
            transaction {
                SchemaUtils.create(
                    TenantGroups,
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
                    AppointmentIdempotencies,
                    AppointmentNotes,
                    AppointmentStateHistory,
                    RescheduleCandidates,
                    AppointmentEventLogs,
                    ProductCatalogProjections,
                    ProductCatalogBomItems,
                    ProductCatalogBomDependencies,
                    AppointmentPlans,
                    PlannedTreatments,
                    TreatmentDependencies,
                    SchedulingInboxEvents,
                    SchedulingOutboxEvents,
                    UntrustedSchedulingEventRejections,
                    SchedulingQuarantineEvents,
                    SchedulingQuarantineAuditEvents,
                    SchedulingPolicyDefinitions,
                    SchedulingPolicyApprovals,
                    SchedulingPolicyScopeHeads,
                    EffectiveSchedulingPolicySnapshots,
                    SchedulingPolicyActivationCommands,
                    SchedulingPolicyPreviewJobs,
                )
            }
        }
}
