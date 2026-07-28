package io.bluetape4k.clinic.appointment.api.policy

import io.bluetape4k.clinic.appointment.model.dto.PolicyScopeRef
import io.bluetape4k.clinic.appointment.model.policy.CompiledSchedulingPolicy
import io.bluetape4k.clinic.appointment.model.policy.PolicyScope
import io.bluetape4k.clinic.appointment.model.policy.SchedulingPolicyKind
import io.bluetape4k.clinic.appointment.model.policy.SchedulingPolicyPayload
import io.bluetape4k.clinic.appointment.model.policy.SourceVersion
import io.bluetape4k.clinic.appointment.repository.SchedulingPolicyRepository
import io.bluetape4k.clinic.appointment.service.SchedulingPolicyPayloadCodec
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import tools.jackson.databind.SerializationFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.security.MessageDigest
import java.time.Instant

/**
 * clinic sentinel 없이 tenant baseline 자체의 effective 정책을 조회한다.
 *
 * clinic-resolved [EffectiveSchedulingPolicyService]는 clinic override와 snapshot identity를
 * 포함하므로 양수 clinic ID가 본질적인 입력이다. tenant admin route에서 임의의 clinic
 * `0` 또는 첫 번째 clinic을 대신 넣으면 존재하지 않는 경계를 만들거나 특정 병원의 override가
 * tenant baseline처럼 보이는 결함이 된다. 이 서비스는 tenant scope head와 여덟 tenant
 * definition만 선택해 별도의 namespaced baseline hash를 만든다.
 *
 * 권위 최신성은 clinic 조회와 같은 double-read 규칙을 사용한다. 첫 generation에서 active
 * definition을 읽고 decode한 뒤 같은 tenant generation을 다시 확인한다. 중간에 활성화가
 * 발생하면 결과를 폐기하고 제한 횟수만 재시도한다. 반환값은 관리 조회용 불변 projection이며
 * clinic snapshot table에는 저장하지 않는다.
 *
 * @property repository 호출자가 연 Exposed transaction을 요구하는 정책 저장소.
 * @property payloadCodec 신뢰된 kind/scope/schema tuple로만 dispatch하는 strict decoder.
 * @property maximumAttempts generation 변경 시 허용하는 양수 bounded retry 횟수.
 */
