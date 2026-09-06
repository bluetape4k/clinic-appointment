package io.bluetape4k.clinic.appointment.test

import io.bluetape4k.exposed.tests.currentJdbcTestDbFixture
import org.junit.jupiter.api.Test

class TestDBFixtureAdapterTest {

    @Test
    fun `withDb delegates local TestDB to the provider fixture`() {
        withDb(TestDB.H2) { selected ->
            check(selected == TestDB.H2)
            check(currentJdbcTestDbFixture === TestDB.H2.fixture)
            check(TestDB.H2.fixture.database === db)
            check(TestDB.H2.db === db)
        }
    }
}
