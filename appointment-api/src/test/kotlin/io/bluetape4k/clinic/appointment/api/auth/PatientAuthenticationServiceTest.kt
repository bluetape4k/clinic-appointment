package io.bluetape4k.clinic.appointment.api.auth

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.clinic.appointment.api.security.AuthenticationAssurance
import io.bluetape4k.clinic.appointment.api.security.JwtSecurityProperties
import io.bluetape4k.clinic.appointment.api.security.JwtTokenParser
import io.bluetape4k.clinic.appointment.api.security.PatientJwtIssuer
import io.bluetape4k.clinic.appointment.api.security.PatientLoginAttemptLimiter
import io.bluetape4k.clinic.appointment.api.security.SchedulingRole
import io.bluetape4k.clinic.appointment.api.security.SchedulingUserPrincipal
import io.bluetape4k.clinic.appointment.model.identity.PatientLoginIdentifierKey
import io.bluetape4k.clinic.appointment.model.tables.PatientAccounts
import io.bluetape4k.clinic.appointment.model.tables.PatientLoginIdentities
import io.bluetape4k.clinic.appointment.model.tables.TenantGroups
import io.bluetape4k.clinic.appointment.repository.PatientAccountRepository
import io.bluetape4k.clinic.appointment.repository.PatientLoginIdentityRepository
import io.bluetape4k.clinic.appointment.repository.TenantGroupRepository
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

/** tenant-scoped 환자 계정, multi-identifier login, dummy verification 계약입니다. */
class PatientAuthenticationServiceTest {

    private val now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val password = "correct horse battery staple"
    private val testSecret = "dGVzdC1zZWNyZXQta2V5LWZvci1hcHBvaW50bWVudC1zY2hlZHVsaW5nLXN5c3RlbS01MTItYml0LW1hdGVyaWFsIQ=="
    private lateinit var database: Database
    private lateinit var passwordEncoder: CountingPasswordEncoder
    private lateinit var service: PatientAuthenticationService

    @BeforeEach
    fun setUp() {
        database = Database.connect(
            url = "jdbc:h2:mem:patient_auth_${System.nanoTime()};DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver",
        )
        transaction(database) {
            SchemaUtils.createMissingTablesAndColumns(
                TenantGroups,
                PatientAccounts,
                PatientLoginIdentities,
            )
            TenantGroups.insert {
                it[id] = EntityID(TenantGroups.DEFAULT_TENANT_GROUP_ID, TenantGroups)
                it[tenantCode] = TenantGroups.DEFAULT_TENANT_CODE
                it[displayName] = "기본 병원"
                it[active] = true
            }
            TenantGroups.insert {
                it[id] = EntityID(2L, TenantGroups)
                it[tenantCode] = "tenant-two"
                it[displayName] = "두 번째 병원"
                it[active] = true
            }
        }
        passwordEncoder = CountingPasswordEncoder(BCryptPasswordEncoder())
        val jwtProperties = JwtSecurityProperties(
            enabled = true,
            secret = testSecret,
            issuer = "appointment-auth-service",
            audience = "appointment-api",
        )
        service = PatientAuthenticationService(
            database = database,
            tenantGroupRepository = TenantGroupRepository(),
            patientAccountRepository = PatientAccountRepository(),
            patientLoginIdentityRepository = PatientLoginIdentityRepository(),
            passwordEncoder = passwordEncoder,
            patientJwtIssuer = PatientJwtIssuer(jwtProperties, clock),
            loginAttemptLimiter = PatientLoginAttemptLimiter { _, _, _ -> true },
            properties = PatientAuthenticationProperties(
                dummyPasswordHash = passwordEncoder.encodeRequired("dummy password"),
                cookieSecure = false,
            ),
            clock = clock,
        )
    }

    @Test
    fun `one account can login through phone email and login id`() {
        service.register("tenant-default", registerRequest(allIdentifiers()))

        allIdentifiers().forEach { identifier ->
            val result = service.login(
                tenantCode = "tenant-default",
                request = PatientLoginRequest(identifier, password),
                clientFingerprint = "fingerprint-hash",
            )

            result.session.tenantCode shouldBeEqualTo "tenant-default"
            result.session.role shouldBeEqualTo SchedulingRole.PATIENT
            result.session.displayName shouldBeEqualTo "홍길동"
            result.session.expiresAt.isAfter(now) shouldBeEqualTo true
            result.token.isNotBlank() shouldBeEqualTo true
        }
    }

