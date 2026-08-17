package io.bluetape4k.clinic.appointment.repository

import io.bluetape4k.clinic.appointment.model.commitment.ResourceAllocationDraft
import io.bluetape4k.clinic.appointment.model.commitment.ResourceAllocationMode
import io.bluetape4k.clinic.appointment.model.commitment.ResourceType
import io.bluetape4k.clinic.appointment.model.dto.ResourceAllocationRecord
import io.bluetape4k.clinic.appointment.model.dto.ResourceAllocationRequest
import io.bluetape4k.clinic.appointment.model.dto.ResourceAllocationStatus
import io.bluetape4k.clinic.appointment.model.waitlist.HoldScopeMismatch
import io.bluetape4k.clinic.appointment.model.waitlist.NewHold
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistCapacityHoldRecord
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistCapacityHoldState
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistCapacityReservation
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistScope
import io.bluetape4k.clinic.appointment.model.tables.ResourceAllocations
import io.bluetape4k.clinic.appointment.model.tables.ResourceCapacityBuckets
import io.bluetape4k.clinic.appointment.model.tables.WaitlistCapacityHolds
import io.bluetape4k.support.requirePositiveNumber
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import java.io.Serializable
import java.time.Instant

/**
 * caller transaction 안에서 proposal별 실제 자원 점유를 검증·교체합니다.
 *
 * 잠금 순서는 tenant, clinic, resource type, resource ID로 정규화합니다. 시작 시각이
 * 다른 두 구간도 실제 시간이 겹칠 수 있으므로 동일 자원의 모든 확정은 하나의 DB
 * mutex row로 직렬화합니다. 이 방식은 자원 단위 동시성을 일부 줄이지만 DB 종류와
 * 무관하게 overlap 정합성을 우선 보장합니다.
 *
 * 기존 확정 proposal을 교체할 때 그 proposal의 allocation은 overlap 합계에서
 * 제외하지만, 새 allocation 전체 검증과 insert가 끝나기 전에는 해제하지 않습니다.
 */
class ResourceAllocationRepository {
    /**
     * 새 proposal의 모든 자원 점유를 검증해 insert한 뒤 이전 proposal을 해제합니다.
     *
     * 메서드는 transaction을 열지 않습니다. 예외를 caller가 잡고 transaction을 계속
     * 사용할 수 있도록 모든 검증을 write보다 먼저 수행합니다.
     *
     * @throws ResourceAllocationConflictException 전담 overlap, 공유/전담 충돌, capacity
     * 초과 또는 같은 bucket의 정책 상한 불일치가 있으면 발생합니다.
     */
    internal fun replaceConfirmedAllocations(
        tenantGroupId: Long,
        clinicId: Long,
        proposalId: Long,
        replacingProposalId: Long?,
        requests: List<ResourceAllocationRequest>,
    ): List<ResourceAllocationRecord> {
        val inserted =
            createConfirmedAllocations(
                tenantGroupId = tenantGroupId,
                clinicId = clinicId,
                proposalId = proposalId,
                replacingProposalId = replacingProposalId,
                requests = requests,
            )
        replacingProposalId?.let(::releaseActiveAllocations)
        return inserted
    }

    /**
     * 새 proposal의 자원을 잠그고 재검증한 뒤 active allocation만 생성합니다.
     *
     * 이전 proposal의 allocation은 해제하지 않습니다. application service는 이 메서드가
     * 성공한 뒤 commitment `confirmedProposalId` CAS를 먼저 수행하고, CAS 성공 뒤에만
     * [releaseActiveAllocations]를 호출해야 합니다. CAS 실패 예외로 transaction이
     * rollback되면 여기서 만든 새 row도 함께 사라집니다.
     *
     * [availabilityLock]은 같은 transaction에서 동일 scope·교체 대상·요청으로
     * [lockAndValidateAvailability]를 호출해 받은 불투명 증표만 허용합니다. 임의 boolean로
     * 검증을 건너뛸 수 없으며, 다른 transaction이나 다른 요청에 증표를 재사용하면
     * fail-closed로 거부합니다.
     */
    fun createConfirmedAllocations(
        tenantGroupId: Long,
        clinicId: Long,
        proposalId: Long,
        replacingProposalId: Long?,
        requests: List<ResourceAllocationRequest>,
        availabilityLock: LockedResourceAvailability? = null,
    ): List<ResourceAllocationRecord> {
        val validTenantGroupId = tenantGroupId.requirePositiveNumber("tenantGroupId")
        val validClinicId = clinicId.requirePositiveNumber("clinicId")
        val validProposalId = proposalId.requirePositiveNumber("proposalId")
        val validReplacingProposalId =
            replacingProposalId?.requirePositiveNumber("replacingProposalId")
        require(requests.isNotEmpty()) { "resource allocation requests must not be empty" }
        require(validReplacingProposalId != validProposalId) {
            "proposalId and replacingProposalId must be different"
        }
        if (availabilityLock == null) {
            lockAndValidateAvailability(
                tenantGroupId = validTenantGroupId,
                clinicId = validClinicId,
                replacingProposalId = validReplacingProposalId,
                requests = requests,
            )
        } else {
            availabilityLock.requireMatches(
                tenantGroupId = validTenantGroupId,
                clinicId = validClinicId,
                replacingProposalId = validReplacingProposalId,
                requests = requests,
            )
        }

        val insertedRows =
            ResourceAllocations.batchInsert(requests) { request ->
                val allocation = request.allocation
                this[ResourceAllocations.tenantGroupId] = validTenantGroupId
                this[ResourceAllocations.clinicId] = validClinicId
                this[ResourceAllocations.proposalId] = validProposalId
                this[ResourceAllocations.appointmentItemKey] = allocation.appointmentItemKey
                this[ResourceAllocations.resourceType] = allocation.resourceType
                this[ResourceAllocations.resourceId] = allocation.resourceId
                this[ResourceAllocations.startsAt] = allocation.startsAt
                this[ResourceAllocations.endsAt] = allocation.endsAt
                this[ResourceAllocations.capacityUnits] = allocation.capacityUnits
                this[ResourceAllocations.maximumCapacity] = request.maximumCapacity
                this[ResourceAllocations.allocationMode] = allocation.allocationMode
                this[ResourceAllocations.status] = ResourceAllocationStatus.ACTIVE
            }
        return insertedRows.map(::mapAllocation)
    }

