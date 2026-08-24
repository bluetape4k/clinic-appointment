package io.bluetape4k.clinic.appointment.api.config

import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeEmpty
import io.bluetape4k.clinic.appointment.api.test.AbstractApiIntegrationTest
import io.bluetape4k.clinic.appointment.api.test.Containers
import io.bluetape4k.clinic.appointment.model.dto.DoctorRecord
import io.bluetape4k.clinic.appointment.model.dto.EquipmentRecord
import io.bluetape4k.clinic.appointment.model.dto.TreatmentTypeRecord
import io.bluetape4k.clinic.appointment.model.service.TenantClinicScope
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.Doctors
import io.bluetape4k.clinic.appointment.model.tables.Equipments
import io.bluetape4k.clinic.appointment.model.tables.TenantGroups
import io.bluetape4k.clinic.appointment.model.tables.TreatmentTypes
import io.bluetape4k.clinic.appointment.repository.DoctorRepository
import io.bluetape4k.clinic.appointment.repository.EquipmentRepository
import io.bluetape4k.clinic.appointment.repository.TreatmentTypeRepository
import io.bluetape4k.clinic.appointment.repository.toDoctorRecord
import io.bluetape4k.clinic.appointment.repository.toEquipmentRecord
import io.bluetape4k.clinic.appointment.repository.toTreatmentTypeRecord
import io.bluetape4k.exposed.lettuce.repository.AbstractJdbcLettuceRepository
import io.bluetape4k.exposed.lettuce.repository.ExposedLettuceCodecs
import io.bluetape4k.redis.lettuce.map.LettuceCacheConfig
import io.lettuce.core.RedisClient
import io.lettuce.core.api.sync.RedisCommands
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inSubQuery
import org.jetbrains.exposed.v1.core.statements.BatchInsertStatement
import org.jetbrains.exposed.v1.core.statements.StatementContext
import org.jetbrains.exposed.v1.core.statements.StatementInterceptor
import org.jetbrains.exposed.v1.core.statements.UpdateStatement
import org.jetbrains.exposed.v1.core.statements.api.PreparedStatementApi
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.cache.CacheManager
import java.time.Duration

private const val DOCTOR_PILOT_PREFIX = "issue314:jdbc-lettuce:doctors"
private const val EQUIPMENT_PILOT_PREFIX = "issue314:jdbc-lettuce:equipments"
private const val TREATMENT_TYPE_PILOT_PREFIX = "issue314:jdbc-lettuce:treatment-types"
private const val PILOT_TTL_SECONDS = 3600L
private val PILOT_TTL: Duration = Duration.ofHours(1)

/**
 * #314의 test-only jdbc-lettuce master-data 파일럿을 검증한다.
 *
 * production repository나 cache bean은 변경하지 않고, 현재 [LongJdbcRepository] 기반
 * 범위 조회와 실제 bluetape4k [AbstractJdbcLettuceRepository]의 차이를 증명한다.
 */
