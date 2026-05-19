package io.bluetape4k.clinic.appointment.repository

import io.bluetape4k.clinic.appointment.model.tables.Clinics
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select

internal fun tenantClinicIds(tenantGroupId: Long) =
    Clinics
        .select(Clinics.id)
        .where { Clinics.tenantGroupId eq tenantGroupId }
