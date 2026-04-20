package io.bluetape4k.clinic.appointment.api.test

import io.bluetape4k.logging.KLogging
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * API 통합 테스트 기반 클래스.
 *
 * Spring Profile에 따라 DB를 선택한다:
 * - `test` (기본): H2 in-memory
 * - `test,test-postgresql`: PostgreSQL Testcontainer
 * - `test,test-mysql`: MySQL8 Testcontainer
 *
 * 멀티 DB 실행 예시:
 * ```
 * ./gradlew :appointment-api:test -Dspring.profiles.active=test,test-postgresql
 * ```
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
abstract class AbstractApiIntegrationTest {

    companion object : KLogging() {

        @JvmStatic
        @DynamicPropertySource
        fun configureTestDatabase(registry: DynamicPropertyRegistry) {
            val activeProfiles = System.getProperty("spring.profiles.active", "test")
            when {
                activeProfiles.contains("test-postgresql") -> {
                    val pg = Containers.Postgres
                    registry.add("spring.datasource.url") { pg.jdbcUrl!! }
                    registry.add("spring.datasource.username") { pg.username ?: "test" }
                    registry.add("spring.datasource.password") { pg.password ?: "" }
                }

                activeProfiles.contains("test-mysql") -> {
                    val mysql = Containers.MySql8
                    registry.add("spring.datasource.url") { mysql.jdbcUrl!! }
                    registry.add("spring.datasource.username") { mysql.username ?: "test" }
                    registry.add("spring.datasource.password") { mysql.password ?: "" }
                }
            }
        }
    }
}
