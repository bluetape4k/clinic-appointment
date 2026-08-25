package io.bluetape4k.clinic.appointment.api.projection

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.clinic.appointment.api.test.API_INTEGRATION_RESOURCE
import io.bluetape4k.clinic.appointment.api.security.SchedulingRole
import io.bluetape4k.clinic.appointment.api.security.SchedulingUserPrincipal
import io.bluetape4k.clinic.appointment.api.tenant.TenantClinicAccessChecker
import io.bluetape4k.clinic.appointment.model.dto.ClinicRecord
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.TenantGroups
import io.bluetape4k.clinic.appointment.repository.ClinicRepository
import io.bluetape4k.clinic.appointment.repository.DoctorRepository
import io.bluetape4k.clinic.appointment.repository.EquipmentRepository
import io.bluetape4k.clinic.appointment.repository.TenantGroupRepository
import io.bluetape4k.clinic.appointment.repository.TreatmentTypeRepository
import io.bluetape4k.clinic.appointment.repository.toClinicRecord
import io.bluetape4k.junit5.concurrency.MultithreadingTester
import io.bluetape4k.spring.data.exposed.jdbc.repository.config.EnableExposedJdbcRepositories
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.StatementContext
import org.jetbrains.exposed.v1.core.statements.StatementInterceptor
import org.jetbrains.exposed.v1.core.statements.api.PreparedStatementApi
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.ResourceAccessMode
import org.junit.jupiter.api.parallel.ResourceLock
import org.springframework.jdbc.datasource.DataSourceUtils
import org.springframework.security.access.AccessDeniedException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.math.ceil
import kotlin.math.roundToLong
import kotlin.system.measureNanoTime

@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock(value = API_INTEGRATION_RESOURCE, mode = ResourceAccessMode.READ_WRITE)
class ClinicSpringDataProjectionPilotTest {

    @Test
    fun `adapter 결과가 legacy ClinicRepository와 필드 및 id asc 순서가 같다`(): Unit =
        withPilotContext { context ->
            val fixture = seedFixture(context)
            val adapter = context.getBean(ClinicProjectionAdapter::class.java)
            val legacyRepository = ClinicRepository()

            val (legacy, candidate) = transactionTemplate(context).execute {
                legacyRepository.findByTenant(fixture.tenantA) to adapter.findByTenant(fixture.tenantA)
            }

            legacy shouldBeEqualTo candidate
            candidate.sortedBy { it.id } shouldBeEqualTo candidate
            candidate.all { it.tenantGroupId == fixture.tenantA } shouldBeEqualTo true
        }

    @Test
    fun `tenant 격리와 invalid 및 unknown tenant 입력 계약을 지킨다`(): Unit =
        withPilotContext { context ->
            val fixture = seedFixture(context)
            val adapter = context.getBean(ClinicProjectionAdapter::class.java)

            assertFailsWith<IllegalArgumentException> { adapter.findByTenant(0L) }
            assertFailsWith<IllegalArgumentException> { adapter.findByTenant(-1L) }

            val result = transactionTemplate(context).execute {
                adapter.findByTenant(fixture.tenantB) to adapter.findByTenant(999_999L)
            }

            result.first.single().tenantGroupId shouldBeEqualTo fixture.tenantB
            result.second.isEmpty() shouldBeEqualTo true
        }

