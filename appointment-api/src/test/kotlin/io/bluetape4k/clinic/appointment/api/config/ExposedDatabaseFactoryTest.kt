package io.bluetape4k.clinic.appointment.api.config

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.clinic.appointment.api.test.API_INTEGRATION_RESOURCE
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceAccessMode
import org.junit.jupiter.api.parallel.ResourceLock
import org.springframework.jdbc.datasource.DelegatingDataSource
import java.sql.Connection
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicInteger

@ResourceLock(value = API_INTEGRATION_RESOURCE, mode = ResourceAccessMode.READ_WRITE)
class ExposedDatabaseFactoryTest {

    @Test
    fun `factory reuses injected pool and restores previous default`() {
        val pool = newPool("factory")
        val originalDefaultDatabase = TransactionManager.defaultDatabase
        val sentinel = Database.connect(
            url = "jdbc:h2:mem:sentinel_${System.nanoTime()};DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver",
        )
        TransactionManager.defaultDatabase = sentinel
        try {
            pool.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("CREATE TABLE datasource_marker (marker_value INT NOT NULL)")
                    statement.execute("INSERT INTO datasource_marker(marker_value) VALUES (223)")
                }
            }

            val database = ExposedDatabaseFactory.connect(pool)

            transaction(database) {
                exec("SELECT marker_value FROM datasource_marker") { rows ->
                    rows.next()
                    rows.getInt(1)
                } shouldBeEqualTo 223
            }
            TransactionManager.defaultDatabase shouldBeEqualTo sentinel
            ExposedDatabaseFactory.release(database)
        } finally {
            TransactionManager.closeAndUnregister(sentinel)
            TransactionManager.defaultDatabase = originalDefaultDatabase
            pool.close()
        }
    }

    @Test
    fun `concurrent registrations keep marker access and default restoration bounded`() {
        val pool = newPool("factory_concurrent")
        seedMarker(pool)
        val originalDefaultDatabase = TransactionManager.defaultDatabase
        val sentinel = Database.connect(
            url = "jdbc:h2:mem:sentinel_concurrent_${System.nanoTime()};DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver",
        )
        TransactionManager.defaultDatabase = sentinel
        val workerCount = 6
        val barrier = CyclicBarrier(workerCount)
        val executor = Executors.newFixedThreadPool(workerCount)
        try {
            val futures = (0 until workerCount).map {
                executor.submit<Database> {
                    barrier.await(10, SECONDS)
                    val database = ExposedDatabaseFactory.connect(pool)
                    try {
                        markerValue(database) shouldBeEqualTo 223
                    } finally {
                        ExposedDatabaseFactory.release(database)
                    }
                    database
                }
            }
            futures.forEach { it.get(10, SECONDS) }
            TransactionManager.defaultDatabase shouldBeEqualTo sentinel
        } finally {
            executor.shutdownNow()
            TransactionManager.closeAndUnregister(sentinel)
            TransactionManager.defaultDatabase = originalDefaultDatabase
            pool.close()
        }
    }

    @Test
    fun `repeated transactions acquire connections from the injected data source`() {
        val pool = newPool("factory_reuse")
        seedMarker(pool)
        val originalDefaultDatabase = TransactionManager.defaultDatabase
        val acquisitions = AtomicInteger()
        val trackedDataSource = CountingDataSource(pool, acquisitions)
        val database = ExposedDatabaseFactory.connect(trackedDataSource)
        try {
            markerValue(database) shouldBeEqualTo 223
            val warmupAcquisitions = acquisitions.get()
            repeat(5) { markerValue(database) shouldBeEqualTo 223 }
            (acquisitions.get() - warmupAcquisitions) shouldBeEqualTo 5
        } finally {
            ExposedDatabaseFactory.release(database)
            TransactionManager.defaultDatabase = originalDefaultDatabase
            pool.close()
        }
    }

    @Test
    fun `lifecycle unregisters its handle and preserves the previously registered database`() {
        val pool = newPool("factory_lifecycle")
        val originalDefaultDatabase = TransactionManager.defaultDatabase
        val sentinel = Database.connect(
            url = "jdbc:h2:mem:sentinel_lifecycle_${System.nanoTime()};DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver",
        )
        TransactionManager.defaultDatabase = sentinel
        val database = ExposedDatabaseFactory.connect(pool)
        try {
            TransactionManager.defaultDatabase = null
            ExposedDatabaseLifecycle(database).destroy()
            TransactionManager.primaryDatabase shouldBeEqualTo sentinel
        } finally {
            TransactionManager.closeAndUnregister(sentinel)
            TransactionManager.defaultDatabase = originalDefaultDatabase
            pool.close()
        }
    }

    @Test
    fun `lifecycle leaves an externally registered handle untouched`() {
        val database = Database.connect(
            url = "jdbc:h2:mem:external_lifecycle_${System.nanoTime()};DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver",
        )
        try {
            ExposedDatabaseLifecycle(database).destroy()
            TransactionManager.managerFor(database).shouldNotBeNull()
        } finally {
            TransactionManager.closeAndUnregister(database)
        }
    }

    private fun newPool(name: String): HikariDataSource =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = "jdbc:h2:mem:${name}_${System.nanoTime()};DB_CLOSE_DELAY=-1"
                driverClassName = "org.h2.Driver"
                username = "sa"
            },
        )

    private fun seedMarker(pool: HikariDataSource) {
        pool.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE datasource_marker (marker_value INT NOT NULL)")
                statement.execute("INSERT INTO datasource_marker(marker_value) VALUES (223)")
            }
        }
    }

    private fun markerValue(database: Database): Int =
        transaction(database) {
            exec("SELECT marker_value FROM datasource_marker") { rows ->
                rows.next()
                rows.getInt(1)
            }
        }.shouldNotBeNull()

    private class CountingDataSource(
        target: HikariDataSource,
        private val acquisitions: AtomicInteger,
    ) : DelegatingDataSource(target) {
        override fun getConnection(): Connection {
            acquisitions.incrementAndGet()
            return super.getConnection()
        }

        override fun getConnection(username: String, password: String): Connection {
            acquisitions.incrementAndGet()
            return super.getConnection(username, password)
        }
    }
}
