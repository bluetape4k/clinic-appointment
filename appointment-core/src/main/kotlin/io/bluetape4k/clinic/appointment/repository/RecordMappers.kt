package io.bluetape4k.clinic.appointment.repository

import io.bluetape4k.clinic.appointment.model.dto.AppointmentRecord
import io.bluetape4k.clinic.appointment.model.dto.AppointmentPlanRecord
import io.bluetape4k.clinic.appointment.model.dto.BreakTimeRecord
import io.bluetape4k.clinic.appointment.model.dto.ClinicClosureRecord
import io.bluetape4k.clinic.appointment.model.dto.ClinicDefaultBreakTimeRecord
import io.bluetape4k.clinic.appointment.model.dto.ClinicRecord
import io.bluetape4k.clinic.appointment.model.dto.DoctorAbsenceRecord
import io.bluetape4k.clinic.appointment.model.dto.DoctorRecord
import io.bluetape4k.clinic.appointment.model.dto.DoctorScheduleRecord
import io.bluetape4k.clinic.appointment.model.dto.EquipmentUnavailabilityExceptionRecord
import io.bluetape4k.clinic.appointment.model.dto.EquipmentUnavailabilityRecord
import io.bluetape4k.clinic.appointment.model.dto.HolidayRecord
import io.bluetape4k.clinic.appointment.model.dto.OperatingHoursRecord
import io.bluetape4k.clinic.appointment.model.dto.PlannedTreatmentKey
import io.bluetape4k.clinic.appointment.model.dto.PlannedTreatmentRecord
import io.bluetape4k.clinic.appointment.model.dto.ProductCatalogProjectionRecord
import io.bluetape4k.clinic.appointment.model.dto.EquipmentRecord
import io.bluetape4k.clinic.appointment.model.dto.RescheduleCandidateRecord
import io.bluetape4k.clinic.appointment.model.dto.EffectiveSchedulingPolicySnapshotRecord
import io.bluetape4k.clinic.appointment.model.dto.SchedulingPolicyActivationCommandRecord
import io.bluetape4k.clinic.appointment.model.dto.SchedulingPolicyApprovalRecord
import io.bluetape4k.clinic.appointment.model.dto.SchedulingPolicyDefinitionRecord
import io.bluetape4k.clinic.appointment.model.dto.SchedulingPolicyPreviewJobRecord
import io.bluetape4k.clinic.appointment.model.dto.SchedulingPolicyScopeHeadRecord
import io.bluetape4k.clinic.appointment.model.dto.TenantGroupRecord
import io.bluetape4k.clinic.appointment.model.dto.TreatmentEquipmentRecord
import io.bluetape4k.clinic.appointment.model.dto.TreatmentTypeRecord
import io.bluetape4k.clinic.appointment.model.dto.TreatmentDependencyRecord
import io.bluetape4k.clinic.appointment.model.catalog.CatalogBomDependency
import io.bluetape4k.clinic.appointment.model.catalog.CatalogBomItem
import io.bluetape4k.clinic.appointment.model.catalog.InitialBookingRule
import io.bluetape4k.clinic.appointment.model.catalog.ProductCatalogDefinition
import io.bluetape4k.clinic.appointment.model.plan.BookingPreferenceSnapshot
import io.bluetape4k.clinic.appointment.model.plan.LocalTimeWindow
import io.bluetape4k.clinic.appointment.model.tables.AppointmentPlans
import io.bluetape4k.clinic.appointment.model.tables.Appointments
import io.bluetape4k.clinic.appointment.model.tables.EquipmentUnavailabilities
import io.bluetape4k.clinic.appointment.model.tables.EquipmentUnavailabilityExceptions
import io.bluetape4k.clinic.appointment.model.tables.Equipments
import io.bluetape4k.clinic.appointment.model.tables.TreatmentEquipments
import io.bluetape4k.clinic.appointment.model.tables.BreakTimes
import io.bluetape4k.clinic.appointment.model.tables.ClinicDefaultBreakTimes
import io.bluetape4k.clinic.appointment.model.tables.ClinicClosures
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.DoctorAbsences
import io.bluetape4k.clinic.appointment.model.tables.DoctorSchedules
import io.bluetape4k.clinic.appointment.model.tables.Doctors
import io.bluetape4k.clinic.appointment.model.tables.Holidays
import io.bluetape4k.clinic.appointment.model.tables.OperatingHoursTable
import io.bluetape4k.clinic.appointment.model.tables.PlannedTreatments
import io.bluetape4k.clinic.appointment.model.tables.ProductCatalogBomDependencies
import io.bluetape4k.clinic.appointment.model.tables.ProductCatalogBomItems
import io.bluetape4k.clinic.appointment.model.tables.ProductCatalogProjections
import io.bluetape4k.clinic.appointment.model.tables.RescheduleCandidates
import io.bluetape4k.clinic.appointment.model.tables.EffectiveSchedulingPolicySnapshots
import io.bluetape4k.clinic.appointment.model.tables.SchedulingPolicyActivationCommands
import io.bluetape4k.clinic.appointment.model.tables.SchedulingPolicyApprovals
import io.bluetape4k.clinic.appointment.model.tables.SchedulingPolicyDefinitions
import io.bluetape4k.clinic.appointment.model.tables.SchedulingPolicyPreviewJobs
import io.bluetape4k.clinic.appointment.model.tables.SchedulingPolicyScopeHeads
import io.bluetape4k.clinic.appointment.model.tables.TenantGroups
import io.bluetape4k.clinic.appointment.model.tables.TreatmentTypes
import io.bluetape4k.clinic.appointment.model.tables.TreatmentDependencies
import org.jetbrains.exposed.v1.core.ResultRow
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

