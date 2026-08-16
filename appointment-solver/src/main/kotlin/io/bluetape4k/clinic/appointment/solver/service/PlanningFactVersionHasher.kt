package io.bluetape4k.clinic.appointment.solver.service

import io.bluetape4k.clinic.appointment.model.dto.BreakTimeRecord
import io.bluetape4k.clinic.appointment.model.dto.ClinicClosureRecord
import io.bluetape4k.clinic.appointment.model.dto.ClinicDefaultBreakTimeRecord
import io.bluetape4k.clinic.appointment.model.dto.DoctorAbsenceRecord
import io.bluetape4k.clinic.appointment.model.dto.DoctorScheduleRecord
import io.bluetape4k.clinic.appointment.model.dto.HolidayRecord
import io.bluetape4k.clinic.appointment.model.dto.OperatingHoursRecord
import io.bluetape4k.clinic.appointment.model.dto.TreatmentEquipmentRecord
import io.bluetape4k.clinic.appointment.model.service.TenantClinicScope
import io.bluetape4k.clinic.appointment.solver.domain.EquipmentUnavailabilityFact
import io.bluetape4k.clinic.appointment.solver.domain.ScheduleSolution
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/**
 * Solver가 실제로 읽은 planning fact 입력을 순서와 타입이 고정된 digest로 변환합니다.
 *
 * Record의 `toString()`이나 DB 조회 순서를 사용하지 않으므로, 같은 snapshot은 같은
 * version을 만들고 fact의 추가·수정·삭제는 version을 바꿉니다. Appointment entity와
 * score는 별도의 source version/CAS와 solver 결과이므로 이 digest에서 제외합니다.
 */
internal object PlanningFactVersionHasher {