    @Test
    fun `same identifier value is independently resolved in each tenant`() {
        val identifier = PatientLoginIdentifierRequest(PatientLoginIdentifierKey.EMAIL, "shared@example.com")
        service.register("tenant-default", registerRequest(listOf(identifier), displayName = "첫 병원 환자"))
        service.register("tenant-two", registerRequest(listOf(identifier), displayName = "둘 병원 환자"))

        service.login("tenant-default", PatientLoginRequest(identifier, password), "fp-1")
            .session.displayName shouldBeEqualTo "첫 병원 환자"
        service.login("tenant-two", PatientLoginRequest(identifier, password), "fp-2")
            .session.displayName shouldBeEqualTo "둘 병원 환자"
    }

    @Test
    fun `login token contains strict patient claims without exposing identifier`() {
        service.register("tenant-default", registerRequest(allIdentifiers()))

        val result = service.login(
            "tenant-default",
            PatientLoginRequest(
                PatientLoginIdentifierRequest(PatientLoginIdentifierKey.EMAIL, "PATIENT@example.com"),
                password,
            ),
            "fingerprint-hash",
        )
        val principal = JwtTokenParser(
            JwtSecurityProperties(
                secret = testSecret,
                issuer = "appointment-auth-service",
                audience = "appointment-api",
            ),
            clock,
        ).parse(result.token).shouldNotBeNull()

        principal.actorType.name shouldBeEqualTo SchedulingRole.PATIENT
        principal.roles shouldBeEqualTo setOf(SchedulingRole.PATIENT)
        principal.allowedTenants shouldBeEqualTo setOf("tenant-default")
        principal.patientSubjectId.shouldNotBeNull()
        principal.assurance shouldBeEqualTo AuthenticationAssurance.PASSWORD
        principal.authenticatedAt shouldBeEqualTo now
    }

    @Test
    fun `wrong password missing identity and inactive account share generic credentials failure`() {
        service.register("tenant-default", registerRequest(allIdentifiers()))
        val callsBeforeFailures = passwordEncoder.matchesCalls.get()

        assertFailsWith<PatientInvalidCredentialsException> {
            service.login(
                "tenant-default",
                PatientLoginRequest(
                    PatientLoginIdentifierRequest(PatientLoginIdentifierKey.EMAIL, "patient@example.com"),
                    "wrong password value",
                ),
                "fingerprint-hash",
            )
        }
        assertFailsWith<PatientInvalidCredentialsException> {
            service.login(
                "tenant-default",
                PatientLoginRequest(
                    PatientLoginIdentifierRequest(PatientLoginIdentifierKey.EMAIL, "missing@example.com"),
                    password,
                ),
                "fingerprint-hash",
            )
        }
        (passwordEncoder.matchesCalls.get() - callsBeforeFailures) shouldBeEqualTo 2

        val inactive = transaction(database) {
            val accountId = PatientAccounts.insertAndGetId {
                it[tenantGroupId] = TenantGroups.DEFAULT_TENANT_GROUP_ID
                it[patientSubject] = "inactive-subject"
                it[displayName] = "비활성 환자"
                it[passwordHash] = passwordEncoder.encodeRequired(password)
                it[active] = false
            }
            PatientLoginIdentities.insert {
                it[patientAccountId] = accountId
                it[tenantGroupId] = TenantGroups.DEFAULT_TENANT_GROUP_ID
                it[key] = PatientLoginIdentifierKey.LOGIN_ID
                it[normalizedValue] = "inactive.patient"
            }
            accountId.value
        }
        inactive shouldBeEqualTo inactive
        assertFailsWith<PatientInvalidCredentialsException> {
            service.login(
                "tenant-default",
                PatientLoginRequest(
                    PatientLoginIdentifierRequest(PatientLoginIdentifierKey.LOGIN_ID, "inactive.patient"),
                    password,
                ),
                "fingerprint-hash",
            )
        }
    }

    @Test
    fun `registration accepts one or three keys and rejects invalid key cardinality`() {
        service.register(
            "tenant-default",
            registerRequest(
                listOf(PatientLoginIdentifierRequest(PatientLoginIdentifierKey.LOGIN_ID, "single.patient")),
            ),
        )
        service.register("tenant-two", registerRequest(allIdentifiers()))

        assertFailsWith<PatientAuthenticationValidationException> {
            service.register("tenant-default", registerRequest(emptyList()))
        }
        assertFailsWith<PatientAuthenticationValidationException> {
            service.register(
                "tenant-default",
                registerRequest(
                    listOf(
                        PatientLoginIdentifierRequest(PatientLoginIdentifierKey.EMAIL, "four@example.com"),
                        PatientLoginIdentifierRequest(PatientLoginIdentifierKey.PHONE, "010-1111-2222"),
                        PatientLoginIdentifierRequest(PatientLoginIdentifierKey.LOGIN_ID, "four.patient"),
                        PatientLoginIdentifierRequest(PatientLoginIdentifierKey.EMAIL, "other@example.com"),
                    ),
                ),
            )
        }
        assertFailsWith<PatientAuthenticationValidationException> {
            service.register(
                "tenant-default",
                registerRequest(
                    listOf(
                        PatientLoginIdentifierRequest(PatientLoginIdentifierKey.EMAIL, "duplicate@example.com"),
                        PatientLoginIdentifierRequest(PatientLoginIdentifierKey.EMAIL, "other@example.com"),
                    ),
                ),
            )
        }
    }

