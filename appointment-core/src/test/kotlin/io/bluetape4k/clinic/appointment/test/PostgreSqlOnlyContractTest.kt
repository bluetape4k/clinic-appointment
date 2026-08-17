package io.bluetape4k.clinic.appointment.test

import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test

class PostgreSqlOnlyContractTest {
    @Test
    fun `test database registry contains only H2 fixtures and PostgreSQL`() {
        TestDB.entries.map { it.name }.toSet() shouldBeEqualTo setOf("H2", "H2_COMMITMENT", "POSTGRESQL")
    }
}