fun ResultRow.toTenantGroupRecord() = TenantGroupRecord(
    id = this[TenantGroups.id].value,
    tenantCode = this[TenantGroups.tenantCode],
    displayName = this[TenantGroups.displayName],
    active = this[TenantGroups.active],
    createdAt = this[TenantGroups.createdAt],
)

fun ResultRow.toClinicRecord() = ClinicRecord(
    id = this[Clinics.id].value,
    tenantGroupId = this[Clinics.tenantGroupId].value,
    name = this[Clinics.name],
    slotDurationMinutes = this[Clinics.slotDurationMinutes],
    timezone = this[Clinics.timezone],
    locale = this[Clinics.locale],
    maxConcurrentPatients = this[Clinics.maxConcurrentPatients],
    openOnHolidays = this[Clinics.openOnHolidays],
)

fun ResultRow.toOperatingHoursRecord() = OperatingHoursRecord(
    id = this[OperatingHoursTable.id].value,
    clinicId = this[OperatingHoursTable.clinicId].value,
    dayOfWeek = this[OperatingHoursTable.dayOfWeek],
    openTime = this[OperatingHoursTable.openTime],
    closeTime = this[OperatingHoursTable.closeTime],
    isActive = this[OperatingHoursTable.isActive],
)

fun ResultRow.toClinicDefaultBreakTimeRecord() = ClinicDefaultBreakTimeRecord(
    id = this[ClinicDefaultBreakTimes.id].value,
    clinicId = this[ClinicDefaultBreakTimes.clinicId].value,
    name = this[ClinicDefaultBreakTimes.name],
    startTime = this[ClinicDefaultBreakTimes.startTime],
    endTime = this[ClinicDefaultBreakTimes.endTime],
)

fun ResultRow.toBreakTimeRecord() = BreakTimeRecord(
    id = this[BreakTimes.id].value,
    clinicId = this[BreakTimes.clinicId].value,
    dayOfWeek = this[BreakTimes.dayOfWeek],
    startTime = this[BreakTimes.startTime],
    endTime = this[BreakTimes.endTime],
)

fun ResultRow.toClinicClosureRecord() = ClinicClosureRecord(
    id = this[ClinicClosures.id].value,
    clinicId = this[ClinicClosures.clinicId].value,
    closureDate = this[ClinicClosures.closureDate],
    reason = this[ClinicClosures.reason],
    isFullDay = this[ClinicClosures.isFullDay],
    startTime = this[ClinicClosures.startTime],
    endTime = this[ClinicClosures.endTime],
)

