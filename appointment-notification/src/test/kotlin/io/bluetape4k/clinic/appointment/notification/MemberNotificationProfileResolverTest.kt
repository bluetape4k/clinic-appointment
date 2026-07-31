package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.clinic.appointment.event.notification.ClinicId
import io.bluetape4k.clinic.appointment.event.notification.NotificationChannelType
import io.bluetape4k.clinic.appointment.event.notification.NotificationFailureCode
import io.bluetape4k.clinic.appointment.event.notification.NotificationSuppressionReasonCode
import io.bluetape4k.clinic.appointment.event.notification.TenantGroupId
import io.bluetape4k.clinic.appointment.model.identity.MemberId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

internal class MemberNotificationProfileResolverTest {

    private val context = MemberProfileResolutionContext(
        tenantGroupId = TenantGroupId(1L),
        clinicId = ClinicId(10L),
        channel = NotificationChannelType.SMS,
        memberId = MemberId("member-1"),
    )

    @Test
    fun `회원 서비스 일시 장애는 retryable failure code로 분류한다`() {
        val decision = MemberNotificationProfileClassifier.classify(
            MemberNotificationProfileResult.DirectoryUnavailable,
            context,
        )

        decision.failureCode shouldBeEqualTo NotificationFailureCode.MEMBER_DIRECTORY_UNAVAILABLE
    }

    @Test
    fun `bounded resolver는 request scope를 전달하고 429와 5xx를 닫힌 결과로 분류한다`() {
        runBlocking {
            var captured: MemberNotificationProfileRequest? = null
            val rateLimited = BoundedMemberNotificationProfileResolver(
                delegate = MemberNotificationProfileResolver {
                    captured = it
                    throw MemberNotificationProfileRateLimitedException()
                },
                timeout = Duration.ofSeconds(1),
            )
            val unavailable = BoundedMemberNotificationProfileResolver(
                delegate = MemberNotificationProfileResolver {
                    throw MemberNotificationProfileUnavailableException()
                },
                timeout = Duration.ofSeconds(1),
            )

            rateLimited.resolve(request()) shouldBeEqualTo MemberNotificationProfileResult.RateLimited
            unavailable.resolve(request()) shouldBeEqualTo MemberNotificationProfileResult.DirectoryUnavailable
            captured shouldBeEqualTo request()
        }
    }

    @Test
    fun `bounded resolver는 cancellation을 retryable 결과로 삼키지 않는다`() {
        val resolver = BoundedMemberNotificationProfileResolver(
            delegate = MemberNotificationProfileResolver { throw CancellationException("cancelled") },
            timeout = Duration.ofSeconds(1),
        )

        assertFailsWith<CancellationException> {
            runBlocking { resolver.resolve(request()) }
        }
    }

    @Test
    fun `bounded resolver timeout은 directory unavailable로 분류한다`() {
        val resolver = BoundedMemberNotificationProfileResolver(
            delegate = MemberNotificationProfileResolver {
                delay(100)
                MemberNotificationProfileResult.NotFound
            },
            timeout = Duration.ofMillis(1),
        )

        runBlocking {
            resolver.resolve(request()) shouldBeEqualTo MemberNotificationProfileResult.DirectoryUnavailable
        }
    }

    @Test
    fun `resolver는 설정된 동시성 상한 안에서만 회원 시스템을 호출한다`() = runBlocking {
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val resolver = BoundedMemberNotificationProfileResolver(
            delegate = MemberNotificationProfileResolver {
                val current = active.incrementAndGet()
                maximum.accumulateAndGet(current, ::maxOf)
                delay(25)
                active.decrementAndGet()
                MemberNotificationProfileResult.NotFound
            },
            timeout = Duration.ofSeconds(1),
            maxConcurrency = 2,
        )

        coroutineScope {
            (1..8).map { async { resolver.resolve(request()) } }.awaitAll()
        }

        maximum.get() shouldBeEqualTo 2
        Unit
    }

    @Test
    fun `탈퇴나 없음은 회원 없음 suppression으로 분류한다`() {
        val decision = MemberNotificationProfileClassifier.classify(MemberNotificationProfileResult.Withdrawn, context)

        decision.suppressionReason shouldBeEqualTo NotificationSuppressionReasonCode.MEMBER_NOT_AVAILABLE
    }

    @Test
    fun `연락처 없음과 동의 거부를 닫힌 suppression code로 분류한다`() {
        val noDestination = MemberNotificationProfileClassifier.classify(
            MemberNotificationProfileResult.Resolved(profile(destination = null)),
            context,
        )
        val noConsent = MemberNotificationProfileClassifier.classify(
            MemberNotificationProfileResult.Resolved(profile(consent = NotificationConsent(sms = false))),
            context,
        )

        noDestination.suppressionReason shouldBeEqualTo NotificationSuppressionReasonCode.DESTINATION_UNAVAILABLE
        noConsent.suppressionReason shouldBeEqualTo NotificationSuppressionReasonCode.CONSENT_DENIED
    }

    @Test
    fun `scope 불일치는 raw ID 없는 security event와 MEMBER_SCOPE_MISMATCH로 분류한다`() {
        val events = mutableListOf<NotificationScopeMismatchSecurityEvent>()
        val decision = MemberNotificationProfileClassifier.classify(
            MemberNotificationProfileResult.Resolved(profile(tenantGroupId = TenantGroupId(2L))),
            context,
            NotificationSecurityAuditSink { events += it },
        )

        decision.suppressionReason shouldBeEqualTo NotificationSuppressionReasonCode.MEMBER_SCOPE_MISMATCH
        val eventText = events.single().toString()
        eventText.contains("member-1") shouldBeEqualTo false
        eventText.contains("tenant") shouldBeEqualTo false
        eventText.contains("10") shouldBeEqualTo false
        eventText.contains("appointment") shouldBeEqualTo false
        profile().toString().contains("홍길동") shouldBeEqualTo false
        profile().toString().contains("+821012345678") shouldBeEqualTo false
        request().toString().contains("member-1") shouldBeEqualTo false
    }

    @Test
    fun `scope 보안 이벤트는 raw 식별자를 fingerprint로 허용하지 않는다`() {
        assertFailsWith<IllegalArgumentException> {
            NotificationScopeMismatchSecurityEvent(
                channel = NotificationChannelType.SMS,
                auditFingerprint = "member-1",
            )
        }
    }

    private fun request(): MemberNotificationProfileRequest =
        MemberNotificationProfileRequest(
            tenantGroupId = TenantGroupId(1L),
            clinicId = ClinicId(10L),
            memberId = MemberId("member-1"),
            channel = NotificationChannelType.SMS,
        )

    private fun profile(
        destination: String? = "+821012345678",
        consent: NotificationConsent = NotificationConsent(),
        tenantGroupId: TenantGroupId = TenantGroupId(1L),
        clinicId: ClinicId = ClinicId(10L),
    ): MemberNotificationProfile =
        MemberNotificationProfile(
            displayName = "홍길동",
            destination = destination,
            locale = Locale.KOREAN,
            consent = consent,
            tenantGroupId = tenantGroupId,
            clinicId = clinicId,
        )
}
