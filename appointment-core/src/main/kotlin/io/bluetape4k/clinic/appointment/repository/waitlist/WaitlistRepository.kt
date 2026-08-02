package io.bluetape4k.clinic.appointment.repository.waitlist

import io.bluetape4k.clinic.appointment.model.identity.MemberId
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.Doctors
import io.bluetape4k.clinic.appointment.model.tables.TenantGroups
import io.bluetape4k.clinic.appointment.model.tables.TreatmentTypes
import io.bluetape4k.clinic.appointment.model.tables.WaitlistCapacityHolds
import io.bluetape4k.clinic.appointment.model.tables.WaitlistEntries
import io.bluetape4k.clinic.appointment.model.tables.WaitlistOfferEvents
import io.bluetape4k.clinic.appointment.model.tables.WaitlistOffers
import io.bluetape4k.clinic.appointment.model.waitlist.DecisionStamp
import io.bluetape4k.clinic.appointment.model.waitlist.HoldScopeMismatch
import io.bluetape4k.clinic.appointment.model.waitlist.NewHold
import io.bluetape4k.clinic.appointment.model.waitlist.NewOffer
import io.bluetape4k.clinic.appointment.model.waitlist.OfferAlreadyExists
import io.bluetape4k.clinic.appointment.model.waitlist.OfferHoldIds
import io.bluetape4k.clinic.appointment.model.waitlist.OfferScopeMismatch
import io.bluetape4k.clinic.appointment.model.waitlist.VacancyDescriptor
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistCapacityHoldRecord
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistCapacityHoldState
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistCursor
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistEntryRecord
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistEntryState
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistOfferEventRecord
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistOfferRecord
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistOfferState
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistReasonCode
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistScope
import io.bluetape4k.support.requirePositiveNumber
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.time.ZoneOffset

/**
 * waitlist entry, offer, hold, history row를 caller-owned transaction 안에서 다룹니다.
 *
 * 이 repository는 transaction을 직접 열지 않습니다. 서비스 계층은 자원 mutex, reliability
 * recheck, row mutation을 하나의 Exposed transaction으로 묶어 호출해야 합니다.
 */
class WaitlistRepository {

    /** vacancy scope와 keyset cursor에 맞는 WAITING entry 후보를 조회합니다. */
    fun findCandidatePage(
        vacancy: VacancyDescriptor,
        cursor: WaitlistCursor?,
        limit: Int,
    ): List<WaitlistEntryRecord> {
        require(limit in 1..500) { "limit must be in 1..500" }
        val rows = mutableListOf<WaitlistEntryRecord>()
        if (vacancy.doctorId != null && cursor.allowsSlotFit(SLOT_FIT_EXACT)) {
            rows += selectCandidateRows(
                vacancy = vacancy,
                slotFit = SLOT_FIT_EXACT,
                cursor = cursor,
                limit = limit,
            )
        }
        if (rows.size < limit && cursor.allowsSlotFit(SLOT_FIT_UNSPECIFIED)) {
            rows += selectCandidateRows(
                vacancy = vacancy,
                slotFit = SLOT_FIT_UNSPECIFIED,
                cursor = cursor,
                limit = limit - rows.size,
            )
        }
        return rows.take(limit)
    }

    /** scope가 일치하는 offer row를 비관 잠금으로 읽습니다. */
    fun findOfferForUpdate(scope: WaitlistScope, offerId: Long): WaitlistOfferRecord? {
        offerId.requirePositiveNumber("offerId")
        return WaitlistOffers
            .selectAll()
            .where { offerScopeCondition(scope) and (WaitlistOffers.id eq offerId) }
            .forUpdate()
            .singleOrNull()
            ?.takeIf { row -> row[WaitlistOffers.memberId] == scope.memberId.value }
            ?.toOfferRecord()
    }

    /** hold가 유실된 claim이 자원 snapshot으로 canonical mutex를 찾을 수 있도록 offer를 읽습니다. */
    fun findOffer(scope: WaitlistScope, offerId: Long): WaitlistOfferRecord? {
        offerId.requirePositiveNumber("offerId")
        return WaitlistOffers
            .selectAll()
            .where { offerScopeCondition(scope) and (WaitlistOffers.id eq offerId) }
            .singleOrNull()
            ?.takeIf { row -> row[WaitlistOffers.memberId] == scope.memberId.value }
            ?.toOfferRecord()
    }

