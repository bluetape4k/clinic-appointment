package io.bluetape4k.clinic.appointment.api.auth

import io.bluetape4k.clinic.appointment.api.security.PatientJwtIssuer
import io.bluetape4k.clinic.appointment.api.security.PatientLoginAttemptLimiter
import io.bluetape4k.clinic.appointment.api.security.SchedulingRole
import io.bluetape4k.clinic.appointment.model.dto.PatientAccountRecord
import io.bluetape4k.clinic.appointment.model.dto.PatientLoginIdentityRecord
import io.bluetape4k.clinic.appointment.model.identity.PatientLoginIdentifier
import io.bluetape4k.clinic.appointment.model.identity.PatientLoginIdentifierKey
import io.bluetape4k.clinic.appointment.repository.PatientAccountRepository
import io.bluetape4k.clinic.appointment.repository.PatientLoginIdentityRepository
import io.bluetape4k.clinic.appointment.repository.TenantGroupRepository
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.security.crypto.password.PasswordEncoder
import java.text.Normalizer
import java.time.Clock
import java.util.UUID

/** 환자 인증의 normalization, credential verification, tenant transaction 경계를 소유합니다. */
class PatientAuthenticationService(
    private val database: Database,
    private val tenantGroupRepository: TenantGroupRepository,
    private val patientAccountRepository: PatientAccountRepository,
    private val patientLoginIdentityRepository: PatientLoginIdentityRepository,
    private val passwordEncoder: PasswordEncoder,
    private val patientJwtIssuer: PatientJwtIssuer,
    private val loginAttemptLimiter: PatientLoginAttemptLimiter,
    private val properties: PatientAuthenticationProperties,
    private val clock: Clock = Clock.systemUTC(),
) {
    /** 한 transaction에서 account와 1~3개 identifier를 등록합니다. */
    fun register(tenantCode: String, request: PatientRegisterRequest): PatientRegistrationResult {
        val displayName = normalizeDisplayName(request.displayName)
        val password = validatePassword(request.password)
        val identifiers = normalizeRegistrationIdentifiers(request.identifiers)
        requirePasswordDiffersFromIdentifiers(password, identifiers)

        return transaction(database) {
            val tenant = tenantGroupRepository.findActiveByCode(tenantCode)
                ?: throw PatientTenantNotFoundException()
            val tenantId = tenant.id ?: throw PatientTenantNotFoundException()
            identifiers.forEach { identifier ->
                if (patientLoginIdentityRepository.findActiveByIdentifier(tenantId, identifier.key, identifier.value) != null) {
                    throw PatientDuplicateIdentifierException()
                }
            }

            val subject = "patient-${UUID.randomUUID()}"
            val account = patientAccountRepository.save(
                PatientAccountRecord(
                    tenantGroupId = tenantId,
                    patientSubject = subject,
                    displayName = displayName,
                    passwordHash = requireNotNull(passwordEncoder.encode(password)) {
                        "password encoder returned no hash"
                    },
                )
            )
            val accountId = account.id ?: error("patient account insert did not return an id")
            identifiers.forEach { identifier ->
                patientLoginIdentityRepository.save(
                    PatientLoginIdentityRecord(
                        patientAccountId = accountId,
                        tenantGroupId = tenantId,
                        key = identifier.key,
                        normalizedValue = identifier.value,
                    )
                )
            }
            PatientRegistrationResult(
                accountId = accountId,
                patientSubject = subject,
                identifierKeys = identifiers.mapTo(sortedSetOf()) { it.key },
            )
        }
    }

    /** tenant-scoped identity를 조회하고 성공 시 cookie 발급용 token과 public summary를 반환합니다. */
    fun login(
        tenantCode: String,
        request: PatientLoginRequest,
        clientFingerprint: String,
    ): PatientLoginResult {
        val identifier = PatientLoginIdentifierNormalizer.normalize(request.identifier)
        val password = validatePassword(request.password)
        requirePasswordDiffersFromIdentifiers(password, listOf(identifier))

        val resolved = transaction(database) {
            val tenant = tenantGroupRepository.findActiveByCode(tenantCode)
                ?: throw PatientTenantNotFoundException()
            val tenantId = tenant.id ?: throw PatientTenantNotFoundException()
            tenantId to tenant.tenantCode
        }
        val (tenantId, canonicalTenantCode) = resolved
        if (!loginAttemptLimiter.allow(tenantId, identifier.key.name, clientFingerprint.take(MAX_FINGERPRINT_LENGTH))) {
            throw PatientLoginRateLimitedException()
        }

        val account = transaction(database) {
            val identity = patientLoginIdentityRepository.findActiveByIdentifier(
                tenantGroupId = tenantId,
                key = identifier.key,
                normalizedValue = identifier.value,
            )
            identity?.let { patientAccountRepository.findActiveById(tenantId, it.patientAccountId) }
        }
        val encodedPassword = account?.passwordHash ?: properties.dummyPasswordHash
        val passwordMatches = passwordEncoder.matches(password, encodedPassword)
        if (account == null || !passwordMatches) {
            throw PatientInvalidCredentialsException()
        }

        val expiresAt = clock.instant().plus(properties.sessionTtl)
        val subject = account.patientSubject
        val token = patientJwtIssuer.issue(canonicalTenantCode, subject, expiresAt)
        return PatientLoginResult(
            token = token,
            session = PatientSessionSummary(
                tenantCode = canonicalTenantCode,
                role = SchedulingRole.PATIENT,
                displayName = account.displayName,
                expiresAt = expiresAt,
            ),
        )
    }

    private fun normalizeRegistrationIdentifiers(
        requests: List<PatientLoginIdentifierRequest>,
    ): List<PatientLoginIdentifier> {
        if (requests.size !in 1..PatientLoginIdentifierKey.entries.size) {
            throw PatientAuthenticationValidationException("one to three identifiers are required")
        }
        val identifiers = requests.map(PatientLoginIdentifierNormalizer::normalize)
        try {
            PatientLoginIdentifier.validateForRegistration(identifiers)
        } catch (_: IllegalArgumentException) {
            throw PatientAuthenticationValidationException("identifier keys must be unique")
        }
        return identifiers
    }

    private fun normalizeDisplayName(raw: String): String {
        val value = Normalizer.normalize(raw.trim(), Normalizer.Form.NFC)
        if (value.isBlank() || value.length > MAX_DISPLAY_NAME_LENGTH || value.any(Char::isISOControl)) {
            throw PatientAuthenticationValidationException("displayName is invalid")
        }
        return value
    }

    private fun validatePassword(raw: String): String {
        if (
            raw.length !in properties.minPasswordLength..properties.maxPasswordLength ||
            raw.isBlank() ||
            raw.any(Char::isISOControl)
        ) {
            throw PatientAuthenticationValidationException("password is invalid")
        }
        return raw
    }

    private fun requirePasswordDiffersFromIdentifiers(
        password: String,
        identifiers: Collection<PatientLoginIdentifier>,
    ) {
        if (identifiers.any { it.value.equals(password, ignoreCase = true) }) {
            throw PatientAuthenticationValidationException("password is invalid")
        }
    }

    private companion object {
        const val MAX_DISPLAY_NAME_LENGTH = 100
        const val MAX_FINGERPRINT_LENGTH = 256
    }
}