    /**
     * 영속 proposal을 만들기 전에 자원 mutex를 잡고 현재 availability를 재검증합니다.
     *
     * 인기 자원에 다수의 신규 확정이 동시에 들어오면 loser가 appointment, commitment,
     * proposal, consent를 모두 쓴 뒤 충돌하는 비용이 커집니다. direct-confirm 경로는
     * 이 preflight를 먼저 호출해 winner가 mutex를 보유한 동안 loser를 안정 충돌로
     * 즉시 거절합니다. 반환된 [LockedResourceAvailability]를 같은 transaction의
     * [createConfirmedAllocations]에 전달하면 mutex를 다시 잠그지 않고 insert합니다.
     */
    fun lockAndValidateAvailability(
        tenantGroupId: Long,
        clinicId: Long,
        replacingProposalId: Long?,
        requests: List<ResourceAllocationRequest>,
    ): LockedResourceAvailability {
        val validTenantGroupId = tenantGroupId.requirePositiveNumber("tenantGroupId")
        val validClinicId = clinicId.requirePositiveNumber("clinicId")
        val validReplacingProposalId = replacingProposalId?.requirePositiveNumber("replacingProposalId")
        require(requests.isNotEmpty()) { "resource allocation requests must not be empty" }
        val resourceGroups =
            requests.groupBy { request ->
                ResourceMutexKey(
                    tenantGroupId = validTenantGroupId,
                    clinicId = validClinicId,
                    resourceType = request.allocation.resourceType,
                    resourceId = request.allocation.resourceId,
                )
            }
        resourceGroups.entries
            .sortedBy(Map.Entry<ResourceMutexKey, List<ResourceAllocationRequest>>::key)
            .forEach { (key, _) -> lockResourceMutex(key) }
        validateInternalConflicts(requests)
        validateExistingConflicts(
            tenantGroupId = validTenantGroupId,
            clinicId = validClinicId,
            replacingProposalId = validReplacingProposalId,
            resourceGroups = resourceGroups,
        )
        return LockedResourceAvailability.create(
            transactionIdentity = System.identityHashCode(TransactionManager.current()),
            tenantGroupId = validTenantGroupId,
            clinicId = validClinicId,
            replacingProposalId = validReplacingProposalId,
            requests = requests.toAvailabilityKeys(),
        )
    }

    /**
     * waitlist hold가 차지할 자원 mutex를 먼저 획득하고 confirmed allocation과 기존
     * active hold를 함께 검증합니다. 이 메서드는 transaction을 열지 않습니다.
     */
    fun lockAndValidateWaitlistCapacity(
        scope: WaitlistScope,
        hold: NewHold,
    ): WaitlistCapacityReservation =
        lockAndValidateWaitlistCapacity(
            tenantGroupId = scope.tenantGroupId,
            clinicId = scope.clinicId,
            hold = hold,
        )

    /** scope를 별도로 보유한 caller를 위한 waitlist capacity preflight입니다. */
    fun lockAndValidateWaitlistCapacity(
        tenantGroupId: Long,
        clinicId: Long,
        hold: NewHold,
    ): WaitlistCapacityReservation {
        val validTenantGroupId = tenantGroupId.requirePositiveNumber("tenantGroupId")
        val validClinicId = clinicId.requirePositiveNumber("clinicId")
        val key =
            ResourceMutexKey(
                tenantGroupId = validTenantGroupId,
                clinicId = validClinicId,
                resourceType = hold.resourceType,
                resourceId = hold.resourceId,
            )
        lockResourceMutex(key)
        validateWaitlistCapacity(
            tenantGroupId = validTenantGroupId,
            clinicId = validClinicId,
            hold = hold,
        )
        return WaitlistCapacityReservation(
            resourceType = hold.resourceType,
            resourceId = hold.resourceId,
            startsAt = hold.startsAt,
            endsAt = hold.endsAt,
            capacityUnits = hold.capacityUnits,
            maximumCapacity = hold.maximumCapacity,
        )
    }

