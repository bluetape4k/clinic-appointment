package io.bluetape4k.clinic.appointment.model.commitment

import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import java.io.Serializable
import java.time.Instant

/**
 * 제안 확정 시 원자적으로 점유할 자원 구간 초안입니다.
 *
 * @property resourceType 담당자, 장비, 실제 진료 공간 또는 capacity bucket 분류입니다.
 * @property resourceId 병원 범위에서 실제 점유 대상을 식별하는 안정적인 키입니다.
 * 표시용 room type이나 장비 종류만으로 실제 자원을 대신하지 않습니다.
 * @property startsAt 자원 점유 UTC 시작 시각입니다.
 * @property endsAt [startsAt]보다 뒤인 UTC 종료 시각입니다.
 * @property capacityUnits 이 구간에서 소비하는 양수 수용량 단위입니다. 전담 자원은
 * 일반적으로 1이며 bucket 자원은 같은 계산 단위의 합계가 정책 상한을 넘지 않아야 합니다.
 * @property allocationMode 공유 가능 여부와 capacity 계산 방식을 나타냅니다.
 * @property appointmentItemKey 이 점유의 원인이 된 세부 진료 키입니다. 방문 전체 점유는
 * `null`일 수 있으나 item별 준비·회복 구간을 잃어서는 안 됩니다.
 */
class ResourceAllocationDraft(
    val resourceType: ResourceType,
    resourceId: String,
    val startsAt: Instant,
    val endsAt: Instant,
    capacityUnits: Int,
    val allocationMode: ResourceAllocationMode,
    appointmentItemKey: String?,
) : Serializable {
    val resourceId = resourceId.requireNotBlank("resourceId")
    val capacityUnits = capacityUnits.requirePositiveNumber("capacityUnits")
    val appointmentItemKey = appointmentItemKey?.requireNotBlank("appointmentItemKey")

    init {
        require(startsAt < endsAt) { "startsAt must be before endsAt" }
        require(allocationMode == ResourceAllocationMode.CAPACITY_BUCKET || this.capacityUnits == 1) {
            "exclusive and shared resources must consume exactly one capacity unit"
        }
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * 실제 점유 대상의 종류입니다.
 */
enum class ResourceType {
    /** 담당 의사 또는 자격을 갖춘 시술자입니다. */
    PRACTITIONER,

    /** 개별 식별되는 장비입니다. */
    EQUIPMENT,

    /** 개별 식별되는 진료실·수술실·회복 공간입니다. */
    TREATMENT_SPACE,

    /** 정해진 시간 bucket의 합산 수용량입니다. */
    CAPACITY_BUCKET,
}

/**
 * 자원 충돌 계산 방식입니다.
 */
enum class ResourceAllocationMode {
    /** 겹치는 시간에 다른 활성 allocation을 허용하지 않는 전담 점유입니다. */
    EXCLUSIVE,

    /** 별도 정책이 허용한 공유 점유이며 각 allocation은 한 단위를 사용합니다. */
    SHARED,

    /** 동일 bucket의 [ResourceAllocationDraft.capacityUnits] 합계를 상한과 비교합니다. */
    CAPACITY_BUCKET,
}
