package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.mockk.every
import io.mockk.mockk
import java.sql.SQLException
import java.sql.SQLInvalidAuthorizationSpecException
import java.sql.SQLNonTransientConnectionException
import java.sql.SQLTimeoutException
import org.junit.jupiter.api.Test

internal class NotificationSchemaReadinessDiagnosticTest {

    @Test
    fun `schema missing은 stable table code와 non retryable 진단으로 보존된다`() {
        val diagnostic = NotificationSchemaReadiness.readinessDiagnosticFor(
            operation = "schema.table",
            target = "scheduling_notification_outbox",
            failure = SQLException("raw sql and secret must not escape", "42S02"),
        )

        diagnostic.code shouldBeEqualTo "SCHEMA_TABLE_MISSING"
        diagnostic.retryable.shouldBeFalse()
        diagnostic.errorClass shouldBeEqualTo "SQLException"
        diagnostic.toHealthDetail().containsKey("message").shouldBeFalse()
    }

    @Test
    fun `permission denied는 대상과 권한 code만 보존한다`() {
        val diagnostic = NotificationSchemaReadiness.readinessDiagnosticFor(
            operation = "schema.column",
            target = "scheduling_appointment_event_logs.tenant_group_id",
            failure = SQLInvalidAuthorizationSpecException("role=secret-user", "28000"),
        )

        diagnostic.code shouldBeEqualTo "SCHEMA_PERMISSION_DENIED"
        diagnostic.retryable.shouldBeFalse()
        diagnostic.toHealthDetail().values.any { it.toString().contains("secret-user") }.shouldBeFalse()
    }

    @Test
    fun `timeout과 connection failure는 재시도 가능한 code로 구분된다`() {
        val timeout = NotificationSchemaReadiness.readinessDiagnosticFor(
            operation = "schema.tenant-preflight",
            target = "scheduling_appointment_event_logs.tenant_scope",
            failure = SQLTimeoutException("database timeout"),
        )
        val connection = NotificationSchemaReadiness.readinessDiagnosticFor(
            operation = "schema.check",
            target = "database",
            failure = SQLNonTransientConnectionException("connection refused", "08001"),
        )

        timeout.code shouldBeEqualTo "SCHEMA_METADATA_TIMEOUT"
        timeout.retryable.shouldBeTrue()
        connection.code shouldBeEqualTo "SCHEMA_CONNECTION_FAILURE"
        connection.retryable.shouldBeTrue()
    }

    @Test
    fun `auto configuration readiness snapshot은 schema diagnostic code를 health code로 전달한다`() {
        val schema = mockk<NotificationSchemaReadiness>()
        every { schema.check() } returns NotificationReadiness.down(
            reason = "database permission denied",
            diagnostic = NotificationReadinessDiagnostic(
                operation = "schema.table",
                target = "scheduling_notification_outbox",
                code = "SCHEMA_PERMISSION_DENIED",
                errorClass = "SQLInvalidAuthorizationSpecException",
                retryable = false,
            ),
        )
        val producer = mockk<NotificationProducerSchemaReadiness>()
        every { producer.check() } returns NotificationReadiness.up()

        val snapshot = NotificationAutoConfiguration()
            .notificationOutboxReadinessSource(schema, producer)
            .snapshot()

        snapshot.schema.code shouldBeEqualTo "SCHEMA_PERMISSION_DENIED"
        snapshot.keyRing.code shouldBeEqualTo "KEY_RING_NOT_READY"
        snapshot.diagnostics.single().code shouldBeEqualTo "SCHEMA_PERMISSION_DENIED"
    }
}