    /** 기존 waitlist hold mutation 전에 같은 자원의 canonical mutex만 획득합니다. */
    internal fun lockWaitlistHoldResource(hold: WaitlistCapacityHoldRecord) {
        lockWaitlistResource(hold.scope, hold.resourceType, hold.resourceId)
    }

    /** hold가 유실된 claim이 offer snapshot으로 동일한 자원 mutex를 선점합니다. */
    internal fun lockWaitlistResource(
        scope: WaitlistScope,
        resourceType: ResourceType,
        resourceId: String,
    ) {
        lockResourceMutex(
            ResourceMutexKey(
                tenantGroupId = scope.tenantGroupId,
                clinicId = scope.clinicId,
                resourceType = resourceType,
                resourceId = resourceId,
            ),
        )
    }

    /** 이미 자원 mutex를 선점한 claim이 durable hold 복구 전 capacity를 재검증합니다. */
    internal fun validateWaitlistCapacityAfterResourceLock(
        scope: WaitlistScope,
        hold: NewHold,
    ) {
        validateWaitlistCapacity(
            tenantGroupId = scope.tenantGroupId,
            clinicId = scope.clinicId,
            hold = hold,
        )
    }

    /** offer insert가 끝난 뒤 같은 transaction에서 durable hold를 생성합니다. */
    fun reserveWaitlistCapacityHold(
        scope: WaitlistScope,
        offerId: Long,
        hold: NewHold,
        now: Instant = Instant.now(),
    ): WaitlistCapacityHoldRecord {
        val validOfferId = offerId.requirePositiveNumber("offerId")
        val id =
            WaitlistCapacityHolds.insertAndGetId {
                it[tenantGroupId] = scope.tenantGroupId
                it[clinicId] = scope.clinicId
                it[memberId] = scope.memberId.value
                it[WaitlistCapacityHolds.offerId] = validOfferId
                it[vacancyKey] = hold.vacancyKey
                it[activeVacancyKey] = hold.activeVacancyKey
                it[resourceType] = hold.resourceType
                it[resourceId] = hold.resourceId
                it[startsAt] = hold.startsAt
                it[endsAt] = hold.endsAt
                it[capacityUnits] = hold.capacityUnits
                it[maximumCapacity] = hold.maximumCapacity
                it[status] = WaitlistCapacityHoldState.OFFERED
                it[holdExpiresAt] = hold.holdExpiresAt
                it[version] = 0L
                it[createdAt] = now
                it[updatedAt] = now
            }.value
        return WaitlistCapacityHolds
            .selectAll()
            .where { WaitlistCapacityHolds.id eq id }
            .single()
            .toWaitlistCapacityHoldRecord()
    }

    /** replacement allocation 성공 뒤 accepted hold를 consumed로 CAS합니다. */
    fun consumeWaitlistCapacityHold(
        scope: WaitlistScope,
        holdId: Long,
        expectedVersion: Long,
        consumedAt: Instant = Instant.now(),
    ): Boolean {
        val validHoldId = holdId.requirePositiveNumber("holdId")
        require(expectedVersion >= 0L) { "expectedVersion must be zero or positive" }
        ensureWaitlistHoldScope(scope, validHoldId)
        return WaitlistCapacityHolds.update(
            where = {
                (WaitlistCapacityHolds.id eq validHoldId) and
                    (WaitlistCapacityHolds.tenantGroupId eq scope.tenantGroupId) and
                    (WaitlistCapacityHolds.clinicId eq scope.clinicId) and
                    (WaitlistCapacityHolds.memberId eq scope.memberId.value) and
                    (WaitlistCapacityHolds.status eq WaitlistCapacityHoldState.ACCEPTED) and
                    (WaitlistCapacityHolds.version eq expectedVersion)
            },
        ) {
            it[status] = WaitlistCapacityHoldState.CONSUMED
            it[version] = expectedVersion + 1L
            it[updatedAt] = consumedAt
            it[WaitlistCapacityHolds.consumedAt] = consumedAt
            it[WaitlistCapacityHolds.activeVacancyKey] = null
        } == 1
    }

    /** decline/withdraw/expiry 시 hold를 terminal 상태로 만들고 audit row를 보존합니다. */
    fun releaseWaitlistCapacityHold(
        scope: WaitlistScope,
        holdId: Long,
        terminal: WaitlistCapacityHoldState,
        releasedAt: Instant = Instant.now(),
    ): Boolean {
        require(terminal == WaitlistCapacityHoldState.RELEASED || terminal == WaitlistCapacityHoldState.EXPIRED) {
            "waitlist hold release terminal must be RELEASED or EXPIRED"
        }
        val validHoldId = holdId.requirePositiveNumber("holdId")
        val row = ensureWaitlistHoldScope(scope, validHoldId) ?: return false
        if (!row[WaitlistCapacityHolds.status].isActive) return false
        return WaitlistCapacityHolds.update(
            where = {
                (WaitlistCapacityHolds.id eq validHoldId) and
                    (WaitlistCapacityHolds.tenantGroupId eq scope.tenantGroupId) and
                    (WaitlistCapacityHolds.clinicId eq scope.clinicId) and
                    (WaitlistCapacityHolds.memberId eq scope.memberId.value) and
                    (WaitlistCapacityHolds.version eq row[WaitlistCapacityHolds.version])
            },
        ) {
            it[status] = terminal
            it[version] = row[WaitlistCapacityHolds.version] + 1L
            it[updatedAt] = releasedAt
            it[WaitlistCapacityHolds.releasedAt] = releasedAt.takeIf { terminal == WaitlistCapacityHoldState.RELEASED }
            it[WaitlistCapacityHolds.activeVacancyKey] = null
        } == 1
    }

