package io.bluetape4k.clinic.appointment.api.config

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import org.junit.jupiter.api.Test

/**
 * Issue #313 파일럿의 JDBC Caffeine snapshot 계약을 고정한다.
 *
 * 이 테스트는 운영 캐시를 바꾸지 않고, 실제 [EffectivePolicyCacheKey]와
 * [EffectiveSchedulingPolicy]를 detached value로 매핑하는 test-only fixture만 검증한다.
 */
class JdbcCaffeineEffectivePolicyPilotTest {

    @Test
    fun `commit 뒤에만 정책 기준 데이터를 게시한다`() {
        JdbcCaffeineEffectivePolicyPilotFixture().use { fixture ->
            val sample = fixture.sample()

            val miss = fixture.capture(sample)
            fixture.lookup(sample.key).shouldBeNull()

            fixture.commit(sample, miss)

            fixture.lookup(sample.key) shouldBeEqualTo sample.value
        }
    }

    @Test
    fun `rollback이면 준비한 정책 기준 데이터를 게시하지 않는다`() {
        JdbcCaffeineEffectivePolicyPilotFixture().use { fixture ->
            val sample = fixture.sample()
            val miss = fixture.capture(sample)

            assertFailsWith<JdbcCaffeineEffectivePolicyPilotFixture.RollbackMarker> {
                fixture.rollback(sample, miss)
            }

            fixture.lookup(sample.key).shouldBeNull()
        }
    }

    @Test
    fun `세대 저장 충돌이면 stage하지 않고 캐시를 오염시키지 않는다`() {
        JdbcCaffeineEffectivePolicyPilotFixture().use { fixture ->
            val sample = fixture.sample()
            val miss = fixture.capture(sample)

            fixture.publishAfterGenerationCheck(sample, miss, generationMatches = false) shouldBeEqualTo false

            fixture.lookup(sample.key).shouldBeNull()
        }
    }

    @Test
    fun `clinic 무효화 뒤 오래된 miss는 local fence에서 거부한다`() {
        JdbcCaffeineEffectivePolicyPilotFixture().use { fixture ->
            val sample = fixture.sample()
            val staleMiss = fixture.capture(sample)

            fixture.invalidate(sample.key)
            fixture.commit(sample, staleMiss)

            fixture.lookup(sample.key).shouldBeNull()
        }
    }

    @Test
    fun `tenant와 clinic scope를 key로 격리하고 miss token을 재사용하지 않는다`() {
        JdbcCaffeineEffectivePolicyPilotFixture().use { fixture ->
            val first = fixture.sample(tenantGroupId = 1L, clinicId = 11L)
            val siblingClinic = fixture.sample(tenantGroupId = 1L, clinicId = 12L)
            val siblingTenant = fixture.sample(tenantGroupId = 2L, clinicId = 11L)
            val firstMiss = fixture.capture(first)
            val clinicMiss = fixture.capture(siblingClinic)
            val tenantMiss = fixture.capture(siblingTenant)

            fixture.commit(first, firstMiss)
            fixture.commit(siblingClinic, clinicMiss)
            fixture.commit(siblingTenant, tenantMiss)
            assertFailsWith<IllegalStateException> { fixture.commit(first, firstMiss) }

            fixture.invalidate(first.key)

            fixture.lookup(first.key).shouldBeNull()
            fixture.lookup(siblingClinic.key) shouldBeEqualTo siblingClinic.value
            fixture.lookup(siblingTenant.key) shouldBeEqualTo siblingTenant.value
        }
    }

    @Test
    fun `pilot toggle를 끄면 기존 EffectivePolicyCache 경로를 사용한다`() {
        JdbcCaffeineEffectivePolicyPilotFixture().use { fixture ->
            val sample = fixture.sample()

            fixture.publish(sample, pilotEnabled = false)

            fixture.lookup(sample.key).shouldBeNull()
            fixture.lookupBaseline(sample.key) shouldBeEqualTo sample.value
        }
    }
}