fun ResultRow.toDoctorRecord() = DoctorRecord(
    id = this[Doctors.id].value,
    clinicId = this[Doctors.clinicId].value,
    name = this[Doctors.name],
    specialty = this[Doctors.specialty],
    providerType = this[Doctors.providerType],
    maxConcurrentPatients = this[Doctors.maxConcurrentPatients],
)

fun ResultRow.toDoctorScheduleRecord() = DoctorScheduleRecord(
    id = this[DoctorSchedules.id].value,
    doctorId = this[DoctorSchedules.doctorId].value,
    dayOfWeek = this[DoctorSchedules.dayOfWeek],
    startTime = this[DoctorSchedules.startTime],
    endTime = this[DoctorSchedules.endTime],
)

fun ResultRow.toDoctorAbsenceRecord() = DoctorAbsenceRecord(
    id = this[DoctorAbsences.id].value,
    doctorId = this[DoctorAbsences.doctorId].value,
    absenceDate = this[DoctorAbsences.absenceDate],
    startTime = this[DoctorAbsences.startTime],
    endTime = this[DoctorAbsences.endTime],
    reason = this[DoctorAbsences.reason],
)

fun ResultRow.toTreatmentTypeRecord() = TreatmentTypeRecord(
    id = this[TreatmentTypes.id].value,
    clinicId = this[TreatmentTypes.clinicId].value,
    name = this[TreatmentTypes.name],
    category = this[TreatmentTypes.category],
    defaultDurationMinutes = this[TreatmentTypes.defaultDurationMinutes],
    requiredProviderType = this[TreatmentTypes.requiredProviderType],
    consultationMethod = this[TreatmentTypes.consultationMethod],
    requiresEquipment = this[TreatmentTypes.requiresEquipment],
    maxConcurrentPatients = this[TreatmentTypes.maxConcurrentPatients],
)

fun ResultRow.toHolidayRecord() = HolidayRecord(
    id = this[Holidays.id].value,
    tenantGroupId = this[Holidays.tenantGroupId].value,
    holidayDate = this[Holidays.holidayDate],
    name = this[Holidays.name],
    recurring = this[Holidays.recurring],
)

fun ResultRow.toAppointmentRecord() = AppointmentRecord(
    id = this[Appointments.id].value,
    clinicId = this[Appointments.clinicId].value,
    doctorId = this[Appointments.doctorId].value,
    treatmentTypeId = this[Appointments.treatmentTypeId].value,
    equipmentId = this[Appointments.equipmentId]?.value,
    consultationTopicId = this[Appointments.consultationTopicId]?.value,
    consultationMethod = this[Appointments.consultationMethod],
    rescheduleFromId = this[Appointments.rescheduleFromId],
    patientName = this[Appointments.patientName],
    patientPhone = this[Appointments.patientPhone],
    patientExternalId = this[Appointments.patientExternalId],
    appointmentDate = this[Appointments.appointmentDate],
    startTime = this[Appointments.startTime],
    endTime = this[Appointments.endTime],
    status = this[Appointments.status],
    createdAt = this[Appointments.createdAt],
    updatedAt = this[Appointments.updatedAt],
)

fun ResultRow.toEquipmentRecord() = EquipmentRecord(
    id = this[Equipments.id].value,
    clinicId = this[Equipments.clinicId].value,
    name = this[Equipments.name],
    usageDurationMinutes = this[Equipments.usageDurationMinutes],
    quantity = this[Equipments.quantity],
)

fun ResultRow.toTreatmentEquipmentRecord() = TreatmentEquipmentRecord(
    id = this[TreatmentEquipments.id].value,
    treatmentTypeId = this[TreatmentEquipments.treatmentTypeId].value,
    equipmentId = this[TreatmentEquipments.equipmentId].value,
)

fun ResultRow.toRescheduleCandidateRecord() = RescheduleCandidateRecord(
    id = this[RescheduleCandidates.id].value,
    originalAppointmentId = this[RescheduleCandidates.originalAppointmentId].value,
    candidateDate = this[RescheduleCandidates.candidateDate],
    startTime = this[RescheduleCandidates.startTime],
    endTime = this[RescheduleCandidates.endTime],
    doctorId = this[RescheduleCandidates.doctorId].value,
    priority = this[RescheduleCandidates.priority],
    selected = this[RescheduleCandidates.selected],
    createdAt = this[RescheduleCandidates.createdAt],
)

