package io.bluetape4k.clinic.appointment.model.dto

import io.bluetape4k.clinic.appointment.model.identity.PatientLoginIdentifierKey
import java.io.Serializable
import java.time.Instant

/** tenant에 속한 환자 인증 계정 record입니다. */
data class PatientAccountRecord(
    val id: Long? = null,
    val tenantGroupId: Long,
    val patientSubject: String,
    val displayName: String,
    val passwordHash: String,
    val active: Boolean = true,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/** 환자 계정의 tenant-scoped login identifier record입니다. */
data class PatientLoginIdentityRecord(
    val id: Long? = null,
    val patientAccountId: Long,
    val tenantGroupId: Long,
    val key: PatientLoginIdentifierKey,
    val normalizedValue: String,
    val createdAt: Instant? = null,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
