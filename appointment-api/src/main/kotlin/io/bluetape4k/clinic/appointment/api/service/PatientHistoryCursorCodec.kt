package io.bluetape4k.clinic.appointment.api.service

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** cursor의 인증된 keyset 경계입니다. */
data class PatientHistoryCursorPayload(
    val issuedKeyId: String,
    val issuedAt: Instant,
    val issuedAtBucket: Instant,
    val tenantGroupId: Long,
    val patientScopeFingerprint: String,
    val occurredAt: Instant,
    val detailId: Long,
)

/** AES-GCM key ring에 등록할 cursor key입니다. 첫 key만 발급에 사용됩니다. */
data class PatientHistoryCursorKey(
    val id: String,
    val secret: ByteArray,
)

/** cursor 입력 오류와 registry 장애를 구분하는 고정 failure 분류입니다. */
enum class PatientHistoryCursorFailure {
    MALFORMED,
    UNKNOWN_KEY,
    AUTHENTICATION_FAILED,
    EXPIRED,
    MISSING_ENTRY,
    REGISTRY_UNAVAILABLE,
}

/** cursor는 내부 원인을 응답으로 직접 반사하지 않고 code만 상위 계층에 전달합니다. */
class PatientHistoryCursorException(
    val failure: PatientHistoryCursorFailure,
    cause: Throwable? = null,
) : RuntimeException(failure.name, cause)

