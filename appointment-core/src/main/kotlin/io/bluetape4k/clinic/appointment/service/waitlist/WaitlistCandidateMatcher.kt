package io.bluetape4k.clinic.appointment.service.waitlist

import io.bluetape4k.clinic.appointment.model.waitlist.DecisionStamp
import io.bluetape4k.clinic.appointment.model.waitlist.VacancyDescriptor
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistCursor
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistEntryRecord
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistScope
import io.bluetape4k.clinic.appointment.repository.waitlist.ClinicWaitlistPolicyRecord
import io.bluetape4k.clinic.appointment.repository.waitlist.RankedWaitlistCursor
import io.bluetape4k.clinic.appointment.repository.waitlist.RankedWaitlistCandidateRow
import io.bluetape4k.clinic.appointment.repository.waitlist.WaitlistRepository
import io.bluetape4k.clinic.appointment.service.reliability.BookingReliabilityDecisionBatchPort
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import java.io.Serializable
import java.time.Duration

/**
 * 대기 후보를 deterministic keyset 순서로 읽고 booking reliability decision을 결합합니다.
 *
 * 후보 조회와 decision 조회는 caller-owned transaction 안에서 실행되며 row lock을 잡지
 * 않습니다. 실제 승격 단계는 [WaitlistOfferService]가 entry를 다시 잠가 재검증합니다.
 */
class WaitlistCandidateMatcher(
    private val repository: WaitlistRepository,
    private val decisionPort: BookingReliabilityDecisionBatchPort,
    private val timeBudget: Duration = DEFAULT_TIME_BUDGET,
    private val nanoTime: () -> Long = System::nanoTime,
) {

    init {
        require(!timeBudget.isNegative && !timeBudget.isZero) { "timeBudget must be positive" }
        require(timeBudget.toNanos() > 0L) { "timeBudget must fit in nanoseconds" }
    }

    /**
     * vacancy에 맞는 후보를 bounded page로 조회하고 decision snapshot을 결합합니다.
     * 호출자는 후보를 승격하기 전에 같은 transaction에서 entry와 자원을 다시 검증해야 합니다.
     */
    fun findCandidates(
        vacancy: VacancyDescriptor,
        request: WaitlistCandidateRequest = WaitlistCandidateRequest(),
    ): WaitlistCandidatePage {
        if (!vacancy.startsAt.isAfter(vacancy.now)) {
            return WaitlistCandidatePage.empty(cursor = request.cursor)
        }

        val pageSize = request.pageSize.coerceIn(1, MAX_RANKED_PAGE_SIZE)
        val maxCandidates = request.maxCandidates.coerceIn(1, MAX_RANKED_CANDIDATES)
        val maxPages = request.maxPages.coerceIn(1, MAX_RANKED_PAGES)
        val deadline = nanoTime() + timeBudget.toNanos()
        val candidates = mutableListOf<WaitlistCandidate>()
        var cursor = request.cursor
        var pageCount = 0
        var decisionBatchCalls = 0
        var exhausted = false

        while (pageCount < maxPages && candidates.size < maxCandidates && nanoTime() < deadline) {
            val entries = repository.findCandidatePage(vacancy, cursor = cursor, limit = pageSize)
            if (entries.isEmpty()) {
                exhausted = true
                break
            }

            pageCount += 1
            decisionBatchCalls += 1
            val decisionByMember =
                decisionPort.findLatestDecisionStamps(
                    scope = WaitlistScope(vacancy.tenantGroupId, vacancy.clinicId, entries.first().scope.memberId),
                    memberIds = entries.map { it.scope.memberId },
                    evaluatedAt = vacancy.now,
                )

            entries.forEach { entry ->
                cursor = entry.toCursor(vacancy)
                val decision = decisionByMember[entry.scope.memberId]
                if (decision != null && decision.isUsableAt(vacancy.now)) {
                    candidates += WaitlistCandidate(
                        entry = entry,
                        decisionStamp = decision,
                        rank = candidates.size + 1,
                        cursor = cursor,
                    )
                }
            }
        }

        if (!exhausted && nanoTime() >= deadline) {
            log.info { "waitlist candidate matcher time budget exhausted after $pageCount page(s)" }
        }

        return WaitlistCandidatePage(
            candidates = candidates,
            nextCursor = cursor,
            exhausted = exhausted,
            scannedPages = pageCount,
            decisionBatchCalls = decisionBatchCalls,
        )
    }

    /**
     * active policy snapshot으로 정렬된 후보 page를 읽고 reliability decision snapshot을 결합합니다.
     */
    fun findCandidates(
        vacancy: VacancyDescriptor,
        policy: ClinicWaitlistPolicyRecord,
        request: WaitlistCandidateRequest = WaitlistCandidateRequest(),
    ): WaitlistCandidatePage {
        if (!vacancy.startsAt.isAfter(vacancy.now)) {
            return WaitlistCandidatePage.empty(cursor = request.cursor)
        }

        val pageSize = request.pageSize.coerceIn(1, MAX_RANKED_PAGE_SIZE)
        val maxCandidates = request.maxCandidates.coerceIn(1, MAX_RANKED_CANDIDATES)
        val maxPages = request.maxPages.coerceIn(1, MAX_RANKED_PAGES)
        val deadline = nanoTime() + timeBudget.toNanos()
        val candidates = mutableListOf<WaitlistCandidate>()
        var rankedCursor = request.rankedCursor
        var legacyCursor = request.cursor
        var pageCount = 0
        var decisionBatchCalls = 0
        var exhausted = false

        while (pageCount < maxPages && candidates.size < maxCandidates && nanoTime() < deadline) {
            val rows = repository.findRankedCandidatePage(vacancy, policy, rankedCursor, pageSize)
            if (rows.isEmpty()) {
                exhausted = true
                break
            }

            pageCount += 1
            decisionBatchCalls += 1
            val decisionByMember =
                decisionPort.findLatestDecisionStamps(
                    scope = WaitlistScope(vacancy.tenantGroupId, vacancy.clinicId, rows.first().entry.scope.memberId),
                    memberIds = rows.map { it.entry.scope.memberId },
                    evaluatedAt = vacancy.now,
                )

            rows.forEach { ranked ->
                rankedCursor = ranked.toCursor()
                legacyCursor = ranked.entry.toCursor(vacancy)
                val decision = decisionByMember[ranked.entry.scope.memberId]
                if (decision != null && decision.isUsableAt(vacancy.now)) {
                    candidates += WaitlistCandidate(
                        entry = ranked.entry,
                        decisionStamp = decision,
                        rank = candidates.size + 1,
                        cursor = legacyCursor,
                        ranked = ranked.withDecisionStamp(decision),
                    )
                }
            }
        }

        if (!exhausted && nanoTime() >= deadline) {
            log.info { "waitlist ranked candidate matcher time budget exhausted after $pageCount page(s)" }
        }

        return WaitlistCandidatePage(
            candidates = candidates,
            nextCursor = legacyCursor,
            nextRankedCursor = rankedCursor,
            exhausted = exhausted,
            scannedPages = pageCount,
            decisionBatchCalls = decisionBatchCalls,
        )
    }

    private fun WaitlistEntryRecord.toCursor(vacancy: VacancyDescriptor): WaitlistCursor =
        WaitlistCursor(
            slotFit = if (doctorId == vacancy.doctorId) SLOT_FIT_EXACT else SLOT_FIT_UNSPECIFIED,
            priorityRank = priorityRank,
            waitingSince = waitingSince,
            entryId = id,
        )

    private companion object : KLogging() {
        private const val SLOT_FIT_EXACT = 1
        private const val SLOT_FIT_UNSPECIFIED = 0
        private const val MAX_PAGE_SIZE = 500
        private const val MAX_PAGES = 10
        private const val MAX_CANDIDATES = 1_000
        private const val MAX_RANKED_PAGE_SIZE = 100
        private const val MAX_RANKED_PAGES = 4
        private const val MAX_RANKED_CANDIDATES = MAX_RANKED_PAGE_SIZE * MAX_RANKED_PAGES
        private val DEFAULT_TIME_BUDGET: Duration = Duration.ofSeconds(2)
    }
}