    @Test
    fun `Spring transaction과 Exposed 및 DataSource connection을 공유한다`(): Unit =
        withPilotContext { context ->
            val dataSource = context.getBean(DataSource::class.java)
            val transactionManager = context.getBean(
                "springTransactionManager",
                PlatformTransactionManager::class.java,
            )
            val database = TransactionManager.primaryDatabase
            val managerNames = context.getBeansOfType(PlatformTransactionManager::class.java).keys
            val repositoryBeans = context.getBeansOfType(ClinicProjectionRepository::class.java)
            val repositoryBeanName = repositoryBeans.keys.single()
            val repositoryBeanDefinition = context.beanFactory.getBeanDefinition(repositoryBeanName)
            val repositoryConfiguration = PilotTestConfiguration::class.java
                .getAnnotation(EnableExposedJdbcRepositories::class.java)

            managerNames shouldBeEqualTo setOf("springTransactionManager")
            repositoryBeans.size shouldBeEqualTo 1
            repositoryConfiguration.transactionManagerRef shouldBeEqualTo "springTransactionManager"
            repositoryBeanDefinition.propertyValues.get("transactionManager") shouldBeEqualTo
                "springTransactionManager"
            (database != null) shouldBeEqualTo true

            TransactionTemplate(transactionManager).executeWithoutResult {
                org.springframework.transaction.support.TransactionSynchronizationManager
                    .isActualTransactionActive() shouldBeEqualTo true
                val springConnection = DataSourceUtils.getConnection(dataSource)
                val exposedConnection = TransactionManager.current().connection.connection
                (springConnection === exposedConnection) shouldBeEqualTo true
                (database === TransactionManager.current().db) shouldBeEqualTo true
            }
        }

    @Test
    fun `PartTree 조회가 tenant predicate와 id asc 단일 SELECT를 만든다`(): Unit =
        withPilotContext { context ->
            val fixture = seedFixture(context)
            val adapter = context.getBean(ClinicProjectionAdapter::class.java)
            val statements = mutableListOf<String>()

            transactionTemplate(context).executeWithoutResult {
                val transaction = TransactionManager.current()
                val interceptor = SqlStatementCapture(statements)
                transaction.registerInterceptor(interceptor)
                try {
                    adapter.findByTenant(fixture.tenantA)
                } finally {
                    transaction.unregisterInterceptor(interceptor)
                }
            }

            val clinicSelects = statements.filter {
                it.contains("select") && it.contains("scheduling_clinics")
            }
            clinicSelects.size shouldBeEqualTo 1
            clinicSelects.single().contains("tenant_group_id") shouldBeEqualTo true
            Regex("order by .*id asc").containsMatchIn(clinicSelects.single()) shouldBeEqualTo true
            statements.count { it.contains("scheduling_clinics") } shouldBeEqualTo 1
        }

    @Test
    fun `현재 Spring Data repository 경로는 Clinics 전체 컬럼을 읽는 full-row DAO SQL을 만든다`(): Unit =
        withPilotContext { context ->
            val fixture = seedFixture(context)
            val repository = context.getBean(ClinicProjectionRepository::class.java)
            val statements = mutableListOf<String>()

            transactionTemplate(context).executeWithoutResult {
                val transaction = TransactionManager.current()
                val interceptor = SqlStatementCapture(statements)
                transaction.registerInterceptor(interceptor)
                try {
                    repository.findByTenantGroupIdOrderByIdAsc(EntityID(fixture.tenantA, TenantGroups))
                } finally {
                    transaction.unregisterInterceptor(interceptor)
                }
            }

            val select = statements.single {
                it.contains("select") && it.contains("scheduling_clinics")
            }
            val selectedColumns = Clinics.columns.count { column ->
                select.contains(column.name.lowercase())
            }

            selectedColumns shouldBeEqualTo Clinics.columns.size
            println(
                "ISSUE315_PROJECTION_CAPABILITY profile=${context.environment.activeProfiles.contentToString()} " +
                    "repository=bluetape4k-exposed-spring-boot-jdbc:1.12.1 " +
                    "mode=full_row_dao selectedColumns=$selectedColumns " +
                    "tableColumns=${Clinics.columns.size} columnLevelProjection=NOT_AVAILABLE",
            )
        }

