package io.bluetape4k.clinic.appointment.api.service

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.assertFailsWith
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.Duration
class PatientHistoryCursorCodecTest {
    private val now = Instant.parse("2026-08-14T00:00:00Z")
    private val key1 = PatientHistoryCursorKey("k1", ByteArray(32) { 1 })
    private val key2 = PatientHistoryCursorKey("k2", ByteArray(32) { 2 })
    private val registry = InMemoryPatientHistoryTokenRegistry(clock = { now })
    private val codec = PatientHistoryCursorCodec(
        keys = listOf(key1, key2),
        registry = registry,
        clock = Clock.fixed(now, ZoneOffset.UTC),
    )
    private val payload = PatientHistoryCursorPayload(
        issuedKeyId = "k1",
        issuedAt = now,
        issuedAtBucket = now,
        tenantGroupId = 7L,
        patientScopeFingerprint = "a".repeat(64),
        occurredAt = Instant.parse("2026-08-13T23:00:00Z"),
        detailId = 19L,
    )

    @Test
    fun `same boundary reuses deterministic registry token`() {
        val first = codec.encode(payload)
        val second = codec.encode(payload)

        second shouldBeEqualTo first
        codec.decode(first) shouldBeEqualTo payload
    }

    @Test
    fun `same boundary reuses registry token when issuance instant changes inside bucket`() {
        val first = codec.encode(payload)
        val second = codec.encode(
            payload.copy(
                issuedAt = now.plusSeconds(10),
                issuedAtBucket = now,
            ),
        )

        second shouldBeEqualTo first
        codec.decode(second) shouldBeEqualTo payload
    }

    @Test
    fun `tampered token and padded segments fail closed`() {
        val token = codec.encode(payload)
        val parts = token.split('.').toMutableList()
        parts[3] = parts[3].replaceFirstChar { if (it == 'A') 'B' else 'A' }

        assertFailsWith<PatientHistoryCursorException> { codec.decode(parts.joinToString(".")) }
        assertFailsWith<PatientHistoryCursorException> { codec.decode("$token=") }
        assertFailsWith<PatientHistoryCursorException> {
            codec.decode(token.substringBeforeLast('.') + "." + token.substringAfterLast('.') + "=")
        }
    }

    @Test
    fun `previous key token remains readable but new issuance uses active key`() {
        val previousRegistry = InMemoryPatientHistoryTokenRegistry(clock = { now })
        val previousCodec = PatientHistoryCursorCodec(
            keys = listOf(key2),
            registry = previousRegistry,
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )
        val previousPayload = payload.copy(issuedKeyId = "k2")
        val previousToken = previousCodec.encode(previousPayload)
        val rotatedCodec = PatientHistoryCursorCodec(
            keys = listOf(key1, key2),
            registry = previousRegistry,
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )

        // rotated codec must issue the active key; previous-key decode is covered by the key-ring vector
        val fresh = rotatedCodec.encode(payload)
        fresh.split('.')[1] shouldBeEqualTo "k1"
        rotatedCodec.decode(previousToken).issuedKeyId shouldBeEqualTo "k2"
    }

    @Test
    fun `reference is scope bound and etag changes with nullable field`() {
        val reference = PatientHistoryReferenceCodec(
            listOf(PatientHistoryReferenceKey("k1", ByteArray(32) { 3 })),
        )
        val appointmentRef = reference.encode(7L, "a".repeat(64), 11L, 19L)
        reference.matches(appointmentRef, 7L, "a".repeat(64), 11L, 19L).shouldBeTrue()
        reference.matches(appointmentRef, 8L, "a".repeat(64), 11L, 19L).shouldBeFalse()

        val etag = PatientHistoryEtagCodec()
        val entry = PatientHistoryEtagEntry(
            appointmentRef, null, null, null, null, null, null, null,
            "CANCELLED", "취소", "REFUND", "환불", null, "PATIENT", "환자", now.toString(),
        )
        val first = etag.strongTag(20, null, listOf(entry), null)
        val second = etag.strongTag(20, null, listOf(entry.copy(reasonDetail = "환불 처리")), null)
        etag.isStrongTag(first).shouldBeTrue()
        first shouldBeEqualTo first
        (first != second).shouldBeTrue()
    }

    @Test
    fun `future occurredAt and issuedAt are rejected`() {
        val futurePayload = payload.copy(
            issuedAt = now.plusSeconds(61),
            issuedAtBucket = now,
            occurredAt = now.plusSeconds(3651L * 24L * 60L * 60L),
        )

        val failure = assertFailsWith<PatientHistoryCursorException> { codec.encode(futurePayload) }
        failure.failure shouldBeEqualTo PatientHistoryCursorFailure.MALFORMED
    }

    @Test
    fun `registry readiness and token collision fail closed`() {
        val unavailable = object : PatientHistoryTokenRegistry {
            override fun get(key: String): PatientHistoryTokenEntry? = null
            override fun putIfAbsent(key: String, entry: PatientHistoryTokenEntry): PatientHistoryTokenEntry = entry
            override fun isReady(): Boolean = false
        }
        val unavailableCodec = PatientHistoryCursorCodec(
            keys = listOf(key1),
            registry = unavailable,
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )
        val unavailableFailure = assertFailsWith<PatientHistoryCursorException> {
            unavailableCodec.encode(payload)
        }
        unavailableFailure.failure shouldBeEqualTo PatientHistoryCursorFailure.REGISTRY_UNAVAILABLE

        val collision = object : PatientHistoryTokenRegistry {
            private val wrong = "v1.k1.AAAAAAAAAAAA.AA.AAAAAAAAAAAAAAAAAAAAAA"
            override fun get(key: String): PatientHistoryTokenEntry = PatientHistoryTokenEntry(wrong, now)
            override fun putIfAbsent(key: String, entry: PatientHistoryTokenEntry): PatientHistoryTokenEntry =
                PatientHistoryTokenEntry(wrong, now)
        }
        val collisionCodec = PatientHistoryCursorCodec(
            keys = listOf(key1),
            registry = collision,
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )
        val collisionFailure = assertFailsWith<PatientHistoryCursorException> {
            collisionCodec.encode(payload)
        }
        collisionFailure.failure shouldBeEqualTo PatientHistoryCursorFailure.REGISTRY_UNAVAILABLE
    }

    @Test
    fun `bounded registry rejects a full capacity and reclaims expired entries`() {
        var clockNow = now
        val bounded = InMemoryPatientHistoryTokenRegistry(
            clock = { clockNow },
            ttl = Duration.ofMinutes(5),
            capacity = 1,
        )
        val first = PatientHistoryTokenEntry("first", now)
        val second = PatientHistoryTokenEntry("second", now)

        bounded.putIfAbsent("first-key", first) shouldBeEqualTo first
        val full = assertFailsWith<PatientHistoryRegistryException> {
            bounded.putIfAbsent("second-key", second)
        }
        full.reason shouldBeEqualTo PatientHistoryRegistryFailureReason.CAPACITY_FULL

        clockNow = now.plusSeconds(301)
        bounded.get("first-key").shouldBeNull()
        bounded.putIfAbsent("second-key", second) shouldBeEqualTo second
    }
}
