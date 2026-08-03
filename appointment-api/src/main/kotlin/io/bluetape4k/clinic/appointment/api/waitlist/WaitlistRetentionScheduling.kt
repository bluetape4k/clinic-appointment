package io.bluetape4k.clinic.appointment.api.waitlist

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled

/** retention store가 조립된 애플리케이션에서만 bounded purge trigger를 등록합니다. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnBean(WaitlistRetentionRunner::class)
class WaitlistRetentionSchedulingConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun waitlistRetentionScheduler(runner: WaitlistRetentionRunner): WaitlistRetentionScheduler =
        WaitlistRetentionScheduler(runner)
}

/** purge 구현과 scheduler thread를 분리한 얇은 trigger입니다. */
class WaitlistRetentionScheduler(
    private val runner: WaitlistRetentionRunner,
) {
    @Scheduled(fixedDelayString = "\${appointment.waitlist.delivery.retention-interval:PT1H}")
    fun purge(): WaitlistRetentionBatchResult = runner.run()
}
