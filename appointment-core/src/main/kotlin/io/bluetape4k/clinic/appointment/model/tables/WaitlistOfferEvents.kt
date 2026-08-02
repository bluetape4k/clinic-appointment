package io.bluetape4k.clinic.appointment.model.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.regexp
import org.jetbrains.exposed.v1.javatime.CurrentTimestamp
import org.jetbrains.exposed.v1.javatime.timestamp

/** 대기열 제안·hold lifecycle의 append-only 전이 원장입니다. */
object WaitlistOfferEvents : LongIdTable("scheduling_waitlist_offer_events") {
    val waitlistEntryId = reference("waitlist_entry_id", WaitlistEntries, onDelete = ReferenceOption.CASCADE)
    val offerId = reference("offer_id", WaitlistOffers, onDelete = ReferenceOption.CASCADE).nullable()
    val holdId = reference("hold_id", WaitlistCapacityHolds, onDelete = ReferenceOption.CASCADE).nullable()
    val fromState = varchar("from_state", 32).nullable()
    val toState = varchar("to_state", 32)
    val reasonCode = varchar("reason_code", 64)
    val actorRef = varchar("actor_ref", 128)
    val correlationId = varchar("correlation_id", 160)
    val occurredAt = timestamp("occurred_at").defaultExpression(CurrentTimestamp)
    val eventVersion = long("event_version")

    init {
        check("ck_waitlist_offer_event_version") { eventVersion greater 0L }
        check("ck_waitlist_offer_event_actor_ref") { actorRef regexp "^.{1,128}$" }
        check("ck_waitlist_offer_event_correlation_id") {
            correlationId regexp "^[A-Za-z0-9._:-]{1,160}$"
        }
        index("idx_waitlist_offer_event_entry_time", false, waitlistEntryId, occurredAt, id)
        index("idx_waitlist_offer_event_offer_version", false, offerId, eventVersion, id)
    }
}