fun ResultRow.toEquipmentUnavailabilityRecord() = EquipmentUnavailabilityRecord(
    id = this[EquipmentUnavailabilities.id].value,
    equipmentId = this[EquipmentUnavailabilities.equipmentId].value,
    clinicId = this[EquipmentUnavailabilities.clinicId].value,
    unavailableDate = this[EquipmentUnavailabilities.unavailableDate],
    isRecurring = this[EquipmentUnavailabilities.isRecurring],
    recurringDayOfWeek = this[EquipmentUnavailabilities.recurringDayOfWeek],
    effectiveFrom = this[EquipmentUnavailabilities.effectiveFrom],
    effectiveUntil = this[EquipmentUnavailabilities.effectiveUntil],
    startTime = this[EquipmentUnavailabilities.startTime],
    endTime = this[EquipmentUnavailabilities.endTime],
    reason = this[EquipmentUnavailabilities.reason],
)

fun ResultRow.toEquipmentUnavailabilityExceptionRecord() = EquipmentUnavailabilityExceptionRecord(
    id = this[EquipmentUnavailabilityExceptions.id].value,
    unavailabilityId = this[EquipmentUnavailabilityExceptions.unavailabilityId].value,
    originalDate = this[EquipmentUnavailabilityExceptions.originalDate],
    exceptionType = this[EquipmentUnavailabilityExceptions.exceptionType],
    rescheduledDate = this[EquipmentUnavailabilityExceptions.rescheduledDate],
    rescheduledStartTime = this[EquipmentUnavailabilityExceptions.rescheduledStartTime],
    rescheduledEndTime = this[EquipmentUnavailabilityExceptions.rescheduledEndTime],
    reason = this[EquipmentUnavailabilityExceptions.reason],
)

internal fun ResultRow.toCatalogBomItem(): CatalogBomItem = CatalogBomItem(
    bomItemId = this[ProductCatalogBomItems.bomItemId],
    representativeTreatmentName = this[ProductCatalogBomItems.representativeTreatmentName],
    detailedTreatmentCodes = decodeStringList(this[ProductCatalogBomItems.detailedTreatmentCodesJson]),
    repeatCount = this[ProductCatalogBomItems.repeatCount],
    durationMinutes = this[ProductCatalogBomItems.durationMinutes],
    minimumIntervalDays = this[ProductCatalogBomItems.minimumIntervalDays],
    preferredIntervalDays = this[ProductCatalogBomItems.preferredIntervalDays],
    maximumIntervalDays = this[ProductCatalogBomItems.maximumIntervalDays],
    practitionerQualifications = decodeStringList(this[ProductCatalogBomItems.practitionerQualificationsJson]),
    equipmentTypes = decodeStringList(this[ProductCatalogBomItems.equipmentTypesJson]),
    roomTypes = decodeStringList(this[ProductCatalogBomItems.roomTypesJson]),
)

internal fun ResultRow.toCatalogBomDependency(): CatalogBomDependency = CatalogBomDependency(
    predecessorBomItemId = this[ProductCatalogBomDependencies.predecessorBomItemId],
    predecessorSequenceNo = this[ProductCatalogBomDependencies.predecessorSequenceNo].sentinelToSequence(),
    successorBomItemId = this[ProductCatalogBomDependencies.successorBomItemId],
    successorSequenceNo = this[ProductCatalogBomDependencies.successorSequenceNo].sentinelToSequence(),
    minimumIntervalDays = this[ProductCatalogBomDependencies.minimumIntervalDays],
    preferredIntervalDays = this[ProductCatalogBomDependencies.preferredIntervalDays],
    maximumIntervalDays = this[ProductCatalogBomDependencies.maximumIntervalDays],
)

