package io.bluetape4k.clinic.appointment.api.security

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.clinic.appointment.api.tenant.TenantContextFilter
import io.bluetape4k.clinic.appointment.api.tenant.TenantPathValidationFilter
import io.bluetape4k.clinic.appointment.repository.TenantGroupRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.web.SecurityFilterChain
import org.springframework.test.context.TestPropertySource

@SpringBootTest(
    classes = [SecurityConfig::class, SecurityConfigFilterOrderTest.Fixture::class],
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
)
@TestPropertySource(
    properties = [
        "scheduling.security.jwt.secret=dGVzdC1zZWNyZXQta2V5LWZvci1hcHBvaW50bWVudC1zY2hlZHVsaW5nLXN5c3RlbS01MTItYml0LW1hdGVyaWFsIQ==",
    ],
)
class SecurityConfigFilterOrderTest {

    @Autowired
    @Qualifier("correlationIdFilterRegistration")
    private lateinit var correlationRegistration: FilterRegistrationBean<CorrelationIdFilter>

    @Autowired
    @Qualifier("tenantPathValidationFilterRegistration")
    private lateinit var tenantPathRegistration: FilterRegistrationBean<TenantPathValidationFilter>

    @Autowired
    @Qualifier("jwtAuthenticationFilterRegistration")
    private lateinit var jwtRegistration: FilterRegistrationBean<JwtAuthenticationFilter>

    @Autowired
    @Qualifier("tenantContextFilterRegistration")
    private lateinit var tenantContextRegistration: FilterRegistrationBean<TenantContextFilter>

    @Autowired
    private lateinit var securityFilterChains: List<SecurityFilterChain>

    @Test
    fun `custom filters are registered only in security chain`() {
        listOf(
            correlationRegistration,
            tenantPathRegistration,
            jwtRegistration,
            tenantContextRegistration,
        ).forEach { it.isEnabled.shouldBeEqualTo(false) }
    }

    @Test
    fun `security chain orders correlation tenant path JWT and tenant context`() {
        val filterTypes = securityFilterChains.single().filters.map { it::class.java }
        val expected = listOf(
            CorrelationIdFilter::class.java,
            TenantPathValidationFilter::class.java,
            JwtAuthenticationFilter::class.java,
            TenantContextFilter::class.java,
        )

        val positions = expected.map { filterType ->
            filterTypes.indexOfFirst { it == filterType }.also { (it >= 0).shouldBeTrue() }
        }
        positions shouldBeEqualTo positions.sorted()
    }

    @Configuration(proxyBeanMethods = false)
    class Fixture {
        @Bean
        fun tenantGroupRepository(): TenantGroupRepository = TenantGroupRepository()
    }
}
