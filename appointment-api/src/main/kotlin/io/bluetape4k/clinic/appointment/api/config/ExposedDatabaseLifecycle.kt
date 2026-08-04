package io.bluetape4k.clinic.appointment.api.config

import org.jetbrains.exposed.v1.jdbc.Database
import org.springframework.beans.factory.DisposableBean

/**
 * Spring context가 닫힐 때 factory가 등록한 Exposed manager를 해제합니다.
 *
 * 이 lifecycle bean은 [Database] handle만 해제하며, [javax.sql.DataSource]와 pool은
 * Spring의 원래 destroy 순서에 맡깁니다. 외부에서 제공한 Database를 임의로 해제하지
 * 않도록 [ExposedDatabaseFactory]가 factory 소유 handle인지 확인합니다.
 */
internal class ExposedDatabaseLifecycle(
    private val database: Database,
) : DisposableBean {
    override fun destroy() {
        ExposedDatabaseFactory.release(database)
    }
}
