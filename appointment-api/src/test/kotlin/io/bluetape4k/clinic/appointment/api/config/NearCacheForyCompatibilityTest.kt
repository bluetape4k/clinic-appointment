package io.bluetape4k.clinic.appointment.api.config

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.clinic.appointment.model.dto.DoctorRecord
import io.bluetape4k.clinic.appointment.model.dto.EquipmentRecord
import io.bluetape4k.clinic.appointment.model.dto.TreatmentTypeRecord
import io.bluetape4k.io.serializer.ForyBinarySerializer
import io.bluetape4k.junit5.concurrency.MultithreadingTester
import io.bluetape4k.redis.lettuce.codec.LettuceBinaryCodecs
import org.apache.fory.Fory
import org.apache.fory.kotlin.ForyKotlin
import org.apache.fory.serializer.CodegenSerializer
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.util.Base64
import java.util.Properties

class NearCacheForyCompatibilityTest {

    @Test
    fun `현재 resolved Fory provenance가 2_0_0 dependency graph와 일치한다`() {
        val provenance = loadProperties("/cache/issue-322/fixture-provenance.properties")

        provenance.getProperty("source.fixture") shouldBeEqualTo "issue-253/1.3.1"
        provenance.getProperty("resolved.bluetape4k.dependencies") shouldBeEqualTo "2.0.0"
        provenance.getProperty("resolved.fory.core") shouldBeEqualTo "1.6.0"
        provenance.getProperty("resolved.fory.kotlin") shouldBeEqualTo "1.6.0"
        provenance.getProperty("codec") shouldBeEqualTo
            "io.bluetape4k.redis.lettuce.codec.LettuceBinaryCodecs.default"
        Fory::class.java.`package`.implementationVersion shouldBeEqualTo
            provenance.getProperty("resolved.fory.core")
        ForyKotlin::class.java.`package`.implementationVersion shouldBeEqualTo
            provenance.getProperty("resolved.fory.kotlin")
    }

    @Test
    fun `1_3_1 legacy fixture를 현재 Fory codec이 DTO로 복원한다`() {
        decode<List<DoctorRecord>>("doctors-1.3.1.base64") shouldBeEqualTo listOf(
            DoctorRecord(11L, 7L, "김의사", "내과", "DOCTOR", 2)
        )
        decode<List<EquipmentRecord>>("equipments-1.3.1.base64") shouldBeEqualTo listOf(
            EquipmentRecord(21L, 7L, "MRI", 30, 1)
        )
        decode<List<TreatmentTypeRecord>>("treatment-types-1.3.1.base64") shouldBeEqualTo listOf(
            TreatmentTypeRecord(31L, 7L, "일반 진료", defaultDurationMinutes = 30)
        )
    }

    @Test
    fun `codegen 결과와 ThreadSafeFory pool이 동시 wire round_trip을 보장한다`() {
        val config = CacheConfig.secureThreadSafeFory.execute { it.config }
        config.isCodeGenEnabled().shouldBeTrue()
        config.isAsyncCompilationEnabled().shouldBeTrue()

        val serializerNames = CacheConfig.secureThreadSafeFory.execute { fory ->
            fory.ensureSerializersCompiled()
            listOf(
                DoctorRecord::class.java,
                EquipmentRecord::class.java,
                TreatmentTypeRecord::class.java,
            ).map {
                "${fory.getSerializer(it).javaClass.name}:${fory.typeResolver.getSerializerClass(it).name}"
            }
        }
        listOf(
            DoctorRecord::class.java,
            EquipmentRecord::class.java,
            TreatmentTypeRecord::class.java,
        ).forEach { CodegenSerializer.supportCodegenForJavaSerialization(it).shouldBeTrue() }
        val codegenOutcome = when {
            serializerNames.all { it.contains("ForyRefCodec_") } -> "generated"
            serializerNames.all { it.contains("org.apache.fory.serializer.ObjectSerializer") } ->
                "interpreter-fallback"
            else -> "mixed"
        }
        (codegenOutcome == "generated" || codegenOutcome == "interpreter-fallback").shouldBeTrue()
        serializerNames.forEach {
            (it.contains("ForyRefCodec_") || it.contains("org.apache.fory.serializer.ObjectSerializer"))
                .shouldBeTrue()
        }
        val expectedCodegen = loadProperties("/cache/issue-322/fixture-provenance.properties")
            .getProperty("codegen.expected")
        expectedCodegen shouldBeEqualTo "generated-or-interpreter-fallback"
        println("Issue #322 codegen=$codegenOutcome expected=$expectedCodegen serializerClasses=$serializerNames")

        MultithreadingTester()
            .workers(4)
            .rounds(2)
            .add {
                val expected = listOf(DoctorRecord(11L, 7L, "김의사", "내과", "DOCTOR", 2))
                val wire = CacheConfig.secureCacheSerializer.serialize(expected)
                CacheConfig.secureCacheSerializer.deserialize<List<DoctorRecord>>(wire) shouldBeEqualTo expected
            }
            .run()
    }

    @Test
    fun `LZ4 압축 경로의 wire 크기와 반복 비용을 기록한다`() {
        val payload = List(64) { index ->
            DoctorRecord(
                id = index.toLong() + 1,
                clinicId = 7L,
                name = "김의사-$index",
                specialty = "내과",
                maxConcurrentPatients = 2,
            )
        }
        val rawSerializer = ForyBinarySerializer(CacheConfig.secureThreadSafeFory)
        val rawBytes = rawSerializer.serialize(payload)

        repeat(8) {
            CacheConfig.secureCacheSerializer.serialize(payload)
        }
        val compressedBytes = CacheConfig.secureCacheSerializer.serialize(payload)
        (compressedBytes.size < rawBytes.size).shouldBeTrue()

        val iterations = 64
        val startedAt = System.nanoTime()
        var totalCompressedBytes = 0L
        repeat(iterations) {
            totalCompressedBytes += CacheConfig.secureCacheSerializer.serialize(payload).size
        }
        val elapsedNanos = System.nanoTime() - startedAt
        val averageCompressedBytes = totalCompressedBytes / iterations
        (averageCompressedBytes > 0L).shouldBeTrue()
        println(
            "Issue #322 compression-evidence=" +
                "{\"iterations\":$iterations," +
                "\"rawBytes\":${rawBytes.size}," +
                "\"compressedBytes\":${compressedBytes.size}," +
                "\"averageCompressedBytes\":$averageCompressedBytes," +
                "\"elapsedNanos\":$elapsedNanos," +
                "\"allocationPath\":\"byte-array-compatibility\"}",
        )
    }

    private inline fun <reified T : Any> decode(resourceName: String): T {
        val encoded = checkNotNull(
            NearCacheForyCompatibilityTest::class.java.getResourceAsStream(
                "/cache/issue-253/$resourceName"
            )
        ).use { it.readBytes().toString(Charsets.UTF_8).trim() }
        return checkNotNull(
            LettuceBinaryCodecs.default<T>().decodeValue(
                ByteBuffer.wrap(Base64.getDecoder().decode(encoded))
            )
        )
    }

    private fun loadProperties(resourceName: String): Properties = Properties().apply {
        checkNotNull(NearCacheForyCompatibilityTest::class.java.getResourceAsStream(resourceName))
            .use { load(it) }
    }
}
