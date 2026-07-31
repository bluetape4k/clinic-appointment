package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.clinic.appointment.event.notification.AppointmentCancelledParameters
import io.bluetape4k.clinic.appointment.event.notification.AppointmentConfirmedParameters
import io.bluetape4k.clinic.appointment.event.notification.AppointmentCreatedParameters
import io.bluetape4k.clinic.appointment.event.notification.AppointmentReminderParameters
import io.bluetape4k.clinic.appointment.event.notification.AppointmentRescheduledParameters
import io.bluetape4k.clinic.appointment.event.notification.NotificationChannelType
import io.bluetape4k.clinic.appointment.event.notification.NotificationFailureCode
import io.bluetape4k.clinic.appointment.event.notification.NotificationTemplateParameters
import io.bluetape4k.clinic.appointment.event.notification.NotificationTemplateKey
import io.bluetape4k.clinic.appointment.event.notification.NotificationTemplateVersion
import java.net.URI
import java.time.format.DateTimeFormatter

/**
 * Task2의 typed parameter와 발송 시점 회원 profile만 사용해 versioned template을 렌더링합니다.
 *
 * 임의 map이나 실행 표현식을 받지 않으며 출력 문맥별 escaping과 URI scheme allowlist를
 * 통과하지 못한 값은 fail-closed로 거절합니다.
 */
