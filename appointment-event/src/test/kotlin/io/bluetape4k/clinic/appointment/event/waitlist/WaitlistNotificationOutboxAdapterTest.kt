package io.bluetape4k.clinic.appointment.event.waitlist

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.clinic.appointment.model.waitlist.CorrelationId
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistReasonCode
import io.bluetape4k.clinic.appointment.service.waitlist.WaitlistOfferNotificationDraft
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

class WaitlistNotificationOutboxAdapterTest {

    private val database = Database.connect(
        "jdbc:h2:mem:waitlist_notification_outbox_${System.nanoTime()};DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        driver = "org.h2.Driver",
    )
    private val repository = WaitlistNotificationOutboxRepository()
    private val adapter = WaitlistNotificationOutboxAdapter(repository)

    @BeforeEach
    fun setup() {
        transaction(database) {
            SchemaUtils.createMissingTablesAndColumns(WaitlistNotificationOutboxEvents)
            WaitlistNotificationOutboxEvents.deleteAll()
        }
    }

    @Test
    fun `opaque draft는 canonical JSON과 durable outbox row로 변환된다`() {
        transaction(database) {
            val row = adapter.persist(draft())

            row.status shouldBeEqualTo WaitlistNotificationOutboxStatus.PENDING
            row.tenantGroupId shouldBeEqualTo 7L
            row.clinicId shouldBeEqualTo 11L
            row.offerId shouldBeEqualTo 41L
            row.holdId shouldBeEqualTo 42L
            row.waitlistEntryId shouldBeEqualTo 43L
            row.payloadJson shouldBeEqualTo WaitlistNotificationOutboxCodec().encode(
                WaitlistNotificationOutboxEnvelope.from(draft()),
            )
            row.payloadJson.contains("member").shouldBeEqualTo(false)
            row.payloadJson.contains("appointment").shouldBeEqualTo(false)
            WaitlistNotificationOutboxEvents.selectAll().count() shouldBeEqualTo 1L
        }
    }

    @Test
    fun `같은 offer draft는 idempotency row 하나로 수렴한다`() {
        transaction(database) {
            val first = adapter.persist(draft())
            val second = adapter.persist(draft())

            second.id shouldBeEqualTo first.id
            WaitlistNotificationOutboxEvents.selectAll().count() shouldBeEqualTo 1L
        }
    }

    @Test
    fun `adapter는 caller transaction 밖에서 자체 transaction을 열지 않는다`() {
        var persisted: WaitlistNotificationOutboxRow? = null
        val observingAdapter = WaitlistNotificationOutboxAdapter(sink = { row ->
            persisted = row
            WaitlistNotificationOutboxRecord.from(row, id = 99L)
        })

        observingAdapter.enqueue(draft())

        persisted?.id shouldBeEqualTo null
        persisted?.status shouldBeEqualTo WaitlistNotificationOutboxStatus.PENDING
    }

    @Test
    fun `repository adapter는 caller transaction이 없으면 실패한다`() {
        assertFailsWith<IllegalStateException> {
            adapter.persist(draft())
        }
    }

    @Test
    fun `codec는 schema version과 opaque payload를 strict하게 round trip한다`() {
        val envelope = WaitlistNotificationOutboxEnvelope.from(draft())
        val codec = WaitlistNotificationOutboxCodec()

        codec.decode(codec.encode(envelope)) shouldBeEqualTo envelope
        codec.encode(envelope).contains("member").shouldBeEqualTo(false)
        codec.encode(envelope).contains("appointment").shouldBeEqualTo(false)
        assertFailsWith<WaitlistNotificationOutboxContractException> {
            codec.decode(codec.encode(envelope).replace("\"schemaVersion\":1", "\"schemaVersion\":2"))
        }
    }

    private fun draft() = WaitlistOfferNotificationDraft(
        tenantGroupId = 7L,
        clinicId = 11L,
        offerId = 41L,
        holdId = 42L,
        waitlistEntryId = 43L,
        reasonCode = WaitlistReasonCode("OFFER_CREATED"),
        correlationId = CorrelationId("corr-170"),
        occurredAt = Instant.parse("2026-08-03T10:00:00Z"),
    )
}
