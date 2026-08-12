package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.clinic.appointment.event.notification.AppointmentConfirmedParameters
import io.bluetape4k.clinic.appointment.event.notification.AppointmentCancelledParameters
import io.bluetape4k.clinic.appointment.event.notification.CancellationReasonCode
import io.bluetape4k.clinic.appointment.event.notification.AppointmentRescheduledParameters
import io.bluetape4k.clinic.appointment.event.notification.ClinicId
import io.bluetape4k.clinic.appointment.event.notification.NotificationChannelType
import io.bluetape4k.clinic.appointment.event.notification.NotificationFailureCode
import io.bluetape4k.clinic.appointment.event.notification.NotificationTemplateKey
import io.bluetape4k.clinic.appointment.event.notification.NotificationTemplateVersion
import io.bluetape4k.clinic.appointment.event.notification.TenantGroupId
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale

internal class NotificationTemplateRendererTest {

    private val key = NotificationTemplateKey("appointment.confirmed")
    private val version = NotificationTemplateVersion(1)
    private val profile = MemberNotificationProfile(
        displayName = "<script>alert(1)</script>",
        destination = "+821012345678",
        locale = Locale.KOREAN,
        consent = NotificationConsent(),
        tenantGroupId = TenantGroupId(1L),
        clinicId = ClinicId(1L),
    )

    @Test
    fun `알 수 없는 version은 TEMPLATE_NOT_FOUND로 실패한다`() {
        val renderer = NotificationTemplateRenderer(StaticCatalog(emptyList()))

        val failure = assertThrows<NotificationTemplateException> {
            renderer.render(key, version, NotificationChannelType.SMS, confirmedParameters(), profile)
        }

        failure.failureCode shouldBeEqualTo NotificationFailureCode.TEMPLATE_NOT_FOUND
    }

    @Test
    fun `template이 typed parameter에 없는 field를 요구하면 fail closed 한다`() {
        val renderer = NotificationTemplateRenderer(
            StaticCatalog(
                listOf(
                    NotificationTemplate(
                        key = key,
                        version = version,
                        channel = NotificationChannelType.SMS,
                        fields = setOf("rawPhone"),
                        textTemplate = "{{rawPhone}}",
                    )
                )
            )
        )

        val unknown = assertThrows<NotificationTemplateException> {
            renderer.render(key, version, NotificationChannelType.SMS, confirmedParameters(), profile)
        }

        unknown.failureCode shouldBeEqualTo NotificationFailureCode.TEMPLATE_PARAMETER_INVALID
    }

    @Test
    fun `catalog가 요청한 template identity와 다른 row를 반환하면 fail closed 한다`() {
        val renderer = NotificationTemplateRenderer(
            NotificationTemplateCatalog { _, _, channel ->
                NotificationTemplate(
                    key = NotificationTemplateKey("appointment.other"),
                    version = version,
                    channel = channel,
                    fields = setOf("clinicDisplayName"),
                    textTemplate = "{{clinicDisplayName}}",
                )
            },
        )

        val failure = assertThrows<NotificationTemplateException> {
            renderer.render(key, version, NotificationChannelType.SMS, confirmedParameters(), profile)
        }

        failure.failureCode shouldBeEqualTo NotificationFailureCode.TEMPLATE_PARAMETER_INVALID
    }

    @Test
    fun `typed durable parameter 값의 control delimiter length 위반을 거절한다`() {
        val control = assertThrows<IllegalArgumentException> {
            confirmedParameters(clinicDisplayName = "clinic\nname")
        }
        val tooLong = assertThrows<IllegalArgumentException> {
            confirmedParameters(clinicDisplayName = "a".repeat(121))
        }

        control.message!!.contains("control") shouldBeEqualTo true
        tooLong.message!!.contains("120") shouldBeEqualTo true
    }

    @Test
    fun `email html은 profile 값을 HTML escape 하고 deep-link scheme allowlist를 적용한다`() {
        val renderer = renderer(NotificationChannelType.EMAIL)

        val rendered = renderer.render(
            key,
            version,
            NotificationChannelType.EMAIL,
            confirmedParameters(),
            profile,
        )
        val badScheme = assertThrows<NotificationTemplateException> {
            NotificationTemplateRenderer(
                StaticCatalog(
                    listOf(NotificationTemplate(
                        key = key,
                        version = version,
                        channel = NotificationChannelType.EMAIL,
                        fields = setOf("clinicDisplayName", "template.deepLink"),
                        textTemplate = "{{clinicDisplayName}} {{template.deepLink}}",
                        deepLink = "javascript:alert(1)",
                    ))
                )
            ).render(
                key = key,
                version = version,
                channel = NotificationChannelType.EMAIL,
                parameters = confirmedParameters(),
                profile = profile,
            )
        }
        val script = assertThrows<IllegalArgumentException> {
            NotificationTemplate(
                key = key,
                version = version,
                channel = NotificationChannelType.EMAIL,
                fields = setOf("clinicDisplayName"),
                textTemplate = "{{clinicDisplayName}}",
                htmlTemplate = "<script>alert(1)</script>",
            )
        }

        rendered.htmlBody!!.contains("&lt;script&gt;") shouldBeEqualTo true
        badScheme.failureCode shouldBeEqualTo NotificationFailureCode.TEMPLATE_PARAMETER_INVALID
        script.message!!.contains("script") shouldBeEqualTo true
    }

