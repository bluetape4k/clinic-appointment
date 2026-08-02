package io.bluetape4k.clinic.appointment.model.waitlist

import io.bluetape4k.clinic.appointment.model.commitment.ResourceType
import io.bluetape4k.clinic.appointment.model.identity.MemberId
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import java.io.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * 대기 목록 aggregate의 tenant, clinic, member 범위입니다.
 *
 * [memberId]는 회원 서비스의 opaque 값이며 로그·metric label로 사용하지 않습니다.
 */
data class WaitlistScope(
    val tenantGroupId: Long,
    val clinicId: Long,
    val memberId: MemberId,
) : Serializable {
    init {
        tenantGroupId.requirePositiveNumber("tenantGroupId")
        clinicId.requirePositiveNumber("clinicId")
        memberId.value.requireNotBlank("memberId")
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * 대기 후보가 점유할 수 있는 빈자리 descriptor입니다.
 *
 * 모든 시각은 UTC [Instant]로 다루며, client가 vacancy key를 주입하지 않습니다.
 */
data class VacancyDescriptor(
    val tenantGroupId: Long,
    val clinicId: Long,
    val treatmentTypeId: Long,
    val doctorId: Long?,
    val startsAt: Instant,
    val endsAt: Instant,
    val resourceType: ResourceType,
    val resourceId: String,
    val capacityUnits: Int,
    val maximumCapacity: Int,
    val now: Instant,
) : Serializable {
    init {
        tenantGroupId.requirePositiveNumber("tenantGroupId")
        clinicId.requirePositiveNumber("clinicId")
        treatmentTypeId.requirePositiveNumber("treatmentTypeId")
        doctorId?.requirePositiveNumber("doctorId")
        resourceId.requireNotBlank("resourceId")
        capacityUnits.requirePositiveNumber("capacityUnits")
        maximumCapacity.requirePositiveNumber("maximumCapacity")
        require(startsAt < endsAt) { "startsAt must be before endsAt" }
        require(capacityUnits <= maximumCapacity) {
            "capacityUnits must not exceed maximumCapacity"
        }
        require(resourceType == ResourceType.CAPACITY_BUCKET || capacityUnits == 1) {
            "non-bucket resources must consume exactly one capacity unit"
        }
        require(now < endsAt) { "now must be before endsAt" }
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/** offer에 결합되는 booking reliability decision snapshot입니다. */
data class DecisionStamp(
    val scope: WaitlistScope,
    val decisionId: Long,
    val policyVersionId: Long,
    val policyHash: String,
    val evaluationDigest: String,
    val expiresAt: Instant?,
) : Serializable {
    init {
        decisionId.requirePositiveNumber("decisionId")
        policyVersionId.requirePositiveNumber("policyVersionId")
        require(SHA256_REGEX.matches(policyHash)) { "policyHash must be lowercase SHA-256" }
        require(SHA256_REGEX.matches(evaluationDigest)) {
            "evaluationDigest must be lowercase SHA-256"
        }
    }

    fun isUsableAt(now: Instant): Boolean = expiresAt == null || expiresAt.isAfter(now)

    companion object {
        private const val serialVersionUID = 1L
    }
}

/** 대기 entry row의 immutable record입니다. */
data class WaitlistEntryRecord(
    val id: Long,
    val scope: WaitlistScope,
    val treatmentTypeId: Long,
    val doctorId: Long?,
    val preferredDateFrom: LocalDate,
    val preferredDateTo: LocalDate,
    val preferredStartTime: LocalTime,
    val preferredEndTime: LocalTime,
    val priorityRank: Int,
    val status: WaitlistEntryState,
    val waitingSince: Instant,
    val version: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
) : Serializable {
    init {
        id.requirePositiveNumber("id")
        treatmentTypeId.requirePositiveNumber("treatmentTypeId")
        doctorId?.requirePositiveNumber("doctorId")
        require(!preferredDateFrom.isAfter(preferredDateTo)) {
            "preferredDateFrom must be on or before preferredDateTo"
        }
        require(preferredStartTime < preferredEndTime) {
            "preferredStartTime must be before preferredEndTime"
        }
        require(priorityRank >= 0) { "priorityRank must be zero or positive" }
        require(version >= 0L) { "version must be zero or positive" }
        require(!updatedAt.isBefore(createdAt)) { "updatedAt must not be before createdAt" }
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/** concrete offer row의 immutable record입니다. */
data class WaitlistOfferRecord(
    val id: Long,
    val scope: WaitlistScope,
    val waitlistEntryId: Long,
    val vacancyKey: String,
    val activeEntryKey: String?,
    val activeVacancyKey: String?,
    val resourceType: ResourceType,
    val resourceId: String,
    val capacityUnits: Int,
    val maximumCapacity: Int,
    val doctorId: Long?,
    val treatmentTypeId: Long,
    val startsAt: Instant,
    val endsAt: Instant,
    val expiresAt: Instant,
    val status: WaitlistOfferState,
    val decisionStamp: DecisionStamp,
    val candidateRank: Int,
    val selectionReasonCode: WaitlistReasonCode,
    val version: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
) : Serializable {
    init {
        id.requirePositiveNumber("id")
        waitlistEntryId.requirePositiveNumber("waitlistEntryId")
        vacancyKey.requireNotBlank("vacancyKey")
        activeEntryKey?.requireNotBlank("activeEntryKey")
        activeVacancyKey?.requireNotBlank("activeVacancyKey")
        resourceId.requireNotBlank("resourceId")
        capacityUnits.requirePositiveNumber("capacityUnits")
        maximumCapacity.requirePositiveNumber("maximumCapacity")
        require(capacityUnits <= maximumCapacity) {
            "capacityUnits must not exceed maximumCapacity"
        }
        require(resourceType == ResourceType.CAPACITY_BUCKET || capacityUnits == 1) {
            "non-bucket resources must consume exactly one capacity unit"
        }
        doctorId?.requirePositiveNumber("doctorId")
        treatmentTypeId.requirePositiveNumber("treatmentTypeId")
        require(startsAt < endsAt) { "startsAt must be before endsAt" }
        require(expiresAt <= endsAt) { "expiresAt must be on or before endsAt" }
        require(candidateRank > 0) { "candidateRank must be positive" }
        require(version >= 0L) { "version must be zero or positive" }
        require(!updatedAt.isBefore(createdAt)) { "updatedAt must not be before createdAt" }
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/** capacity hold row의 immutable record입니다. */
data class WaitlistCapacityHoldRecord(
    val id: Long,
    val scope: WaitlistScope,
    val offerId: Long,
    val vacancyKey: String,
    val activeVacancyKey: String?,
    val resourceType: ResourceType,
    val resourceId: String,
    val startsAt: Instant,
    val endsAt: Instant,
    val capacityUnits: Int,
    val maximumCapacity: Int,
    val status: WaitlistCapacityHoldState,
    val holdExpiresAt: Instant,
    val version: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
    val releasedAt: Instant?,
    val consumedAt: Instant?,
) : Serializable {
    init {
        id.requirePositiveNumber("id")
        offerId.requirePositiveNumber("offerId")
        vacancyKey.requireNotBlank("vacancyKey")
        activeVacancyKey?.requireNotBlank("activeVacancyKey")
        resourceId.requireNotBlank("resourceId")
        capacityUnits.requirePositiveNumber("capacityUnits")
        maximumCapacity.requirePositiveNumber("maximumCapacity")
        require(startsAt < endsAt) { "startsAt must be before endsAt" }
        require(capacityUnits <= maximumCapacity) {
            "capacityUnits must not exceed maximumCapacity"
        }
        require(resourceType == ResourceType.CAPACITY_BUCKET || capacityUnits == 1) {
            "non-bucket resources must consume exactly one capacity unit"
        }
        require(holdExpiresAt <= endsAt) { "holdExpiresAt must be on or before endsAt" }
        require(version >= 0L) { "version must be zero or positive" }
        require(!updatedAt.isBefore(createdAt)) { "updatedAt must not be before createdAt" }
        check(status != WaitlistCapacityHoldState.CONSUMED || consumedAt != null) {
            "consumed hold must have consumedAt"
        }
        check(status != WaitlistCapacityHoldState.RELEASED || releasedAt != null) {
            "released hold must have releasedAt"
        }
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/** 후보 keyset 조회의 cursor입니다. */
data class WaitlistCursor(
    val slotFit: Int,
    val priorityRank: Int,
    val waitingSince: Instant,
    val entryId: Long,
) : Serializable {
    init {
        require(slotFit == 0 || slotFit == 1) { "slotFit must be 0 or 1" }
        require(priorityRank >= 0) { "priorityRank must be zero or positive" }
        entryId.requirePositiveNumber("entryId")
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/** offer와 hold를 한 transaction에서 만든 결과 ID입니다. */
data class OfferHoldIds(
    val offerId: Long,
    val holdId: Long,
) : Serializable {
    init {
        offerId.requirePositiveNumber("offerId")
        holdId.requirePositiveNumber("holdId")
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/** capacity 검증이 성공했을 때 같은 transaction에서 전달하는 예약 snapshot입니다. */
data class WaitlistCapacityReservation(
    val resourceType: ResourceType,
    val resourceId: String,
    val startsAt: Instant,
    val endsAt: Instant,
    val capacityUnits: Int,
    val maximumCapacity: Int,
) : Serializable {
    init {
        resourceId.requireNotBlank("resourceId")
        capacityUnits.requirePositiveNumber("capacityUnits")
        maximumCapacity.requirePositiveNumber("maximumCapacity")
        require(startsAt < endsAt) { "startsAt must be before endsAt" }
        require(capacityUnits <= maximumCapacity) {
            "capacityUnits must not exceed maximumCapacity"
        }
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

internal val SHA256_REGEX = Regex("^[a-f0-9]{64}$")