class NotificationTemplateRenderer(
    private val catalog: NotificationTemplateCatalog,
    private val allowedDeepLinkSchemes: Set<String> = setOf("https", "bluetape"),
) {

    fun render(
        key: NotificationTemplateKey,
        version: NotificationTemplateVersion,
        channel: NotificationChannelType,
        parameters: NotificationTemplateParameters,
        profile: MemberNotificationProfile,
    ): RenderedNotificationTemplate {
        val template = catalog.find(key, version, channel)
            ?: throw NotificationTemplateException(NotificationFailureCode.TEMPLATE_NOT_FOUND, "notification template not found")
        val values = validateParameters(template, parameters, profile)
        return RenderedNotificationTemplate(
            title = template.titleTemplate?.let { renderText(it, values, channel) },
            textBody = renderText(template.textTemplate, values, channel),
            htmlBody = template.htmlTemplate?.let { renderHtml(it, values) },
        )
    }

    private fun validateParameters(
        template: NotificationTemplate,
        parameters: NotificationTemplateParameters,
        profile: MemberNotificationProfile,
    ): Map<String, String> {
        val parameterValues = parameters.toTemplateFields()
        val allowed = parameterValues.keys + PROFILE_FIELDS + TEMPLATE_FIELDS
        val unknown = template.fields - allowed
        if (unknown.isNotEmpty()) {
            throw NotificationTemplateException(NotificationFailureCode.TEMPLATE_PARAMETER_INVALID, "unknown template parameter")
        }
        parameterValues.forEach { (key, value) ->
            validateTemplateToken(key, "parameter", 64)
            validateRuntimeText(value, "parameterValue", 1_000)
            require(!value.contains("{{") && !value.contains("}}") && !value.contains("\${")) {
                "parameterValue must not contain template delimiters"
            }
        }
        val runtimeValues = parameterValues + mapOf(
            "profile.displayName" to profile.displayName,
            "profile.locale" to profile.locale.toLanguageTag(),
        )
        return template.deepLink?.let {
            validateDeepLink(it)
            runtimeValues + ("template.deepLink" to it)
        } ?: runtimeValues
    }

    private fun renderText(
        template: String,
        values: Map<String, String>,
        channel: NotificationChannelType,
    ): String {
        val rendered = replaceTokens(template, values)
        return when (channel) {
            NotificationChannelType.EMAIL -> escapeText(rendered)
            NotificationChannelType.SMS,
            NotificationChannelType.PUSH,
            NotificationChannelType.DUMMY,
            -> escapeText(rendered)
        }
    }

    private fun renderHtml(
        template: String,
        values: Map<String, String>,
    ): String {
        rejectUnsafeHtmlTemplate(template)
        return replaceTokens(template, values.mapValues { escapeHtml(it.value) })
    }

    private fun replaceTokens(template: String, values: Map<String, String>): String {
        var rendered = template
        TOKEN_REGEX.findAll(template).forEach { match ->
            val name = match.groupValues[1]
            val value = values[name]
                ?: throw NotificationTemplateException(NotificationFailureCode.TEMPLATE_PARAMETER_INVALID, "missing template parameter")
            rendered = rendered.replace(match.value, value)
        }
        if (rendered.contains("{{") || rendered.contains("}}")) {
            throw NotificationTemplateException(NotificationFailureCode.TEMPLATE_PARAMETER_INVALID, "unresolved template parameter")
        }
        validateRuntimeText(rendered, "renderedTemplate", 4_000)
        return rendered
    }

    private fun validateDeepLink(value: String) {
        val scheme = runCatching { URI(value).scheme }.getOrNull()
            ?: throw NotificationTemplateException(NotificationFailureCode.TEMPLATE_PARAMETER_INVALID, "deep-link scheme is required")
        if (scheme.lowercase() !in allowedDeepLinkSchemes) {
            throw NotificationTemplateException(NotificationFailureCode.TEMPLATE_PARAMETER_INVALID, "deep-link scheme is not allowed")
        }
    }

    private fun NotificationTemplateParameters.toTemplateFields(): Map<String, String> =
        when (this) {
            is AppointmentCreatedParameters -> baseFields()
            is AppointmentConfirmedParameters -> baseFields()
            is AppointmentReminderParameters -> baseFields()
            is AppointmentCancelledParameters -> mapOf(
                "clinicDisplayName" to clinicDisplayName,
                "appointmentDate" to appointmentDate.format(DATE_FORMAT),
                "startTime" to startTime.format(TIME_FORMAT),
                "cancellationReasonCode" to (cancellationReasonCode?.value ?: "UNSPECIFIED"),
            )
            is AppointmentRescheduledParameters -> mapOf(
                "clinicDisplayName" to clinicDisplayName,
                "previousAppointmentDate" to previousAppointmentDate.format(DATE_FORMAT),
                "previousStartTime" to previousStartTime.format(TIME_FORMAT),
                "replacementAppointmentDate" to replacementAppointmentDate.format(DATE_FORMAT),
                "replacementStartTime" to replacementStartTime.format(TIME_FORMAT),
            )
        }

    private fun AppointmentCreatedParameters.baseFields(): Map<String, String> =
        mapOf(
            "clinicDisplayName" to clinicDisplayName,
            "appointmentDate" to appointmentDate.format(DATE_FORMAT),
            "startTime" to startTime.format(TIME_FORMAT),
        )

    private fun AppointmentConfirmedParameters.baseFields(): Map<String, String> =
        mapOf(
            "clinicDisplayName" to clinicDisplayName,
            "appointmentDate" to appointmentDate.format(DATE_FORMAT),
            "startTime" to startTime.format(TIME_FORMAT),
        )

    private fun AppointmentReminderParameters.baseFields(): Map<String, String> =
        mapOf(
            "clinicDisplayName" to clinicDisplayName,
            "appointmentDate" to appointmentDate.format(DATE_FORMAT),
            "startTime" to startTime.format(TIME_FORMAT),
        )

    private companion object {
        val TOKEN_REGEX = Regex("\\{\\{([A-Za-z0-9_.-]+)}}")
        val PROFILE_FIELDS = setOf("profile.displayName", "profile.locale")
        val TEMPLATE_FIELDS = setOf("template.deepLink")
        val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_TIME
    }
}

private fun escapeText(value: String): String =
    value.replace("\r", " ").replace("\n", " ")

private fun escapeHtml(value: String): String =
    buildString(value.length) {
        value.forEach { ch ->
            append(
                when (ch) {
                    '&' -> "&amp;"
                    '<' -> "&lt;"
                    '>' -> "&gt;"
                    '"' -> "&quot;"
                    '\'' -> "&#39;"
                    else -> ch
                }
            )
        }
    }

internal fun rejectUnsafeHtmlTemplate(template: String) {
    require(!Regex("(?i)<\\s*script").containsMatchIn(template)) {
        "htmlTemplate must not contain script tags"
    }
    require(!Regex("(?i)\\son[a-z]+\\s*=").containsMatchIn(template)) {
        "htmlTemplate must not contain event handler attributes"
    }
    require(!Regex("(?i)javascript\\s*:").containsMatchIn(template)) {
        "htmlTemplate must not contain javascript URI"
    }
}
