package io.bluetape4k.clinic.appointment.messaging

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test

class AppointmentPartitionKeyFactoryTest {
    @Test
    fun `partition key is stable across event types`() {
        val first = AppointmentPartitionKeyFactory.create(7, 31, 924)
        val second = AppointmentPartitionKeyFactory.create(7, 31, 924)

        first shouldBeEqualTo second
        first.value shouldBeEqualTo "tenant-7:CLINIC:clinic-31:APPOINTMENT:apt-924"
    }

    @Test
    fun `partition key rejects invalid scope ids`() {
        assertFailsWith<IllegalArgumentException> { AppointmentPartitionKeyFactory.create(0, 31, 924) }
        assertFailsWith<IllegalArgumentException> { AppointmentPartitionKeyFactory.create(7, 0, 924) }
        assertFailsWith<IllegalArgumentException> { AppointmentPartitionKeyFactory.create(7, 31, 0) }
    }
}