class TenantEffectiveSchedulingPolicyService(
    private val repository: SchedulingPolicyRepository,
    private val payloadCodec: SchedulingPolicyPayloadCodec = SchedulingPolicyPayloadCodec(),
    private val maximumAttempts: Int = DEFAULT_MAXIMUM_ATTEMPTS,
) {
    companion object : KLogging() {
        private const val DEFAULT_MAXIMUM_ATTEMPTS = 3
        private const val HASH_NAMESPACE = "tenant-effective-scheduling-policy-v1"

        private val HASH_MAPPER: JsonMapper = JsonMapper.builder()
            .addModule(KotlinModule.Builder().build())
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build()
    }

    init {
        require(maximumAttempts > 0) { "maximumAttempts must be positive" }
    }

    /**
     * 명시한 두 시각에 선택되는 완전한 tenant baseline을 반환한다.
     *
     * @param tenantGroupId 신뢰된 tenant path가 해석한 양수 database identity.
     * @param decisionAt 의사결정 시점 정책 kind를 선택할 UTC instant.
     * @param serviceAt 시술 시점 정책 kind를 선택할 UTC instant. [decisionAt]보다 빠를 수 없다.
     * @throws EffectivePolicyGenerationConflictException bounded retry 동안 generation이 계속 변경된 경우.
     * @throws EffectivePolicyReadUnavailableException tenant baseline이 불완전하거나 권위 저장소를
     * 안전하게 읽고 decode할 수 없는 경우.
     */
    fun getEffective(
        tenantGroupId: Long,
        decisionAt: Instant,
        serviceAt: Instant,
    ): TenantEffectiveSchedulingPolicy {
        require(tenantGroupId > 0) { "tenantGroupId must be positive" }
        require(!serviceAt.isBefore(decisionAt)) { "serviceAt must not be before decisionAt" }

        repeat(maximumAttempts) {
            val firstGeneration = authoritative { readGeneration(tenantGroupId) }
            val loaded = authoritative {
                loadBaseline(tenantGroupId, decisionAt, serviceAt)
            }
            val secondGeneration = authoritative { readGeneration(tenantGroupId) }
            if (firstGeneration != secondGeneration) {
                log.debug { "Tenant effective policy retried: reason=generation_changed" }
                return@repeat
            }
            val payload = loaded.payloads.toTenantPolicy()
            val sourceVersions = loaded.versions
            val snapshotHash = baselineHash(
                tenantGroupId = tenantGroupId,
                decisionAt = decisionAt,
                serviceAt = serviceAt,
                tenantGeneration = firstGeneration,
                sourceVersions = sourceVersions,
                payload = payload,
            )
            return TenantEffectiveSchedulingPolicy(
                tenantGroupId = tenantGroupId,
                decisionAt = decisionAt,
                serviceAt = serviceAt,
                tenantGeneration = firstGeneration,
                sourceVersions = sourceVersions,
                payload = payload,
                snapshotHash = snapshotHash,
            )
        }
        throw EffectivePolicyGenerationConflictException(maximumAttempts)
    }

    private fun readGeneration(tenantGroupId: Long): Long =
        transaction {
            repository.findScopeHead(
                PolicyScopeRef(tenantGroupId, PolicyScope.TENANT_DEFAULT)
            )?.generation?.takeIf { it > 0 }
                ?: error("active tenant scheduling policy baseline does not exist")
        }

    private fun loadBaseline(
        tenantGroupId: Long,
        decisionAt: Instant,
        serviceAt: Instant,
    ): LoadedTenantBaseline =
        transaction {
            val scope = PolicyScopeRef(tenantGroupId, PolicyScope.TENANT_DEFAULT)
            val evaluationAtByKind = SchedulingPolicyKind.entries.associateWith { kind ->
                kind.evaluationInstant(decisionAt, serviceAt)
            }
            val definitions = repository.findActiveDefinitionsAt(scope, evaluationAtByKind)
            val payloads = linkedMapOf<SchedulingPolicyKind, SchedulingPolicyPayload>()
            val versions = linkedMapOf<SchedulingPolicyKind, SourceVersion>()
            SchedulingPolicyKind.entries.forEach { kind ->
                val definition = requireNotNull(definitions[kind]) {
                    "active tenant scheduling policy is incomplete"
                }
                payloads[kind] = payloadCodec.decode(
                    kind = kind,
                    scope = PolicyScope.TENANT_DEFAULT,
                    schemaVersion = definition.schemaVersion,
                    json = definition.payloadJson,
                )
                versions[kind] = SourceVersion(
                    tenantVersion = definition.version,
                    clinicVersion = null,
                )
            }
            LoadedTenantBaseline(payloads, versions)
        }

    private inline fun <T> authoritative(block: () -> T): T =
        try {
            block()
        } catch (error: EffectivePolicyGenerationConflictException) {
            throw error
        } catch (error: RuntimeException) {
            log.warn { "Tenant effective policy read failed: reason=authoritative_store_unavailable" }
            throw EffectivePolicyReadUnavailableException(error)
        }

    private fun baselineHash(
        tenantGroupId: Long,
        decisionAt: Instant,
        serviceAt: Instant,
        tenantGeneration: Long,
        sourceVersions: Map<SchedulingPolicyKind, SourceVersion>,
        payload: CompiledSchedulingPolicy,
    ): String {
        val canonical = linkedMapOf<String, Any>(
            "namespace" to HASH_NAMESPACE,
            "tenantGroupId" to tenantGroupId,
            "decisionAt" to decisionAt.toString(),
            "serviceAt" to serviceAt.toString(),
            "tenantGeneration" to tenantGeneration,
            "sourceVersions" to sourceVersions
                .toSortedMap(compareBy(SchedulingPolicyKind::name))
                .mapKeys { it.key.name },
            "payload" to payload,
        )
        return MessageDigest.getInstance("SHA-256")
            .digest(HASH_MAPPER.writeValueAsBytes(canonical))
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private data class LoadedTenantBaseline(
        val payloads: Map<SchedulingPolicyKind, SchedulingPolicyPayload>,
        val versions: Map<SchedulingPolicyKind, SourceVersion>,
    )
}

/**
 * tenant baseline effective 조회의 불변 결과다.
 *
 * [snapshotHash]는 clinic-resolved snapshot hash와 다른 namespace를 사용한다. 따라서
 * 같은 payload라도 tenant baseline과 clinic snapshot identity가 충돌하지 않는다.
 *
 * @property tenantGroupId path tenant code에서 해석한 양수 database identity.
 * @property decisionAt 의사결정 기준 정책을 선택한 UTC instant.
 * @property serviceAt 시술 기준 정책을 선택한 UTC instant.
 * @property tenantGeneration compile 전후에 일치함을 확인한 tenant 권위 세대.
 * @property sourceVersions 각 정책 kind에 선택된 tenant definition version.
 * @property payload 모든 kind가 채워진 완전한 tenant baseline 정책.
 * @property snapshotHash tenant baseline 전용 namespace로 계산한 deterministic SHA-256.
 */
data class TenantEffectiveSchedulingPolicy(
    val tenantGroupId: Long,
    val decisionAt: Instant,
    val serviceAt: Instant,
    val tenantGeneration: Long,
    val sourceVersions: Map<SchedulingPolicyKind, SourceVersion>,
    val payload: CompiledSchedulingPolicy,
    val snapshotHash: String,
)
