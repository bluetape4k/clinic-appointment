package io.bluetape4k.clinic.appointment.api.config

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test
import java.util.Base64

class PatientHistoryPropertiesTest {
    private val secret = Base64.getEncoder().encodeToString(ByteArray(32) { 7 })

    @Test
    fun `history api is disabled without secret-backed configuration`() {
        val properties = PatientHistoryProperties()

        properties.apiEnabled shouldBeEqualTo false
        properties.cursorKeys() shouldBeEqualTo emptyList()
        properties.referenceKeys() shouldBeEqualTo emptyList()
    }

    @Test
    fun `enabled configuration orders active key first and decodes both rings`() {
        val properties = PatientHistoryProperties(
            apiEnabled = true,
            activeKeyId = "k2",
            cursorKeySecrets = mapOf("k1" to secret, "k2" to secret),
            referenceKeySecrets = mapOf("k1" to secret, "k2" to secret),
        )

        properties.cursorKeys().map { it.id } shouldBeEqualTo listOf("k2", "k1")
        properties.referenceKeys().map { it.id } shouldBeEqualTo listOf("k2", "k1")
    }

    @Test
    fun `enabled configuration rejects missing or malformed secrets`() {
        assertFailsWith<IllegalArgumentException> {
            PatientHistoryProperties(apiEnabled = true, activeKeyId = "k1")
        }
        assertFailsWith<IllegalArgumentException> {
            PatientHistoryProperties(
                apiEnabled = true,
                activeKeyId = "k1",
                cursorKeySecrets = mapOf("k1" to "not-base64"),
                referenceKeySecrets = mapOf("k1" to secret),
            ).cursorKeys()
        }
    }
}