/** 후보 keyset 조회의 page·budget 요청입니다. */
data class WaitlistCandidateRequest(
    val cursor: WaitlistCursor? = null,
    val rankedCursor: RankedWaitlistCursor? = null,
    val pageSize: Int = 100,
    val maxPages: Int = 10,
    val maxCandidates: Int = 1_000,
) : Serializable {
    init {
        require(pageSize > 0) { "pageSize must be positive" }
        require(maxPages > 0) { "maxPages must be positive" }
        require(maxCandidates > 0) { "maxCandidates must be positive" }
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/** decision 검증을 통과해 offer 승격 대상으로 남은 후보입니다. */
data class WaitlistCandidate(
    val entry: WaitlistEntryRecord,
    val decisionStamp: DecisionStamp,
    val rank: Int,
    val cursor: WaitlistCursor?,
    val ranked: RankedWaitlistCandidateRow? = null,
) : Serializable {
    init {
        require(rank > 0) { "rank must be positive" }
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/** 후보 page 결과와 다음 keyset cursor를 함께 전달합니다. */
data class WaitlistCandidatePage(
    val candidates: List<WaitlistCandidate>,
    val nextCursor: WaitlistCursor?,
    val nextRankedCursor: RankedWaitlistCursor? = null,
    val exhausted: Boolean,
    val scannedPages: Int,
    val decisionBatchCalls: Int,
) : Serializable {
    init {
        require(scannedPages >= 0) { "scannedPages must be zero or positive" }
        require(decisionBatchCalls >= 0) { "decisionBatchCalls must be zero or positive" }
    }

    companion object {
        private const val serialVersionUID = 1L

        /** 입력 cursor 이후에 더 조회할 후보가 없음을 표현합니다. */
        fun empty(cursor: WaitlistCursor?): WaitlistCandidatePage =
            WaitlistCandidatePage(
                candidates = emptyList(),
                nextCursor = cursor,
                nextRankedCursor = null,
                exhausted = true,
                scannedPages = 0,
                decisionBatchCalls = 0,
            )
    }
}