    fun hash(
        scope: TenantClinicScope,
        dateRange: ClosedRange<LocalDate>,
        solution: ScheduleSolution,
    ): String {
        val writer = CanonicalWriter()
        writer.record("scope", scope.tenantGroupId, scope.clinicId)
        writer.record("date-range", dateRange.start, dateRange.endInclusive)

        writer.record(
            "clinic",
            solution.clinic.id,
            solution.clinic.slotDurationMinutes,
            solution.clinic.maxConcurrentPatients,
            solution.clinic.openOnHolidays,
        )

        writer.collection("doctors", solution.doctors.size) {
            solution.doctors
                .sortedWith(
                    compareBy<io.bluetape4k.clinic.appointment.solver.domain.DoctorFact> { it.id }
                        .thenBy { it.clinicId }
                        .thenBy { it.providerType }
                        .thenBy { it.maxConcurrentPatients ?: Int.MIN_VALUE },
                )
                .forEach { doctor ->
                    record(
                        "doctor",
                        doctor.id,
                        doctor.clinicId,
                        doctor.providerType,
                        doctor.maxConcurrentPatients,
                    )
                }
        }

        writer.collection("treatments", solution.treatments.size) {
            solution.treatments
                .sortedWith(
                    compareBy<io.bluetape4k.clinic.appointment.solver.domain.TreatmentFact> { it.id }
                        .thenBy { it.defaultDurationMinutes }
                        .thenBy { it.requiredProviderType }
                        .thenBy { it.requiresEquipment }
                        .thenBy { it.maxConcurrentPatients ?: Int.MIN_VALUE },
                )
                .forEach { treatment ->
                    record(
                        "treatment",
                        treatment.id,
                        treatment.defaultDurationMinutes,
                        treatment.requiredProviderType,
                        treatment.requiresEquipment,
                        treatment.maxConcurrentPatients,
                    )
                }
        }

        writer.collection("equipments", solution.equipments.size) {
            solution.equipments
                .sortedWith(
                    compareBy<io.bluetape4k.clinic.appointment.solver.domain.EquipmentFact> { it.id }
                        .thenBy { it.usageDurationMinutes }
                        .thenBy { it.quantity },
                )
                .forEach { equipment ->
                    record("equipment", equipment.id, equipment.usageDurationMinutes, equipment.quantity)
                }
        }

        writer.collection("operating-hours", solution.operatingHours.size) {
            solution.operatingHours
                .sortedWith(operatingHoursComparator())
                .forEach { operatingHours ->
                    record(
                        "operating-hour",
                        operatingHours.id,
                        operatingHours.clinicId,
                        operatingHours.dayOfWeek,
                        operatingHours.openTime,
                        operatingHours.closeTime,
                        operatingHours.isActive,
                    )
                }
        }

        writer.collection("doctor-schedules", solution.doctorSchedules.size) {
            solution.doctorSchedules
                .sortedWith(doctorScheduleComparator())
                .forEach { schedule ->
                    record("doctor-schedule", schedule.id, schedule.doctorId, schedule.dayOfWeek, schedule.startTime, schedule.endTime)
                }
        }

        writer.collection("doctor-absences", solution.doctorAbsences.size) {
            solution.doctorAbsences
                .sortedWith(doctorAbsenceComparator())
                .forEach { absence ->
                    record(
                        "doctor-absence",
                        absence.id,
                        absence.doctorId,
                        absence.absenceDate,
                        absence.startTime,
                        absence.endTime,
                        absence.reason,
                    )
                }
        }

        writer.collection("break-times", solution.breakTimes.size) {
            solution.breakTimes
                .sortedWith(breakTimeComparator())
                .forEach { breakTime ->
                    record("break-time", breakTime.id, breakTime.clinicId, breakTime.dayOfWeek, breakTime.startTime, breakTime.endTime)
                }
        }

        writer.collection("default-break-times", solution.defaultBreakTimes.size) {
            solution.defaultBreakTimes
                .sortedWith(defaultBreakTimeComparator())
                .forEach { breakTime ->
                    record("default-break-time", breakTime.id, breakTime.clinicId, breakTime.name, breakTime.startTime, breakTime.endTime)
                }
        }

        writer.collection("closures", solution.closures.size) {
            solution.closures
                .sortedWith(closureComparator())
                .forEach { closure ->
                    record(
                        "closure",
                        closure.id,
                        closure.clinicId,
                        closure.closureDate,
                        closure.reason,
                        closure.isFullDay,
                        closure.startTime,
                        closure.endTime,
                    )
                }
        }

        writer.collection("holidays", solution.holidays.size) {
            solution.holidays
                .sortedWith(holidayComparator())
                .forEach { holiday ->
                    record("holiday", holiday.id, holiday.tenantGroupId, holiday.holidayDate, holiday.name, holiday.recurring)
                }
        }

        writer.collection("treatment-equipments", solution.treatmentEquipments.size) {
            solution.treatmentEquipments
                .sortedWith(treatmentEquipmentComparator())
                .forEach { mapping ->
                    record("treatment-equipment", mapping.id, mapping.treatmentTypeId, mapping.equipmentId)
                }
        }

        writer.collection("equipment-unavailabilities", solution.equipmentUnavailabilities.size) {
            solution.equipmentUnavailabilities
                .sortedWith(equipmentUnavailabilityComparator())
                .forEach { unavailability ->
                    record("equipment-unavailability", unavailability.equipmentId, unavailability.date, unavailability.startTime, unavailability.endTime)
                }
        }

        writer.collection("doctor-ids", solution.doctorIds.size) {
            solution.doctorIds.sorted().forEach { doctorId -> record("doctor-id", doctorId) }
        }
        writer.collection("solution-date-range", solution.dateRange.size) {
            solution.dateRange.sorted().forEach { date -> record("solution-date", date) }
        }
        writer.collection("time-slots", solution.timeSlots.size) {
            solution.timeSlots.sorted().forEach { time -> record("time-slot", time) }
        }

        return writer.digest()
    }

    private class CanonicalWriter {
        private val bytes = ByteArrayOutputStream()

        fun record(type: String, vararg values: Any?) {
            field(type)
            values.forEach(::field)
            field(RECORD_END)
        }

        fun collection(name: String, size: Int, block: CanonicalWriter.() -> Unit) {
            record("$name.count", size)
            block()
        }

        fun digest(): String = MessageDigest
            .getInstance("SHA-256")
            .digest(bytes.toByteArray())
            .joinToString(separator = "") { byte -> "%02x".format(byte) }

        private fun field(value: Any?) {
            if (value == null) {
                bytes.write(NULL_MARKER.toInt())
                return
            }

            bytes.write(VALUE_MARKER.toInt())
            val payload = value.toCanonicalBytes()
            bytes.write(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(payload.size).array())
            bytes.write(payload)
        }

