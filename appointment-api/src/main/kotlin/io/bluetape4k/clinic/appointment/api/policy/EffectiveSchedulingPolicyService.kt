package io.bluetape4k.clinic.appointment.api.policy

import io.bluetape4k.clinic.appointment.model.policy.ClinicSchedulingPolicyOverrides
import io.bluetape4k.clinic.appointment.model.policy.BookingCommitmentOverride
import io.bluetape4k.clinic.appointment.model.policy.BookingCommitmentPolicy
import io.bluetape4k.clinic.appointment.model.policy.CapacityAndOverbookingOverride
import io.bluetape4k.clinic.appointment.model.policy.CapacityAndOverbookingPolicy
import io.bluetape4k.clinic.appointment.model.policy.CompiledSchedulingPolicy
import io.bluetape4k.clinic.appointment.model.policy.DisruptionRecoveryOverride
import io.bluetape4k.clinic.appointment.model.policy.DisruptionRecoveryPolicy
import io.bluetape4k.clinic.appointment.model.policy.EffectiveSchedulingPolicy
import io.bluetape4k.clinic.appointment.model.policy.HoldAndConsentOverride
import io.bluetape4k.clinic.appointment.model.policy.HoldAndConsentPolicy
import io.bluetape4k.clinic.appointment.model.policy.NotificationAndSlaOverride
import io.bluetape4k.clinic.appointment.model.policy.NotificationAndSlaPolicy
import io.bluetape4k.clinic.appointment.model.policy.OperatingExtensionOverride
import io.bluetape4k.clinic.appointment.model.policy.OperatingExtensionPolicy
import io.bluetape4k.clinic.appointment.model.policy.PolicyGenerationVector
import io.bluetape4k.clinic.appointment.model.policy.PolicyScope
import io.bluetape4k.clinic.appointment.model.policy.PriorityAndReliabilityOverride
import io.bluetape4k.clinic.appointment.model.policy.PriorityAndReliabilityPolicy
import io.bluetape4k.clinic.appointment.model.policy.ReconfirmationOverride
import io.bluetape4k.clinic.appointment.model.policy.ReconfirmationPolicy
import io.bluetape4k.clinic.appointment.model.policy.SchedulingPolicyKind
import io.bluetape4k.clinic.appointment.model.policy.SchedulingPolicyPayload
import io.bluetape4k.clinic.appointment.model.policy.SourceVersion
import io.bluetape4k.clinic.appointment.model.dto.PolicyScopeRef
import io.bluetape4k.clinic.appointment.repository.SchedulingPolicyRepository
import io.bluetape4k.clinic.appointment.service.EffectivePolicyCache
import io.bluetape4k.clinic.appointment.service.EffectivePolicyCacheKey
import io.bluetape4k.clinic.appointment.service.SchedulingPolicyCompiler
import io.bluetape4k.clinic.appointment.service.SchedulingPolicyPayloadCodec
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import tools.jackson.databind.SerializationFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.io.Serializable
import java.nio.charset.StandardCharsets
import java.time.Instant

/**
 * 영속화된 활성 정책 정의에서 선택하고 역직렬화한 완전한 컴파일 입력이다.
 *
 * 저장소는 신뢰된 정의 엔벌로프와 엄격한 폐쇄형 payload codec을 사용해 이 값을 만든다.
 * 서로 다른 테넌트·병원·평가 구간의 행을 한 입력에 섞으면 안 된다.
 *
 * @property sourceVersions 스키마 1의 모든 [SchedulingPolicyKind]에 대응하는 정확한 테넌트
 * 버전과 선택적 병원 버전. 여덟 종류가 모두 존재해야 한다.
 * @property tenant 각 정책 종류가 선언한 의사결정 시점 또는 시술 시점에 선택한 완전한 테넌트
 * 기본 정책.
 * @property clinic 정책 종류별 선택적 병원 재정의. 속성이 `null`이면 해당 평가 시각에
 * 유효한 활성 재정의 정의가 없다는 뜻이며, 모든 필드가 상속으로 지정된 재정의 객체와는 다르다.
 */
