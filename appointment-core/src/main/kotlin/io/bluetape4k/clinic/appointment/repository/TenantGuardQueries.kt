package io.bluetape4k.clinic.appointment.repository

import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.Doctors
import io.bluetape4k.clinic.appointment.model.service.TenantClinicScope
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.inSubQuery
import org.jetbrains.exposed.v1.jdbc.select

internal fun tenantClinicIds(tenantGroupId: Long) =
    Clinics
        .select(Clinics.id)
        .where { Clinics.tenantGroupId eq tenantGroupId }

internal fun tenantDoctorIds(scope: TenantClinicScope) =
    Doctors
        .select(Doctors.id)
        .where {
            (Doctors.clinicId eq scope.clinicId) and
                (Doctors.clinicId inSubQuery tenantClinicIds(scope.tenantGroupId))
        }