    @Test
    fun `candidate 결과는 기존 tenant clinic role 권한 경계 뒤에서만 허용된다`(): Unit =
        withPilotContext { context ->
            val fixture = seedFixture(context)
            val candidate = context.getBean(ClinicProjectionAdapter::class.java)
            val accessChecker = TenantClinicAccessChecker(
                tenantGroupRepository = TenantGroupRepository(),
                clinicRepository = ClinicRepository(),
                doctorRepository = DoctorRepository(),
                treatmentTypeRepository = TreatmentTypeRepository(),
                equipmentRepository = EquipmentRepository(),
            )
            val rows = transactionTemplate(context).execute {
                candidate.findByTenant(fixture.tenantA)
            }
            val allowedPrincipal = SchedulingUserPrincipal(
                userId = "issue315-staff",
                clinicId = 102L,
                roles = setOf(SchedulingRole.STAFF),
                allowedTenants = setOf("issue315-tenant-a"),
                allowedClinicIds = setOf(102L),
            )

            rows.map { it.id } shouldBeEqualTo listOf(101L, 102L)
            transactionTemplate(context).executeWithoutResult {
                accessChecker.verifyClinicForPrincipal(
                    tenantCode = "issue315-tenant-a",
                    clinicId = 102L,
                    principal = allowedPrincipal,
                )
            }
            assertFailsWith<AccessDeniedException> {
                transactionTemplate(context).executeWithoutResult {
                    accessChecker.verifyClinicForPrincipal(
                        tenantCode = "issue315-tenant-a",
                        clinicId = 101L,
                        principal = allowedPrincipal,
                    )
                }
            }
            assertFailsWith<AccessDeniedException> {
                transactionTemplate(context).executeWithoutResult {
                    accessChecker.verifyClinicForPrincipal(
                        tenantCode = "issue315-tenant-b",
                        clinicId = 201L,
                        principal = allowedPrincipal,
                    )
                }
            }
            assertFailsWith<AccessDeniedException> {
                transactionTemplate(context).executeWithoutResult {
                    accessChecker.verifyClinicForPrincipal(
                        tenantCode = "issue315-tenant-a",
                        clinicId = 102L,
                        principal = allowedPrincipal.copy(
                            roles = setOf(SchedulingRole.PATIENT),
                        ),
                    )
                }
            }

            println(
                "ISSUE315_AUTHZ_BOUNDARY tenantPredicate=preserved allowed=1 " +
                    "deniedClinic=1 deniedTenant=1 deniedRole=1 routeIntegration=NOT_APPLICABLE",
            )
        }

