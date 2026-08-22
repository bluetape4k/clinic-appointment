package io.bluetape4k.clinic.appointment.api

import io.bluetape4k.leader.spring.LeaderElectionAutoConfiguration
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

// NotificationAutoConfiguration이 Redis elector를 직접 구성하고 AOP factory는
// 별도 auto-configuration으로 동작한다. 상위 통합 election 설정은 API runtime에
// 없는 선택적 Exposed backend 클래스를 먼저 가져오므로 이 애플리케이션에서는 제외한다.
@SpringBootApplication(
    proxyBeanMethods = false,
    exclude = [LeaderElectionAutoConfiguration::class],
)
class AppointmentApiApplication

fun main(args: Array<String>) {
    runApplication<AppointmentApiApplication>(*args)
}
