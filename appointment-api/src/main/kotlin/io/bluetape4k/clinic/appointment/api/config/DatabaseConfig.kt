package io.bluetape4k.clinic.appointment.api.config

import io.bluetape4k.clinic.appointment.event.AppointmentEventLogs
import io.bluetape4k.clinic.appointment.event.integration.SchedulingInboxEvents
import io.bluetape4k.clinic.appointment.event.integration.SchedulingOutboxEvents
import io.bluetape4k.clinic.appointment.event.integration.SchedulingQuarantineAuditEvents
import io.bluetape4k.clinic.appointment.event.integration.SchedulingQuarantineEvents
import io.bluetape4k.clinic.appointment.event.integration.UntrustedSchedulingEventRejections
import io.bluetape4k.clinic.appointment.model.tables.AppointmentAuditEvents
import io.bluetape4k.clinic.appointment.model.tables.AppointmentCommandIdempotencies
import io.bluetape4k.clinic.appointment.model.tables.AppointmentCommitments
import io.bluetape4k.clinic.appointment.model.tables.AppointmentIdempotencies
import io.bluetape4k.clinic.appointment.model.tables.AppointmentItems
import io.bluetape4k.clinic.appointment.model.tables.AppointmentNotes
import io.bluetape4k.clinic.appointment.model.tables.AppointmentOperationalExceptions
import io.bluetape4k.clinic.appointment.model.tables.AppointmentPlanRevisions
import io.bluetape4k.clinic.appointment.model.tables.AppointmentStateHistory
import io.bluetape4k.clinic.appointment.model.tables.AppointmentProposals
import io.bluetape4k.clinic.appointment.model.tables.Appointments
import io.bluetape4k.clinic.appointment.model.tables.AppointmentPlans
import io.bluetape4k.clinic.appointment.model.tables.BreakTimes
import io.bluetape4k.clinic.appointment.model.tables.ClinicClosures
import io.bluetape4k.clinic.appointment.model.tables.ClinicDefaultBreakTimes
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.ConsultationTopics
import io.bluetape4k.clinic.appointment.model.tables.ConsentDecisions
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
import io.bluetape4k.clinic.appointment.model.tables.PlanRevisionDependencies
import io.bluetape4k.clinic.appointment.model.tables.PlanRevisionTreatments
import io.bluetape4k.clinic.appointment.model.tables.ProductCatalogBomDependencies
import io.bluetape4k.clinic.appointment.model.tables.ProductCatalogBomItems
import io.bluetape4k.clinic.appointment.model.tables.ProductCatalogProjections
import io.bluetape4k.clinic.appointment.model.tables.RescheduleCandidates
import io.bluetape4k.clinic.appointment.model.tables.ResourceAllocations
import io.bluetape4k.clinic.appointment.model.tables.ResourceCapacityBuckets
import io.bluetape4k.clinic.appointment.model.tables.SchedulingPolicyActivationCommands
import io.bluetape4k.clinic.appointment.model.tables.SchedulingPolicyApprovals
import io.bluetape4k.clinic.appointment.model.tables.SchedulingPolicyDefinitions
import io.bluetape4k.clinic.appointment.model.tables.SchedulingPolicyPreviewJobs
import io.bluetape4k.clinic.appointment.model.tables.SchedulingPolicyScopeHeads
import io.bluetape4k.clinic.appointment.model.tables.TenantGroups
import io.bluetape4k.clinic.appointment.model.tables.TreatmentSpaces
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
 * 로컬 개발과 테스트에서 사용할 전체 Exposed 스키마를 생성한다.
 *
 * 운영 스키마를 Flyway가 관리하는 환경에서는 이 초기화 구성이 의도적으로 비활성화된다.
 * 따라서 이 테이블 목록은 정책 정의와 방문 commitment, proposal, consent, 실제 자원
 * 점유, Plan revision 및 감사 projection까지 최신 Flyway 마이그레이션과 같은 영속화
 * 표면을 가져야 한다.
 * 두 경로가 어긋나면 로컬 테스트는 통과하지만 운영 마이그레이션으로는 만들 수 없는
 * 스키마를 허용하게 되므로, 예제 저장소에서도 이 목록을 명시적으로 유지한다.
 *
 * 운영 환경은 이 초기화기 대신 Flyway 마이그레이션을 사용해야 한다.
 */
@Configuration(proxyBeanMethods = false)
@Profile("dev", "test")
@ConditionalOnProperty(name = ["spring.flyway.enabled"], havingValue = "false", matchIfMissing = true)
class SchemaInitConfig {
    /**
     * `dev`/`test` 프로필에서 애플리케이션 테이블을 의존 순서대로 생성한다.
     *
     * 승인 증적은 정책 정의를 참조하므로 정책 정의 테이블을 승인/실행 테이블보다
     * 먼저 둔다. 런타임 정책 head와 유효 정책 스냅샷은 활성화/미리보기 작업
     * 테이블보다 먼저 등록하여, 새 인메모리 데이터베이스도 V9 마이그레이션과
     * 동일한 정책 영속화 표면을 노출하도록 한다.
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
                    AppointmentPlanRevisions,
                    PlanRevisionTreatments,
                    PlanRevisionDependencies,
                    AppointmentCommitments,
                    AppointmentProposals,
                    ConsentDecisions,
                    AppointmentItems,
                    TreatmentSpaces,
                    ResourceCapacityBuckets,
                    ResourceAllocations,
                    AppointmentOperationalExceptions,
                    AppointmentCommandIdempotencies,
                    AppointmentAuditEvents,
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