    /** 만료 후보를 bounded batch로 조회하며 row를 삭제하지 않습니다. */
    fun findExpiredWaitlistHolds(
        limit: Int,
        now: Instant,
    ): List<WaitlistCapacityHoldRecord> {
        require(limit in 1..500) { "limit must be in 1..500" }
        return WaitlistCapacityHolds
            .selectAll()
            .where {
                activeWaitlistHoldCondition() and
                    (
                        (WaitlistCapacityHolds.holdExpiresAt lessEq now) or
                            (
                                (WaitlistCapacityHolds.status eq WaitlistCapacityHoldState.ACCEPTED) and
                                    (WaitlistCapacityHolds.startsAt lessEq now)
                            )
                    )
            }
            .orderBy(WaitlistCapacityHolds.holdExpiresAt to org.jetbrains.exposed.v1.core.SortOrder.ASC)
            .limit(limit)
            .forUpdate()
            .map { it.toWaitlistCapacityHoldRecord() }
    }

    /**
     * commitment CAS로 대체가 확정된 이전 proposal의 active allocation을 해제합니다.
     *
     * @return `ACTIVE`에서 `RELEASED`로 전환된 row 수입니다.
     */
    fun releaseActiveAllocations(
        proposalId: Long,
        releasedAt: Instant = Instant.now(),
    ): Int {
        val validProposalId = proposalId.requirePositiveNumber("proposalId")
        return ResourceAllocations.update(
            where = {
                (ResourceAllocations.proposalId eq validProposalId) and
                    (ResourceAllocations.status eq ResourceAllocationStatus.ACTIVE)
            },
        ) {
            it[status] = ResourceAllocationStatus.RELEASED
            it[ResourceAllocations.releasedAt] = releasedAt
        }
    }

    /** proposal의 allocation 이력을 생성 순서로 반환합니다. */
    fun findByProposal(proposalId: Long): List<ResourceAllocationRecord> {
        val validProposalId = proposalId.requirePositiveNumber("proposalId")
        return ResourceAllocations
            .selectAll()
            .where { ResourceAllocations.proposalId eq validProposalId }
            .orderBy(ResourceAllocations.id)
            .map(::mapAllocation)
    }

    /**
     * 교체 또는 fallback 전에 이전 proposal의 active allocation을 생성 순서로 잠급니다.
     */
    fun findActiveByProposalForUpdate(proposalId: Long): List<ResourceAllocationRecord> {
        val validProposalId = proposalId.requirePositiveNumber("proposalId")
        return ResourceAllocations
            .selectAll()
            .where {
                (ResourceAllocations.proposalId eq validProposalId) and
                    (ResourceAllocations.status eq ResourceAllocationStatus.ACTIVE)
            }.orderBy(ResourceAllocations.id)
            .forUpdate()
            .map(::mapAllocation)
    }

    /**
     * 동일 병원·자원의 overlap 검증을 직렬화하는 coarse-grained DB mutex를 획득합니다.
     *
     * [ResourceCapacityBuckets.bucketStartAt]에는 실제 예약 시각이 아니라 모든 지원
     * DB에서 안전한 고정 시각을 저장합니다. `maximumCapacity`도 사용량 정책이 아니라
     * mutex row의 필수 컬럼을 채우는 값입니다. 실제 상한은 proposal에 고정된
     * [ResourceAllocationRequest.maximumCapacity]와 활성 allocation으로 검증합니다.
     */
    private fun lockResourceMutex(key: ResourceMutexKey) {
        ResourceCapacityBuckets.insertIgnore {
            it[tenantGroupId] = key.tenantGroupId
            it[clinicId] = key.clinicId
            it[resourceType] = key.resourceType
            it[resourceId] = key.resourceId
            it[bucketStartAt] = RESOURCE_MUTEX_INSTANT
            it[ResourceCapacityBuckets.maximumCapacity] = RESOURCE_MUTEX_CAPACITY
        }
        val query =
            ResourceCapacityBuckets
            .selectAll()
            .where {
                resourceMutexCondition(key)
            }
        try {
            query
                .forUpdate(
                    ForUpdateOption.PostgreSQL.ForUpdate(ForUpdateOption.PostgreSQL.MODE.NO_WAIT),
                )
                .single()
        } catch (failure: ExposedSQLException) {
            if (failure.sqlState == POSTGRES_LOCK_UNAVAILABLE) {
                throw ResourceAllocationConflictException("resource confirmation is already in progress")
            }
            throw failure
        }
    }