    /** scope가 일치하는 waitlist entry row를 비관 잠금으로 읽습니다. */
    fun findEntryForUpdate(scope: WaitlistScope, entryId: Long): WaitlistEntryRecord? {
        entryId.requirePositiveNumber("entryId")
        return WaitlistEntries
            .selectAll()
            .where { entryScopeCondition(scope) and (WaitlistEntries.id eq entryId) }
            .forUpdate()
            .singleOrNull()
            ?.takeIf { row -> row[WaitlistEntries.memberId] == scope.memberId.value }
            ?.toEntryRecord()
    }

    /** scope가 일치하는 capacity hold row를 비관 잠금으로 읽습니다. */
    fun findHoldForUpdate(scope: WaitlistScope, holdId: Long): WaitlistCapacityHoldRecord? {
        holdId.requirePositiveNumber("holdId")
        return WaitlistCapacityHolds
            .selectAll()
            .where { holdScopeCondition(scope) and (WaitlistCapacityHolds.id eq holdId) }
            .forUpdate()
            .singleOrNull()
            ?.takeIf { row -> row[WaitlistCapacityHolds.memberId] == scope.memberId.value }
            ?.toHoldRecord()
    }

    /** offer에 연결된 hold를 scope 검증 없이 잠그지 않고 조회합니다. */
    fun findHoldByOffer(scope: WaitlistScope, offerId: Long): WaitlistCapacityHoldRecord? {
        offerId.requirePositiveNumber("offerId")
        return WaitlistCapacityHolds
            .selectAll()
            .where {
                holdScopeCondition(scope) and
                    (WaitlistCapacityHolds.offerId eq offerId)
            }
            .singleOrNull()
            ?.takeIf { row -> row[WaitlistCapacityHolds.memberId] == scope.memberId.value }
            ?.toHoldRecord()
    }

    /** offer에 연결된 hold를 scope 검증과 함께 비관 잠금으로 읽습니다. */
    fun findHoldByOfferForUpdate(scope: WaitlistScope, offerId: Long): WaitlistCapacityHoldRecord? {
        offerId.requirePositiveNumber("offerId")
        return WaitlistCapacityHolds
            .selectAll()
            .where {
                holdScopeCondition(scope) and
                    (WaitlistCapacityHolds.offerId eq offerId)
            }
            .forUpdate()
            .singleOrNull()
            ?.takeIf { row -> row[WaitlistCapacityHolds.memberId] == scope.memberId.value }
            ?.toHoldRecord()
    }

    /** mutation 대상 hold가 없거나 scope가 다르면 stable domain exception을 던집니다. */
    fun requireHoldForMutation(scope: WaitlistScope, holdId: Long): WaitlistCapacityHoldRecord =
        findHoldForUpdate(scope, holdId) ?: throw HoldScopeMismatch(holdId)

    /** offer와 durable capacity hold를 같은 transaction에서 함께 삽입합니다. */
    fun insertOfferAndHold(
        scope: WaitlistScope,
        entry: WaitlistEntryRecord,
        offer: NewOffer,
        hold: NewHold,
        now: Instant = Instant.now(),
    ): OfferHoldIds {
        if (entry.scope != scope) {
            throw OfferScopeMismatch(entry.id)
        }
        val offerId = insertOffer(scope, entry, offer, hold, now)
        val holdId =
            try {
                WaitlistCapacityHolds.insertAndGetId {
                    it[tenantGroupId] = EntityID(scope.tenantGroupId, TenantGroups)
                    it[clinicId] = EntityID(scope.clinicId, Clinics)
                    it[memberId] = scope.memberId.value
                    it[WaitlistCapacityHolds.offerId] = EntityID(offerId, WaitlistOffers)
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
                    it[version] = INITIAL_VERSION
                    it[createdAt] = now
                    it[updatedAt] = now
                }.value
            } catch (ex: Exception) {
                WaitlistOffers.deleteWhere { WaitlistOffers.id eq offerId }
                throw ex
            }
        return OfferHoldIds(offerId = offerId, holdId = holdId)
    }

