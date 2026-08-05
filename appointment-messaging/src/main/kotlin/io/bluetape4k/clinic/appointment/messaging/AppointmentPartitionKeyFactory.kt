package io.bluetape4k.clinic.appointment.messaging

/** tenant/clinic/appointment 경계가 항상 같은 canonical key로 수렴하도록 한다. */
object AppointmentPartitionKeyFactory {
    fun create(
        tenantGroupId: Long,
        clinicId: Long,
        appointmentId: Long,
    ): AppointmentPartitionKey {
        require(tenantGroupId > 0) { "tenantGroupId must be positive" }
        require(clinicId > 0) { "clinicId must be positive" }
        require(appointmentId > 0) { "appointmentId must be positive" }
        return AppointmentPartitionKey(
            "tenant-$tenantGroupId:CLINIC:clinic-$clinicId:APPOINTMENT:apt-$appointmentId",
        )
    }
}