internal fun ResultRow.toProductCatalogProjectionRecord(
    items: List<CatalogBomItem>,
    dependencies: List<CatalogBomDependency>,
): ProductCatalogProjectionRecord {
    val rule = when (this[ProductCatalogProjections.initialBookingRuleType]) {
        null -> null
        "WITHIN_DAYS_AFTER_PURCHASE" -> InitialBookingRule.WithinDaysAfterPurchase(
            requireNotNull(this[ProductCatalogProjections.initialBookingMaximumDays])
        )
        else -> error("Unknown initial booking rule type")
    }
    return ProductCatalogProjectionRecord(
        id = this[ProductCatalogProjections.id].value,
        definition = ProductCatalogDefinition(
            tenantGroupId = this[ProductCatalogProjections.tenantGroupId].value,
            clinicId = this[ProductCatalogProjections.clinicId].value,
            sourceAuthority = this[ProductCatalogProjections.sourceAuthority],
            productId = this[ProductCatalogProjections.productId],
            catalogVersion = this[ProductCatalogProjections.catalogVersion],
            productName = this[ProductCatalogProjections.productName],
            schemaVersion = this[ProductCatalogProjections.schemaVersion],
            sourceUpdatedAt = this[ProductCatalogProjections.sourceUpdatedAt],
            status = this[ProductCatalogProjections.status],
            items = items,
            dependencies = dependencies,
            initialBookingRule = rule,
        ),
        payloadHash = this[ProductCatalogProjections.payloadHash],
        createdAt = this[ProductCatalogProjections.createdAt],
    )
}

internal fun ResultRow.toAppointmentPlanRecord(): AppointmentPlanRecord =
    AppointmentPlanRecord(
        id = this[AppointmentPlans.id].value,
        tenantGroupId = this[AppointmentPlans.tenantGroupId].value,
        clinicId = this[AppointmentPlans.clinicId].value,
        catalogProjectionId = this[AppointmentPlans.catalogProjectionId].value,
        sourcePurchaseAuthority = this[AppointmentPlans.sourcePurchaseAuthority],
        sourcePurchaseId = this[AppointmentPlans.sourcePurchaseId],
        patientReferenceCiphertext = this[AppointmentPlans.patientReferenceCiphertext],
        patientReferenceKeyId = this[AppointmentPlans.patientReferenceKeyId],
        patientReferenceFingerprint = this[AppointmentPlans.patientReferenceFingerprint],
        catalogSourceAuthority = this[AppointmentPlans.catalogSourceAuthority],
        productId = this[AppointmentPlans.productId],
        catalogVersion = this[AppointmentPlans.catalogVersion],
        catalogPayloadHash = this[AppointmentPlans.catalogPayloadHash],
        productName = this[AppointmentPlans.productName],
        bookingPreference = decodeBookingPreference(
            this[AppointmentPlans.bookingPreferenceType],
            this[AppointmentPlans.bookingPreferencePayload],
        ),
        status = this[AppointmentPlans.status],
        createdAt = this[AppointmentPlans.createdAt],
        updatedAt = this[AppointmentPlans.updatedAt],
    )

internal fun ResultRow.toPlannedTreatmentRecord(): PlannedTreatmentRecord =
    PlannedTreatmentRecord(
        id = this[PlannedTreatments.id].value,
        planId = this[PlannedTreatments.planId].value,
        bomItemId = this[PlannedTreatments.bomItemId],
        sequenceNo = this[PlannedTreatments.sequenceNo],
        bomOrder = this[PlannedTreatments.bomOrder],
        representativeTreatmentName = this[PlannedTreatments.representativeTreatmentName],
        detailedTreatmentCodes = decodeStringList(this[PlannedTreatments.detailedTreatmentCodesJson]),
        durationMinutes = this[PlannedTreatments.durationMinutes],
        minimumIntervalDays = this[PlannedTreatments.minimumIntervalDays],
        preferredIntervalDays = this[PlannedTreatments.preferredIntervalDays],
        maximumIntervalDays = this[PlannedTreatments.maximumIntervalDays],
        practitionerQualifications = decodeStringList(this[PlannedTreatments.practitionerQualificationsJson]),
        equipmentTypes = decodeStringList(this[PlannedTreatments.equipmentTypesJson]),
        roomTypes = decodeStringList(this[PlannedTreatments.roomTypesJson]),
        earliestStartAt = this[PlannedTreatments.earliestStartAt],
        latestStartAt = this[PlannedTreatments.latestStartAt],
        status = this[PlannedTreatments.status],
    )