    @Test
    fun `PostgreSQL Hikari pool contention에서도 후보 조회가 모두 완료되고 결과를 보존한다`(): Unit =
        withPilotContext { context ->
            val schemaOwner = context.getBean(Issue315SchemaOwner::class.java)
            val pool = schemaOwner.pool
            if (pool == null) {
                println(
                    "ISSUE315_POOL profile=${schemaOwner.profile} status=NOT_TESTED " +
                        "reason=Hikari PostgreSQL pool is not active",
                )
                return@withPilotContext
            }

            val fixture = seedFixture(context)
            val adapter = context.getBean(ClinicProjectionAdapter::class.java)
            val dataSource = context.getBean(DataSource::class.java)
            val transactionTemplate = transactionTemplate(context)
            val holdersReady = CountDownLatch(POOL_SIZE)
            val releaseHolders = CountDownLatch(1)
            val samples = CopyOnWriteArrayList<PoolSample>()

            pool.maximumPoolSize shouldBeEqualTo POOL_SIZE

            fun runWorker(worker: Int) {
                val startedAt = System.nanoTime()
                val result = transactionTemplate.execute {
                    val connection = DataSourceUtils.getConnection(dataSource)
                    connection.createStatement().use { statement ->
                        statement.execute("SELECT 1")
                    }
                    holdersReady.countDown()
                    check(releaseHolders.await(POOL_RELEASE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        "Issue #315 pool contention release timed out"
                    }
                    adapter.findByTenant(fixture.tenantA)
                }
                samples += PoolSample(
                    worker = worker,
                    elapsedNanos = System.nanoTime() - startedAt,
                    result = result,
                )
            }

            val releaser = Thread.ofPlatform().name("issue315-pool-release").start {
                try {
                    holdersReady.await(POOL_RELEASE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                } finally {
                    releaseHolders.countDown()
                }
            }
            try {
                MultithreadingTester()
                    .workers(POOL_WORKERS)
                    .rounds(1)
                    .addAll((0 until POOL_WORKERS).map { worker -> { runWorker(worker) } })
                    .run()
            } finally {
                releaseHolders.countDown()
                releaser.join(POOL_RELEASE_TIMEOUT_SECONDS * 1_000)
            }

            samples.size shouldBeEqualTo POOL_WORKERS
            val expected = samples.first().result
            samples.forEach { it.result shouldBeEqualTo expected }
            val elapsed = samples.map { it.elapsedNanos }
            println(
                "ISSUE315_POOL profile=${schemaOwner.profile} status=PASS " +
                    "poolSize=${pool.maximumPoolSize} workers=$POOL_WORKERS " +
                    "completedWorkers=${samples.map { it.worker }.sorted()} " +
                    "minNs=${elapsed.min()} medianNs=${elapsed.median()} p95Ns=${elapsed.p95()} " +
                    "allResultsEqual=true",
            )
        }

    @Test
    fun `refresh 실패와 context close 뒤 global Database 상태를 복원한다`() {
        val previousDefault = TransactionManager.defaultDatabase
        val sentinel = org.jetbrains.exposed.v1.jdbc.Database.connect(
            url = "jdbc:h2:mem:issue315_sentinel_${System.nanoTime()};DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver",
        )
        TransactionManager.defaultDatabase = sentinel
        try {
            val expected = assertFailsWith<IllegalStateException> {
                withPilotContext<Unit> { error("issue315-refresh-failure") }
            }
            expected.message shouldBeEqualTo "issue315-refresh-failure"
            (sentinel === TransactionManager.defaultDatabase) shouldBeEqualTo true
            (sentinel === TransactionManager.primaryDatabase) shouldBeEqualTo true
        } finally {
            TransactionManager.closeAndUnregister(sentinel)
            TransactionManager.defaultDatabase = previousDefault
        }
    }

    @Test
    fun `callback 실패와 context close 실패를 원래 예외와 suppressed 관계로 보존한다`() {
        val expected = assertFailsWith<IllegalStateException> {
            withPilotContext(
                runner = pilotContextRunner()
                    .withUserConfiguration(CloseFailureTestConfiguration::class.java),
            ) {
                error("issue315-callback-failure")
            }
        }

        expected.message shouldBeEqualTo "issue315-callback-failure"
        expected.suppressed.any { it.message == "issue315-close-failure" } shouldBeEqualTo true
    }

    @Test
    fun `H2 benchmark가 cardinality별 대칭 total median p95와 대표 statement 수를 기록한다`() {
        withPilotContext { context ->
            val adapter = context.getBean(ClinicProjectionAdapter::class.java)
            val repository = context.getBean(ClinicProjectionRepository::class.java)
            val legacyRepository = ClinicRepository()
            val transactionTemplate = transactionTemplate(context)
            val schemaOwner = context.getBean(Issue315SchemaOwner::class.java)
            val dataSource = context.getBean(DataSource::class.java)
            val evidence = mutableListOf<BenchmarkSample>()

            BENCHMARK_CARDINALITIES.forEach { cardinality ->
                val fixture = seedBenchmarkFixture(context, cardinality)
                repeat(BENCHMARK_WARMUPS) { warmupIndex ->
                    measurePair(
                        transactionTemplate = transactionTemplate,
                        legacyRepository = legacyRepository,
                        adapter = adapter,
                        tenantGroupId = fixture.tenantGroupId,
                        sampleIndex = warmupIndex,
                    ) { }
                }

                repeat(BENCHMARK_SAMPLES) { sampleIndex ->
                    measurePair(
                        transactionTemplate = transactionTemplate,
                        legacyRepository = legacyRepository,
                        adapter = adapter,
                        tenantGroupId = fixture.tenantGroupId,
                        sampleIndex = sampleIndex,
                    ) { sample ->
                        val recorded = sample.copy(cardinality = cardinality)
                        evidence += recorded
                        println(
                            "ISSUE315_SAMPLE profile=${schemaOwner.profile} dialect=${schemaOwner.dialect} " +
                                "cardinality=${recorded.cardinality} path=${recorded.path} " +
                                "first=${recorded.first} elapsedNs=${recorded.elapsedNanos}",
                        )
                    }
                }

                val representative = representativeStatementCounts(
                    transactionTemplate = transactionTemplate,
                    legacyRepository = legacyRepository,
                    repository = repository,
                    tenantGroupId = fixture.tenantGroupId,
                )
                val explain = explainCandidate(
                    transactionTemplate = transactionTemplate,
                    dataSource = dataSource,
                    tenantGroupId = fixture.tenantGroupId,
                )
                val components = componentTimings(
                    transactionTemplate = transactionTemplate,
                    repository = repository,
                    tenantGroupId = fixture.tenantGroupId,
                )
                val cardinalitySamples = evidence.filter { it.cardinality == cardinality }
                println(
                    "ISSUE315_BENCHMARK " +
                        "profile=${schemaOwner.profile} " +
                        "dialect=${schemaOwner.dialect} " +
                        "jvm=${System.getProperty("java.version")} " +
                        "cardinality=$cardinality " +
                        "warmups=$BENCHMARK_WARMUPS " +
                        "samples=$BENCHMARK_SAMPLES " +
                        "pool=${schemaOwner.poolDescription} " +
                        "representativeStatementCountLegacy=${representative.legacy} " +
                        "representativeStatementCountCandidate=${representative.candidate} " +
                        "legacyMedianNs=${cardinalitySamples.filter { it.path == Path.LEGACY }.map { it.elapsedNanos }.median()} " +
                        "legacyP95Ns=${cardinalitySamples.filter { it.path == Path.LEGACY }.map { it.elapsedNanos }.p95()} " +
                        "candidateMedianNs=${cardinalitySamples.filter { it.path == Path.CANDIDATE }.map { it.elapsedNanos }.median()} " +
                        "candidateP95Ns=${cardinalitySamples.filter { it.path == Path.CANDIDATE }.map { it.elapsedNanos }.p95()} " +
                        "componentTiming=candidate_query_and_mapping_diagnostic_only " +
                        "legacyQueryNs=${components.legacyQueryNs} " +
                        "legacyMappingNs=${components.legacyMappingNs} " +
                        "candidateQueryNs=${components.candidateQueryNs} " +
                        "candidateMappingNs=${components.candidateMappingNs} " +
                        "explainIndexUsage=${explain.indexUsage} " +
                        "explainPlan=${explain.plan}",
                )
            }

            evidence.size shouldBeEqualTo BENCHMARK_CARDINALITIES.size * BENCHMARK_SAMPLES * 2
        }
    }

    private fun transactionTemplate(context: org.springframework.context.ApplicationContext): TransactionTemplate =
        TransactionTemplate(
            context.getBean("springTransactionManager", PlatformTransactionManager::class.java),
        ).apply { timeout = 5 }

    private fun seedFixture(context: org.springframework.context.ApplicationContext): Fixture {
        val fixture = Fixture(tenantA = 101L, tenantB = 202L)
        transactionTemplate(context).executeWithoutResult {
            SchemaUtils.createMissingTablesAndColumns(TenantGroups, Clinics)
            Clinics.deleteAll()
            TenantGroups.deleteAll()

            TenantGroups.insert {
                it[id] = EntityID(fixture.tenantA, TenantGroups)
                it[tenantCode] = "issue315-tenant-a"
                it[displayName] = "Issue 315 Tenant A"
            }
            TenantGroups.insert {
                it[id] = EntityID(fixture.tenantB, TenantGroups)
                it[tenantCode] = "issue315-tenant-b"
                it[displayName] = "Issue 315 Tenant B"
            }
            insertClinic(fixture.tenantA, clinicId = 102L, name = "A-102")
            insertClinic(fixture.tenantA, clinicId = 101L, name = "A-101")
            insertClinic(fixture.tenantB, clinicId = 201L, name = "B-201")
        }
        return fixture
    }

    private fun seedBenchmarkFixture(
        context: org.springframework.context.ApplicationContext,
        cardinality: Int,
    ): BenchmarkFixture {
        val fixture = BenchmarkFixture(tenantGroupId = 50_000L + cardinality)
        transactionTemplate(context).executeWithoutResult {
            SchemaUtils.createMissingTablesAndColumns(TenantGroups, Clinics)
            Clinics.deleteAll()
            TenantGroups.deleteAll()
            TenantGroups.insert {
                it[id] = EntityID(fixture.tenantGroupId, TenantGroups)
                it[tenantCode] = "issue315-benchmark-$cardinality"
                it[displayName] = "Issue 315 Benchmark $cardinality"
            }
            (1..cardinality).forEach { index ->
                insertClinic(
                    tenantGroupId = fixture.tenantGroupId,
                    clinicId = fixture.tenantGroupId * 10 + (cardinality - index + 1),
                    name = "benchmark-$cardinality-$index",
                )
            }
        }
        return fixture
    }

    private fun measurePair(
        transactionTemplate: TransactionTemplate,
        legacyRepository: ClinicRepository,
        adapter: ClinicProjectionAdapter,
        tenantGroupId: Long,
        sampleIndex: Int,
        record: (BenchmarkSample) -> Unit,
    ) {
        val first = if (sampleIndex % 2 == 0) Path.LEGACY else Path.CANDIDATE
        val paths = if (first == Path.LEGACY) {
            listOf(Path.LEGACY, Path.CANDIDATE)
        } else {
            listOf(Path.CANDIDATE, Path.LEGACY)
        }
        var legacyResult: List<ClinicRecord>? = null
        var candidateResult: List<ClinicRecord>? = null
        paths.forEach { path ->
            lateinit var result: List<ClinicRecord>
            val elapsedNanos = measureNanoTime {
                result = transactionTemplate.execute {
                    when (path) {
                        Path.LEGACY -> legacyRepository.findByTenant(tenantGroupId)
                        Path.CANDIDATE -> adapter.findByTenant(tenantGroupId)
                    }
                }
            }
            when (path) {
                Path.LEGACY -> legacyResult = result
                Path.CANDIDATE -> candidateResult = result
            }
            record(BenchmarkSample(0, path, elapsedNanos, first))
        }
        legacyResult shouldBeEqualTo candidateResult
    }

    private fun representativeStatementCounts(
        transactionTemplate: TransactionTemplate,
        legacyRepository: ClinicRepository,
        repository: ClinicProjectionRepository,
        tenantGroupId: Long,
    ): StatementCounts {
        fun capture(block: () -> Unit): Int {
            val statements = mutableListOf<String>()
            transactionTemplate.executeWithoutResult {
                val transaction = TransactionManager.current()
                val interceptor = SqlStatementCapture(statements)
                transaction.registerInterceptor(interceptor)
                try {
                    block()
                } finally {
                    transaction.unregisterInterceptor(interceptor)
                }
            }
            return statements.count { it.contains("select") && it.contains("scheduling_clinics") }
        }

        return StatementCounts(
            legacy = capture { legacyRepository.findByTenant(tenantGroupId) },
            candidate = capture {
                repository.findByTenantGroupIdOrderByIdAsc(EntityID(tenantGroupId, TenantGroups))
                    .map(ClinicProjectionEntity::toClinicRecord)
            },
        )
    }

    private fun explainCandidate(
        transactionTemplate: TransactionTemplate,
        dataSource: DataSource,
        tenantGroupId: Long,
    ): ExplainEvidence {
        var plan = "not-applicable"
        transactionTemplate.executeWithoutResult {
            val connection = DataSourceUtils.getConnection(dataSource)
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "EXPLAIN SELECT id FROM scheduling_clinics " +
                        "WHERE tenant_group_id = $tenantGroupId ORDER BY id ASC",
                ).use { resultSet ->
                    val lines = buildList {
                        while (resultSet.next()) add(resultSet.getString(1))
                    }
                    plan = lines.joinToString("|").replace(Regex("\\s+"), " ").trim()
                }
            }
        }
        return ExplainEvidence(
            indexUsage = plan.contains("idx_clinics_tenant", ignoreCase = true),
            plan = plan.replace(" ", "_").take(500),
        )
    }

