package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.clinic.appointment.event.notification.NotificationChannelType
import io.bluetape4k.clinic.appointment.event.notification.NotificationTemplateKey
import io.bluetape4k.clinic.appointment.event.notification.NotificationTemplateVersion
import java.io.Serializable

/** code-owned 고정 template을 key, version, channel로 찾는 runtime catalog입니다. */
fun interface NotificationTemplateCatalog {
    fun find(
        key: NotificationTemplateKey,
        version: NotificationTemplateVersion,
        channel: NotificationChannelType,
    ): NotificationTemplate?
}

/** 실행 표현식 없이 허용 field와 출력 문맥만 선언하는 versioned template입니다. */
data class NotificationTemplate(
    val key: NotificationTemplateKey,
    val version: NotificationTemplateVersion,
    val channel: NotificationChannelType,
    val fields: Set<String>,
    val titleTemplate: String? = null,
    val textTemplate: String,
    val htmlTemplate: String? = null,
    val deepLink: String? = null,
) : Serializable {
    init {
        require(fields.isNotEmpty()) { "fields must not be empty" }
        fields.forEach { validateTemplateToken(it, "field", 64) }
        validateTemplateSource(textTemplate, "textTemplate")
        titleTemplate?.let { validateTemplateSource(it, "titleTemplate") }
        htmlTemplate?.let {
            validateTemplateSource(it, "htmlTemplate")
            rejectUnsafeHtmlTemplate(it)
        }
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/** provider 호출 동안에만 메모리에 유지하는 렌더링 결과입니다. */
data class RenderedNotificationTemplate(
    val title: String?,
    val textBody: String,
    val htmlBody: String?,
) : Serializable {
    override fun toString(): String =
        "RenderedNotificationTemplate(title=<redacted>, textBody=<redacted>, htmlBody=<redacted>)"

    companion object {
        private const val serialVersionUID = 1L
    }
}

class NotificationTemplateException(
    val failureCode: io.bluetape4k.clinic.appointment.event.notification.NotificationFailureCode,
    message: String,
) : RuntimeException(message) {
    companion object {
        private const val serialVersionUID = 1L
    }
}

internal fun validateTemplateToken(value: String, fieldName: String, maxLength: Int): String {
    validateRuntimeText(value, fieldName, maxLength)
    require(value.all { it.isLetterOrDigit() || it == '_' || it == '.' || it == '-' }) {
        "$fieldName contains unsupported characters"
    }
    require(!value.contains("{{") && !value.contains("}}") && !value.contains("\${")) {
        "$fieldName must not contain template delimiters"
    }
    return value
}

internal fun validateTemplateSource(value: String, fieldName: String): String {
    validateRuntimeText(value, fieldName, 2_000)
    require(!value.contains("\${")) { "$fieldName must not contain expression delimiters" }
    return value
}
