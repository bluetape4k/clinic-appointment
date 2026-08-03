package io.bluetape4k.clinic.appointment.service.waitlist

import io.bluetape4k.clinic.appointment.model.waitlist.ClinicWaitlistScope
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistCommandKey
import io.bluetape4k.support.requireNotBlank
import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * waitlist command의 원본 idempotency key를 scope와 command별 HMAC digest로 변환합니다.
 *
 * 원본 key는 이 경계 밖으로 반환하지 않으며, 저장소에는 `hmac-sha256:` prefix가 붙은
 * digest만 전달합니다. HMAC secret은 `appointment.waitlist.idempotency-hmac-secret`
 * 설정에서 주입해야 하며 최소 32바이트여야 합니다.
 */
class WaitlistCommandIdempotencyKeyHasher(
    secret: ByteArray,
) {
    private val secret = secret.copyOf().also {
        require(it.size >= MIN_SECRET_BYTES) {
            "$PROPERTY_NAME must contain at least $MIN_SECRET_BYTES bytes"
        }
    }

    /**
     * 동일 raw key라도 tenant, clinic, command가 다르면 서로 다른 저장 key가 됩니다.
     */
    fun hash(
        scope: ClinicWaitlistScope,
        commandType: String,
        rawKey: String,
    ): WaitlistCommandKey {
        val validCommandType = commandType.requireNotBlank("commandType")
        require(COMMAND_TYPE_REGEX.matches(validCommandType)) {
            "commandType must contain 1..64 uppercase safe characters"
        }
        require(rawKey.length in MIN_KEY_LENGTH..MAX_KEY_LENGTH && rawKey.all { it.code in ASCII_VISIBLE_RANGE }) {
            "rawKey must contain $MIN_KEY_LENGTH..$MAX_KEY_LENGTH visible ASCII characters"
        }

        val mac = Mac.getInstance(HMAC_SHA256)
        mac.init(SecretKeySpec(secret, HMAC_SHA256))
        mac.updateDomainField("waitlist-command-idempotency-v1")
        mac.updateDomainField(scope.tenantGroupId)
        mac.updateDomainField(scope.clinicId)
        mac.updateDomainField(validCommandType)
        mac.updateDomainField(rawKey)

        return WaitlistCommandKey(
            tenantGroupId = scope.tenantGroupId,
            clinicId = scope.clinicId,
            commandType = validCommandType,
            keyDigest = "hmac-sha256:${mac.doFinal().toHex()}",
        )
    }

    private fun Mac.updateDomainField(value: Any) {
        val bytes = value.toString().toByteArray(StandardCharsets.UTF_8)
        update(bytes.size.toString().toByteArray(StandardCharsets.UTF_8))
        update(0)
        update(bytes)
        update(0)
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { byte -> "%02x".format(byte) }

    companion object {
        const val PROPERTY_NAME = "appointment.waitlist.idempotency-hmac-secret"
        private const val HMAC_SHA256 = "HmacSHA256"
        private const val MIN_SECRET_BYTES = 32
        private const val MIN_KEY_LENGTH = 16
        private const val MAX_KEY_LENGTH = 128
        private val ASCII_VISIBLE_RANGE = 0x21..0x7e
        private val COMMAND_TYPE_REGEX = Regex("[A-Z0-9_]{1,64}")
    }
}
