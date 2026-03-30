package io.bluetape4k.clinic.appointment.solver.domain

/**
 * 병원 정보 Problem Fact.
 */
data class ClinicFact(
    val id: Long,
    val slotDurationMinutes: Int,
    val maxConcurrentPatients: Int,
    val openOnHolidays: Boolean,
)