    /** offer 상태·version·scope를 조건으로 compare-and-set 합니다. */
    fun casOffer(
        scope: WaitlistScope,
        offerId: Long,
        expectedVersion: Long,
        from: WaitlistOfferState,
        to: WaitlistOfferState,
        now: Instant = Instant.now(),
    ): Boolean {
        offerId.requirePositiveNumber("offerId")
        require(expectedVersion >= 0L) { "expectedVersion must be zero or positive" }
        val updated = WaitlistOffers.update({
            offerScopeCondition(scope) and
                (WaitlistOffers.id eq offerId) and
                (WaitlistOffers.memberId eq scope.memberId.value) and
                (WaitlistOffers.version eq expectedVersion) and
                (WaitlistOffers.status eq from)
        }) { row ->
            row[status] = to
            row[version] = expectedVersion + 1L
            row[updatedAt] = now
            if (to.isTerminal) {
                row[activeEntryKey] = null
                row[activeVacancyKey] = null
            }
        }
        return updated == 1
    }

    /** hold 상태·version·scope를 조건으로 compare-and-set 하고 terminal key를 정리합니다. */
    fun casHold(
        scope: WaitlistScope,
        holdId: Long,
        expectedVersion: Long,
        from: WaitlistCapacityHoldState,
        to: WaitlistCapacityHoldState,
        now: Instant = Instant.now(),
    ): Boolean {
        holdId.requirePositiveNumber("holdId")
        require(expectedVersion >= 0L) { "expectedVersion must be zero or positive" }
        val current =
            WaitlistCapacityHolds
                .selectAll()
                .where {
                    holdScopeCondition(scope) and
                        (WaitlistCapacityHolds.id eq holdId) and
                        (WaitlistCapacityHolds.memberId eq scope.memberId.value) and
                        (WaitlistCapacityHolds.version eq expectedVersion) and
                        (WaitlistCapacityHolds.status eq from)
                }
                .forUpdate()
                .singleOrNull()
                ?: return false
        val updated = WaitlistCapacityHolds.update({
            holdScopeCondition(scope) and
                (WaitlistCapacityHolds.id eq holdId) and
                (WaitlistCapacityHolds.memberId eq scope.memberId.value) and
                (WaitlistCapacityHolds.version eq expectedVersion) and
                (WaitlistCapacityHolds.status eq from)
        }) { row ->
            row[status] = to
            row[version] = expectedVersion + 1L
            row[updatedAt] = now
            if (to == WaitlistCapacityHoldState.ACCEPTED) {
                // ACCEPTED hold는 slot 시작 시각까지만 replacement handoff를 허용합니다.
                // OFFERED TTL이 더 길어도 recovery가 slot 이후 hold를 active로 남기지 않도록
                // 이 전이에서 deadline을 immutable slot 상한으로 수렴시킵니다.
                row[holdExpiresAt] = minOf(
                    current[WaitlistCapacityHolds.holdExpiresAt],
                    current[WaitlistCapacityHolds.startsAt],
                )
            }
            if (to.isTerminal) {
                row[activeVacancyKey] = null
            }
            when (to) {
                WaitlistCapacityHoldState.CONSUMED -> row[consumedAt] = now
                WaitlistCapacityHoldState.RELEASED,
                WaitlistCapacityHoldState.EXPIRED -> row[releasedAt] = now
                WaitlistCapacityHoldState.OFFERED,
                WaitlistCapacityHoldState.ACCEPTED -> Unit
            }
        }
        return updated == 1
    }

    /** entry 상태·version·scope를 조건으로 compare-and-set 합니다. */
    fun casEntry(
        scope: WaitlistScope,
        entryId: Long,
        expectedVersion: Long,
        from: WaitlistEntryState,
        to: WaitlistEntryState,
        now: Instant = Instant.now(),
    ): Boolean {
        entryId.requirePositiveNumber("entryId")
        require(expectedVersion >= 0L) { "expectedVersion must be zero or positive" }
        val updated = WaitlistEntries.update({
            entryScopeCondition(scope) and
                (WaitlistEntries.id eq entryId) and
                (WaitlistEntries.memberId eq scope.memberId.value) and
                (WaitlistEntries.version eq expectedVersion) and
                (WaitlistEntries.status eq from)
        }) { row ->
            row[status] = to
            row[version] = expectedVersion + 1L
            row[updatedAt] = now
        }
        return updated == 1
    }

