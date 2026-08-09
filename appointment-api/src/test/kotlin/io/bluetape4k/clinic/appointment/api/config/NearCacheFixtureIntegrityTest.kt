package io.bluetape4k.clinic.appointment.api.config

import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.util.Properties

class NearCacheFixtureIntegrityTest {
    @Test
    fun `1_3_1 fixture provenance와 SHA_256이 일치한다`() {
        val provenance = Properties().apply {
            checkNotNull(
                NearCacheFixtureIntegrityTest::class.java
                    .getResourceAsStream("/cache/issue-253/fixture-provenance.properties")
            )
                .use { load(it) }
        }
        provenance.getProperty("base.commit") shouldBeEqualTo
            "e790793a2e8eccf4269eba97f3faad084b7c568d"
        provenance.getProperty("bluetape4k.dependencies") shouldBeEqualTo "1.3.1"
        provenance.getProperty("fory.core") shouldBeEqualTo "1.1.0"
        provenance.getProperty("fory.kotlin") shouldBeEqualTo "1.3.0"
        provenance.getProperty("codec") shouldBeEqualTo
            "io.bluetape4k.redis.lettuce.codec.LettuceBinaryCodecs.default"

        mapOf(
            "doctors" to "doctors-1.3.1.base64",
            "equipments" to "equipments-1.3.1.base64",
            "treatment-types" to "treatment-types-1.3.1.base64",
        ).forEach { (family, resourceName) ->
            val bytes = checkNotNull(
                NearCacheFixtureIntegrityTest::class.java.getResourceAsStream(
                    "/cache/issue-253/$resourceName"
                )
            )
                .use { it.readAllBytes() }
            sha256(bytes) shouldBeEqualTo provenance.getProperty("$family.sha256")
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            "%02x".format(it.toInt() and 0xff)
        }
}
