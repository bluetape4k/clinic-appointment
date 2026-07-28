package io.bluetape4k.clinic.appointment.api.test

import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.ResourceAccessMode
import org.junit.jupiter.api.parallel.ResourceLock
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.ActiveProfilesResolver
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * API 통합 테스트가 공유하는 Spring context, Exposed 기본 DB, singleton container의
 * JUnit resource 이름이다.
 *
 * [AbstractApiIntegrationTest]를 상속하지 않더라도 같은 `appointment-test` DB 또는
 * [Containers]의 DB에 대해 schema/data lifecycle을 변경하는 Spring·Flyway 테스트는
 * 반드시 이 resource의 write lock에 참여해야 한다.
 */
const val API_INTEGRATION_RESOURCE = "clinic-api-spring-context"

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
 *
 * 공유 Testcontainers launcher는 JVM shutdown hook에서 종료된다. Spring context를 같은
 * JVM shutdown 단계까지 캐시하면 Redis container 종료와 near-cache `close()`가 경쟁해
 * `CLIENT TRACKING OFF`가 기본 command timeout까지 대기할 수 있다. 각 통합 테스트 class
 * 뒤에 context를 먼저 닫아 Redis client와 near-cache를 컨테이너가 살아 있는 동안
 * 정리한다. 컨테이너 자체는 singleton으로 재사용하므로 raw container를 반복 생성하지 않는다.
 *
 * 이 기반 class의 subclass와 별도 `@SpringBootTest`/Flyway migration 검사는 같은 Spring
 * context cache, Exposed 기본 DB, singleton container를 공유한다. class 병렬 실행 중 한
 * 검사가 schema/data를 초기화하거나 `AFTER_CLASS`로 context를 닫으면 다른 검사가 이미
 * 주입받은 datasource·web server·security chain을 잃을 수 있다. [ResourceLock]의 공통
 * write lock으로 공유 자원 사용자를 서로 배타 실행하고, [ExecutionMode.SAME_THREAD]로 한
 * class 안의 method도 순차 실행한다. 독립 unit test의 class 병렬성은 유지한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles(resolver = DatabaseProfileResolver::class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock(value = API_INTEGRATION_RESOURCE, mode = ResourceAccessMode.READ_WRITE)
abstract class AbstractApiIntegrationTest {

    companion object : KLogging() {

        @JvmStatic
        @DynamicPropertySource
        fun configureTestContainers(registry: DynamicPropertyRegistry) {
            registry.add("spring.data.redis.url") { Containers.Redis.url }

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
