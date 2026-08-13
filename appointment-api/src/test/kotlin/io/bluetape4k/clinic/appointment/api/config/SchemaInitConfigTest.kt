package io.bluetape4k.clinic.appointment.api.config

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.clinic.appointment.api.test.API_INTEGRATION_RESOURCE
import io.bluetape4k.clinic.appointment.model.tables.AppointmentCancellationDetails
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceAccessMode
import org.junit.jupiter.api.parallel.ResourceLock
import org.springframework.boot.DefaultApplicationArguments

@ResourceLock(value = API_INTEGRATION_RESOURCE, mode = ResourceAccessMode.READ_WRITE)
internal class SchemaInitConfigTest {

    @Test
    fun `Flyway 비활성 스키마는 취소 detail snapshot 테이블을 생성한다`() {
        val database = Database.connect(
            url = "jdbc:h2:mem:schema_init_${System.nanoTime()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver",
        )
        val previousDefaultDatabase = TransactionManager.defaultDatabase
        TransactionManager.defaultDatabase = database

        try {
            SchemaInitConfig().schemaInitializer().run(DefaultApplicationArguments())

            transaction(database) {
                AppointmentCancellationDetails.selectAll().count() shouldBeEqualTo 0L
            }
        } finally {
            TransactionManager.closeAndUnregister(database)
            TransactionManager.defaultDatabase = previousDefaultDatabase
        }
    }
}