class JdbcLettuceMasterCachePilotIntegrationTest @Autowired constructor(
    private val redisClient: RedisClient,
    private val doctorRepository: DoctorRepository,
    private val equipmentRepository: EquipmentRepository,
    private val treatmentTypeRepository: TreatmentTypeRepository,
    private val cacheManager: CacheManager,
) : AbstractApiIntegrationTest() {

    private lateinit var fixture: Fixture
    private lateinit var doctorProbe: DoctorJdbcLettuceProbe
    private lateinit var equipmentProbe: EquipmentJdbcLettuceProbe
    private lateinit var treatmentTypeProbe: TreatmentTypeJdbcLettuceProbe

    @BeforeEach
    fun setUp() {
        clearProductionCaches()
        clearPilotKeys()

        transaction {
            SchemaUtils.createMissingTablesAndColumns(TenantGroups, Clinics, Doctors, Equipments, TreatmentTypes)
            TreatmentTypes.deleteAll()
            Equipments.deleteAll()
            Doctors.deleteAll()
            Clinics.deleteAll()
            TenantGroups.deleteAll()

            insertTenantGroup(1L, TenantGroups.DEFAULT_TENANT_CODE, TenantGroups.DEFAULT_TENANT_NAME)
            insertTenantGroup(2L, "tenant-other", "Other Tenant")

            val clinicA = insertClinic(1L, "Clinic A")
            val emptyClinic = insertClinic(1L, "Empty Clinic")
            val clinicB = insertClinic(2L, "Clinic B")

            val doctorId = Doctors.insertAndGetId {
                it[clinicId] = clinicA
                it[name] = "Dr. A"
                it[specialty] = "내과"
                it[providerType] = "DOCTOR"
                it[maxConcurrentPatients] = 2
            }.value
            val equipmentId = Equipments.insertAndGetId {
                it[clinicId] = clinicA
                it[name] = "MRI A"
                it[usageDurationMinutes] = 30
                it[quantity] = 2
            }.value
            val treatmentTypeId = TreatmentTypes.insertAndGetId {
                it[clinicId] = clinicA
                it[name] = "General A"
                it[category] = "TREATMENT"
                it[defaultDurationMinutes] = 30
                it[requiredProviderType] = "DOCTOR"
                it[consultationMethod] = "IN_PERSON"
                it[requiresEquipment] = false
                it[maxConcurrentPatients] = 2
            }.value

            Doctors.insertAndGetId {
                it[clinicId] = clinicB
                it[name] = "Dr. B"
                it[specialty] = "외과"
                it[providerType] = "DOCTOR"
                it[maxConcurrentPatients] = 1
            }
            Equipments.insertAndGetId {
                it[clinicId] = clinicB
                it[name] = "X-Ray B"
                it[usageDurationMinutes] = 20
                it[quantity] = 1
            }
            TreatmentTypes.insertAndGetId {
                it[clinicId] = clinicB
                it[name] = "Consultation B"
                it[category] = "CONSULTATION"
                it[defaultDurationMinutes] = 20
                it[requiredProviderType] = "DOCTOR"
                it[consultationMethod] = "VIDEO"
                it[requiresEquipment] = false
                it[maxConcurrentPatients] = 1
            }

            fixture = Fixture(
                tenantA = 1L,
                tenantB = 2L,
                clinicA = clinicA,
                emptyClinic = emptyClinic,
                clinicB = clinicB,
                doctorId = doctorId,
                equipmentId = equipmentId,
                treatmentTypeId = treatmentTypeId,
            )
        }

        doctorProbe = DoctorJdbcLettuceProbe(redisClient, pilotConfig(DOCTOR_PILOT_PREFIX))
        equipmentProbe = EquipmentJdbcLettuceProbe(redisClient, pilotConfig(EQUIPMENT_PILOT_PREFIX))
        treatmentTypeProbe = TreatmentTypeJdbcLettuceProbe(redisClient, pilotConfig(TREATMENT_TYPE_PILOT_PREFIX))
    }

    @AfterEach
    fun tearDown() {
        runCatching { clearPilotKeys() }
        runCatching { clearProductionCaches() }
        runCatching { doctorProbe.close() }
        runCatching { equipmentProbe.close() }
        runCatching { treatmentTypeProbe.close() }
    }

    @Test
    fun `legacy와 pilot 범위 조회 결과가 같고 tenant를 격리한다`() {
        val scopeA = TenantClinicScope(fixture.tenantA, fixture.clinicA)
        val wrongTenantScope = TenantClinicScope(fixture.tenantB, fixture.clinicA)
        val scopeB = TenantClinicScope(fixture.tenantB, fixture.clinicB)

        transaction {
            doctorRepository.findByScope(scopeA) shouldBeEqualTo doctorProbe.findAll(where = doctorWhere(scopeA))
            equipmentRepository.findByScope(scopeA) shouldBeEqualTo equipmentProbe.findAll(where = equipmentWhere(scopeA))
            treatmentTypeRepository.findByScope(scopeA) shouldBeEqualTo
                treatmentTypeProbe.findAll(where = treatmentTypeWhere(scopeA))
        }

        doctorProbe.findAll(where = doctorWhere(wrongTenantScope)).shouldBeEmpty()
        equipmentProbe.findAll(where = equipmentWhere(wrongTenantScope)).shouldBeEmpty()
        treatmentTypeProbe.findAll(where = treatmentTypeWhere(wrongTenantScope)).shouldBeEmpty()

        doctorProbe.findAll(where = doctorWhere(scopeB)).single().name shouldBeEqualTo "Dr. B"
        equipmentProbe.findAll(where = equipmentWhere(scopeB)).single().name shouldBeEqualTo "X-Ray B"
        treatmentTypeProbe.findAll(where = treatmentTypeWhere(scopeB)).single().name shouldBeEqualTo "Consultation B"
    }

    @Test
    fun `빈 결과는 key를 만들지 않고 이후 삽입을 즉시 조회한다`() {
        val scope = TenantClinicScope(fixture.tenantA, fixture.emptyClinic)

        doctorProbe.findAll(where = doctorWhere(scope)).shouldBeEmpty()
        withRawCommands { it.keys("$DOCTOR_PILOT_PREFIX:*").shouldBeEmpty() }

        val doctorId = transaction {
            Doctors.insertAndGetId {
                it[clinicId] = fixture.emptyClinic
                it[name] = "Dr. Newly Added"
                it[specialty] = "소아과"
                it[providerType] = "DOCTOR"
                it[maxConcurrentPatients] = 1
            }.value
        }

        doctorProbe.findAll(where = doctorWhere(scope)).single().id shouldBeEqualTo doctorId
        withRawCommands { it.exists("$DOCTOR_PILOT_PREFIX:$doctorId") shouldBeEqualTo 1L }
    }

    @Test
    fun `명시적 codec namespace와 TTL이 실제 Redis key에 남는다`() {
        val scope = TenantClinicScope(fixture.tenantA, fixture.clinicA)
        val doctor = doctorProbe.findAll(where = doctorWhere(scope)).single()

        doctorProbe.get(requireNotNull(doctor.id)) shouldBeEqualTo doctor

        withRawCommands { commands ->
            val keys = commands.keys("$DOCTOR_PILOT_PREFIX:*")
            keys.shouldNotBeEmpty()
            keys.all { commands.ttl(it) in 1L..PILOT_TTL_SECONDS }.shouldBeTrue()
            keys.all { !it.contains("clinic-doctors-v3") }.shouldBeTrue()
            commands.keys("*clinic-doctors-v3:${doctor.id}").shouldBeEmpty()
        }
    }

    @Test
    fun `legacy 두 번째 호출은 cache hit이고 pilot findAll은 한 번의 SQL로 warm한다`() {
        val scope = TenantClinicScope(fixture.tenantA, fixture.clinicA)
        cacheManager.getCache("clinic-doctors")?.clear()

        val legacyFirst = captureSql {
            doctorRepository.findByScope(scope)
        }
        val legacySecond = captureSql {
            doctorRepository.findByScope(scope)
        }
        legacyFirst.countSelects("scheduling_doctors") shouldBeEqualTo 1
        legacySecond.countSelects("scheduling_doctors") shouldBeEqualTo 0

        doctorProbe.clear()
        val pilot = captureSql {
            doctorProbe.findAll(where = doctorWhere(scope))
        }
        pilot.countSelects("scheduling_doctors") shouldBeEqualTo 1
    }

    @Test
    fun `pilot findAll 뒤 get은 SQL 없이 hit하고 invalidate 뒤에만 재조회한다`() {
        val scope = TenantClinicScope(fixture.tenantA, fixture.clinicA)
        doctorProbe.clear()
        doctorProbe.findAll(where = doctorWhere(scope))

        val hitSql = captureSql {
            doctorProbe.get(fixture.doctorId)
        }
        hitSql.countSelects("scheduling_doctors") shouldBeEqualTo 0

        doctorProbe.invalidate(fixture.doctorId)
        val missSql = captureSql {
            doctorProbe.get(fixture.doctorId)
        }
        missSql.countSelects("scheduling_doctors") shouldBeEqualTo 1
    }

    @Test
    fun `외부 DB 변경은 stale cache를 유지하다 invalidate 뒤 반영하고 삭제 후 null이 된다`() {
        doctorProbe.clear()
        val original = doctorProbe.get(fixture.doctorId)
        original?.name shouldBeEqualTo "Dr. A"

        transaction {
            Doctors.update({ Doctors.id eq fixture.doctorId }) {
                it[name] = "Dr. A Updated"
            }
        }

        doctorProbe.get(fixture.doctorId)?.name shouldBeEqualTo "Dr. A"
        doctorProbe.invalidate(fixture.doctorId)
        doctorProbe.get(fixture.doctorId)?.name shouldBeEqualTo "Dr. A Updated"

        transaction { Doctors.deleteWhere { Doctors.id eq fixture.doctorId } }
        doctorProbe.invalidate(fixture.doctorId)
        doctorProbe.get(fixture.doctorId).shouldBeNull()
        withRawCommands { it.exists("$DOCTOR_PILOT_PREFIX:${fixture.doctorId}") shouldBeEqualTo 0L }
    }

    @Test
    fun `transaction 경계와 close 반복 호출이 안전하다`() {
        val scope = TenantClinicScope(fixture.tenantA, fixture.clinicA)
        val result = transaction {
            doctorProbe.findAll(where = doctorWhere(scope))
        }
        result.shouldNotBeEmpty()

        val closeProbe = DoctorJdbcLettuceProbe(redisClient, pilotConfig("$DOCTOR_PILOT_PREFIX:close"))
        assertDoesNotThrow {
            closeProbe.close()
            closeProbe.close()
        }
    }

    @Test
    fun `Redis 연결 실패에도 DB 결과를 반환하고 bounded timeout으로 종료한다`() {
        val failedClient = CacheConfig().redisClientWithTimeout(
            url = Containers.Redis.url,
            requireTls = false,
            commandTimeout = Duration.ofMillis(200),
        )
        val failedProbe = DoctorJdbcLettuceProbe(failedClient, pilotConfig("$DOCTOR_PILOT_PREFIX:failure"))
        try {
            val scope = TenantClinicScope(fixture.tenantA, fixture.clinicA)
            // 먼저 실제 client/codec connection을 열고, 그 뒤 client를 종료해 명령 실패를 만든다.
            failedProbe.findAll(where = doctorWhere(scope)).single().name shouldBeEqualTo "Dr. A"
            failedClient.shutdown()
            failedProbe.get(fixture.doctorId)?.name shouldBeEqualTo "Dr. A"
        } finally {
            runCatching { failedProbe.close() }
            runCatching { failedClient.shutdown() }
        }
    }

    private fun pilotConfig(prefix: String): LettuceCacheConfig =
        LettuceCacheConfig.READ_ONLY.copy(keyPrefix = prefix, ttl = PILOT_TTL)

    private fun clearProductionCaches() {
        cacheManager.getCache("clinic-doctors")?.clear()
        cacheManager.getCache("clinic-equipments")?.clear()
        cacheManager.getCache("clinic-treatment-types")?.clear()
    }

    private fun clearPilotKeys() {
        withRawCommands { commands ->
            listOf(
                DOCTOR_PILOT_PREFIX,
                EQUIPMENT_PILOT_PREFIX,
                TREATMENT_TYPE_PILOT_PREFIX,
                "$DOCTOR_PILOT_PREFIX:close",
                "$DOCTOR_PILOT_PREFIX:failure",
            ).forEach { prefix ->
                commands.keys("$prefix:*").takeIf { it.isNotEmpty() }?.let { commands.unlink(*it.toTypedArray()) }
            }
        }
    }

    private fun <T> withRawCommands(block: (RedisCommands<String, String>) -> T): T {
        val connection = redisClient.connect()
        return try {
            block(connection.sync())
        } finally {
            connection.close()
        }
    }

    private fun captureSql(block: () -> Unit): List<String> {
        val statements = mutableListOf<String>()
        transaction {
            val current = TransactionManager.current()
            val interceptor = SqlStatementCapture(statements)
            current.registerInterceptor(interceptor)
            try {
                block()
            } finally {
                current.unregisterInterceptor(interceptor)
            }
        }
        return statements
    }

    private fun insertTenantGroup(id: Long, code: String, displayName: String) {
        TenantGroups.insertAndGetId {
            it[TenantGroups.id] = EntityID(id, TenantGroups)
            it[tenantCode] = code
            it[TenantGroups.displayName] = displayName
            it[active] = true
        }
    }

    private fun insertClinic(tenantGroupId: Long, name: String): Long =
        Clinics.insertAndGetId {
            it[Clinics.tenantGroupId] = EntityID(tenantGroupId, TenantGroups)
            it[Clinics.name] = name
            it[slotDurationMinutes] = 30
            it[timezone] = "Asia/Seoul"
            it[locale] = "ko-KR"
            it[maxConcurrentPatients] = 3
            it[openOnHolidays] = false
        }.value

    private fun doctorWhere(scope: TenantClinicScope): () -> org.jetbrains.exposed.v1.core.Op<Boolean> = {
        (Doctors.clinicId eq scope.clinicId) and
            (Doctors.clinicId inSubQuery tenantClinicIds(scope.tenantGroupId))
    }

    private fun equipmentWhere(scope: TenantClinicScope): () -> org.jetbrains.exposed.v1.core.Op<Boolean> = {
        (Equipments.clinicId eq scope.clinicId) and
            (Equipments.clinicId inSubQuery tenantClinicIds(scope.tenantGroupId))
    }

    private fun treatmentTypeWhere(scope: TenantClinicScope): () -> org.jetbrains.exposed.v1.core.Op<Boolean> = {
        (TreatmentTypes.clinicId eq scope.clinicId) and
            (TreatmentTypes.clinicId inSubQuery tenantClinicIds(scope.tenantGroupId))
    }

    private fun tenantClinicIds(tenantGroupId: Long) =
        Clinics.select(Clinics.id).where { Clinics.tenantGroupId eq tenantGroupId }

    private data class Fixture(
        val tenantA: Long,
        val tenantB: Long,
        val clinicA: Long,
        val emptyClinic: Long,
        val clinicB: Long,
        val doctorId: Long,
        val equipmentId: Long,
        val treatmentTypeId: Long,
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

    private fun List<String>.countSelects(table: String): Int =
        count { it.contains("select") && it.contains(table) }
}

private class DoctorJdbcLettuceProbe(
    client: RedisClient,
    config: LettuceCacheConfig,
) : AbstractJdbcLettuceRepository<Long, DoctorRecord>(
    client = client,
    config = config,
    valueCodec = ExposedLettuceCodecs.jackson3(DoctorRecord::class.java),
) {
    override val table = Doctors

    override fun ResultRow.toEntity(): DoctorRecord = toDoctorRecord()

    override fun extractId(entity: DoctorRecord): Long = requireNotNull(entity.id)

    override fun UpdateStatement.updateEntity(entity: DoctorRecord) {
        this[Doctors.clinicId] = entity.clinicId
        this[Doctors.name] = entity.name
        this[Doctors.specialty] = entity.specialty
        this[Doctors.providerType] = entity.providerType
        this[Doctors.maxConcurrentPatients] = entity.maxConcurrentPatients
    }

    override fun BatchInsertStatement.insertEntity(entity: DoctorRecord) {
        this[Doctors.id] = requireNotNull(entity.id)
        this[Doctors.clinicId] = entity.clinicId
        this[Doctors.name] = entity.name
        this[Doctors.specialty] = entity.specialty
        this[Doctors.providerType] = entity.providerType
        this[Doctors.maxConcurrentPatients] = entity.maxConcurrentPatients
    }
}

private class EquipmentJdbcLettuceProbe(
    client: RedisClient,
    config: LettuceCacheConfig,
) : AbstractJdbcLettuceRepository<Long, EquipmentRecord>(
    client = client,
    config = config,
    valueCodec = ExposedLettuceCodecs.jackson3(EquipmentRecord::class.java),
) {
    override val table = Equipments

    override fun ResultRow.toEntity(): EquipmentRecord = toEquipmentRecord()

    override fun extractId(entity: EquipmentRecord): Long = requireNotNull(entity.id)

    override fun UpdateStatement.updateEntity(entity: EquipmentRecord) {
        this[Equipments.clinicId] = entity.clinicId
        this[Equipments.name] = entity.name
        this[Equipments.usageDurationMinutes] = entity.usageDurationMinutes
        this[Equipments.quantity] = entity.quantity
    }

    override fun BatchInsertStatement.insertEntity(entity: EquipmentRecord) {
        this[Equipments.id] = requireNotNull(entity.id)
        this[Equipments.clinicId] = entity.clinicId
        this[Equipments.name] = entity.name
        this[Equipments.usageDurationMinutes] = entity.usageDurationMinutes
        this[Equipments.quantity] = entity.quantity
    }
}

private class TreatmentTypeJdbcLettuceProbe(
    client: RedisClient,
    config: LettuceCacheConfig,
) : AbstractJdbcLettuceRepository<Long, TreatmentTypeRecord>(
    client = client,
    config = config,
    valueCodec = ExposedLettuceCodecs.jackson3(TreatmentTypeRecord::class.java),
) {
    override val table = TreatmentTypes

    override fun ResultRow.toEntity(): TreatmentTypeRecord = toTreatmentTypeRecord()

    override fun extractId(entity: TreatmentTypeRecord): Long = requireNotNull(entity.id)

    override fun UpdateStatement.updateEntity(entity: TreatmentTypeRecord) {
        this[TreatmentTypes.clinicId] = entity.clinicId
        this[TreatmentTypes.name] = entity.name
        this[TreatmentTypes.category] = entity.category
        this[TreatmentTypes.defaultDurationMinutes] = entity.defaultDurationMinutes
        this[TreatmentTypes.requiredProviderType] = entity.requiredProviderType
        this[TreatmentTypes.consultationMethod] = entity.consultationMethod
        this[TreatmentTypes.requiresEquipment] = entity.requiresEquipment
        this[TreatmentTypes.maxConcurrentPatients] = entity.maxConcurrentPatients
    }

    override fun BatchInsertStatement.insertEntity(entity: TreatmentTypeRecord) {
        this[TreatmentTypes.id] = requireNotNull(entity.id)
        this[TreatmentTypes.clinicId] = entity.clinicId
        this[TreatmentTypes.name] = entity.name
        this[TreatmentTypes.category] = entity.category
        this[TreatmentTypes.defaultDurationMinutes] = entity.defaultDurationMinutes
        this[TreatmentTypes.requiredProviderType] = entity.requiredProviderType
        this[TreatmentTypes.consultationMethod] = entity.consultationMethod
        this[TreatmentTypes.requiresEquipment] = entity.requiresEquipment
        this[TreatmentTypes.maxConcurrentPatients] = entity.maxConcurrentPatients
    }
}