    /** waitlist lifecycle 전이 history를 append-only row로 기록합니다. */
    fun appendEvent(event: WaitlistOfferEventRecord): Long {
        return WaitlistOfferEvents.insertAndGetId {
            it[waitlistEntryId] = EntityID(event.waitlistEntryId, WaitlistEntries)
            it[offerId] = event.offerId?.let { id -> EntityID(id, WaitlistOffers) }
            it[holdId] = event.holdId?.let { id -> EntityID(id, WaitlistCapacityHolds) }
            it[WaitlistOfferEvents.fromState] = event.fromState?.name
            it[toState] = event.toState.name
            it[reasonCode] = event.reasonCode.code
            it[actorRef] = event.actorRef.value
            it[correlationId] = event.correlationId.value
            it[occurredAt] = event.occurredAt
            it[eventVersion] = event.eventVersion
        }.value
    }

    /** active OFFERED/ACCEPTED hold 중 만료·시작된 bounded recovery 후보를 조회합니다. */
    fun findExpiredHolds(limit: Int, now: Instant): List<WaitlistCapacityHoldRecord> {
        require(limit in 1..500) { "limit must be in 1..500" }
        return WaitlistCapacityHolds
            .selectAll()
            .where {
                (WaitlistCapacityHolds.status inList WaitlistCapacityHoldState.activeStates.toList()) and
                    (
                        (WaitlistCapacityHolds.holdExpiresAt lessEq now) or
                            (
                                (WaitlistCapacityHolds.status inList listOf(
                                    WaitlistCapacityHoldState.OFFERED,
                                    WaitlistCapacityHoldState.ACCEPTED,
                                )) and
                                    (WaitlistCapacityHolds.startsAt lessEq now)
                            )
                    )
            }
            .orderBy(
                WaitlistCapacityHolds.holdExpiresAt to SortOrder.ASC,
                WaitlistCapacityHolds.id to SortOrder.ASC,
            )
            .limit(limit)
            .map { it.toHoldRecord() }
    }

    private fun selectCandidateRows(
        vacancy: VacancyDescriptor,
        slotFit: Int,
        cursor: WaitlistCursor?,
        limit: Int,
    ): List<WaitlistEntryRecord> =
        WaitlistEntries
            .selectAll()
            .where {
                candidateBaseCondition(vacancy) and
                    doctorCondition(vacancy, slotFit) and
                    keysetCondition(slotFit, cursor)
            }
            .orderBy(
                WaitlistEntries.priorityRank to SortOrder.DESC,
                WaitlistEntries.waitingSince to SortOrder.ASC,
                WaitlistEntries.id to SortOrder.ASC,
            )
            .limit(limit)
            .map { it.toEntryRecord() }

    private fun candidateBaseCondition(vacancy: VacancyDescriptor): Op<Boolean> {
        val vacancyDateTime = vacancy.startsAt.atOffset(ZoneOffset.UTC)
        val vacancyEndTime = vacancy.endsAt.atOffset(ZoneOffset.UTC).toLocalTime()
        val startsAtDate = vacancyDateTime.toLocalDate()
        val startsAtTime = vacancyDateTime.toLocalTime()
        return (WaitlistEntries.tenantGroupId eq vacancy.tenantGroupId) and
            (WaitlistEntries.clinicId eq vacancy.clinicId) and
            (WaitlistEntries.treatmentTypeId eq vacancy.treatmentTypeId) and
            (WaitlistEntries.status eq WaitlistEntryState.WAITING) and
            (WaitlistEntries.preferredDateFrom lessEq startsAtDate) and
            (WaitlistEntries.preferredDateTo greaterEq startsAtDate) and
            (WaitlistEntries.preferredStartTime lessEq startsAtTime) and
            (WaitlistEntries.preferredEndTime greaterEq vacancyEndTime)
    }

    private fun doctorCondition(vacancy: VacancyDescriptor, slotFit: Int): Op<Boolean> =
        when (slotFit) {
            SLOT_FIT_EXACT -> WaitlistEntries.doctorId eq checkNotNull(vacancy.doctorId) {
                "doctorId must be present for exact slot fit"
            }
            SLOT_FIT_UNSPECIFIED -> WaitlistEntries.doctorId.isNull()
            else -> error("unsupported slotFit")
        }

