package io.bluetape4k.clinic.appointment.messaging

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpResponse

class JdkSchemaRegistryCompatibilityReaderTest {
    private val client = mockk<HttpClient>()
    private val response = mockk<HttpResponse<InputStream>>()

    @BeforeEach
    fun resetMocks() {
        clearMocks(client, response)
    }

    @ParameterizedTest
    @ValueSource(ints = [401, 403, 500])
    fun `비성공 응답은 본문을 읽지 않고 한 번 닫는다`(status: Int) {
        val input = TrackingInputStream("private response".toByteArray())
        val reader = reader(status, input)

        val failure = assertFailsWith<AppointmentSchemaRegistryUnavailableException> { reader() }

        failure.message shouldBeEqualTo "schema registry request failed"
        input.readCalls shouldBeEqualTo 0
        input.closeCalls shouldBeEqualTo 1
        verify(exactly = 0) { client.close() }
    }

    @ParameterizedTest
    @ValueSource(ints = [200, 299])
    fun `성공 응답은 compatibility를 반환하고 한 번 닫는다`(status: Int) {
        val input = TrackingInputStream("{\"compatibilityLevel\":\"BACKWARD_TRANSITIVE\"}".toByteArray())

        reader(status, input)() shouldBeEqualTo "BACKWARD_TRANSITIVE"

        input.closeCalls shouldBeEqualTo 1
        verify(exactly = 0) { client.close() }
    }

    @Test
    fun `빈 성공 응답은 닫은 뒤 기존 도메인 예외를 던진다`() {
        val input = TrackingInputStream(byteArrayOf())

        val failure = assertFailsWith<AppointmentSchemaRegistryUnavailableException> { reader(204, input)() }

        failure.message shouldBeEqualTo "schema registry compatibility is missing"
        input.closeCalls shouldBeEqualTo 1
    }

    @ParameterizedTest
    @ValueSource(ints = [65535, 65536])
    fun `바이트 상한 이내의 응답은 잘리지 않고 해석된다`(size: Int) {
        val body = "{\"compatibilityLevel\":\"BACKWARD\"}".padStart(size)
        val input = TrackingInputStream(body.toByteArray())

        reader(200, input)() shouldBeEqualTo "BACKWARD"

        input.closeCalls shouldBeEqualTo 1
    }

    @Test
    fun `바이트 상한 초과는 본문을 노출하지 않고 닫는다`() {
        val input = TrackingInputStream(ByteArray(65537) { 'x'.code.toByte() })

        val failure = assertFailsWith<IllegalArgumentException> { reader(200, input)() }

        failure.message shouldBeEqualTo "schema registry response is too large"
        input.closeCalls shouldBeEqualTo 1
    }

    @Test
    fun `읽기 실패는 원래 예외를 유지하고 한 번 닫는다`() {
        val readFailure = IOException("read failed")
        val input = TrackingInputStream(byteArrayOf(), readFailure = readFailure)

        val failure = assertFailsWith<IOException> { reader(200, input)() }

        failure shouldBeEqualTo readFailure
        input.closeCalls shouldBeEqualTo 1
    }

    @Test
    fun `비성공 응답의 닫기 실패는 도메인 예외에 suppressed로 보존된다`() {
        val closeFailure = IOException("close failed")
        val input = TrackingInputStream(byteArrayOf(), closeFailure = closeFailure)

        val failure = assertFailsWith<AppointmentSchemaRegistryUnavailableException> { reader(500, input)() }

        failure.message shouldBeEqualTo "schema registry request failed"
        failure.suppressed.single() shouldBeEqualTo closeFailure
        input.readCalls shouldBeEqualTo 0
        input.closeCalls shouldBeEqualTo 1
    }

    @Test
    fun `읽기와 닫기가 함께 실패하면 읽기 예외가 우선한다`() {
        val readFailure = IOException("read failed")
        val closeFailure = IOException("close failed")
        val input = TrackingInputStream(byteArrayOf(), readFailure, closeFailure)

        val failure = assertFailsWith<IOException> { reader(200, input)() }

        failure shouldBeEqualTo readFailure
        failure.suppressed.single() shouldBeEqualTo closeFailure
        input.closeCalls shouldBeEqualTo 1
    }

    private fun reader(status: Int, input: InputStream): JdkSchemaRegistryCompatibilityReader {
        every { client.send(any(), any<HttpResponse.BodyHandler<InputStream>>()) } returns response
        every { response.statusCode() } returns status
        every { response.body() } returns input
        return JdkSchemaRegistryCompatibilityReader(
            baseUri = URI("https://registry.example.com"),
            subject = "appointment-events-value",
            client = client,
        )
    }

    /** 실제 바이트 읽기를 수행하면서 읽기·닫기 횟수와 실패 순서를 관찰합니다. */
    private class TrackingInputStream(
        bytes: ByteArray,
        private val readFailure: IOException? = null,
        private val closeFailure: IOException? = null,
    ) : ByteArrayInputStream(bytes) {
        var readCalls = 0
            private set
        var closeCalls = 0
            private set

        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            readCalls++
            readFailure?.let { throw it }
            return super.read(bytes, offset, length)
        }

        override fun close() {
            closeCalls++
            super.close()
            closeFailure?.let { throw it }
        }
    }
}
