package io.bluetape4k.clinic.appointment.api.projection

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.bluetape4k.clinic.appointment.api.test.Containers
import io.bluetape4k.clinic.appointment.model.dto.ClinicRecord
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.TenantGroups
import io.bluetape4k.spring.data.exposed.jdbc.annotation.ExposedEntity
import io.bluetape4k.spring.data.exposed.jdbc.repository.ExposedJdbcRepository
import io.bluetape4k.spring.data.exposed.jdbc.repository.config.EnableExposedJdbcRepositories
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.dao.LongEntity
import org.jetbrains.exposed.v1.dao.LongEntityClass
import org.jetbrains.exposed.v1.spring7.transaction.SpringTransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.DependsOn
import org.springframework.context.annotation.Bean
import org.springframework.beans.factory.DisposableBean
import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType
import org.springframework.jdbc.datasource.SimpleDriverDataSource
import org.springframework.transaction.PlatformTransactionManager
import java.sql.Driver
import java.util.UUID
import javax.sql.DataSource

@ExposedEntity
internal class ClinicProjectionEntity(id: EntityID<Long>) : LongEntity(id) {
    var tenantGroupId by Clinics.tenantGroupId
    var name by Clinics.name
    var slotDurationMinutes by Clinics.slotDurationMinutes
    var timezone by Clinics.timezone
    var locale by Clinics.locale
    var maxConcurrentPatients by Clinics.maxConcurrentPatients
    var openOnHolidays by Clinics.openOnHolidays

    companion object : LongEntityClass<ClinicProjectionEntity>(Clinics)
}

internal interface ClinicProjectionRepository : ExposedJdbcRepository<ClinicProjectionEntity, Long> {
    override val table
        get() = Clinics

    override fun extractId(entity: ClinicProjectionEntity): Long? =
        entity.id.value.takeIf { it != 0L }

    fun findByTenantGroupIdOrderByIdAsc(tenantGroupId: EntityID<Long>): List<ClinicProjectionEntity>
}

/**
 * Spring-managed transaction 안에서 Clinics read-only pilot을 호출합니다.
 * 기존 ClinicRepository의 drop-in replacement나 API pagination 계약이 아닙니다.
 * 예: `adapter.findByTenant(tenantGroupId = 42L)`
 */
internal class ClinicProjectionAdapter(
    private val repository: ClinicProjectionRepository,
) {
    fun findByTenant(tenantGroupId: Long): List<ClinicRecord> {
        require(tenantGroupId > 0) { "tenantGroupId는 양수여야 합니다: $tenantGroupId" }
        return repository
            .findByTenantGroupIdOrderByIdAsc(EntityID(tenantGroupId, TenantGroups))
            .map(ClinicProjectionEntity::toClinicRecord)
    }
}

internal fun ClinicProjectionEntity.toClinicRecord(): ClinicRecord =
    ClinicRecord(
        id = id.value,
        tenantGroupId = tenantGroupId.value,
        name = name,
        slotDurationMinutes = slotDurationMinutes,
        timezone = timezone,
        locale = locale,
        maxConcurrentPatients = maxConcurrentPatients,
        openOnHolidays = openOnHolidays,
    )

@TestConfiguration(proxyBeanMethods = false)
@EnableExposedJdbcRepositories(
    basePackageClasses = [ClinicProjectionRepository::class],
    transactionManagerRef = "springTransactionManager",
)
internal class PilotTestConfiguration {

    @Bean
    fun pilotSchemaOwner(environment: Environment): Issue315SchemaOwner =
        if (environment.acceptsProfiles(Profiles.of("test-postgresql"))) {
            Issue315SchemaOwner.createPostgres()
        } else {
            Issue315SchemaOwner.none()
        }

    @Bean
    @DependsOn("pilotSchemaOwner")
    fun dataSource(environment: Environment, schemaOwner: Issue315SchemaOwner): DataSource =
        schemaOwner.pool ?: EmbeddedDatabaseBuilder()
            .generateUniqueName(true)
            .setType(EmbeddedDatabaseType.H2)
            .build()

    @Bean("springTransactionManager")
    fun springTransactionManager(dataSource: DataSource): PlatformTransactionManager =
        SpringTransactionManager(dataSource, DatabaseConfig {}, false)

    @Bean
    fun clinicProjectionAdapter(repository: ClinicProjectionRepository): ClinicProjectionAdapter =
        ClinicProjectionAdapter(repository)
}

@TestConfiguration(proxyBeanMethods = false)
internal class CloseFailureTestConfiguration {

    @Bean
    fun closeFailureProbe(): CloseFailureProbe = CloseFailureProbe()
}

internal class CloseFailureProbe : DisposableBean {
    var observedFailure: Throwable? = null

    override fun destroy() {
        observedFailure = IllegalStateException("issue315-close-failure")
        throw observedFailure!!
    }
}

/**
 * PostgreSQL pilot 전용 schema와 pool의 수명을 context에 묶습니다.
 * H2 실행에서는 아무 외부 자원도 만들지 않습니다.
 */