    private fun keysetCondition(slotFit: Int, cursor: WaitlistCursor?): Op<Boolean> {
        if (cursor == null || cursor.slotFit > slotFit) {
            return Op.TRUE
        }
        if (cursor.slotFit < slotFit) {
            return Op.FALSE
        }
        return (WaitlistEntries.priorityRank less cursor.priorityRank) or
            ((WaitlistEntries.priorityRank eq cursor.priorityRank) and
                (WaitlistEntries.waitingSince greater cursor.waitingSince)) or
            ((WaitlistEntries.priorityRank eq cursor.priorityRank) and
                (WaitlistEntries.waitingSince eq cursor.waitingSince) and
                (WaitlistEntries.id greater cursor.entryId))
    }

    private fun WaitlistCursor?.allowsSlotFit(slotFit: Int): Boolean =
        this == null || this.slotFit >= slotFit

    private fun insertOffer(
        scope: WaitlistScope,
        entry: WaitlistEntryRecord,
        offer: NewOffer,
        hold: NewHold,
        now: Instant,
    ): Long =
        try {
            WaitlistOffers.insertAndGetId {
                it[tenantGroupId] = EntityID(scope.tenantGroupId, TenantGroups)
                it[clinicId] = EntityID(scope.clinicId, Clinics)
                it[memberId] = scope.memberId.value
                it[waitlistEntryId] = EntityID(entry.id, WaitlistEntries)
                it[vacancyKey] = offer.vacancyKey
                it[activeEntryKey] = offer.activeEntryKey
                it[activeVacancyKey] = offer.activeVacancyKey
                it[resourceType] = hold.resourceType
                it[resourceId] = hold.resourceId
                it[capacityUnits] = hold.capacityUnits
                it[maximumCapacity] = hold.maximumCapacity
                it[doctorId] = offer.doctorId
                it[treatmentTypeId] = offer.treatmentTypeId
                it[startsAt] = offer.startsAt
                it[endsAt] = offer.endsAt
                it[expiresAt] = offer.expiresAt
                it[status] = WaitlistOfferState.OFFERED
                it[bookingReliabilityDecisionId] = offer.decisionStamp.decisionId
                it[bookingReliabilityPolicyVersionId] = offer.decisionStamp.policyVersionId
                it[bookingReliabilityPolicyHash] = offer.decisionStamp.policyHash
                it[bookingReliabilityEvaluationDigest] = offer.decisionStamp.evaluationDigest
                it[bookingReliabilityExpiresAt] = offer.decisionStamp.expiresAt
                it[candidateRank] = offer.candidateRank
                it[selectionReasonCode] = offer.selectionReasonCode.code
                it[version] = INITIAL_VERSION
                it[createdAt] = now
                it[updatedAt] = now
            }.value
        } catch (ex: Exception) {
            if (ex.isActiveOfferConflict()) {
                throw OfferAlreadyExists()
            }
            throw ex
        }

    private fun Exception.isActiveOfferConflict(): Boolean {
        val text = sequenceOf(message, cause?.message).filterNotNull().joinToString(" ")
        return text.contains("uq_waitlist_offer_active", ignoreCase = true) ||
            text.contains("active_entry_key", ignoreCase = true) ||
            text.contains("active_vacancy_key", ignoreCase = true)
    }

    private fun entryScopeCondition(scope: WaitlistScope): Op<Boolean> =
        (WaitlistEntries.tenantGroupId eq scope.tenantGroupId) and
            (WaitlistEntries.clinicId eq scope.clinicId)

    private fun offerScopeCondition(scope: WaitlistScope): Op<Boolean> =
        (WaitlistOffers.tenantGroupId eq scope.tenantGroupId) and
            (WaitlistOffers.clinicId eq scope.clinicId)

    private fun holdScopeCondition(scope: WaitlistScope): Op<Boolean> =
        (WaitlistCapacityHolds.tenantGroupId eq scope.tenantGroupId) and
            (WaitlistCapacityHolds.clinicId eq scope.clinicId)

