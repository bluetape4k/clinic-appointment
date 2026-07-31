package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.clinic.appointment.event.notification.ClinicId
import io.bluetape4k.clinic.appointment.event.notification.NotificationChannelType
import io.bluetape4k.clinic.appointment.event.notification.TenantGroupId
import io.bluetape4k.clinic.appointment.model.identity.MemberId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.io.Serializable
import java.time.Duration

/**
 * 회원 profile을 발송 시점에만 조회하는 runtime port입니다.
 *
 * 요청에는 scope 검증에 필요한 tenant/clinic/member/channel만 담는다. 이름, 연락처,
 * 동의는 resolver 응답으로만 흐르고 outbox 직렬화 대상이 아니다.
 */
fun interface MemberNotificationProfileResolver {
    suspend fun resolve(request: MemberNotificationProfileRequest): MemberNotificationProfileResult
}

data class MemberNotificationProfileRequest(
    val tenantGroupId: TenantGroupId,
    val clinicId: ClinicId,
    val memberId: MemberId,
    val channel: NotificationChannelType,
) : Serializable {
    override fun toString(): String =
        "MemberNotificationProfileRequest(scope=<redacted>, memberId=<redacted>, channel=$channel)"

    companion object {
        private const val serialVersionUID = 1L
    }
}

sealed class MemberNotificationProfileResult : Serializable {
    data class Resolved(val profile: MemberNotificationProfile) : MemberNotificationProfileResult() {
        companion object {
            private const val serialVersionUID = 1L
        }
    }

    data object NotFound : MemberNotificationProfileResult() {
        private const val serialVersionUID = 1L
    }

    data object Withdrawn : MemberNotificationProfileResult() {
        private const val serialVersionUID = 1L
    }

    data object DirectoryUnavailable : MemberNotificationProfileResult() {
        private const val serialVersionUID = 1L
    }

    data object RateLimited : MemberNotificationProfileResult() {
        private const val serialVersionUID = 1L
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

class MemberNotificationProfileRateLimitedException : RuntimeException() {
    companion object {
        private const val serialVersionUID = 1L
    }
}

class MemberNotificationProfileUnavailableException : RuntimeException() {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * 회원 client 예외를 닫힌 resolver 결과로 변환한다.
 *
 * 취소는 장애 결과로 삼키지 않고 coroutine 취소로 전파한다. timeout, rate-limit,
 * directory 장애만 retryable 분류로 낮춘다.
 */
class BoundedMemberNotificationProfileResolver(
    private val delegate: MemberNotificationProfileResolver,
    private val timeout: Duration,
) : MemberNotificationProfileResolver {

    init {
        require(!timeout.isNegative && !timeout.isZero) { "timeout must be positive" }
    }

    override suspend fun resolve(request: MemberNotificationProfileRequest): MemberNotificationProfileResult =
        try {
            withTimeout(timeout.toMillis()) {
                delegate.resolve(request)
            }
        } catch (e: MemberNotificationProfileRateLimitedException) {
            MemberNotificationProfileResult.RateLimited
        } catch (e: TimeoutCancellationException) {
            MemberNotificationProfileResult.DirectoryUnavailable
        } catch (e: MemberNotificationProfileUnavailableException) {
            MemberNotificationProfileResult.DirectoryUnavailable
        } catch (e: CancellationException) {
            throw e
        }
}