    private fun resourceMutexCondition(key: ResourceMutexKey): Op<Boolean> =
        (ResourceCapacityBuckets.tenantGroupId eq key.tenantGroupId) and
            (ResourceCapacityBuckets.clinicId eq key.clinicId) and
            (ResourceCapacityBuckets.resourceType eq key.resourceType) and
            (ResourceCapacityBuckets.resourceId eq key.resourceId) and
            (ResourceCapacityBuckets.bucketStartAt eq RESOURCE_MUTEX_INSTANT)

    private fun validateInternalConflicts(requests: List<ResourceAllocationRequest>) {
        requests.forEachIndexed { index, first ->
            requests.drop(index + 1).forEach { second ->
                val firstAllocation = first.allocation
                val secondAllocation = second.allocation
                if (
                    firstAllocation.resourceType == secondAllocation.resourceType &&
                    firstAllocation.resourceId == secondAllocation.resourceId &&
                    firstAllocation.startsAt < secondAllocation.endsAt &&
                    firstAllocation.endsAt > secondAllocation.startsAt
                ) {
                    when {
                        firstAllocation.allocationMode == ResourceAllocationMode.EXCLUSIVE ||
                            secondAllocation.allocationMode == ResourceAllocationMode.EXCLUSIVE -> {
                            throw ResourceAllocationConflictException(
                                "request contains overlapping exclusive allocations",
                            )
                        }

                        firstAllocation.allocationMode != secondAllocation.allocationMode -> {
                            throw ResourceAllocationConflictException(
                                "request mixes allocation modes for one overlapping resource",
                            )
                        }
                    }
                }
            }
        }
    }

    private fun validateWaitlistCapacity(
        tenantGroupId: Long,
        clinicId: Long,
        hold: NewHold,
    ) {
        val existingAllocations =
            ResourceAllocations
                .selectAll()
                .where {
                    (ResourceAllocations.tenantGroupId eq tenantGroupId) and
                        (ResourceAllocations.clinicId eq clinicId) and
                        (ResourceAllocations.resourceType eq hold.resourceType) and
                        (ResourceAllocations.resourceId eq hold.resourceId) and
                        (ResourceAllocations.status eq ResourceAllocationStatus.ACTIVE) and
                        (ResourceAllocations.startsAt less hold.endsAt) and
                        (ResourceAllocations.endsAt greater hold.startsAt)
                }
                .forUpdate()
                .toList()
        val existingHolds =
            WaitlistCapacityHolds
                .selectAll()
                .where {
                    (WaitlistCapacityHolds.tenantGroupId eq tenantGroupId) and
                        (WaitlistCapacityHolds.clinicId eq clinicId) and
                        (WaitlistCapacityHolds.resourceType eq hold.resourceType) and
                        (WaitlistCapacityHolds.resourceId eq hold.resourceId) and
                        activeWaitlistHoldCondition() and
                        (WaitlistCapacityHolds.startsAt less hold.endsAt) and
                        (WaitlistCapacityHolds.endsAt greater hold.startsAt)
                }
                .forUpdate()
                .toList()

        if (hold.resourceType != ResourceType.CAPACITY_BUCKET) {
            if (existingAllocations.isNotEmpty() || existingHolds.isNotEmpty()) {
                throw ResourceAllocationConflictException("waitlist hold overlaps an active allocation")
            }
            return
        }

        if (existingAllocations.any { it[ResourceAllocations.allocationMode] != ResourceAllocationMode.CAPACITY_BUCKET }) {
            throw ResourceAllocationConflictException("waitlist bucket overlaps an incompatible allocation")
        }
        val checkpoints =
            buildSet {
                add(hold.startsAt)
                existingAllocations.mapTo(this) { it[ResourceAllocations.startsAt] }
                existingHolds.mapTo(this) { it[WaitlistCapacityHolds.startsAt] }
            }
        checkpoints.forEach { checkpoint ->
            val allocationUnits = existingAllocations.sumOf { row ->
                if (
                    row[ResourceAllocations.startsAt] <= checkpoint &&
                    row[ResourceAllocations.endsAt] > checkpoint
                ) {
                    row[ResourceAllocations.capacityUnits]
                } else {
                    0
                }
            }
            val holdUnits = existingHolds.sumOf { row ->
                if (
                    row[WaitlistCapacityHolds.startsAt] <= checkpoint &&
                    row[WaitlistCapacityHolds.endsAt] > checkpoint
                ) {
                    row[WaitlistCapacityHolds.capacityUnits]
                } else {
                    0
                }
            }
            val newUnits =
                if (hold.startsAt <= checkpoint && hold.endsAt > checkpoint) {
                    hold.capacityUnits
                } else {
                    0
                }
            val maxima = buildList {
                add(hold.maximumCapacity)
                existingAllocations
                    .filter { row ->
                        row[ResourceAllocations.startsAt] <= checkpoint &&
                            row[ResourceAllocations.endsAt] > checkpoint
                    }
                    .mapTo(this) { it[ResourceAllocations.maximumCapacity] }
                existingHolds
                    .filter { row ->
                        row[WaitlistCapacityHolds.startsAt] <= checkpoint &&
                            row[WaitlistCapacityHolds.endsAt] > checkpoint
                    }
                    .mapTo(this) { it[WaitlistCapacityHolds.maximumCapacity] }
            }
            if (allocationUnits + holdUnits + newUnits > maxima.min()) {
                throw ResourceAllocationConflictException("waitlist bucket capacity is exhausted")
            }
        }
    }

