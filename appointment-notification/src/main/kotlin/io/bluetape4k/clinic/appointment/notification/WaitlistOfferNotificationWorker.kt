package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.clinic.appointment.event.notification.ClinicId
import io.bluetape4k.clinic.appointment.event.notification.NotificationChannelType
import io.bluetape4k.clinic.appointment.event.notification.NotificationFailureCode
import io.bluetape4k.clinic.appointment.event.notification.NotificationIdempotencyKey
import io.bluetape4k.clinic.appointment.event.notification.NotificationProviderMessageReference
import io.bluetape4k.clinic.appointment.event.notification.NotificationSuppressionReasonCode
import io.bluetape4k.clinic.appointment.event.notification.TenantGroupId
import kotlinx.coroutines.CancellationException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock

/** claim snapshot과 runtime profile로 provider 요청을 만드는 renderer입니다. */
fun interface WaitlistOfferNotificationRequestRenderer {
    fun render(
        claim: WaitlistOfferNotificationClaim,
        profile: MemberNotificationProfile,
    ): NotificationProviderRequest
}

/** code-owned waitlist template과 provider key factory를 연결하는 기본 request renderer입니다. */
class DefaultWaitlistOfferNotificationRequestRenderer(
    private val providerIdempotencyKeyFactory: NotificationProviderIdempotencyKeyFactory,
    private val templateCatalog: NotificationTemplateCatalog = BuiltInWaitlistNotificationTemplateCatalog,
    private val channel: NotificationChannelType,
) : WaitlistOfferNotificationRequestRenderer {
    private val templateRenderer = WaitlistOfferNotificationTemplateRenderer(templateCatalog)

    override fun render(
        claim: WaitlistOfferNotificationClaim,
        profile: MemberNotificationProfile,
    ): NotificationProviderRequest {
        val destination = checkNotNull(profile.destination) { "resolved profile destination must exist" }
        val rendered = templateRenderer.render(claim, profile, channel)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(claim.idempotencyKey.toByteArray(StandardCharsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
        return NotificationProviderRequest(
            channel = channel,
            destination = destination,
            idempotencyKey = providerIdempotencyKeyFactory.create(NotificationIdempotencyKey(digest)),
            templateKey = WAITLIST_SLOT_OFFER_TEMPLATE_KEY,
            templateVersion = WAITLIST_SLOT_OFFER_TEMPLATE_VERSION,
            rendered = rendered,
        )
    }
}

/** waitlist offer notification 한 건의 bounded 실행 결과입니다. */
sealed interface WaitlistDeliveryAttempt {
    val outcome: DeliveryOutcome

    data object Idle : WaitlistDeliveryAttempt {
        override val outcome: DeliveryOutcome = DeliveryOutcome.IDLE
    }

    data object Disabled : WaitlistDeliveryAttempt {
        override val outcome: DeliveryOutcome = DeliveryOutcome.DISABLED
    }

    data class Sent(
        val providerMessageReference: NotificationProviderMessageReference? = null,
    ) : WaitlistDeliveryAttempt {
        override val outcome: DeliveryOutcome = DeliveryOutcome.SENT
    }

    data class RetryScheduled(
        val failureCode: NotificationFailureCode,
    ) : WaitlistDeliveryAttempt {
        override val outcome: DeliveryOutcome = DeliveryOutcome.RETRY_SCHEDULED
    }

    data class Suppressed(
        val reason: NotificationSuppressionReasonCode,
    ) : WaitlistDeliveryAttempt {
        override val outcome: DeliveryOutcome = DeliveryOutcome.SUPPRESSED
    }

    data object Unknown : WaitlistDeliveryAttempt {
        override val outcome: DeliveryOutcome = DeliveryOutcome.UNKNOWN
    }

    data object LeaseLost : WaitlistDeliveryAttempt {
        override val outcome: DeliveryOutcome = DeliveryOutcome.LEASE_LOST
    }
}

/**
 * waitlist offer outbox를 처리합니다.
 *
 * [WaitlistOfferNotificationStore.claim], [WaitlistOfferNotificationStore.authorizeSend],
 * [WaitlistOfferNotificationStore.recordResult]만 DB transaction을 사용하며, member profile
 * 조회와 provider IO 중에는 transaction을 열지 않습니다. provider 결과는 offer state를
 * 변경하지 않으므로 늦은 성공 결과가 expired/terminal offer를 되살릴 수 없습니다.
 */
class WaitlistOfferNotificationWorker(
    private val store: WaitlistOfferNotificationStore,
    private val profileResolver: MemberNotificationProfileResolver,
    private val channel: NotificationChannel,
    private val requestRenderer: WaitlistOfferNotificationRequestRenderer =
        WaitlistOfferNotificationRequestRenderer { _, _ ->
            throw NotificationProviderException(NotificationFailureCode.HMAC_KEY_UNAVAILABLE)
        },
    private val clock: Clock = Clock.systemUTC(),
    private val leaseOwner: String = DEFAULT_LEASE_OWNER,
    private val enabled: Boolean = true,
    private val securityAuditSink: NotificationSecurityAuditSink = NoopNotificationSecurityAuditSink,
) {

    init {
        require(leaseOwner.isNotBlank() && leaseOwner.length <= 128) {
            "leaseOwner must contain 1..128 characters"
        }
    }

    /** 테스트와 scheduler가 한 claim만 bounded하게 처리하는 진입점입니다. */
    suspend fun runOnce(): WaitlistDeliveryAttempt {
        if (!enabled) return WaitlistDeliveryAttempt.Disabled
        val claim = store.claim(clock.instant(), leaseOwner) ?: return WaitlistDeliveryAttempt.Idle
        claim.suppressionReason?.let { return WaitlistDeliveryAttempt.Suppressed(it) }
        val memberId = claim.memberId ?: run {
            val suppressed = WaitlistNotificationDeliveryResult.Suppressed(
                NotificationSuppressionReasonCode.MEMBER_NOT_AVAILABLE,
            )
            return record(claim, suppressed, WaitlistDeliveryAttempt.Suppressed(suppressed.reason))
        }

        val profileResult = try {
            profileResolver.resolve(
                MemberNotificationProfileRequest(
                    tenantGroupId = TenantGroupId(claim.tenantGroupId),
                    clinicId = ClinicId(claim.clinicId),
                    memberId = memberId,
                    channel = channel.channelType,
                )
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            MemberNotificationProfileResult.DirectoryUnavailable
        }
        val profileDecision = MemberNotificationProfileClassifier.classify(
            result = profileResult,
            context = MemberProfileResolutionContext(
                tenantGroupId = TenantGroupId(claim.tenantGroupId),
                clinicId = ClinicId(claim.clinicId),
                channel = channel.channelType,
                memberId = memberId,
            ),
            auditSink = securityAuditSink,
        )
        profileDecision.failureCode?.let { failureCode ->
            val retry = WaitlistNotificationDeliveryResult.Retryable(failureCode)
            return record(claim, retry, WaitlistDeliveryAttempt.RetryScheduled(failureCode))
        }
        profileDecision.suppressionReason?.let { reason ->
            val suppressed = WaitlistNotificationDeliveryResult.Suppressed(reason)
            return record(claim, suppressed, WaitlistDeliveryAttempt.Suppressed(reason))
        }
        val profile = checkNotNull(profileDecision.profile)

        val request = try {
            requestRenderer.render(claim, profile)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: NotificationProviderException) {
            val retry = WaitlistNotificationDeliveryResult.Retryable(failure.failureCode)
            return record(claim, retry, WaitlistDeliveryAttempt.RetryScheduled(failure.failureCode))
        } catch (_: NotificationTemplateException) {
            val retry = WaitlistNotificationDeliveryResult.Retryable(
                NotificationFailureCode.TEMPLATE_PARAMETER_INVALID,
            )
            return record(claim, retry, WaitlistDeliveryAttempt.RetryScheduled(retry.failureCode))
        } catch (_: Exception) {
            return record(claim, WaitlistNotificationDeliveryResult.Unknown, WaitlistDeliveryAttempt.Unknown)
        }

        // Rendering may be non-trivial, so the durable fence is acquired immediately before
        // crossing into provider IO rather than before rendering.
        val authorized = store.authorizeSend(claim, clock.instant())
        if (!authorized) return WaitlistDeliveryAttempt.Suppressed(
            NotificationSuppressionReasonCode.WAITLIST_OFFER_NOT_ACTIVE,
        )
        val beforeProvider = clock.instant()
        if (!claim.deliveryDeadline.isAfter(beforeProvider)) {
            val suppressed = WaitlistNotificationDeliveryResult.Suppressed(
                NotificationSuppressionReasonCode.WAITLIST_OFFER_EXPIRED,
            )
            return record(claim, suppressed, WaitlistDeliveryAttempt.Suppressed(suppressed.reason))
        }

        val providerResult = try {
            channel.send(request, claim.deliveryDeadline)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: NotificationProviderException) {
            NotificationProviderResult.RetryableFailure(failure.failureCode)
        } catch (_: Exception) {
            return record(claim, WaitlistNotificationDeliveryResult.Unknown, WaitlistDeliveryAttempt.Unknown)
        }
        return when (providerResult) {
            is NotificationProviderResult.Accepted -> {
                val result = WaitlistNotificationDeliveryResult.Sent(providerResult.providerMessageReference)
                record(
                    claim,
                    result,
                    WaitlistDeliveryAttempt.Sent(providerResult.providerMessageReference),
                )
            }
            is NotificationProviderResult.RetryableFailure -> {
                val result = WaitlistNotificationDeliveryResult.Retryable(providerResult.failureCode)
                record(claim, result, WaitlistDeliveryAttempt.RetryScheduled(providerResult.failureCode))
            }
            is NotificationProviderResult.Suppressed -> {
                val result = WaitlistNotificationDeliveryResult.Suppressed(providerResult.reason)
                record(claim, result, WaitlistDeliveryAttempt.Suppressed(providerResult.reason))
            }
        }
    }

    private suspend fun record(
        claim: WaitlistOfferNotificationClaim,
        result: WaitlistNotificationDeliveryResult,
        attempt: WaitlistDeliveryAttempt,
    ): WaitlistDeliveryAttempt =
        if (store.recordResult(claim, result, clock.instant())) attempt else WaitlistDeliveryAttempt.LeaseLost

    private companion object {
        const val DEFAULT_LEASE_OWNER = "waitlist-offer-notification-worker"
    }
}
