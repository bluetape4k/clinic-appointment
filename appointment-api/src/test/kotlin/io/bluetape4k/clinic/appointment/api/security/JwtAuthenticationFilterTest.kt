package io.bluetape4k.clinic.appointment.api.security

import io.bluetape4k.assertions.shouldBeNull
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder

/**
 * stateless JWT 요청 사이에서 이전 인증 정보가 재사용되지 않는지 검증한다.
 *
 * servlet thread가 재사용되거나 upstream 코드가 잘못된 context를 남기더라도 bearer token이
 * 없는 새 요청은 반드시 anonymous 상태에서 시작해야 한다. 이 규칙은 무인증 요청을 403으로
 * 오분류하거나 이전 tenant 권한을 재사용하는 것을 막는다.
 */
class JwtAuthenticationFilterTest {

    private val filter = JwtAuthenticationFilter(mockk(relaxed = true))

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `request without bearer token clears stale authentication`() {
        val stalePrincipal = SchedulingUserPrincipal(
            userId = "stale-admin",
            clinicId = 7L,
            roles = listOf(SchedulingRole.ADMIN),
            allowedTenants = listOf("tenant-a"),
        )
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                stalePrincipal,
                null,
                stalePrincipal.authorities,
            )

        filter.doFilter(
            MockHttpServletRequest(),
            MockHttpServletResponse(),
            MockFilterChain(),
        )

        SecurityContextHolder.getContext().authentication.shouldBeNull()
    }
}
