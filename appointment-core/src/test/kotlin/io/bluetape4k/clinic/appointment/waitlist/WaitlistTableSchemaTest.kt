package io.bluetape4k.clinic.appointment.waitlist

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.clinic.appointment.model.tables.WaitlistCapacityHolds
import io.bluetape4k.clinic.appointment.model.tables.WaitlistEntries
import io.bluetape4k.clinic.appointment.model.tables.WaitlistOfferEvents
import io.bluetape4k.clinic.appointment.model.tables.WaitlistOffers
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.Doctors
import io.bluetape4k.clinic.appointment.model.tables.TenantGroups
import io.bluetape4k.clinic.appointment.model.tables.TreatmentTypes
import io.bluetape4k.clinic.appointment.test.AbstractExposedTest
import io.bluetape4k.clinic.appointment.test.TestDB
import io.bluetape4k.clinic.appointment.test.withDb
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/** Waitlist table의 이름과 FK graph를 검증합니다.
 *
 * 공용 TestDB에는 다른 테스트가 만든 clinic FK가 남을 수 있으므로 tenant
 * parent는 유지하고 waitlist table만 역순으로 정리합니다.
 */
class WaitlistTableSchemaTest : AbstractExposedTest() {

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `waitlist tables create with the expected names`(testDB: TestDB) {
        withDb(testDB) {
            val waitlistTables = arrayOf(
                WaitlistOfferEvents,
                WaitlistCapacityHolds,
                WaitlistOffers,
                WaitlistEntries,
            )
            SchemaUtils.drop(*waitlistTables)
            SchemaUtils.create(
                TenantGroups,
                Clinics,
                Doctors,
                TreatmentTypes,
                WaitlistEntries,
                WaitlistOffers,
                WaitlistCapacityHolds,
                WaitlistOfferEvents,
            )
            WaitlistEntries.tableName shouldBeEqualTo "scheduling_waitlist_entries"
            WaitlistOffers.tableName shouldBeEqualTo "scheduling_waitlist_offers"
            WaitlistCapacityHolds.tableName shouldBeEqualTo "scheduling_waitlist_capacity_holds"
            WaitlistOfferEvents.tableName shouldBeEqualTo "scheduling_waitlist_offer_events"
            commit()
            SchemaUtils.drop(*waitlistTables)
        }
    }
}