    private fun ensureWaitlistHoldScope(
        scope: WaitlistScope,
        holdId: Long,
    ): ResultRow? {
        val scoped =
            WaitlistCapacityHolds
                .selectAll()
                .where {
                    (WaitlistCapacityHolds.id eq holdId) and
                        (WaitlistCapacityHolds.tenantGroupId eq scope.tenantGroupId) and
                        (WaitlistCapacityHolds.clinicId eq scope.clinicId) and
                        (WaitlistCapacityHolds.memberId eq scope.memberId.value)
                }
                .forUpdate()
                .singleOrNull()
        if (scoped != null) return scoped
        if (WaitlistCapacityHolds.selectAll().where { WaitlistCapacityHolds.id eq holdId }.any()) {
            throw HoldScopeMismatch(holdId)
        }
        return null
    }

    private fun validateExistingConflicts(
        tenantGroupId: Long,
        clinicId: Long,
        replacingProposalId: Long?,
        resourceGroups: Map<ResourceMutexKey, List<ResourceAllocationRequest>>,
    ) {
        resourceGroups.forEach { (_, groupedRequests) ->
            val groupStart = groupedRequests.minOf { it.allocation.startsAt }
            val groupEnd = groupedRequests.maxOf { it.allocation.endsAt }
            val representative = groupedRequests.first()
            val representativeAllocation = representative.allocation
            val query =
                ResourceAllocations
                    .selectAll()
                    .where {
                        (ResourceAllocations.tenantGroupId eq tenantGroupId) and
                            (ResourceAllocations.clinicId eq clinicId) and
                            (ResourceAllocations.resourceType eq representativeAllocation.resourceType) and
                            (ResourceAllocations.resourceId eq representativeAllocation.resourceId) and
                            (ResourceAllocations.status eq ResourceAllocationStatus.ACTIVE) and
                            (ResourceAllocations.startsAt less groupEnd) and
                            (ResourceAllocations.endsAt greater groupStart)
                    }
            replacingProposalId?.let { oldProposalId ->
                query.andWhere { ResourceAllocations.proposalId neq oldProposalId }
            }
            /*
             * mutex를 canonical 순서로 먼저 잡았기 때문에 이 locking read도 같은 자원
             * 순서를 유지하며, 직전 winner가 커밋한 allocation을 읽는다.
             */
            val existing = query.forUpdate().toList()
            val existingWaitlistHolds =
                WaitlistCapacityHolds
                    .selectAll()
                    .where {
                        (WaitlistCapacityHolds.tenantGroupId eq tenantGroupId) and
                            (WaitlistCapacityHolds.clinicId eq clinicId) and
                            (WaitlistCapacityHolds.resourceType eq representativeAllocation.resourceType) and
                            (WaitlistCapacityHolds.resourceId eq representativeAllocation.resourceId) and
                            activeWaitlistHoldCondition() and
                            (WaitlistCapacityHolds.startsAt less groupEnd) and
                            (WaitlistCapacityHolds.endsAt greater groupStart)
                    }
                    .forUpdate()
                    .toList()
            groupedRequests.forEach { request ->
                validateExistingOverlap(request, existing)
                validateWaitlistHoldOverlap(request, existingWaitlistHolds)
            }
            val capacityRequests =
                groupedRequests.filter { request ->
                    request.allocation.allocationMode == ResourceAllocationMode.CAPACITY_BUCKET
                }
            if (capacityRequests.isNotEmpty()) {
                validateCapacityTimeline(
                    requests = capacityRequests,
                    existing = existing,
                    existingWaitlistHolds = existingWaitlistHolds,
                )
            }
        }
    }

    /**
     * 요청 하나의 실제 구간과 겹치는 활성 allocation만 모드 호환성에 포함합니다.
     */
    private fun validateExistingOverlap(
        request: ResourceAllocationRequest,
        existing: List<ResultRow>,
    ) {
        val allocation = request.allocation
        val overlapping =
            existing.filter { row ->
                row[ResourceAllocations.startsAt] < allocation.endsAt &&
                    row[ResourceAllocations.endsAt] > allocation.startsAt
            }
        when (allocation.allocationMode) {
            ResourceAllocationMode.EXCLUSIVE -> {
                if (overlapping.isNotEmpty()) {
                    throw ResourceAllocationConflictException(
                        "exclusive resource overlaps an active allocation",
                    )
                }
            }

            ResourceAllocationMode.SHARED -> {
                if (
                    overlapping.any {
                        it[ResourceAllocations.allocationMode] != ResourceAllocationMode.SHARED
                    }
                ) {
                    throw ResourceAllocationConflictException(
                        "shared resource overlaps an incompatible allocation mode",
                    )
                }
            }

            ResourceAllocationMode.CAPACITY_BUCKET -> {
                if (
                    overlapping.any {
                        it[ResourceAllocations.allocationMode] != ResourceAllocationMode.CAPACITY_BUCKET
                    }
                ) {
                    throw ResourceAllocationConflictException(
                        "capacity resource overlaps an incompatible allocation mode",
                    )
                }
            }
        }
    }

