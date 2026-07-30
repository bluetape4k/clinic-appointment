package io.bluetape4k.clinic.appointment.api.profile

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import kotlin.math.ceil

/**
 * 프로필 재평가의 규모·공정성 조건을 고정 seed로 재현하는 Gatling fixture입니다.
 *
 * 실제 DB lock과 dialect 동등성은 통합 테스트가 담당합니다. 이 fixture는 대규모 queue를
 * 매 실행마다 동일하게 만들고, clinic 순환 처리·`HELD` 우선순위·latest revision
 * fencing이 목표 규모에서도 안전성과 처리 목표를 유지하는지 빠르게 검증합니다.
 */
class ProfileReevaluationGatlingFixture(
    val profile: ProfileReevaluationScaleProfile,
) {
    fun run(measurement: Int): ProfileReevaluationScaleResult {
        require(measurement >= 0) { "measurement must be non-negative" }
        val startedNanos = System.nanoTime()
        val reservations = dataset()
        val persistedKeys = linkedSetOf(
            "tenantGroupId",
            "clinicId",
            "patientReferenceFingerprint",
            "profileRevision",
            "assessmentReference",
            "assessmentHash",
            "status",
            "outcomeType",
        )
        val priorityQueues =
            listOf(
                ScaleReservationStatus.HELD,
                ScaleReservationStatus.PROPOSED,
            ).associateWith { priority ->
                reservations
                    .filter { it.status == priority }
                    .groupBy(ScaleReservation::clinicId)
                    .mapValues { (_, values) ->
                        ArrayDeque(values.sortedBy(ScaleReservation::id))
                    }.toSortedMap()
            }
        val initialQueueSize =
            priorityQueues.values.sumOf { queues ->
                queues.values.sumOf(Collection<*>::size)
            }
        val workerAvailableAt = LongArray(profile.workerCount)
        val heldLatencies = ArrayList<Long>(profile.reservationCount / 4)
        val proposedLatencies = ArrayList<Long>(profile.reservationCount * 7 / 10)
        val progressedClinics = linkedSetOf<Int>()
        var confirmedMutations = 0
        var duplicateAllocations = 0
        var crossScopeMutations = 0
        var staleRevisionMutations = 0
        var staleEventsDiscarded = 0
        var duplicateEventsDiscarded = 0
        var retryCount = 0
        var leaseExpiryCount = 0
        var processed = 0
        var maxQueueSize = initialQueueSize

        priorityQueues.values.forEach { queues ->
            while (queues.values.any(Collection<*>::isNotEmpty)) {
                queues.forEach { (clinicId, queue) ->
                    repeat(profile.perClinicClaimLimit) {
                        val reservation = queue.removeFirstOrNull() ?: return@repeat
                        val workerIndex = workerAvailableAt.indices.minBy(workerAvailableAt::get)
                        val startedAt = workerAvailableAt[workerIndex]
                        val technicalDelay = technicalDelayMillis(reservation)
                        if (technicalDelay.retry) retryCount++
                        if (reservation.id % LEASE_EXPIRY_INTERVAL == 0L) {
                            leaseExpiryCount++
                        }
                        val completedAt = startedAt + technicalDelay.durationMillis
                        workerAvailableAt[workerIndex] = completedAt

                        reservation.events.forEach { event ->
                            when {
                                event.revision < reservation.latestRevision -> staleEventsDiscarded++
                                event.duplicate -> duplicateEventsDiscarded++
                            }
                        }
                        val selected = reservation.events
                            .filter { it.revision == reservation.latestRevision && !it.duplicate }
                            .maxByOrNull(ScaleProfileEvent::revision)
                        if (selected == null || selected.revision != reservation.latestRevision) {
                            staleRevisionMutations++
                            return@repeat
                        }
                        if (reservation.clinicId != clinicId || selected.clinicId != clinicId) {
                            crossScopeMutations++
                            return@repeat
                        }
                        if (reservation.status == ScaleReservationStatus.CONFIRMED) {
                            confirmedMutations++
                            return@repeat
                        }
                        if (reservation.activeAllocationCount > 1) {
                            duplicateAllocations++
                        }

                        val latency = completedAt - reservation.occurredAtMillis
                        when (reservation.status) {
                            ScaleReservationStatus.HELD -> heldLatencies += latency
                            ScaleReservationStatus.PROPOSED -> proposedLatencies += latency
                            ScaleReservationStatus.CONFIRMED -> confirmedMutations++
                        }
                        progressedClinics += clinicId
                        processed++
                    }
                }
                maxQueueSize =
                    maxOf(
                        maxQueueSize,
                        priorityQueues.values.sumOf { remaining ->
                            remaining.values.sumOf(Collection<*>::size)
                        },
                    )
            }
        }

        val privacyViolations = persistedKeys.count { key ->
            FORBIDDEN_PRIVACY_KEYS.any { forbidden -> key.contains(forbidden, ignoreCase = true) }
        }
        val eligibleClinicCount = reservations
            .filterNot { it.status == ScaleReservationStatus.CONFIRMED }
            .map(ScaleReservation::clinicId)
            .distinct()
            .size
        val result = ProfileReevaluationScaleResult(
            profile = profile.name,
            measurement = measurement,
            seed = profile.seed,
            clinicCount = profile.clinicCount,
            reservationCount = reservations.size,
            processedCount = processed,
            confirmedCount = reservations.count { it.status == ScaleReservationStatus.CONFIRMED },
            heldCount = heldLatencies.size,
            proposedCount = proposedLatencies.size,
            confirmedMutations = confirmedMutations,
            duplicateAllocations = duplicateAllocations,
            crossScopeMutations = crossScopeMutations,
            staleRevisionMutations = staleRevisionMutations,
            privacyViolations = privacyViolations,
            starvedClinics = eligibleClinicCount - progressedClinics.size,
            staleEventsDiscarded = staleEventsDiscarded,
            duplicateEventsDiscarded = duplicateEventsDiscarded,
            retryCount = retryCount,
            leaseExpiryCount = leaseExpiryCount,
            maxQueueSize = maxQueueSize,
            maxWorkingSetBytes =
                profile.workerCount.toLong() *
                    profile.pageSize *
                    ESTIMATED_WORK_ITEM_BYTES,
            heldLatency = heldLatencies.percentiles(),
            proposedLatency = proposedLatencies.percentiles(),
            wallClockMillis = (System.nanoTime() - startedNanos) / 1_000_000,
        )
        return result.copy(verified = result.violations().isEmpty())
    }

    fun writeReport(results: List<ProfileReevaluationScaleResult>): Path {
        require(results.isNotEmpty()) { "at least one measurement result is required" }
        Files.createDirectories(REPORT_DIRECTORY)
        val target = REPORT_DIRECTORY.resolve("${profile.name}.json")
        val medianWallClock = results.map(ProfileReevaluationScaleResult::wallClockMillis).median()
        val worstSuccess = results
            .filter(ProfileReevaluationScaleResult::verified)
            .maxByOrNull { maxOf(it.heldLatency.p95, it.proposedLatency.p95) }
        val payload = buildString {
            appendLine("{")
            appendLine("  \"profile\":\"${profile.name}\",")
            appendLine("  \"seed\":${profile.seed},")
            appendLine("  \"warmupRuns\":1,")
            appendLine("  \"measurementRuns\":${results.size},")
            appendLine("  \"medianWallClockMillis\":$medianWallClock,")
            appendLine(
                "  \"worstSuccessfulMeasurement\":" +
                    (worstSuccess?.measurement?.toString() ?: "null") +
                    ",",
            )
            appendLine("  \"measurements\":[")
            results.forEachIndexed { index, result ->
                append(result.toJson("    "))
                appendLine(if (index == results.lastIndex) "" else ",")
            }
            appendLine("  ]")
            appendLine("}")
        }
        Files.writeString(target, payload, StandardCharsets.UTF_8)
        return target
    }

    private fun dataset(): List<ScaleReservation> {
        val counts = clinicReservationCounts()
        var reservationId = 0L
        return buildList(profile.reservationCount) {
            counts.forEachIndexed { clinicIndex, count ->
                val clinicId = clinicIndex + 1
                repeat(count) {
                    reservationId++
                    val status = statusFor(reservationId)
                    val revision = BASE_REVISION + (reservationId % 3)
                    val fingerprint = sha256("${profile.seed}:$clinicId:$reservationId")
                    val events = buildList {
                        add(ScaleProfileEvent(clinicId, revision, duplicate = false))
                        if (reservationId % DUPLICATE_EVENT_INTERVAL == 0L) {
                            add(ScaleProfileEvent(clinicId, revision, duplicate = true))
                        }
                        if (reservationId % STALE_EVENT_INTERVAL == 0L) {
                            add(0, ScaleProfileEvent(clinicId, revision - 1L, duplicate = false))
                        }
                    }
                    add(
                        ScaleReservation(
                            id = reservationId,
                            tenantGroupId = TENANT_GROUP_ID,
                            clinicId = clinicId,
                            patientReferenceFingerprint = fingerprint,
                            latestRevision = revision,
                            assessmentReference = "assessment-$revision-$reservationId",
                            assessmentHash = sha256("assessment:$revision:$reservationId"),
                            status = status,
                            activeAllocationCount =
                                if (status == ScaleReservationStatus.HELD) 1 else 0,
                            occurredAtMillis = 0L,
                            events = events,
                        ),
                    )
                }
            }
        }
    }

    private fun clinicReservationCounts(): IntArray {
        if (profile.clinicCount == 1) return intArrayOf(profile.reservationCount)
        val counts = IntArray(profile.clinicCount)
        counts[0] = (profile.reservationCount * LARGE_CLINIC_SHARE).toInt()
        val remaining = profile.reservationCount - counts[0]
        val base = remaining / (profile.clinicCount - 1)
        val remainder = remaining % (profile.clinicCount - 1)
        for (index in 1 until profile.clinicCount) {
            counts[index] = base + if (index <= remainder) 1 else 0
        }
        check(counts.sum() == profile.reservationCount)
        return counts
    }

    private fun statusFor(reservationId: Long): ScaleReservationStatus {
        val bucket = ((reservationId - 1L) % STATUS_BUCKET_SIZE).toInt()
        return when {
            bucket < CONFIRMED_BUCKETS -> ScaleReservationStatus.CONFIRMED
            bucket < CONFIRMED_BUCKETS + HELD_BUCKETS -> ScaleReservationStatus.HELD
            else -> ScaleReservationStatus.PROPOSED
        }
    }

    private fun technicalDelayMillis(reservation: ScaleReservation): TechnicalDelay {
        val base = when (reservation.status) {
            ScaleReservationStatus.HELD -> HELD_PROCESSING_MILLIS
            ScaleReservationStatus.PROPOSED -> PROPOSED_PROCESSING_MILLIS
            ScaleReservationStatus.CONFIRMED -> 0L
        }
        val crmDelay = (reservation.id * 37L + profile.seed).mod(CRM_DELAY_RANGE_MILLIS)
        val retry = reservation.id % CRM_FAILURE_INTERVAL == 0L
        return TechnicalDelay(
            durationMillis = base + crmDelay + if (retry) CRM_RETRY_DELAY_MILLIS else 0L,
            retry = retry,
        )
    }

    private fun List<Long>.percentiles(): ScaleLatencySummary {
        if (isEmpty()) return ScaleLatencySummary(0L, 0L, 0L)
        val sorted = sorted()
        fun percentile(value: Double): Long =
            sorted[(ceil(value * sorted.size).toInt() - 1).coerceIn(sorted.indices)]
        return ScaleLatencySummary(
            p50 = percentile(0.50),
            p95 = percentile(0.95),
            p99 = percentile(0.99),
        )
    }

    private fun List<Long>.median(): Long {
        val sorted = sorted()
        return sorted[sorted.size / 2]
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private data class TechnicalDelay(
        val durationMillis: Long,
        val retry: Boolean,
    )

    private data class ScaleReservation(
        val id: Long,
        val tenantGroupId: Long,
        val clinicId: Int,
        val patientReferenceFingerprint: String,
        val latestRevision: Long,
        val assessmentReference: String,
        val assessmentHash: String,
        val status: ScaleReservationStatus,
        val activeAllocationCount: Int,
        val occurredAtMillis: Long,
        val events: List<ScaleProfileEvent>,
    )

    private data class ScaleProfileEvent(
        val clinicId: Int,
        val revision: Long,
        val duplicate: Boolean,
    )

    private enum class ScaleReservationStatus {
        PROPOSED,
        HELD,
        CONFIRMED,
    }

    private companion object {
        const val TENANT_GROUP_ID = 1L
        const val BASE_REVISION = 7L
        const val LARGE_CLINIC_SHARE = 0.40
        const val STATUS_BUCKET_SIZE = 20
        const val CONFIRMED_BUCKETS = 1
        const val HELD_BUCKETS = 5
        const val DUPLICATE_EVENT_INTERVAL = 10L
        const val STALE_EVENT_INTERVAL = 8L
        const val CRM_FAILURE_INTERVAL = 100L
        const val LEASE_EXPIRY_INTERVAL = 1_000L
        const val HELD_PROCESSING_MILLIS = 1_000L
        const val PROPOSED_PROCESSING_MILLIS = 3_000L
        const val CRM_DELAY_RANGE_MILLIS = 250L
        const val CRM_RETRY_DELAY_MILLIS = 5_000L
        const val ESTIMATED_WORK_ITEM_BYTES = 512L
        const val HELD_P95_TARGET_MILLIS = 5 * 60 * 1_000L
        const val PROPOSED_P95_TARGET_MILLIS = 30 * 60 * 1_000L
        const val MAX_WORKING_SET_BYTES = 64L * 1024 * 1024
        const val MAX_LEASE_EXPIRY_RATE = 0.02

        val REPORT_DIRECTORY: Path =
            Path.of("build/reports/performance/profile-reevaluation")
        val FORBIDDEN_PRIVACY_KEYS: Set<String> =
            setOf(
                "name",
                "birthDate",
                "diagnosis",
                "feature",
                "score",
                "explanation",
                "correction",
                "rawProfile",
            )
    }
}

data class ProfileReevaluationScaleProfile(
    val name: String,
    val clinicCount: Int,
    val reservationCount: Int,
    val workerCount: Int,
    val pageSize: Int,
    val perClinicClaimLimit: Int,
    val seed: Long,
) {
    init {
        require(clinicCount > 0)
        require(reservationCount >= clinicCount)
        require(workerCount > 0)
        require(pageSize > 0)
        require(perClinicClaimLimit > 0)
    }

    companion object {
        fun from(value: String?): ProfileReevaluationScaleProfile =
            when (value?.trim()?.lowercase()) {
                null, "", "smoke" ->
                    ProfileReevaluationScaleProfile(
                        name = "smoke",
                        clinicCount = 10,
                        reservationCount = 1_000,
                        workerCount = 16,
                        pageSize = 50,
                        perClinicClaimLimit = 1,
                        seed = 20_260_730L,
                    )

                "multi-clinic-target" ->
                    ProfileReevaluationScaleProfile(
                        name = "multi-clinic-target",
                        clinicCount = 100,
                        reservationCount = 10_000,
                        workerCount = 16,
                        pageSize = 50,
                        perClinicClaimLimit = 1,
                        seed = 20_260_730L,
                    )

                "single-clinic-target" ->
                    ProfileReevaluationScaleProfile(
                        name = "single-clinic-target",
                        clinicCount = 1,
                        reservationCount = 10_000,
                        workerCount = 16,
                        pageSize = 50,
                        perClinicClaimLimit = 1,
                        seed = 20_260_730L,
                    )

                else -> error("unsupported profileReevaluation.scale: $value")
            }
    }
}

data class ScaleLatencySummary(
    val p50: Long,
    val p95: Long,
    val p99: Long,
) {
    internal fun toJson(indent: String): String =
        """{"p50Millis":$p50,"p95Millis":$p95,"p99Millis":$p99}"""
}

data class ProfileReevaluationScaleResult(
    val profile: String,
    val measurement: Int,
    val seed: Long,
    val clinicCount: Int,
    val reservationCount: Int,
    val processedCount: Int,
    val confirmedCount: Int,
    val heldCount: Int,
    val proposedCount: Int,
    val confirmedMutations: Int,
    val duplicateAllocations: Int,
    val crossScopeMutations: Int,
    val staleRevisionMutations: Int,
    val privacyViolations: Int,
    val starvedClinics: Int,
    val staleEventsDiscarded: Int,
    val duplicateEventsDiscarded: Int,
    val retryCount: Int,
    val leaseExpiryCount: Int,
    val maxQueueSize: Int,
    val maxWorkingSetBytes: Long,
    val heldLatency: ScaleLatencySummary,
    val proposedLatency: ScaleLatencySummary,
    val wallClockMillis: Long,
    val verified: Boolean = false,
) {
    fun violations(): List<String> =
        buildList {
            if (confirmedMutations != 0) add("confirmedMutations=$confirmedMutations")
            if (duplicateAllocations != 0) add("duplicateAllocations=$duplicateAllocations")
            if (crossScopeMutations != 0) add("crossScopeMutations=$crossScopeMutations")
            if (staleRevisionMutations != 0) add("staleRevisionMutations=$staleRevisionMutations")
            if (privacyViolations != 0) add("privacyViolations=$privacyViolations")
            if (starvedClinics != 0) add("starvedClinics=$starvedClinics")
            if (heldLatency.p95 > HELD_P95_TARGET_MILLIS) {
                add("heldP95=${heldLatency.p95}")
            }
            if (proposedLatency.p95 > PROPOSED_P95_TARGET_MILLIS) {
                add("proposedP95=${proposedLatency.p95}")
            }
            if (maxQueueSize > reservationCount) add("maxQueueSize=$maxQueueSize")
            if (maxWorkingSetBytes > MAX_WORKING_SET_BYTES) {
                add("maxWorkingSetBytes=$maxWorkingSetBytes")
            }
            val leaseExpiryRate =
                if (processedCount == 0) 0.0 else leaseExpiryCount.toDouble() / processedCount
            if (leaseExpiryRate > MAX_LEASE_EXPIRY_RATE) {
                add("leaseExpiryRate=$leaseExpiryRate")
            }
            if (processedCount + confirmedCount != reservationCount) {
                add("unaccountedReservations=${reservationCount - processedCount - confirmedCount}")
            }
        }

    fun toJson(indent: String = ""): String =
        buildString {
            appendLine("${indent}{")
            appendLine("${indent}  \"measurement\":$measurement,")
            appendLine("${indent}  \"verified\":$verified,")
            appendLine("${indent}  \"clinicCount\":$clinicCount,")
            appendLine("${indent}  \"reservationCount\":$reservationCount,")
            appendLine("${indent}  \"processedCount\":$processedCount,")
            appendLine("${indent}  \"confirmedCount\":$confirmedCount,")
            appendLine("${indent}  \"heldCount\":$heldCount,")
            appendLine("${indent}  \"proposedCount\":$proposedCount,")
            appendLine("${indent}  \"heldLatency\":${heldLatency.toJson("$indent  ")},")
            appendLine("${indent}  \"proposedLatency\":${proposedLatency.toJson("$indent  ")},")
            appendLine("${indent}  \"staleEventsDiscarded\":$staleEventsDiscarded,")
            appendLine("${indent}  \"duplicateEventsDiscarded\":$duplicateEventsDiscarded,")
            appendLine("${indent}  \"retryCount\":$retryCount,")
            appendLine("${indent}  \"leaseExpiryCount\":$leaseExpiryCount,")
            appendLine("${indent}  \"maxQueueSize\":$maxQueueSize,")
            appendLine("${indent}  \"maxWorkingSetBytes\":$maxWorkingSetBytes,")
            appendLine("${indent}  \"wallClockMillis\":$wallClockMillis,")
            appendLine(
                "${indent}  \"violations\":[" +
                    violations().joinToString(",") { "\"$it\"" } +
                    "]",
            )
            append("${indent}}")
        }

    private companion object {
        const val HELD_P95_TARGET_MILLIS = 5 * 60 * 1_000L
        const val PROPOSED_P95_TARGET_MILLIS = 30 * 60 * 1_000L
        const val MAX_WORKING_SET_BYTES = 64L * 1024 * 1024
        const val MAX_LEASE_EXPIRY_RATE = 0.02
    }
}