data class EffectivePolicyCompilationInput(
    val sourceVersions: Map<SchedulingPolicyKind, SourceVersion>,
    val tenant: CompiledSchedulingPolicy,
    val clinic: ClinicSchedulingPolicyOverrides,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * [EffectiveSchedulingPolicyService]가 사용하는 권위 영속성 경계다.
 *
 * 구현체가 각 Exposed 트랜잭션의 소유자다. 정의를 디코딩하고 컴파일하는 동안 발생한 세대
 * 변경을 서비스가 감지할 수 있도록 각 메서드는 의도적으로 별도의 일관성 관측을 수행한다.
 */
interface EffectivePolicyStore {

    /**
     * 데이터베이스에서 현재 테넌트 및 병원 스코프 헤드의 세대를 읽는다.
     *
     * 컴파일에는 완전한 활성 기본 정책이 필요하므로 테넌트 세대는 양수여야 한다. 병원 재정의가
     * 한 번도 활성화되지 않았다면 병원 세대는 `0`일 수 있다. 테넌트 헤드가 없거나 읽을 수
     * 없는 상황은 권위 조회 실패이며 캐시 데이터를 사용할 근거가 되지 않는다.
     */
    fun readGeneration(
        tenantGroupId: Long,
        clinicId: Long,
    ): PolicyGenerationVector

    /**
     * 두 UTC 평가 시각에 맞는 완전한 정책 입력을 선택하고 엄격하게 디코딩한다.
     *
     * 의사결정 시점 기준 종류는 [decisionAt], 시술 시점 기준 종류는 [serviceAt]에서
     * 선택한다. 유효성은 `effectiveFrom <= instant < effectiveUntil`인 반개구간으로
     * 판단하며, 종료 시각이 `null`이면 예정된 종료가 없다는 뜻이다.
     */
    fun loadCompilationInput(
        tenantGroupId: Long,
        clinicId: Long,
        decisionAt: Instant,
        serviceAt: Instant,
    ): EffectivePolicyCompilationInput

    /**
     * 잠근 두 스코프 헤드가 [expectedGeneration]과 계속 일치할 때만 [snapshot]을
     * 저장하거나 기존 스냅샷을 재사용한다.
     *
     * 구현체는 한 트랜잭션에서 테넌트를 먼저, 병원을 나중에 잠그고 두 세대를 다시 확인한 뒤
     * 삽입 또는 재사용해야 한다. 불일치하면 [EffectivePolicyGenerationChangedException]을
     * 던지고 서로 다른 세대가 섞인 스냅샷은 영속화하지 않는다.
     */
    fun saveIfGenerationMatches(
        expectedGeneration: PolicyGenerationVector,
        snapshot: EffectiveSchedulingPolicy,
    ): EffectiveSchedulingPolicy
}

/**
 * 유효 예약 정책 조회를 위한 Exposed 기반 권위 저장소다.
 *
 * 각 공개 연산은 짧은 Exposed 트랜잭션 하나를 직접 소유한다. 컴파일은 의도적으로 트랜잭션
 * 밖에서 수행한다. 마지막 스냅샷 저장 연산만 테넌트-병원 순서로 두 스코프 헤드를 잠그고,
 * 예상 세대 확인과 불변 스냅샷 삽입·재사용을 원자적으로 수행한다.
 *
 * 수명 주기가 `ACTIVE`인 정의만 선택한다. `SCHEDULED` 정의는 활성화 명령이 스코프 세대를
 * 증가시키기 전까지 컴파일에 포함하지 않는다. 이 규칙은 예약 실행기가 늦더라도 정의 가시성과
 * 세대 최신성을 일치시킨다.
 *
 * @property repository 호출자 트랜잭션을 전제로 한 영속성 프리미티브. 이 어댑터가 실제
 * 트랜잭션 경계를 소유한다.
 * @property payloadCodec 스키마 1 전용 폐쇄형 디코더. 엔벌로프의 종류와 스코프는 신뢰된
 * 데이터베이스 열이며 payload JSON이 임의의 Kotlin 타입을 선택할 수 없다.
 */
class ExposedEffectivePolicyStore(
    val repository: SchedulingPolicyRepository,
    val payloadCodec: SchedulingPolicyPayloadCodec = SchedulingPolicyPayloadCodec(),
) : EffectivePolicyStore {

    override fun readGeneration(
        tenantGroupId: Long,
        clinicId: Long,
    ): PolicyGenerationVector =
        transaction {
            val tenantScope = PolicyScopeRef(tenantGroupId, PolicyScope.TENANT_DEFAULT)
            val clinicScope = PolicyScopeRef(tenantGroupId, PolicyScope.CLINIC_OVERRIDE, clinicId)
            val tenantHead = requireNotNull(repository.findScopeHead(tenantScope)) {
                "tenant scheduling policy head does not exist"
            }
            val clinicGeneration = repository.findScopeHead(clinicScope)?.generation ?: 0L
            PolicyGenerationVector(
                tenantGeneration = tenantHead.generation,
                clinicGeneration = clinicGeneration,
            )
        }

    override fun loadCompilationInput(
        tenantGroupId: Long,
        clinicId: Long,
        decisionAt: Instant,
        serviceAt: Instant,
    ): EffectivePolicyCompilationInput =
        transaction {
            val tenantScope = PolicyScopeRef(tenantGroupId, PolicyScope.TENANT_DEFAULT)
            val clinicScope = PolicyScopeRef(tenantGroupId, PolicyScope.CLINIC_OVERRIDE, clinicId)
            val tenantPayloads = linkedMapOf<SchedulingPolicyKind, SchedulingPolicyPayload>()
            val clinicPayloads = linkedMapOf<SchedulingPolicyKind, SchedulingPolicyPayload>()
            val sourceVersions = linkedMapOf<SchedulingPolicyKind, SourceVersion>()
            val evaluationAtByKind = SchedulingPolicyKind.entries.associateWith { kind ->
                kind.evaluationInstant(decisionAt, serviceAt)
            }
            val tenantDefinitions = repository.findActiveDefinitionsAt(tenantScope, evaluationAtByKind)
            val clinicDefinitions = repository.findActiveDefinitionsAt(clinicScope, evaluationAtByKind)

            SchedulingPolicyKind.entries.forEach { kind ->
                val tenantDefinition = requireNotNull(tenantDefinitions[kind]) {
                    "active tenant scheduling policy is incomplete"
                }
                val tenantPayload = payloadCodec.decode(
                    kind = kind,
                    scope = PolicyScope.TENANT_DEFAULT,
                    schemaVersion = tenantDefinition.schemaVersion,
                    json = tenantDefinition.payloadJson,
                )
                tenantPayloads[kind] = tenantPayload

                val clinicDefinition = clinicDefinitions[kind]
                clinicDefinition?.let { definition ->
                    clinicPayloads[kind] = payloadCodec.decode(
                        kind = kind,
                        scope = PolicyScope.CLINIC_OVERRIDE,
                        schemaVersion = definition.schemaVersion,
                        json = definition.payloadJson,
                    )
                }
                sourceVersions[kind] = SourceVersion(
                    tenantVersion = tenantDefinition.version,
                    clinicVersion = clinicDefinition?.version,
                )
            }

            EffectivePolicyCompilationInput(
                sourceVersions = sourceVersions,
                tenant = tenantPayloads.toTenantPolicy(),
                clinic = clinicPayloads.toClinicOverrides(),
            )
        }

    override fun saveIfGenerationMatches(
        expectedGeneration: PolicyGenerationVector,
        snapshot: EffectiveSchedulingPolicy,
    ): EffectiveSchedulingPolicy {
        require(snapshot.generation == expectedGeneration) {
            "snapshot generation must match expectedGeneration"
        }
        return transaction {
            val tenantScope = PolicyScopeRef(snapshot.tenantGroupId, PolicyScope.TENANT_DEFAULT)
            val clinicScope = PolicyScopeRef(
                snapshot.tenantGroupId,
                PolicyScope.CLINIC_OVERRIDE,
                snapshot.clinicId,
            )
            val lockedHeads = repository.lockScopeHeads(tenantScope, clinicScope)
            val lockedTenantGeneration = lockedHeads
                .single { it.scope == PolicyScope.TENANT_DEFAULT }
                .generation
            val lockedClinicGeneration = lockedHeads
                .single { it.scope == PolicyScope.CLINIC_OVERRIDE }
                .generation
            if (lockedTenantGeneration != expectedGeneration.tenantGeneration ||
                lockedClinicGeneration != expectedGeneration.clinicGeneration
            ) {
                throw EffectivePolicyGenerationChangedException(expectedGeneration)
            }

            val sourceVersionsJson = SNAPSHOT_MAPPER.writeValueAsString(
                snapshot.sourceVersions.toSortedMap(compareBy(SchedulingPolicyKind::name))
            )
            val sourceByPathJson = SNAPSHOT_MAPPER.writeValueAsString(snapshot.sourceByPath.toSortedMap())
            val disabledFeaturesJson = SNAPSHOT_MAPPER.writeValueAsString(snapshot.disabledFeatures.sorted())
            val warningsJson = SNAPSHOT_MAPPER.writeValueAsString(snapshot.warnings)
            val payloadJson = SNAPSHOT_MAPPER.writeValueAsString(snapshot.payload)
            val persisted = repository.saveSnapshot(
                tenantGroupId = snapshot.tenantGroupId,
                clinicId = snapshot.clinicId,
                decisionAt = snapshot.decisionAt,
                serviceAt = snapshot.serviceAt,
                tenantGeneration = snapshot.generation.tenantGeneration,
                clinicGeneration = snapshot.generation.clinicGeneration,
                sourceVersionsJson = sourceVersionsJson,
                sourceByPathJson = sourceByPathJson,
                disabledFeaturesJson = disabledFeaturesJson,
                warningsJson = warningsJson,
                payloadJson = payloadJson,
                snapshotHash = snapshot.snapshotHash,
            )
            check(
                persisted.decisionAt == snapshot.decisionAt &&
                    persisted.serviceAt == snapshot.serviceAt &&
                    persisted.tenantGeneration == snapshot.generation.tenantGeneration &&
                    persisted.clinicGeneration == snapshot.generation.clinicGeneration &&
                    persisted.sourceVersionsJson == sourceVersionsJson &&
                    persisted.sourceByPathJson == sourceByPathJson &&
                    persisted.disabledFeaturesJson == disabledFeaturesJson &&
                    persisted.warningsJson == warningsJson &&
                    persisted.payloadJson == payloadJson
            ) {
                "Persisted effective policy snapshot does not match its canonical hash"
            }
            snapshot
        }
    }

    private companion object {
        val SNAPSHOT_MAPPER: JsonMapper = JsonMapper.builder()
            .addModule(KotlinModule.Builder().build())
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build()
    }
}

/**
 * 영속화 직전 재검사에서 더 새로운 스코프 헤드를 발견했음을 알리는 내부 낙관적 충돌 신호다.
 *
 * @property expectedGeneration 폐기한 스냅샷을 컴파일할 때 사용한 세대 벡터. 호출자가 예외
 * 메타데이터를 신뢰하지 않고 새 권위 조회부터 다시 시작하도록 현재 관측 벡터는 노출하지 않는다.
 */
class EffectivePolicyGenerationChangedException(
    val expectedGeneration: PolicyGenerationVector,
) : RuntimeException("Effective scheduling policy generation changed") {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * 서로 다른 세대가 섞이는 것을 막기 위한 제한 재시도를 모두 소진했을 때 발생하는 안정된 충돌이다.
 *
 * @property attempts 완료를 시도한 컴파일 횟수. 양수이며 기본값은 `3`이다. 해당 시도에서
 * 생성한 스냅샷은 영속화하거나 캐시하지 않는다.
 * @property code [io.bluetape4k.clinic.appointment.api.config.GlobalExceptionHandler]가
 * `409 POLICY_EFFECTIVE_READ_CONFLICT`로 변환하는 안정된 기계 판독용 코드. 고객에게
 * 직접 노출할 현지화 메시지가 아니다.
 */
class EffectivePolicyGenerationConflictException(
    val attempts: Int,
) : RuntimeException("Effective scheduling policy changed during compilation") {
    val code: String = STABLE_CODE

    init {
        require(attempts > 0) { "attempts must be positive" }
    }

    companion object {
        /** API 계층에서 안정적으로 매핑할 수 있는 내부 충돌 식별자. */
        const val STABLE_CODE = "POLICY_EFFECTIVE_READ_CONFLICT"
        private const val serialVersionUID = 1L
    }
}

/**
 * 권위 영속 저장소를 사용할 수 없을 때 stale 캐시로 우회하지 않고 실패시키는 예외다.
 *
 * @property code [io.bluetape4k.clinic.appointment.api.config.GlobalExceptionHandler]가
 * `503 POLICY_EFFECTIVE_READ_UNAVAILABLE`로 변환하고 운영 지표에도 사용할 안정된 내부
 * 식별자. 예외 메시지는 의도적으로 고정하며 SQL, 인증정보, payload JSON, 테넌트 코드,
 * 원인 예외 메시지를 포함하지 않는다.
 */
class EffectivePolicyReadUnavailableException(
    cause: Throwable,
) : RuntimeException("Authoritative scheduling policy read is unavailable", cause) {
    val code: String = STABLE_CODE

    companion object {
        /** API 계층과 운영 지표에서 사용할 안정된 권위 저장소 장애 식별자. */
        const val STABLE_CODE = "POLICY_EFFECTIVE_READ_UNAVAILABLE"
        private const val serialVersionUID = 1L
    }
}

/**
 * 프로세스 로컬 캐시 용량 제한에 사용할 보관 바이트 수를 추정한다.
 *
 * 반환값은 양수인 보수적 추정치이며 정확한 JVM 힙 사용량이나 과금 지표가 아니다. 구현체는
 * 행위자 식별정보, 원본 인증정보, 멱등 키를 기록하거나 분석하면 안 된다.
 */
fun interface EffectivePolicySnapshotSizeEstimator {
    /** 불변 스냅샷 하나가 차지할 것으로 예상하는 양수 바이트 수를 반환한다. */
    fun estimate(snapshot: EffectiveSchedulingPolicy): Long
}

/**
 * 오래된 데이터를 제공하지 않으면서 불변 유효 정책 스냅샷을 컴파일하고 저장한다.
 *
 * 정확성을 위한 순서는 다음과 같이 고정한다.
 *
 * 1. 권위 저장소에서 세대 벡터를 읽는다.
 * 2. 그 정확한 벡터와 두 UTC 시각으로 캐시를 조회한다.
 * 3. 정의를 읽고 데이터베이스 트랜잭션 밖에서 컴파일한다.
 * 4. 세대 벡터를 다시 읽고 달라졌으면 결과를 폐기한다.
 * 5. 두 스코프 헤드를 잠근 상태에서 세대를 재검사하고 스냅샷을 삽입하거나 재사용한다.
 * 6. 영속화 트랜잭션이 커밋된 뒤에만 캐시를 채운다.
 *
 * 캐시 무효화 이벤트는 선택적 가속 수단이다. 1, 3, 4, 5단계에서 데이터베이스 장애가
 * 발생하면 [EffectivePolicyReadUnavailableException]으로 닫힌 실패를 수행하며 캐시를
 * 대체 권위 저장소로 사용하지 않는다.
 *
 * 세대 충돌 재시도는 의도적으로 짧고 즉시 수행한다. 컴파일 동안 데이터베이스 잠금을 보유하지
 * 않고 최대 시도 횟수가 작기 때문이다. 이 메서드 안에서 잠든 스레드로 요청 자원을 점유하는
 * 대신, 충돌이 계속되면 안정된 충돌 예외로 종료하여 API 경계의 재시도·부하 제한 정책이
 * 요청 전체를 조정하도록 한다.
 *
 * @property store 권위 데이터베이스 접근 경계.
 * @property cache 프로세스 로컬의 용량 제한 성능 최적화.
 * @property sizeEstimator 캐시에 보관할 바이트 수의 보수적 추정기. 구현 오류로 추정에
 * 실패하거나 양수가 아닌 값을 반환해도 이미 영속화한 권위 스냅샷은 반환하며 캐시만 건너뛴다.
 * @property maximumAttempts 세대 충돌 시 허용할 양수 재시도 횟수. 잦은 정책 활성화 중
 * 무한 재시도를 막기 위해 기본값은 `3`이다.
 */
class EffectiveSchedulingPolicyService(
    val store: EffectivePolicyStore,
    val cache: EffectivePolicyCache,
    val sizeEstimator: EffectivePolicySnapshotSizeEstimator = DEFAULT_SIZE_ESTIMATOR,
    val maximumAttempts: Int = DEFAULT_MAXIMUM_ATTEMPTS,
) {
    companion object : KLogging() {
        private const val DEFAULT_MAXIMUM_ATTEMPTS = 3

        private val DEFAULT_SIZE_ESTIMATOR = EffectivePolicySnapshotSizeEstimator { snapshot ->
            val stableText = buildString {
                append(snapshot.snapshotHash)
                append(snapshot.sourceVersions)
                append(snapshot.sourceByPath)
                append(snapshot.disabledFeatures)
                append(snapshot.warnings)
                append(snapshot.payload)
            }
            stableText.toByteArray(StandardCharsets.UTF_8).size.toLong().coerceAtLeast(1L)
        }
    }

    init {
        require(maximumAttempts > 0) { "maximumAttempts must be positive" }
    }

    /**
     * 신뢰된 테넌트/병원과 두 UTC 시각에 대응하는 불변 유효 정책 스냅샷을 반환한다.
     *
     * @param tenantGroupId 요청의 신뢰된 테넌트 컨텍스트가 확정한 양수 테넌트 식별자.
     * @param clinicId 해당 테넌트 소속임을 이미 확인한 양수 병원 식별자.
     * @param decisionAt 현재 명령에 적용할 정책을 선택하는 정확한 UTC 시각.
     * @param serviceAt 시술 시점 정책을 선택하는 정확한 UTC 시각. [decisionAt]보다 빠를 수
     * 없으며 DST 누락·중복 시간을 포함한 로컬 시간 해석은 호출 전에 끝나야 한다.
     * @return 새로 영속화했거나 정확한 키의 캐시에서 찾은 불변 유효 정책 스냅샷.
     * @throws EffectivePolicyReadUnavailableException 권위 데이터를 읽거나 디코딩하거나
     * 안전하게 영속화할 수 없을 때.
     * @throws EffectivePolicyGenerationConflictException 제한된 모든 재시도에서 서로 다른
     * 세대를 관측했을 때.
     * @throws IllegalArgumentException 신뢰된 식별자 또는 시각 순서가 유효하지 않을 때.
     */
    fun getEffective(
        tenantGroupId: Long,
        clinicId: Long,
        decisionAt: Instant,
        serviceAt: Instant,
    ): EffectiveSchedulingPolicy {
        require(tenantGroupId > 0L) { "tenantGroupId must be positive" }
        require(clinicId > 0L) { "clinicId must be positive" }
        require(!serviceAt.isBefore(decisionAt)) { "serviceAt must not be before decisionAt" }

        repeat(maximumAttempts) {
            val firstGeneration = authoritative {
                store.readGeneration(tenantGroupId, clinicId)
            }
            requireValidGeneration(firstGeneration)
            val key = EffectivePolicyCacheKey(
                tenantGroupId = tenantGroupId,
                clinicId = clinicId,
                generation = firstGeneration,
                decisionAt = decisionAt,
                serviceAt = serviceAt,
            )
            cache.get(key)?.let { return it }

            val input = authoritative {
                store.loadCompilationInput(tenantGroupId, clinicId, decisionAt, serviceAt)
            }
            val compiled = SchedulingPolicyCompiler.compile(
                tenantGroupId = tenantGroupId,
                clinicId = clinicId,
                decisionAt = decisionAt,
                serviceAt = serviceAt,
                generation = firstGeneration,
                sourceVersions = input.sourceVersions,
                tenant = input.tenant,
                clinic = input.clinic,
            )
            val secondGeneration = authoritative {
                store.readGeneration(tenantGroupId, clinicId)
            }
            if (firstGeneration != secondGeneration) {
                log.debug { "Effective policy compilation retried: reason=generation_changed_after_compile" }
                return@repeat
            }

            val persisted = try {
                store.saveIfGenerationMatches(firstGeneration, compiled)
            } catch (_: EffectivePolicyGenerationChangedException) {
                log.debug { "Effective policy compilation retried: reason=generation_changed_before_snapshot" }
                return@repeat
            } catch (error: RuntimeException) {
                log.warn { "Effective policy read failed: reason=snapshot_persistence_unavailable" }
                throw EffectivePolicyReadUnavailableException(error)
            }
            cachePersistedSnapshot(key, persisted)
            return persisted
        }
        log.warn { "Effective policy compilation stopped: reason=bounded_generation_conflict" }
        throw EffectivePolicyGenerationConflictException(maximumAttempts)
    }

    private fun requireValidGeneration(generation: PolicyGenerationVector) {
        if (generation.tenantGeneration <= 0L || generation.clinicGeneration < 0L) {
            log.warn { "Effective policy read failed: reason=invalid_authoritative_generation" }
            throw EffectivePolicyReadUnavailableException(
                IllegalStateException("Incomplete effective policy generation")
            )
        }
    }

    /**
     * 성공한 권위 조회 결과를 성능 최적화 실패와 분리한다.
     *
     * custom [sizeEstimator] 또는 프로세스 로컬 [cache]의 계약 위반은 운영 경고로 남기되,
     * 이미 세대 재검사와 영속화를 통과한 불변 스냅샷의 반환을 취소하지 않는다.
     */
    private fun cachePersistedSnapshot(
        key: EffectivePolicyCacheKey,
        persisted: EffectiveSchedulingPolicy,
    ) {
        try {
            cache.put(key, persisted, sizeEstimator.estimate(persisted))
        } catch (_: RuntimeException) {
            log.warn { "Effective policy cache population skipped: reason=cache_contract_violation" }
        }
    }

    private inline fun <T> authoritative(block: () -> T): T =
        try {
            block()
        } catch (error: EffectivePolicyReadUnavailableException) {
            throw error
        } catch (error: RuntimeException) {
            log.warn { "Effective policy read failed: reason=authoritative_store_unavailable" }
            throw EffectivePolicyReadUnavailableException(error)
        }

}

private fun SchedulingPolicyKind.evaluationInstant(
    decisionAt: Instant,
    serviceAt: Instant,
): Instant =
    when (this) {
        SchedulingPolicyKind.BOOKING_COMMITMENT,
        SchedulingPolicyKind.HOLD_AND_CONSENT,
        SchedulingPolicyKind.PRIORITY_AND_RELIABILITY,
        SchedulingPolicyKind.DISRUPTION_RECOVERY,
        -> decisionAt

        SchedulingPolicyKind.CAPACITY_AND_OVERBOOKING,
        SchedulingPolicyKind.RECONFIRMATION,
        SchedulingPolicyKind.OPERATING_EXTENSION,
        SchedulingPolicyKind.NOTIFICATION_AND_SLA,
        -> serviceAt
    }

private fun Map<SchedulingPolicyKind, SchedulingPolicyPayload>.toTenantPolicy() =
    CompiledSchedulingPolicy(
        bookingCommitment = requiredPayload(
            SchedulingPolicyKind.BOOKING_COMMITMENT,
            BookingCommitmentPolicy::class.java,
        ),
        holdAndConsent = requiredPayload(
            SchedulingPolicyKind.HOLD_AND_CONSENT,
            HoldAndConsentPolicy::class.java,
        ),
        capacityAndOverbooking = requiredPayload(
            SchedulingPolicyKind.CAPACITY_AND_OVERBOOKING,
            CapacityAndOverbookingPolicy::class.java,
        ),
        priorityAndReliability = requiredPayload(
            SchedulingPolicyKind.PRIORITY_AND_RELIABILITY,
            PriorityAndReliabilityPolicy::class.java,
        ),
        reconfirmation = requiredPayload(
            SchedulingPolicyKind.RECONFIRMATION,
            ReconfirmationPolicy::class.java,
        ),
        disruptionRecovery = requiredPayload(
            SchedulingPolicyKind.DISRUPTION_RECOVERY,
            DisruptionRecoveryPolicy::class.java,
        ),
        operatingExtension = requiredPayload(
            SchedulingPolicyKind.OPERATING_EXTENSION,
            OperatingExtensionPolicy::class.java,
        ),
        notificationAndSla = requiredPayload(
            SchedulingPolicyKind.NOTIFICATION_AND_SLA,
            NotificationAndSlaPolicy::class.java,
        ),
    )

private fun Map<SchedulingPolicyKind, SchedulingPolicyPayload>.toClinicOverrides() =
    ClinicSchedulingPolicyOverrides(
        bookingCommitment = optionalPayload(
            SchedulingPolicyKind.BOOKING_COMMITMENT,
            BookingCommitmentOverride::class.java,
        ),
        holdAndConsent = optionalPayload(
            SchedulingPolicyKind.HOLD_AND_CONSENT,
            HoldAndConsentOverride::class.java,
        ),
        capacityAndOverbooking = optionalPayload(
            SchedulingPolicyKind.CAPACITY_AND_OVERBOOKING,
            CapacityAndOverbookingOverride::class.java,
        ),
        priorityAndReliability = optionalPayload(
            SchedulingPolicyKind.PRIORITY_AND_RELIABILITY,
            PriorityAndReliabilityOverride::class.java,
        ),
        reconfirmation = optionalPayload(
            SchedulingPolicyKind.RECONFIRMATION,
            ReconfirmationOverride::class.java,
        ),
        disruptionRecovery = optionalPayload(
            SchedulingPolicyKind.DISRUPTION_RECOVERY,
            DisruptionRecoveryOverride::class.java,
        ),
        operatingExtension = optionalPayload(
            SchedulingPolicyKind.OPERATING_EXTENSION,
            OperatingExtensionOverride::class.java,
        ),
        notificationAndSla = optionalPayload(
            SchedulingPolicyKind.NOTIFICATION_AND_SLA,
            NotificationAndSlaOverride::class.java,
        ),
    )

private fun <T : SchedulingPolicyPayload> Map<SchedulingPolicyKind, SchedulingPolicyPayload>.requiredPayload(
    kind: SchedulingPolicyKind,
    expectedType: Class<T>,
): T =
    expectedType.cast(requireNotNull(this[kind]) { "missing tenant payload for $kind" })

private fun <T : SchedulingPolicyPayload> Map<SchedulingPolicyKind, SchedulingPolicyPayload>.optionalPayload(
    kind: SchedulingPolicyKind,
    expectedType: Class<T>,
): T? =
    this[kind]?.let(expectedType::cast)