    /** 활성 waitlist hold를 confirmed allocation과 같은 occupancy domain에 포함합니다. */
    private fun validateWaitlistHoldOverlap(
        request: ResourceAllocationRequest,
        existing: List<ResultRow>,
    ) {
        if (existing.isEmpty()) return
        val allocation = request.allocation
        if (allocation.allocationMode != ResourceAllocationMode.CAPACITY_BUCKET) {
            throw ResourceAllocationConflictException(
                "waitlist hold overlaps an exclusive or shared allocation",
            )
        }
        if (existing.any { it[WaitlistCapacityHolds.resourceType] != ResourceType.CAPACITY_BUCKET }) {
            throw ResourceAllocationConflictException(
                "capacity allocation overlaps a non-bucket waitlist hold",
            )
        }
    }

    /**
     * 서로 다른 시작·종료 시각을 가진 패키지 항목을 half-open 구간으로 합산합니다.
     *
     * 사용량은 allocation 시작점에서만 증가하므로 요청 또는 기존 allocation의 모든
     * 시작점을 검사하면 구간별 최대값을 정확히 찾을 수 있습니다. 그룹 시작 전에 이미
     * 활성인 allocation을 포함하기 위해 첫 요청 시작점도 항상 검사합니다.
     */
    private fun validateCapacityTimeline(
        requests: List<ResourceAllocationRequest>,
        existing: List<ResultRow>,
        existingWaitlistHolds: List<ResultRow>,
    ) {
        val firstRequestStart = requests.minOf { it.allocation.startsAt }
        val checkpoints =
            buildSet {
                add(firstRequestStart)
                requests.mapTo(this) { it.allocation.startsAt }
                existing.mapTo(this) { it[ResourceAllocations.startsAt] }
                existingWaitlistHolds.mapTo(this) { it[WaitlistCapacityHolds.startsAt] }
            }
        checkpoints.forEach { checkpoint ->
            val activeRequests =
                requests
                    .filter { request ->
                        request.allocation.startsAt <= checkpoint &&
                            request.allocation.endsAt > checkpoint
                    }
            if (activeRequests.isEmpty()) {
                return@forEach
            }
            val activeExisting =
                existing
                    .filter { row ->
                        row[ResourceAllocations.startsAt] <= checkpoint &&
                            row[ResourceAllocations.endsAt] > checkpoint
                    }
            val activeWaitlistHolds =
                existingWaitlistHolds
                    .filter { row ->
                        row[WaitlistCapacityHolds.startsAt] <= checkpoint &&
                            row[WaitlistCapacityHolds.endsAt] > checkpoint
                    }
            val effectiveMaximum =
                (
                    activeRequests.map(ResourceAllocationRequest::maximumCapacity) +
                        activeExisting.map { row ->
                            row[ResourceAllocations.maximumCapacity]
                        } +
                        activeWaitlistHolds.map { row ->
                            row[WaitlistCapacityHolds.maximumCapacity]
                        }
                ).min()
            val requestedUnits = activeRequests.sumOf { it.allocation.capacityUnits }
            val existingUnits = activeExisting.sumOf { it[ResourceAllocations.capacityUnits] }
            val waitlistHoldUnits = activeWaitlistHolds.sumOf { it[WaitlistCapacityHolds.capacityUnits] }
            if (existingUnits + waitlistHoldUnits + requestedUnits > effectiveMaximum) {
                throw ResourceAllocationConflictException("resource capacity bucket is exhausted")
            }
        }
    }

    private fun mapAllocation(row: ResultRow) =
        ResourceAllocationRecord(
            id = row[ResourceAllocations.id].value,
            tenantGroupId = row[ResourceAllocations.tenantGroupId].value,
            clinicId = row[ResourceAllocations.clinicId].value,
            proposalId = row[ResourceAllocations.proposalId].value,
            allocation =
                ResourceAllocationDraft(
                    resourceType = row[ResourceAllocations.resourceType],
                    resourceId = row[ResourceAllocations.resourceId],
                    startsAt = row[ResourceAllocations.startsAt],
                    endsAt = row[ResourceAllocations.endsAt],
                    capacityUnits = row[ResourceAllocations.capacityUnits],
                    maximumCapacity = row[ResourceAllocations.maximumCapacity],
                    allocationMode = row[ResourceAllocations.allocationMode],
                    appointmentItemKey = row[ResourceAllocations.appointmentItemKey],
                ),
            maximumCapacity = row[ResourceAllocations.maximumCapacity],
            status = row[ResourceAllocations.status],
        )

    private data class ResourceMutexKey(
        val tenantGroupId: Long,
        val clinicId: Long,
        val resourceType: ResourceType,
        val resourceId: String,
    ) : Comparable<ResourceMutexKey>,
        Serializable {
        override fun compareTo(other: ResourceMutexKey): Int =
            compareValuesBy(
                this,
                other,
                ResourceMutexKey::tenantGroupId,
                ResourceMutexKey::clinicId,
                { it.resourceType.name },
                ResourceMutexKey::resourceId,
            )

        companion object {
            private const val serialVersionUID = 1L
        }
    }

