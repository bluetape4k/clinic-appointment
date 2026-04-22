package io.bluetape4k.clinic.appointment.api.test

import io.bluetape4k.logging.KLogging
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.ActiveProfilesResolver
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * `spring.profiles.active` 시스템 프로퍼티에 따라 DB 프로파일을 동적으로 활성화한다.
 *
 * 기본은 `test` 프로파일(H2)이며, `test-postgresql` 또는 `test-mysql` 포함 시 해당 프로파일을 추가한다.
 */
class DatabaseProfileResolver : ActiveProfilesResolver {
    override fun resolve(testClass: Class<*>): Array<String> {
        val sysProfiles = System.getProperty("spring.profiles.active", "")
        return buildList {
            add("test")
            if ("test-postgresql" in sysProfiles) add("test-postgresql")
            if ("test-mysql" in sysProfiles) add("test-mysql")
        }.toTypedArray()
    }
}

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
@ActiveProfiles(resolver = DatabaseProfileResolver::class)
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
                    registry.add("spring.flyway.enabled") { "true" }
                }

                activeProfiles.contains("test-mysql") -> {
                    val mysql = Containers.MySql8
                    registry.add("spring.datasource.url") { mysql.jdbcUrl!! }
                    registry.add("spring.datasource.username") { mysql.username ?: "test" }
                    registry.add("spring.datasource.password") { mysql.password ?: "" }
                    registry.add("spring.flyway.enabled") { "true" }
                }
            }
        }
    }
}