/** strict grammar, AES-GCM authentication, TTL, key rotation을 소유하는 cursor codec입니다. */
class PatientHistoryCursorCodec(
    keys: List<PatientHistoryCursorKey>,
    private val registry: PatientHistoryTokenRegistry,
    private val clock: Clock = Clock.systemUTC(),
    private val ttl: Duration = Duration.ofMinutes(30),
) {
    private val keyRing: Map<String, ByteArray> = keys.associate { key ->
        require(KEY_ID.matches(key.id)) { "cursor key id is invalid" }
        require(key.secret.size >= 16) { "cursor key must contain at least 128 bits" }
        key.id to key.secret.copyOf()
    }
    private val activeKey: PatientHistoryCursorKey = keys.firstOrNull()
        ?: error("at least one cursor key is required")

    /** 현재 발급 key ID입니다. payload outer key와 항상 동일해야 합니다. */
    val activeKeyId: String
        get() = activeKey.id

    init {
        require(!ttl.isNegative && !ttl.isZero) { "cursor ttl must be positive" }
        require(keyRing.size == keys.size) { "cursor key ids must be unique" }
    }

    /** 같은 page boundary를 같은 bucket에서 요청하면 registry의 최초 token을 재사용합니다. */
    fun encode(payload: PatientHistoryCursorPayload, deadlineNanos: Long? = null): String {
        require(payload.issuedKeyId == activeKey.id) {
            "cursor payload must use the active issuance key"
        }
        try {
            validatePayload(payload)
        } catch (failure: IllegalArgumentException) {
            throw PatientHistoryCursorException(PatientHistoryCursorFailure.MALFORMED, failure)
        }
        val registryKey = registryKey(activeKey.id, payload)
        val token = encrypt(payload, activeKey)
        try {
            if (!registry.isReadyWithin(deadlineNanos)) {
                throw PatientHistoryRegistryException(PatientHistoryRegistryFailureReason.UNAVAILABLE)
            }
            registry.getWithin(registryKey, deadlineNanos)?.let { entry ->
                validateRegistryEntryOrFail(entry)
                validateStoredTokenBoundary(entry.token, payload)
                return entry.token
            }
            val stored = registry.putIfAbsentWithin(
                registryKey,
                PatientHistoryTokenEntry(token = token, issuedAt = payload.issuedAt),
                deadlineNanos,
            )
            validateRegistryEntryOrFail(stored)
            validateStoredTokenBoundary(stored.token, payload)
            return stored.token
        } catch (failure: PatientHistoryRegistryException) {
            throw PatientHistoryCursorException(
                PatientHistoryCursorFailure.REGISTRY_UNAVAILABLE,
                failure,
            )
        }
    }

    /** outer grammar와 인증된 payload를 검증하고 registry entry와 일치하는 경계를 반환합니다. */
    fun decode(token: String): PatientHistoryCursorPayload {
        val authenticated = decodeAuthenticated(token)
        verifyRegistry(token, authenticated)
        return authenticated
    }

    /** 첫 페이지도 shared registry readiness를 통과해야 cursor 경계를 노출할 수 있습니다. */
    fun requireReady(deadlineNanos: Long? = null) {
        try {
            if (!registry.isReadyWithin(deadlineNanos)) {
                throw PatientHistoryRegistryException(PatientHistoryRegistryFailureReason.UNAVAILABLE)
            }
        } catch (failure: PatientHistoryRegistryException) {
            throw PatientHistoryCursorException(PatientHistoryCursorFailure.REGISTRY_UNAVAILABLE, failure)
        }
    }

    /** registry를 조회하기 전 actor scope를 비교할 수 있도록 authenticated payload만 해석합니다. */
    fun decodeAuthenticated(token: String): PatientHistoryCursorPayload {
        if (token.length > MAX_TOKEN_LENGTH || token.any { it.code > 0x7f }) {
            fail(PatientHistoryCursorFailure.MALFORMED)
        }
        val segments = token.split('.')
        if (segments.size != 5 || segments[0] != VERSION || !KEY_ID.matches(segments[1])) {
            fail(PatientHistoryCursorFailure.MALFORMED)
        }
        val keyId = segments[1]
        val secret = keyRing[keyId] ?: fail(PatientHistoryCursorFailure.UNKNOWN_KEY)
        val nonce = decodeSegment(segments[2], NONCE_BYTES)
        val encrypted = decodeSegment(segments[3], MAX_CIPHERTEXT_BYTES)
        val tag = decodeSegment(segments[4], TAG_BYTES)
        val authenticated = try {
            decrypt(secret, nonce, encrypted + tag)
        } catch (failure: Exception) {
            throw PatientHistoryCursorException(
                PatientHistoryCursorFailure.AUTHENTICATION_FAILED,
                failure,
            )
        }
        val payload = decodePayload(authenticated)
        if (payload.issuedKeyId != keyId) fail(PatientHistoryCursorFailure.MALFORMED)
        try {
            validatePayload(payload)
        } catch (failure: IllegalArgumentException) {
            throw PatientHistoryCursorException(PatientHistoryCursorFailure.MALFORMED, failure)
        }
        val now = Instant.now(clock)
        if (payload.issuedAt.plus(ttl).isBefore(now)) fail(PatientHistoryCursorFailure.EXPIRED)

        return payload
    }

    /** scope가 일치한 authenticated payload만 registry와 constant-time으로 대조합니다. */
    fun verifyRegistry(
        token: String,
        payload: PatientHistoryCursorPayload,
        deadlineNanos: Long? = null,
    ) {
        val keyId = payload.issuedKeyId
        val registryKey = registryKey(keyId, payload)
        val entry = try {
            if (!registry.isReadyWithin(deadlineNanos)) {
                throw PatientHistoryRegistryException(PatientHistoryRegistryFailureReason.UNAVAILABLE)
            }
            registry.getWithin(registryKey, deadlineNanos)
        } catch (failure: PatientHistoryRegistryException) {
            throw PatientHistoryCursorException(
                PatientHistoryCursorFailure.REGISTRY_UNAVAILABLE,
                failure,
            )
        } ?: fail(PatientHistoryCursorFailure.MISSING_ENTRY)
        try {
            validateRegistryEntry(entry)
        } catch (failure: IllegalArgumentException) {
            throw PatientHistoryCursorException(PatientHistoryCursorFailure.REGISTRY_UNAVAILABLE, failure)
        }
        if (!constantTimeEquals(entry.token, token) || entry.issuedAt != payload.issuedAt) {
            fail(PatientHistoryCursorFailure.MALFORMED)
        }
    }

    private fun encrypt(
        payload: PatientHistoryCursorPayload,
        key: PatientHistoryCursorKey,
    ): String {
        val plaintext = encodePayload(payload)
        val nonce = deterministicNonce(key.secret, plaintext)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.secret, AES), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(AAD)
        val encrypted = cipher.doFinal(plaintext)
        val ciphertext = encrypted.copyOfRange(0, encrypted.size - TAG_BYTES)
        val tag = encrypted.copyOfRange(encrypted.size - TAG_BYTES, encrypted.size)
        return listOf(
            VERSION,
            key.id,
            encodeSegment(nonce),
            encodeSegment(ciphertext),
            encodeSegment(tag),
        ).joinToString(".")
    }

    private fun decrypt(
        secret: ByteArray,
        nonce: ByteArray,
        encrypted: ByteArray,
    ): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(secret, AES), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(AAD)
        return cipher.doFinal(encrypted)
    }

    private fun encodePayload(payload: PatientHistoryCursorPayload): ByteArray {
        val key = payload.issuedKeyId.toByteArray(StandardCharsets.UTF_8)
        val fingerprint = payload.patientScopeFingerprint.toByteArray(StandardCharsets.UTF_8)
        return ByteBuffer.allocate(4 + key.size + 8 * 5 + 4 + fingerprint.size).apply {
            putInt(key.size)
            put(key)
            putLong(payload.issuedAt.toEpochMilli())
            putLong(payload.issuedAtBucket.toEpochMilli())
            putLong(payload.tenantGroupId)
            putInt(fingerprint.size)
            put(fingerprint)
            putLong(payload.occurredAt.toEpochMilli())
            putLong(payload.detailId)
        }.array()
    }

    private fun decodePayload(bytes: ByteArray): PatientHistoryCursorPayload {
        try {
            val buffer = ByteBuffer.wrap(bytes)
            val key = readString(buffer)
            val issuedAt = Instant.ofEpochMilli(buffer.long)
            val bucket = Instant.ofEpochMilli(buffer.long)
            val tenant = buffer.long
            val fingerprint = readString(buffer)
            val occurredAt = Instant.ofEpochMilli(buffer.long)
            val detailId = buffer.long
            require(!buffer.hasRemaining())
            return PatientHistoryCursorPayload(key, issuedAt, bucket, tenant, fingerprint, occurredAt, detailId)
        } catch (failure: Exception) {
            throw PatientHistoryCursorException(PatientHistoryCursorFailure.MALFORMED, failure)
        }
    }

    private fun readString(buffer: ByteBuffer): String {
        val length = buffer.int
        require(length in 1..MAX_STRING_BYTES && length <= buffer.remaining())
        return ByteArray(length).also(buffer::get).toString(StandardCharsets.UTF_8)
    }

    private fun validatePayload(payload: PatientHistoryCursorPayload) {
        require(KEY_ID.matches(payload.issuedKeyId)) { "cursor key id is invalid" }
        require(FINGERPRINT.matches(payload.patientScopeFingerprint)) { "cursor fingerprint is invalid" }
        require(payload.tenantGroupId > 0 && payload.detailId > 0) { "cursor ids must be positive" }
        val now = Instant.now(clock)
        require(!payload.issuedAt.isAfter(now.plusSeconds(60)))
        require(!payload.issuedAtBucket.isAfter(payload.issuedAt))
        require(payload.issuedAtBucket == floorBucket(payload.issuedAt))
        require(!payload.occurredAt.isBefore(now.minus(Duration.ofDays(3650)))) {
            "cursor occurredAt is too old"
        }
        require(!payload.occurredAt.isAfter(now.plus(Duration.ofDays(3650)))) {
            "cursor occurredAt is too far in the future"
        }
    }

    private fun validateRegistryEntry(entry: PatientHistoryTokenEntry) {
        require(entry.token.length <= MAX_TOKEN_LENGTH) { "registry token is too large" }
        require(!entry.issuedAt.plus(ttl).isBefore(Instant.now(clock))) {
            "registry entry is expired"
        }
    }

    private fun validateRegistryEntryOrFail(entry: PatientHistoryTokenEntry) {
        try {
            validateRegistryEntry(entry)
        } catch (failure: IllegalArgumentException) {
            throw PatientHistoryRegistryException(PatientHistoryRegistryFailureReason.UNAVAILABLE, failure)
        }
    }

    private fun validateStoredTokenBoundary(
        token: String,
        requested: PatientHistoryCursorPayload,
    ) {
        val stored = try {
            decodeAuthenticated(token)
        } catch (failure: Exception) {
            throw PatientHistoryRegistryException(PatientHistoryRegistryFailureReason.COLLISION, failure)
        }
        if (stored.issuedKeyId != requested.issuedKeyId ||
            stored.issuedAtBucket != requested.issuedAtBucket ||
            stored.tenantGroupId != requested.tenantGroupId ||
            stored.patientScopeFingerprint != requested.patientScopeFingerprint ||
            stored.occurredAt != requested.occurredAt ||
            stored.detailId != requested.detailId
        ) {
            throw PatientHistoryRegistryException(PatientHistoryRegistryFailureReason.COLLISION)
        }
    }

    private fun registryKey(
        keyId: String,
        payload: PatientHistoryCursorPayload,
    ): String {
        // issuance instant may vary inside a bucket; the page boundary must not.
        val canonical = encodePayload(payload.copy(issuedAt = payload.issuedAtBucket))
        val mac = Mac.getInstance(HMAC_SHA256)
        mac.init(SecretKeySpec(keyRing.getValue(keyId), HMAC_SHA256))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
            mac.doFinal(REGISTRY_DOMAIN + canonical),
        )
    }

    private fun deterministicNonce(secret: ByteArray, payload: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC_SHA256)
        mac.init(SecretKeySpec(secret, HMAC_SHA256))
        return mac.doFinal(NONCE_DOMAIN + payload).copyOf(NONCE_BYTES)
    }

    private fun floorBucket(instant: Instant): Instant =
        Instant.ofEpochSecond(Math.floorDiv(instant.epochSecond, BUCKET_SECONDS) * BUCKET_SECONDS)

    private fun decodeSegment(value: String, maxBytes: Int): ByteArray {
        if (value.isEmpty() || !BASE64URL.matches(value)) fail(PatientHistoryCursorFailure.MALFORMED)
        val decoded = try {
            Base64.getUrlDecoder().decode(value)
        } catch (failure: IllegalArgumentException) {
            throw PatientHistoryCursorException(PatientHistoryCursorFailure.MALFORMED, failure)
        }
        if (decoded.isEmpty() || decoded.size > maxBytes ||
            Base64.getUrlEncoder().withoutPadding().encodeToString(decoded) != value
        ) {
            fail(PatientHistoryCursorFailure.MALFORMED)
        }
        return decoded
    }

    private fun encodeSegment(value: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value)

    private fun constantTimeEquals(expected: String, actual: String): Boolean =
        MessageDigest.isEqual(
            expected.toByteArray(StandardCharsets.US_ASCII),
            actual.toByteArray(StandardCharsets.US_ASCII),
        )

    private fun fail(failure: PatientHistoryCursorFailure): Nothing =
        throw PatientHistoryCursorException(failure)

    companion object {
        private const val VERSION = "v1"
        private const val AES = "AES"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val HMAC_SHA256 = "HmacSHA256"
        private const val TAG_BITS = 128
        private const val NONCE_BYTES = 12
        private const val TAG_BYTES = 16
        private const val MAX_CIPHERTEXT_BYTES = 256
        private const val MAX_STRING_BYTES = 128
        private const val MAX_TOKEN_LENGTH = 512
        private const val BUCKET_SECONDS = 30L * 60L
        private val AAD = "patient-history-cursor-v1".toByteArray(StandardCharsets.UTF_8)
        private val REGISTRY_DOMAIN = "patient-history-registry-v1".toByteArray(StandardCharsets.UTF_8)
        private val NONCE_DOMAIN = "patient-history-nonce-v1".toByteArray(StandardCharsets.UTF_8)
        private val KEY_ID = Regex("[A-Za-z0-9_-]{1,32}")
        private val BASE64URL = Regex("[A-Za-z0-9_-]+")
        private val FINGERPRINT = Regex("[0-9a-f]{64}")
    }
}
