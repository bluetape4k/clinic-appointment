package io.bluetape4k.clinic.appointment.api.config

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import java.util.concurrent.locks.ReentrantLock
import javax.sql.DataSource
import kotlin.concurrent.withLock

/**
 * Spring이 소유한 [DataSource]로 Exposed [Database] handle을 등록하는 공통 경계입니다.
 *
 * Spring이 pool의 생성·설정·종료를 담당하므로 이 객체는 pool을 생성하거나 닫지 않습니다.
 * Exposed의 [Database.connect]가 전역 기본 database를 잠시 바꾸는 동작은 등록 직후
 * 이전 값으로 복원하여, 서로 다른 Spring context와 legacy transaction 호출이 오염되지
 * 않도록 합니다.
 */
internal object ExposedDatabaseFactory {
    private val registrationLock = ReentrantLock()

    fun connect(dataSource: DataSource): Database = registrationLock.withLock {
        val previousDefaultDatabase = TransactionManager.defaultDatabase
        try {
            Database.connect(dataSource)
        } finally {
            TransactionManager.defaultDatabase = previousDefaultDatabase
        }
    }
}