internal fun ResultRow.toTreatmentDependencyRecord(
    keysByTreatmentId: Map<Long, PlannedTreatmentKey>,
): TreatmentDependencyRecord {
    val predecessorId = this[TreatmentDependencies.predecessorTreatmentId].value
    val successorId = this[TreatmentDependencies.successorTreatmentId].value
    return TreatmentDependencyRecord(
        id = this[TreatmentDependencies.id].value,
        planId = this[TreatmentDependencies.planId].value,
        predecessorTreatmentId = predecessorId,
        successorTreatmentId = successorId,
        predecessor = requireNotNull(keysByTreatmentId[predecessorId]),
        successor = requireNotNull(keysByTreatmentId[successorId]),
        minimumIntervalDays = this[TreatmentDependencies.minimumIntervalDays],
        preferredIntervalDays = this[TreatmentDependencies.preferredIntervalDays],
        maximumIntervalDays = this[TreatmentDependencies.maximumIntervalDays],
    )
}

internal fun encodeStringList(values: List<String>): String =
    if (values.isEmpty()) {
        "[]"
    } else {
        values.joinToString(prefix = "[\"", separator = "\",\"", postfix = "\"]")
    }

internal fun decodeStringList(value: String): List<String> =
    if (value == "[]") {
        emptyList()
    } else {
        require(value.startsWith("[\"") && value.endsWith("\"]")) { "Invalid canonical string list" }
        value.substring(2, value.length - 2).split("\",\"")
    }

internal fun encodeBookingPreference(preference: BookingPreferenceSnapshot): Pair<String, String> =
    when (preference) {
        is BookingPreferenceSnapshot.ExactDateTime ->
            "EXACT_DATE_TIME" to listOf(
                preference.originalLocalDateTime,
                preference.originalOffset,
                preference.zoneId,
                preference.normalizedInstant,
            ).joinToString("\n")
        is BookingPreferenceSnapshot.DateRange ->
            "DATE_RANGE" to listOf(
                preference.startDate,
                preference.endDate,
                preference.zoneId,
            ).joinToString("\n")
        is BookingPreferenceSnapshot.PreferredWeekdaysAndWindows ->
            "PREFERRED_WEEKDAYS_AND_WINDOWS" to listOf(
                preference.weekdays.joinToString(","),
                preference.localTimeWindows.joinToString(",") { window -> "${window.start}/${window.end}" },
                preference.zoneId,
            ).joinToString("\n")
        BookingPreferenceSnapshot.NotProvided -> "NOT_PROVIDED" to ""
    }

private fun decodeBookingPreference(
    type: String,
    payload: String,
): BookingPreferenceSnapshot {
    val fields = if (payload.isEmpty()) emptyList() else payload.split("\n")
    return when (type) {
        "EXACT_DATE_TIME" -> BookingPreferenceSnapshot.ExactDateTime(
            originalLocalDateTime = LocalDateTime.parse(fields[0]),
            originalOffset = ZoneOffset.of(fields[1]),
            zoneId = ZoneId.of(fields[2]),
            normalizedInstant = Instant.parse(fields[3]),
        )
        "DATE_RANGE" -> BookingPreferenceSnapshot.DateRange(
            startDate = LocalDate.parse(fields[0]),
            endDate = LocalDate.parse(fields[1]),
            zoneId = ZoneId.of(fields[2]),
        )
        "PREFERRED_WEEKDAYS_AND_WINDOWS" -> BookingPreferenceSnapshot.PreferredWeekdaysAndWindows(
            weekdays = fields[0].split(",").map(DayOfWeek::valueOf),
            localTimeWindows = fields[1].split(",").filter(String::isNotEmpty).map { encoded ->
                val (start, end) = encoded.split("/", limit = 2)
                LocalTimeWindow(LocalTime.parse(start), LocalTime.parse(end))
            },
            zoneId = ZoneId.of(fields[2]),
        )
        "NOT_PROVIDED" -> BookingPreferenceSnapshot.NotProvided
        else -> error("Unknown booking preference type")
    }
}