    private fun ResultRow.toEntryRecord(): WaitlistEntryRecord =
        WaitlistEntryRecord(
            id = this[WaitlistEntries.id].value,
            scope = WaitlistScope(
                tenantGroupId = this[WaitlistEntries.tenantGroupId].value,
                clinicId = this[WaitlistEntries.clinicId].value,
                memberId = MemberId(this[WaitlistEntries.memberId]),
            ),
            treatmentTypeId = this[WaitlistEntries.treatmentTypeId].value,
            doctorId = this[WaitlistEntries.doctorId]?.value,
            preferredDateFrom = this[WaitlistEntries.preferredDateFrom],
            preferredDateTo = this[WaitlistEntries.preferredDateTo],
            preferredStartTime = this[WaitlistEntries.preferredStartTime],
            preferredEndTime = this[WaitlistEntries.preferredEndTime],
            priorityRank = this[WaitlistEntries.priorityRank],
            status = this[WaitlistEntries.status],
            waitingSince = this[WaitlistEntries.waitingSince],
            version = this[WaitlistEntries.version],
            createdAt = this[WaitlistEntries.createdAt],
            updatedAt = this[WaitlistEntries.updatedAt],
        )

    private fun ResultRow.toOfferRecord(): WaitlistOfferRecord {
        val scope = WaitlistScope(
            tenantGroupId = this[WaitlistOffers.tenantGroupId].value,
            clinicId = this[WaitlistOffers.clinicId].value,
            memberId = MemberId(this[WaitlistOffers.memberId]),
        )
        return WaitlistOfferRecord(
            id = this[WaitlistOffers.id].value,
            scope = scope,
            waitlistEntryId = this[WaitlistOffers.waitlistEntryId].value,
            vacancyKey = this[WaitlistOffers.vacancyKey],
            activeEntryKey = this[WaitlistOffers.activeEntryKey],
            activeVacancyKey = this[WaitlistOffers.activeVacancyKey],
            resourceType = this[WaitlistOffers.resourceType],
            resourceId = this[WaitlistOffers.resourceId],
            capacityUnits = this[WaitlistOffers.capacityUnits],
            maximumCapacity = this[WaitlistOffers.maximumCapacity],
            doctorId = this[WaitlistOffers.doctorId],
            treatmentTypeId = checkNotNull(this[WaitlistOffers.treatmentTypeId]) {
                "treatmentTypeId must be present"
            },
            startsAt = this[WaitlistOffers.startsAt],
            endsAt = this[WaitlistOffers.endsAt],
            expiresAt = this[WaitlistOffers.expiresAt],
            status = this[WaitlistOffers.status],
            decisionStamp = DecisionStamp(
                scope = scope,
                decisionId = checkNotNull(this[WaitlistOffers.bookingReliabilityDecisionId]) {
                    "decisionId must be present"
                },
                policyVersionId = checkNotNull(this[WaitlistOffers.bookingReliabilityPolicyVersionId]) {
                    "policyVersionId must be present"
                },
                policyHash = checkNotNull(this[WaitlistOffers.bookingReliabilityPolicyHash]) {
                    "policyHash must be present"
                },
                evaluationDigest = checkNotNull(this[WaitlistOffers.bookingReliabilityEvaluationDigest]) {
                    "evaluationDigest must be present"
                },
                expiresAt = this[WaitlistOffers.bookingReliabilityExpiresAt],
            ),
            candidateRank = this[WaitlistOffers.candidateRank],
            selectionReasonCode = WaitlistReasonCode(this[WaitlistOffers.selectionReasonCode]),
            version = this[WaitlistOffers.version],
            createdAt = this[WaitlistOffers.createdAt],
            updatedAt = this[WaitlistOffers.updatedAt],
        )
    }

    private fun ResultRow.toHoldRecord(): WaitlistCapacityHoldRecord =
        WaitlistCapacityHoldRecord(
            id = this[WaitlistCapacityHolds.id].value,
            scope = WaitlistScope(
                tenantGroupId = this[WaitlistCapacityHolds.tenantGroupId].value,
                clinicId = this[WaitlistCapacityHolds.clinicId].value,
                memberId = MemberId(this[WaitlistCapacityHolds.memberId]),
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

    private companion object {
        private const val SLOT_FIT_EXACT = 1
        private const val SLOT_FIT_UNSPECIFIED = 0
        private const val INITIAL_VERSION = 0L
    }
}