    @Test
    fun `SMS push text와 email html context를 분리하고 reschedule field를 exhaustively 매핑한다`() {
        val sms = renderer(NotificationChannelType.SMS).render(
            key,
            version,
            NotificationChannelType.SMS,
            confirmedParameters(),
            profile,
        )
        val push = renderer(NotificationChannelType.PUSH).render(
            key,
            version,
            NotificationChannelType.PUSH,
            confirmedParameters(),
            profile,
        )
        val rescheduled = NotificationTemplateRenderer(
            StaticCatalog(
                listOf(
                    NotificationTemplate(
                        key = NotificationTemplateKey("appointment.rescheduled"),
                        version = version,
                        channel = NotificationChannelType.SMS,
                        fields = setOf(
                            "clinicDisplayName",
                            "previousAppointmentDate",
                            "previousStartTime",
                            "replacementAppointmentDate",
                            "replacementStartTime",
                        ),
                        textTemplate = "{{previousAppointmentDate}} {{replacementAppointmentDate}}",
                    )
                )
            )
        ).render(
            NotificationTemplateKey("appointment.rescheduled"),
            version,
            NotificationChannelType.SMS,
            AppointmentRescheduledParameters(
                clinicDisplayName = "서울클리닉",
                previousAppointmentDate = LocalDate.parse("2026-08-01"),
                previousStartTime = LocalTime.parse("09:00:00"),
                replacementAppointmentDate = LocalDate.parse("2026-08-02"),
                replacementStartTime = LocalTime.parse("10:00:00"),
            ),
            profile,
        )

        sms.textBody.contains("<script>") shouldBeEqualTo true
        push.textBody.contains("<script>") shouldBeEqualTo true
        rescheduled.textBody shouldBeEqualTo "2026-08-01 2026-08-02"
    }

    @Test
    fun `취소 template v2는 detail을 text와 HTML에서 escape하고 null이면 code-only로 렌더링한다`() {
        val renderer = NotificationTemplateRenderer(BuiltInWaitlistNotificationTemplateCatalog)
        val detail = "<b>일정 변경</b>"
        val withDetail = renderer.render(
            APPOINTMENT_CANCELLED_TEMPLATE_KEY,
            APPOINTMENT_CANCELLED_TEMPLATE_VERSION,
            NotificationChannelType.EMAIL,
            AppointmentCancelledParameters(
                clinicDisplayName = "서울클리닉",
                appointmentDate = LocalDate.parse("2026-08-01"),
                startTime = LocalTime.parse("09:00:00"),
                cancellationReasonCode = CancellationReasonCode("CUSTOMER_REQUEST"),
                cancellationReasonDetail = detail,
            ),
            profile,
        )
        val withoutDetail = renderer.render(
            APPOINTMENT_CANCELLED_TEMPLATE_KEY,
            APPOINTMENT_CANCELLED_TEMPLATE_VERSION,
            NotificationChannelType.EMAIL,
            AppointmentCancelledParameters(
                clinicDisplayName = "서울클리닉",
                appointmentDate = LocalDate.parse("2026-08-01"),
                startTime = LocalTime.parse("09:00:00"),
                cancellationReasonCode = CancellationReasonCode("CUSTOMER_REQUEST"),
            ),
            profile,
        )

        withDetail.htmlBody!!.contains("&lt;b&gt;일정 변경&lt;/b&gt;") shouldBeEqualTo true
        withDetail.textBody.contains(detail) shouldBeEqualTo true
        withoutDetail.textBody.contains("null") shouldBeEqualTo false
        withoutDetail.textBody.contains("CUSTOMER_REQUEST") shouldBeEqualTo true
    }

    private fun renderer(channel: NotificationChannelType): NotificationTemplateRenderer =
        NotificationTemplateRenderer(
            StaticCatalog(
                listOf(
                    NotificationTemplate(
                        key = key,
                        version = version,
                        channel = channel,
                        fields = setOf("clinicDisplayName", "appointmentDate", "startTime"),
                        titleTemplate = "예약 {{appointmentDate}}",
                        textTemplate = "{{profile.displayName}} {{clinicDisplayName}} {{appointmentDate}}",
                        htmlTemplate = "<a href=\"https://clinic.example/appointments\">{{profile.displayName}}</a>",
                    )
                )
            )
        )

    private fun confirmedParameters(clinicDisplayName: String = "서울클리닉"): AppointmentConfirmedParameters =
        AppointmentConfirmedParameters(
            clinicDisplayName = clinicDisplayName,
            appointmentDate = LocalDate.parse("2026-08-01"),
            startTime = LocalTime.parse("09:00:00"),
        )

    private class StaticCatalog(
        templates: List<NotificationTemplate>,
    ) : NotificationTemplateCatalog {
        private val templates = templates.associateBy { Triple(it.key, it.version, it.channel) }

        override fun find(
            key: NotificationTemplateKey,
            version: NotificationTemplateVersion,
            channel: NotificationChannelType,
        ): NotificationTemplate? =
            templates[Triple(key, version, channel)]
    }
}