        private fun Any.toCanonicalBytes(): ByteArray {
            val type: String
            val value: String
            when (this) {
                is String -> {
                    type = "string"
                    value = this
                }

                is Long -> {
                    type = "long"
                    value = toString()
                }

                is Int -> {
                    type = "int"
                    value = toString()
                }

                is Boolean -> {
                    type = "boolean"
                    value = toString()
                }

                is LocalDate -> {
                    type = "local-date"
                    value = toString()
                }

                is LocalTime -> {
                    type = "local-time"
                    value = toString()
                }

                is DayOfWeek -> {
                    type = "day-of-week"
                    value = name
                }

                is Enum<*> -> {
                    type = "enum:${javaClass.name}"
                    value = name
                }

                else -> throw IllegalArgumentException("Unsupported canonical value type: ${javaClass.name}")
            }
            return "$type:$value".toByteArray(StandardCharsets.UTF_8)
        }

        private companion object {
            const val NULL_MARKER: Byte = 0
            const val VALUE_MARKER: Byte = 1
            const val RECORD_END: String = "<record-end>"
        }
    }

    private fun operatingHoursComparator(): Comparator<OperatingHoursRecord> =
        compareBy<OperatingHoursRecord> { it.id ?: Long.MIN_VALUE }
            .thenBy { it.clinicId }
            .thenBy { it.dayOfWeek }
            .thenBy { it.openTime }
            .thenBy { it.closeTime }
            .thenBy { it.isActive }

    private fun doctorScheduleComparator(): Comparator<DoctorScheduleRecord> =
        compareBy<DoctorScheduleRecord> { it.id ?: Long.MIN_VALUE }
            .thenBy { it.doctorId }
            .thenBy { it.dayOfWeek }
            .thenBy { it.startTime }
            .thenBy { it.endTime }

    private fun doctorAbsenceComparator(): Comparator<DoctorAbsenceRecord> =
        compareBy<DoctorAbsenceRecord> { it.id ?: Long.MIN_VALUE }
            .thenBy { it.doctorId }
            .thenBy { it.absenceDate }
            .thenBy { it.startTime }
            .thenBy { it.endTime }
            .thenBy { it.reason }

    private fun breakTimeComparator(): Comparator<BreakTimeRecord> =
        compareBy<BreakTimeRecord> { it.id ?: Long.MIN_VALUE }
            .thenBy { it.clinicId }
            .thenBy { it.dayOfWeek }
            .thenBy { it.startTime }
            .thenBy { it.endTime }

    private fun defaultBreakTimeComparator(): Comparator<ClinicDefaultBreakTimeRecord> =
        compareBy<ClinicDefaultBreakTimeRecord> { it.id ?: Long.MIN_VALUE }
            .thenBy { it.clinicId }
            .thenBy { it.name }
            .thenBy { it.startTime }
            .thenBy { it.endTime }

    private fun closureComparator(): Comparator<ClinicClosureRecord> =
        compareBy<ClinicClosureRecord> { it.id ?: Long.MIN_VALUE }
            .thenBy { it.clinicId }
            .thenBy { it.closureDate }
            .thenBy { it.reason }
            .thenBy { it.isFullDay }
            .thenBy { it.startTime }
            .thenBy { it.endTime }

    private fun holidayComparator(): Comparator<HolidayRecord> =
        compareBy<HolidayRecord> { it.id ?: Long.MIN_VALUE }
            .thenBy { it.tenantGroupId ?: Long.MIN_VALUE }
            .thenBy { it.holidayDate }
            .thenBy { it.name }
            .thenBy { it.recurring }

    private fun treatmentEquipmentComparator(): Comparator<TreatmentEquipmentRecord> =
        compareBy<TreatmentEquipmentRecord> { it.id ?: Long.MIN_VALUE }
            .thenBy { it.treatmentTypeId }
            .thenBy { it.equipmentId }

    private fun equipmentUnavailabilityComparator(): Comparator<EquipmentUnavailabilityFact> =
        compareBy<EquipmentUnavailabilityFact> { it.equipmentId }
            .thenBy { it.date }
            .thenBy { it.startTime }
            .thenBy { it.endTime }
}