internal fun Int?.sequenceToSentinel(): Int = this ?: 0

private fun Int.sentinelToSequence(): Int? = takeIf { it > 0 }

internal fun ResultRow.toSchedulingPolicyDefinitionRecord() = SchedulingPolicyDefinitionRecord(
    id = this[SchedulingPolicyDefinitions.id].value,
    tenantGroupId = this[SchedulingPolicyDefinitions.tenantGroupId],
    scope = this[SchedulingPolicyDefinitions.scope],
    clinicId = this[SchedulingPolicyDefinitions.clinicId],
    clinicScopeKey = this[SchedulingPolicyDefinitions.clinicScopeKey],
    kind = this[SchedulingPolicyDefinitions.policyKind],
    version = this[SchedulingPolicyDefinitions.version],
    schemaVersion = this[SchedulingPolicyDefinitions.schemaVersion],
    lifecycle = this[SchedulingPolicyDefinitions.lifecycle],
    effectiveFrom = this[SchedulingPolicyDefinitions.effectiveFrom],
    effectiveUntil = this[SchedulingPolicyDefinitions.effectiveUntil],
    revision = this[SchedulingPolicyDefinitions.revision],
    payloadHash = this[SchedulingPolicyDefinitions.payloadHash],
    payloadJson = this[SchedulingPolicyDefinitions.payloadJson],
    createdByActorId = this[SchedulingPolicyDefinitions.createdByActorId],
    createdByActorRole = this[SchedulingPolicyDefinitions.createdByActorRole],
    changeReason = this[SchedulingPolicyDefinitions.changeReason],
    createdAt = this[SchedulingPolicyDefinitions.createdAt],
)

internal fun ResultRow.toSchedulingPolicyApprovalRecord() = SchedulingPolicyApprovalRecord(
    id = this[SchedulingPolicyApprovals.id].value,
    definitionId = this[SchedulingPolicyApprovals.definitionId].value,
    draftRevision = this[SchedulingPolicyApprovals.draftRevision],
    actorId = this[SchedulingPolicyApprovals.actorId],
    actorRole = this[SchedulingPolicyApprovals.actorRole],
    assuranceLevel = this[SchedulingPolicyApprovals.assuranceLevel],
    approvedAt = this[SchedulingPolicyApprovals.approvedAt],
)

internal fun ResultRow.toSchedulingPolicyScopeHeadRecord() = SchedulingPolicyScopeHeadRecord(
    id = this[SchedulingPolicyScopeHeads.id].value,
    tenantGroupId = this[SchedulingPolicyScopeHeads.tenantGroupId],
    scope = this[SchedulingPolicyScopeHeads.scope],
    clinicScopeKey = this[SchedulingPolicyScopeHeads.clinicScopeKey],
    revision = this[SchedulingPolicyScopeHeads.revision],
    generation = this[SchedulingPolicyScopeHeads.generation],
    updatedAt = this[SchedulingPolicyScopeHeads.updatedAt],
)

internal fun ResultRow.toEffectiveSchedulingPolicySnapshotRecord() = EffectiveSchedulingPolicySnapshotRecord(
    id = this[EffectiveSchedulingPolicySnapshots.id].value,
    tenantGroupId = this[EffectiveSchedulingPolicySnapshots.tenantGroupId],
    clinicId = this[EffectiveSchedulingPolicySnapshots.clinicId],
    decisionAt = this[EffectiveSchedulingPolicySnapshots.decisionAt],
    serviceAt = this[EffectiveSchedulingPolicySnapshots.serviceAt],
    tenantGeneration = this[EffectiveSchedulingPolicySnapshots.tenantGeneration],
    clinicGeneration = this[EffectiveSchedulingPolicySnapshots.clinicGeneration],
    sourceVersionsJson = this[EffectiveSchedulingPolicySnapshots.sourceVersionsJson],
    sourceByPathJson = this[EffectiveSchedulingPolicySnapshots.sourceByPathJson],
    disabledFeaturesJson = this[EffectiveSchedulingPolicySnapshots.disabledFeaturesJson],
    warningsJson = this[EffectiveSchedulingPolicySnapshots.warningsJson],
    payloadJson = this[EffectiveSchedulingPolicySnapshots.payloadJson],
    snapshotHash = this[EffectiveSchedulingPolicySnapshots.snapshotHash],
    createdAt = this[EffectiveSchedulingPolicySnapshots.createdAt],
)