    private fun componentTimings(
        transactionTemplate: TransactionTemplate,
        repository: ClinicProjectionRepository,
        tenantGroupId: Long,
    ): ComponentTimings {
        var legacyRows: List<ResultRow> = emptyList()
        var candidateEntities: List<ClinicProjectionEntity> = emptyList()
        var legacyQueryNs = 0L
        var legacyMappingNs = 0L
        transactionTemplate.executeWithoutResult {
            legacyQueryNs = measureNanoTime {
                legacyRows = Clinics
                    .selectAll()
                    .where { Clinics.tenantGroupId eq tenantGroupId }
                    .orderBy(Clinics.id, SortOrder.ASC)
                    .toList()
            }
            legacyMappingNs = measureNanoTime {
                legacyRows.map { it.toClinicRecord() }
            }
        }

        var candidateQueryNs = 0L
        var candidateMappingNs = 0L
        transactionTemplate.executeWithoutResult {
            candidateQueryNs = measureNanoTime {
                candidateEntities = repository
                    .findByTenantGroupIdOrderByIdAsc(EntityID(tenantGroupId, TenantGroups))
            }
            candidateMappingNs = measureNanoTime {
                candidateEntities.map(ClinicProjectionEntity::toClinicRecord)
            }
        }
        return ComponentTimings(
            legacyQueryNs = legacyQueryNs,
            legacyMappingNs = legacyMappingNs,
            candidateQueryNs = candidateQueryNs,
            candidateMappingNs = candidateMappingNs,
        )
    }