    private companion object {
        val RESOURCE_MUTEX_INSTANT: Instant = Instant.parse("2000-01-01T00:00:00Z")
        const val RESOURCE_MUTEX_CAPACITY = 1
        const val POSTGRES_LOCK_UNAVAILABLE = "55P03"
    }
}

/**
 * 한 Exposed transaction에서 완료된 자원 availability 검증을 증명하는 불투명 증표입니다.
 *
 * 생성자는 repository module 밖에서 호출할 수 없습니다. 이 증표는 직렬화·캐시·영속화
 * 대상이 아니며, 생성 transaction과 동일한 scope·교체 proposal·자원 요청에 한해서만
 * allocation insert의 중복 잠금을 생략합니다.
 */
class LockedResourceAvailability private constructor(
    private val transactionIdentity: Int,
    private val tenantGroupId: Long,
    private val clinicId: Long,
    private val replacingProposalId: Long?,
    private val requests: List<ResourceAvailabilityKey>,
) {
    internal fun requireMatches(
        tenantGroupId: Long,
        clinicId: Long,
        replacingProposalId: Long?,
        requests: List<ResourceAllocationRequest>,
    ) {
        require(transactionIdentity == System.identityHashCode(TransactionManager.current())) {
            "availability lock belongs to a different transaction"
        }
        require(
            this.tenantGroupId == tenantGroupId &&
                this.clinicId == clinicId &&
                this.replacingProposalId == replacingProposalId &&
                this.requests == requests.toAvailabilityKeys()
        ) {
            "availability lock does not match allocation request"
        }
    }

    internal companion object {
        fun create(
            transactionIdentity: Int,
            tenantGroupId: Long,
            clinicId: Long,
            replacingProposalId: Long?,
            requests: List<ResourceAvailabilityKey>,
        ): LockedResourceAvailability =
            LockedResourceAvailability(
                transactionIdentity = transactionIdentity,
                tenantGroupId = tenantGroupId,
                clinicId = clinicId,
                replacingProposalId = replacingProposalId,
                requests = requests,
            )
    }
}

internal data class ResourceAvailabilityKey(
    val appointmentItemKey: String?,
    val resourceType: ResourceType,
    val resourceId: String,
    val startsAt: Instant,
    val endsAt: Instant,
    val capacityUnits: Int,
    val maximumCapacity: Int,
    val allocationMode: ResourceAllocationMode,
)

private fun List<ResourceAllocationRequest>.toAvailabilityKeys(): List<ResourceAvailabilityKey> =
    map { request ->
        val allocation = request.allocation
        ResourceAvailabilityKey(
            appointmentItemKey = allocation.appointmentItemKey,
            resourceType = allocation.resourceType,
            resourceId = allocation.resourceId,
            startsAt = allocation.startsAt,
            endsAt = allocation.endsAt,
            capacityUnits = allocation.capacityUnits,
            maximumCapacity = request.maximumCapacity,
            allocationMode = allocation.allocationMode,
        )
    }

private fun activeWaitlistHoldCondition(): Op<Boolean> =
    (WaitlistCapacityHolds.status eq WaitlistCapacityHoldState.OFFERED) or
        (WaitlistCapacityHolds.status eq WaitlistCapacityHoldState.ACCEPTED)

private fun ResultRow.toWaitlistCapacityHoldRecord(): WaitlistCapacityHoldRecord =
    WaitlistCapacityHoldRecord(
        id = this[WaitlistCapacityHolds.id].value,
        scope = WaitlistScope(
            tenantGroupId = this[WaitlistCapacityHolds.tenantGroupId].value,
            clinicId = this[WaitlistCapacityHolds.clinicId].value,
            memberId = io.bluetape4k.clinic.appointment.model.identity.MemberId(this[WaitlistCapacityHolds.memberId]),
        ),
        offerId = this[WaitlistCapacityHolds.offerId].value,
        vacancyKey = this[WaitlistCapacityHolds.vacancyKey],
        activeVacancyKey = this[WaitlistCapacityHolds.activeVacancyKey],
        resourceType = this[WaitlistCapacityHolds.resourceType],
        resourceId = this[WaitlistCapacityHolds.resourceId],
        startsAt = this[WaitlistCapacityHolds.startsAt],
        endsAt = this[WaitlistCapacityHolds.endsAt],
        capacityUnits = this[WaitlistCapacityHolds.capacityUnits],
        maximumCapacity = this[WaitlistCapacityHolds.maximumCapacity],
        status = this[WaitlistCapacityHolds.status],
        holdExpiresAt = this[WaitlistCapacityHolds.holdExpiresAt],
        version = this[WaitlistCapacityHolds.version],
        createdAt = this[WaitlistCapacityHolds.createdAt],
        updatedAt = this[WaitlistCapacityHolds.updatedAt],
        releasedAt = this[WaitlistCapacityHolds.releasedAt],
        consumedAt = this[WaitlistCapacityHolds.consumedAt],
    )

/**
 * 자원 점유 또는 capacity 상한 충돌로 확정 transaction을 진행할 수 없음을 나타냅니다.
 */
class ResourceAllocationConflictException(
    message: String,
) : IllegalStateException(message)