internal fun ResultRow.toSchedulingPolicyActivationCommandRecord() = SchedulingPolicyActivationCommandRecord(
    id = this[SchedulingPolicyActivationCommands.id].value,
    tenantGroupId = this[SchedulingPolicyActivationCommands.tenantGroupId],
    scope = this[SchedulingPolicyActivationCommands.scope],
    clinicId = this[SchedulingPolicyActivationCommands.clinicId],
    clinicScopeKey = this[SchedulingPolicyActivationCommands.clinicScopeKey],
    definitionId = this[SchedulingPolicyActivationCommands.definitionId],
    expectedDraftRevision = this[SchedulingPolicyActivationCommands.expectedDraftRevision],
    expectedActiveRevision = this[SchedulingPolicyActivationCommands.expectedActiveRevision],
    idempotencyKeyHash = this[SchedulingPolicyActivationCommands.idempotencyKeyHash],
    requestFingerprint = this[SchedulingPolicyActivationCommands.requestFingerprint],
    status = this[SchedulingPolicyActivationCommands.status],
    effectiveFrom = this[SchedulingPolicyActivationCommands.effectiveFrom],
    nextAttemptAt = this[SchedulingPolicyActivationCommands.nextAttemptAt],
    leaseOwner = this[SchedulingPolicyActivationCommands.leaseOwner],
    leaseUntil = this[SchedulingPolicyActivationCommands.leaseUntil],
    attempt = this[SchedulingPolicyActivationCommands.attempt],
    resultTenantGeneration = this[SchedulingPolicyActivationCommands.resultTenantGeneration],
    resultClinicGeneration = this[SchedulingPolicyActivationCommands.resultClinicGeneration],
    eventId = this[SchedulingPolicyActivationCommands.eventId],
    lastErrorCode = this[SchedulingPolicyActivationCommands.lastErrorCode],
    createdAt = this[SchedulingPolicyActivationCommands.createdAt],
    updatedAt = this[SchedulingPolicyActivationCommands.updatedAt],
)

internal fun ResultRow.toSchedulingPolicyPreviewJobRecord() = SchedulingPolicyPreviewJobRecord(
    id = this[SchedulingPolicyPreviewJobs.id].value,
    tenantGroupId = this[SchedulingPolicyPreviewJobs.tenantGroupId],
    clinicId = this[SchedulingPolicyPreviewJobs.clinicId],
    definitionId = this[SchedulingPolicyPreviewJobs.definitionId],
    draftRevision = this[SchedulingPolicyPreviewJobs.draftRevision],
    tenantGeneration = this[SchedulingPolicyPreviewJobs.tenantGeneration],
    clinicGeneration = this[SchedulingPolicyPreviewJobs.clinicGeneration],
    partitionCount = this[SchedulingPolicyPreviewJobs.partitionCount],
    cursorPartition = this[SchedulingPolicyPreviewJobs.cursorPartition],
    cursorLastAppointmentId = this[SchedulingPolicyPreviewJobs.cursorLastAppointmentId],
    scannedCount = this[SchedulingPolicyPreviewJobs.scannedCount],
    affectedCount = this[SchedulingPolicyPreviewJobs.affectedCount],
    status = this[SchedulingPolicyPreviewJobs.status],
    deadlineAt = this[SchedulingPolicyPreviewJobs.deadlineAt],
    nextAttemptAt = this[SchedulingPolicyPreviewJobs.nextAttemptAt],
    leaseOwner = this[SchedulingPolicyPreviewJobs.leaseOwner],
    leaseUntil = this[SchedulingPolicyPreviewJobs.leaseUntil],
    lastErrorCode = this[SchedulingPolicyPreviewJobs.lastErrorCode],
    createdAt = this[SchedulingPolicyPreviewJobs.createdAt],
    updatedAt = this[SchedulingPolicyPreviewJobs.updatedAt],
)