internal class Issue315SchemaOwner private constructor(
    val pool: HikariDataSource?,
    private val adminDataSource: DataSource?,
    val schema: String?,
) : AutoCloseable {

    val profile: String = if (pool == null) "test-h2" else "test-postgresql"
    val dialect: String = if (pool == null) "h2" else "postgresql"
    val poolDescription: String =
        pool?.let { "hikari(maximumPoolSize=${it.maximumPoolSize},connectionTimeoutMs=5000)" }
            ?: "embedded-default"

    override fun close() {
        pool?.close()
        val currentSchema = schema ?: return
        val admin = adminDataSource ?: return
        admin.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("DROP SCHEMA IF EXISTS \"$currentSchema\" CASCADE")
            }
        }
    }

    fun assertSchemaDropped() {
        val currentSchema = schema ?: return
        val admin = adminDataSource ?: error("Issue #315 PostgreSQL admin DataSource is missing")
        admin.connection.use { connection ->
            connection.prepareStatement(
                "SELECT EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = ?)",
            ).use { statement ->
                statement.setString(1, currentSchema)
                statement.executeQuery().use { resultSet ->
                    check(resultSet.next() && !resultSet.getBoolean(1)) {
                        "Issue #315 PostgreSQL schema still exists after context close: $currentSchema"
                    }
                }
            }
        }
    }

    companion object {
        fun none(): Issue315SchemaOwner = Issue315SchemaOwner(null, null, null)

        fun createPostgres(): Issue315SchemaOwner {
            val postgres = Containers.Postgres
            val schema = "issue315_${UUID.randomUUID().toString().replace("-", "")}".take(48)
            val driver = Class.forName("org.postgresql.Driver")
                .getDeclaredConstructor()
                .newInstance() as Driver
            val admin = SimpleDriverDataSource(
                driver,
                postgres.jdbcUrl,
                postgres.username ?: "test",
                postgres.password ?: "",
            )
            admin.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("CREATE SCHEMA \"$schema\"")
                }
            }

            val hikariConfig = HikariConfig().apply {
                jdbcUrl = postgres.jdbcUrl
                username = postgres.username ?: "test"
                password = postgres.password ?: ""
                driverClassName = "org.postgresql.Driver"
                maximumPoolSize = 2
                minimumIdle = 0
                connectionTimeout = 5_000
                validationTimeout = 2_000
                connectionInitSql = "SET search_path TO \"$schema\""
                addDataSourceProperty(
                    "options",
                    "-c statement_timeout=5000 -c lock_timeout=2000",
                )
            }
            return Issue315SchemaOwner(
                pool = HikariDataSource(hikariConfig),
                adminDataSource = admin,
                schema = schema,
            )
        }
    }
}

internal fun pilotContextRunner(): ApplicationContextRunner =
    ApplicationContextRunner()
        .withUserConfiguration(PilotTestConfiguration::class.java)

internal fun <T> withPilotContext(
    runner: ApplicationContextRunner = pilotContextRunner(),
    consumer: (AssertableApplicationContext) -> T,
): T {
    val previousDefault = TransactionManager.defaultDatabase
    val previousPrimary = TransactionManager.primaryDatabase
    TransactionManager.defaultDatabase = null
    var result: T? = null
    var schemaOwner: Issue315SchemaOwner? = null
    var closeFailureProbe: CloseFailureProbe? = null
    var failure: Throwable? = null
    try {
        runner.run { context ->
            schemaOwner = context.getBeansOfType(Issue315SchemaOwner::class.java).values.firstOrNull()
            closeFailureProbe = context.getBeansOfType(CloseFailureProbe::class.java).values.firstOrNull()
            context.getStartupFailure()?.let { throw it }
            result = consumer(context)
        }
    } catch (throwable: Throwable) {
        failure = throwable
    } finally {
        var cleanupFailure: Throwable? = null
        closeFailureProbe?.observedFailure?.let { closeFailure ->
            if (failure == null) {
                failure = closeFailure
            } else {
                failure?.addSuppressed(closeFailure)
            }
        }
        fun cleanup(step: () -> Unit) {
            try {
                step()
            } catch (throwable: Throwable) {
                if (cleanupFailure == null) {
                    cleanupFailure = throwable
                } else {
                    cleanupFailure?.addSuppressed(throwable)
                }
            }
        }
        cleanup { schemaOwner?.assertSchemaDropped() }
        cleanup {
            val candidate = TransactionManager.primaryDatabase
            if (candidate != null && candidate !== previousPrimary) {
                TransactionManager.closeAndUnregister(candidate)
            }
        }
        cleanup {
            check(TransactionManager.currentOrNull() == null) {
                "Issue #315 pilot cleanup left an active Exposed transaction"
            }
        }
        cleanup { TransactionManager.defaultDatabase = previousDefault }
        cleanup {
            check(TransactionManager.primaryDatabase === previousPrimary) {
                "Issue #315 pilot cleanup changed the previous primary Database: " +
                    "expected=${previousPrimary?.let { System.identityHashCode(it) }}, " +
                    "actual=${TransactionManager.primaryDatabase?.let { System.identityHashCode(it) }}"
            }
        }
        cleanupFailure?.let { cleanupError ->
            if (failure == null) {
                failure = cleanupError
            } else {
                failure?.addSuppressed(cleanupError)
            }
        }
    }
    failure?.let { throw it }
    @Suppress("UNCHECKED_CAST")
    return result as T
}
