package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.LeaderElectorFactory
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.spring.aop.autoconfigure.LeaderAopAutoConfiguration
import io.bluetape4k.leader.spring.aop.autoconfigure.LeaderAopFactoryAutoConfiguration
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.Test
import org.springframework.aop.framework.AopProxyUtils
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.config.FixedDelayTask
import org.springframework.scheduling.config.ScheduledTask
import org.springframework.scheduling.config.ScheduledTaskHolder
import org.springframework.test.util.AopTestUtils
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal class NotificationLeaderScheduledIntegrationTest {

    @Test
    fun `Spring AOP proxy와 ScheduledTaskHolder가 reminder fixed delay를 등록하고 bootstrap은 proxy를 호출한다`() {
        val scheduler = mockk<AppointmentReminderScheduler>()
        val result = ReminderRecoveryScanResult(notYetDue = 1, enqueued = 2, suppressed = 0)
        coEvery { scheduler.triggerOnce() } returns result
        val elector = electedElector()

        context(scheduler, elector).run { applicationContext ->
            applicationContext.startupFailure shouldBeEqualTo null
            val runner = applicationContext.getBean(NotificationReminderSchedulingRunner::class.java)
            val target = AopTestUtils.getTargetObject<NotificationReminderSchedulingRunner>(runner)
            check(runner !== target) { "@LeaderScheduled runner must be Spring AOP proxied" }
            AopProxyUtils.ultimateTargetClass(runner) shouldBeEqualTo NotificationReminderSchedulingRunner::class.java

            val scheduledTasks = applicationContext.getBean(ScheduledTaskHolder::class.java).scheduledTasks
            scheduledTasks.size shouldBeEqualTo 1
            val fixedDelayTask = scheduledTasks.single().task as FixedDelayTask
            fixedDelayTask.intervalDuration shouldBeEqualTo Duration.ofHours(1)
            scheduledTasks.forEach { it.cancel(false) }

            applicationContext.getBean(NotificationReminderSchedulingBootstrap::class.java).onApplicationReady()

            coVerify(exactly = 1) { scheduler.triggerOnce() }
        }
    }

    @Test
    fun `leader contention에서는 bootstrap 호출이 본문을 실행하지 않는다`() {
        val scheduler = mockk<AppointmentReminderScheduler>()
        val elector = mockk<LeaderElector>(relaxed = true)
        every {
            elector.runIfLeaderResult(any<String>(), any<() -> Any?>())
        } returns LeaderRunResult.Skipped

        context(scheduler, elector).run { applicationContext ->
            applicationContext.startupFailure shouldBeEqualTo null
            applicationContext.getBean(ScheduledTaskHolder::class.java).scheduledTasks.forEach { it.cancel(false) }

            applicationContext.getBean(NotificationReminderSchedulingBootstrap::class.java).onApplicationReady()

            coVerify(exactly = 0) { scheduler.triggerOnce() }
        }
    }

    @Test
    fun `backend 오류는 SKIP mode에서 scheduled 호출 밖으로 전파되지 않는다`() {
        val scheduler = mockk<AppointmentReminderScheduler>()
        val elector = mockk<LeaderElector>(relaxed = true)
        every {
            elector.runIfLeaderResult(any<String>(), any<() -> Any?>())
        } throws IllegalStateException("backend unavailable")

        context(scheduler, elector).run { applicationContext ->
            applicationContext.startupFailure shouldBeEqualTo null
            applicationContext.getBean(ScheduledTaskHolder::class.java).scheduledTasks.forEach { it.cancel(false) }

            applicationContext.getBean(NotificationReminderSchedulingBootstrap::class.java).onApplicationReady()

            coVerify(exactly = 0) { scheduler.triggerOnce() }
        }
    }

    @Test
    fun `cancellation은 scheduled runner 경계에서 재전파된다`() {
        val scheduler = mockk<AppointmentReminderScheduler>()
        val elector = mockk<LeaderElector>(relaxed = true)
        val cancellation = CancellationException("context cancelled")
        every {
            elector.runIfLeaderResult(any<String>(), any<() -> Any?>())
        } throws cancellation

        context(scheduler, elector).run { applicationContext ->
            applicationContext.startupFailure shouldBeEqualTo null
            applicationContext.getBean(ScheduledTaskHolder::class.java).scheduledTasks.forEach { it.cancel(false) }

            val thrown = assertFailsWith<CancellationException> {
                applicationContext.getBean(NotificationReminderSchedulingBootstrap::class.java).onApplicationReady()
            }
            thrown shouldBeEqualTo cancellation
            coVerify(exactly = 0) { scheduler.triggerOnce() }
        }
    }

    @Test
    fun `Spring context close는 reminder scheduled task를 취소한다`() {
        val scheduler = mockk<AppointmentReminderScheduler>()
        val elector = electedElector()
        val cancelled = AtomicBoolean(false)
        val scheduledFuture = mockk<ScheduledFuture<Any?>>(relaxed = true)
        every { scheduledFuture.isCancelled } answers { cancelled.get() }
        every { scheduledFuture.getDelay(any()) } returns TimeUnit.HOURS.toMillis(1)
        every { scheduledFuture.cancel(any()) } answers {
            cancelled.set(true)
            true
        }
        val taskScheduler = mockk<TaskScheduler>(relaxed = true)
        every { taskScheduler.scheduleWithFixedDelay(any(), any<Duration>()) } returns scheduledFuture
        every { taskScheduler.scheduleWithFixedDelay(any(), any<Instant>(), any<Duration>()) } returns scheduledFuture
        var scheduledTask: ScheduledTask? = null

        context(scheduler, elector, taskScheduler).run { applicationContext ->
            applicationContext.startupFailure shouldBeEqualTo null
            scheduledTask = applicationContext
                .getBean(ScheduledTaskHolder::class.java)
                .scheduledTasks
                .single()
            checkNotNull(scheduledTask).nextExecution().shouldNotBeNull()
        }

        cancelled.get().shouldBeTrue()
        checkNotNull(scheduledTask).nextExecution() shouldBeEqualTo null
    }

    private fun electedElector(): LeaderElector {
        val elector = mockk<LeaderElector>(relaxed = true)
        val action = slot<() -> Any?>()
        every {
            elector.runIfLeaderResult(any<String>(), capture(action))
        } answers {
            LeaderRunResult.Elected(action.captured.invoke())
        }
        return elector
    }

    private fun context(
        scheduler: AppointmentReminderScheduler,
        elector: LeaderElector,
        taskScheduler: TaskScheduler = mockk(relaxed = true),
    ): ApplicationContextRunner {
        val factory = mockk<LeaderElectorFactory>()
        every { factory.create(any()) } returns elector
        return ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    AopAutoConfiguration::class.java,
                    LeaderAopFactoryAutoConfiguration::class.java,
                    LeaderAopAutoConfiguration::class.java,
                ),
            )
            .withUserConfiguration(ReminderSchedulingConfiguration::class.java, SchedulingConfiguration::class.java)
            .withPropertyValues(
                "spring.aop.auto=true",
                "spring.aop.proxy-target-class=true",
                "bluetape4k.leader.aop.strict=false",
            )
            .withBean("localLeaderElectionFactory", LeaderElectorFactory::class.java, { factory })
            .withBean("appointmentReminderScheduler", AppointmentReminderScheduler::class.java, { scheduler })
            .withBean("taskScheduler", TaskScheduler::class.java, { taskScheduler })
    }

    @Configuration(proxyBeanMethods = false)
    class ReminderSchedulingConfiguration {
        @Bean
        fun notificationReminderSchedulingRunner(
            scheduler: AppointmentReminderScheduler,
        ): NotificationReminderSchedulingRunner = NotificationReminderSchedulingRunner(scheduler)

        @Bean
        fun notificationReminderSchedulingBootstrap(
            runner: NotificationReminderSchedulingRunner,
        ): NotificationReminderSchedulingBootstrap = NotificationReminderSchedulingBootstrap(runner)
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    class SchedulingConfiguration
}