    @Test
    fun `duplicate identifier is rejected without revealing the stored value`() {
        service.register(
            "tenant-default",
            registerRequest(listOf(PatientLoginIdentifierRequest(PatientLoginIdentifierKey.EMAIL, "same@example.com"))),
        )

        val failure = assertFailsWith<PatientDuplicateIdentifierException> {
            service.register(
                "tenant-default",
                registerRequest(listOf(PatientLoginIdentifierRequest(PatientLoginIdentifierKey.EMAIL, "same@example.com"))),
            )
        }
        failure.message.orEmpty().contains("same@example.com") shouldBeEqualTo false
    }

    @Test
    fun `limiter rejection happens before password verification`() {
        val rejectingService = service.copy(
            loginAttemptLimiter = PatientLoginAttemptLimiter { _, _, _ -> false },
        )
        rejectingService.register("tenant-default", registerRequest(allIdentifiers()))
        val before = passwordEncoder.matchesCalls.get()

        assertFailsWith<PatientLoginRateLimitedException> {
            rejectingService.login(
                "tenant-default",
                PatientLoginRequest(
                    PatientLoginIdentifierRequest(PatientLoginIdentifierKey.LOGIN_ID, "hong.patient"),
                    password,
                ),
                "fingerprint-hash",
            )
        }
        passwordEncoder.matchesCalls.get() shouldBeEqualTo before
    }

    private fun registerRequest(
        identifiers: List<PatientLoginIdentifierRequest>,
        displayName: String = "홍길동",
    ): PatientRegisterRequest = PatientRegisterRequest(
        displayName = displayName,
        password = password,
        identifiers = identifiers,
    )

    private fun allIdentifiers(): List<PatientLoginIdentifierRequest> = listOf(
        PatientLoginIdentifierRequest(PatientLoginIdentifierKey.PHONE, "010-1234-5678"),
        PatientLoginIdentifierRequest(PatientLoginIdentifierKey.EMAIL, "patient@example.com"),
        PatientLoginIdentifierRequest(PatientLoginIdentifierKey.LOGIN_ID, "Hong.Patient"),
    )

    private fun PatientAuthenticationService.copy(
        loginAttemptLimiter: PatientLoginAttemptLimiter,
    ): PatientAuthenticationService = PatientAuthenticationService(
        database = database,
        tenantGroupRepository = TenantGroupRepository(),
        patientAccountRepository = PatientAccountRepository(),
        patientLoginIdentityRepository = PatientLoginIdentityRepository(),
        passwordEncoder = passwordEncoder,
        patientJwtIssuer = PatientJwtIssuer(
            JwtSecurityProperties(secret = testSecret, issuer = "appointment-auth-service", audience = "appointment-api"),
            clock,
        ),
        loginAttemptLimiter = loginAttemptLimiter,
        properties = PatientAuthenticationProperties(
            dummyPasswordHash = passwordEncoder.encodeRequired("dummy password"),
            cookieSecure = false,
        ),
        clock = clock,
    )

    private class CountingPasswordEncoder(
        private val delegate: PasswordEncoder,
    ) : PasswordEncoder {
        val matchesCalls = AtomicInteger()

        override fun encode(rawPassword: CharSequence?): String? = rawPassword?.let(delegate::encode)

        override fun matches(rawPassword: CharSequence?, encodedPassword: String?): Boolean {
            matchesCalls.incrementAndGet()
            return rawPassword != null && encodedPassword != null && delegate.matches(rawPassword, encodedPassword)
        }

        override fun upgradeEncoding(encodedPassword: String?): Boolean =
            encodedPassword != null && delegate.upgradeEncoding(encodedPassword)

        fun encodeRequired(rawPassword: CharSequence): String =
            requireNotNull(encode(rawPassword)) { "test password encoder returned no hash" }
    }
}