    private fun insertClinic(tenantGroupId: Long, clinicId: Long, name: String) {
        Clinics.insert {
            it[id] = EntityID(clinicId, Clinics)
            it[Clinics.tenantGroupId] = EntityID(tenantGroupId, TenantGroups)
            it[Clinics.name] = name
            it[slotDurationMinutes] = 30
            it[timezone] = "Asia/Seoul"
            it[locale] = "ko-KR"
            it[maxConcurrentPatients] = 2
            it[openOnHolidays] = false
        }
    }

    private data class Fixture(
        val tenantA: Long,
        val tenantB: Long,
    )

    private class SqlStatementCapture(
        private val statements: MutableList<String>,
    ) : StatementInterceptor {
        override fun afterExecution(
            transaction: Transaction,
            contexts: List<StatementContext>,
            executedStatement: PreparedStatementApi,
        ) {
            contexts.firstOrNull()?.let { statements += it.sql(transaction).lowercase() }
        }
    }

    private enum class Path {
        LEGACY,
        CANDIDATE,
    }

    private data class BenchmarkSample(
        val cardinality: Int,
        val path: Path,
        val elapsedNanos: Long,
        val first: Path,
    )

    private data class BenchmarkFixture(
        val tenantGroupId: Long,
    )

    private data class StatementCounts(
        val legacy: Int,
        val candidate: Int,
    )

    private data class ExplainEvidence(
        val indexUsage: Boolean,
        val plan: String,
    )

    private data class ComponentTimings(
        val legacyQueryNs: Long,
        val legacyMappingNs: Long,
        val candidateQueryNs: Long,
        val candidateMappingNs: Long,
    )

    private data class PoolSample(
        val worker: Int,
        val elapsedNanos: Long,
        val result: List<ClinicRecord>,
    )

    private fun List<Long>.median(): Long {
        require(isNotEmpty())
        val sorted = sorted()
        return sorted[(sorted.lastIndex) / 2]
    }

    private fun List<Long>.p95(): Long {
        require(isNotEmpty())
        val sorted = sorted()
        val index = ceil(sorted.size * 0.95).roundToLong().toInt().coerceAtLeast(1) - 1
        return sorted[index.coerceAtMost(sorted.lastIndex)]
    }

    private companion object {
        val BENCHMARK_CARDINALITIES = listOf(4, 32, 128)
        const val BENCHMARK_WARMUPS = 5
        const val BENCHMARK_SAMPLES = 30
        const val POOL_SIZE = 2
        const val POOL_WORKERS = 4
        const val POOL_RELEASE_TIMEOUT_SECONDS = 5L
    }
}
