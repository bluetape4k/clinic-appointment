package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.clinic.appointment.event.notification.NotificationChannelType
import io.bluetape4k.clinic.appointment.event.notification.NotificationFailureCode
import io.bluetape4k.clinic.appointment.event.notification.NotificationTemplateKey
import io.bluetape4k.clinic.appointment.event.notification.NotificationTemplateVersion
import java.io.Serializable

val WAITLIST_SLOT_OFFER_TEMPLATE_KEY = NotificationTemplateKey("waitlist.slot.offer")
val WAITLIST_SLOT_OFFER_TEMPLATE_VERSION = NotificationTemplateVersion(1)

/** code-owned 고정 template을 key, version, channel로 찾는 runtime catalog입니다. */
fun interface NotificationTemplateCatalog {
    fun find(
        key: NotificationTemplateKey,
        version: NotificationTemplateVersion,
        channel: NotificationChannelType,
    ): NotificationTemplate?

    fun findWaitlistOffer(channel: NotificationChannelType): NotificationTemplate? =
        find(WAITLIST_SLOT_OFFER_TEMPLATE_KEY, WAITLIST_SLOT_OFFER_TEMPLATE_VERSION, channel)
}

/** 기본 설정에서 사용할 수 있는 PII-free code-owned waitlist offer template입니다. */
object BuiltInWaitlistNotificationTemplateCatalog : NotificationTemplateCatalog {
    override fun find(
        key: NotificationTemplateKey,
        version: NotificationTemplateVersion,
        channel: NotificationChannelType,
    ): NotificationTemplate? {
        if (key != WAITLIST_SLOT_OFFER_TEMPLATE_KEY || version != WAITLIST_SLOT_OFFER_TEMPLATE_VERSION) return null
        return NotificationTemplate(
            key = key,
            version = version,
            channel = channel,
            fields = setOf(
                "profile.displayName",
                "profile.locale",
                "waitlist.reasonCode",
                "waitlist.startsAt",
                "waitlist.endsAt",
            ),
            titleTemplate = "Waitlist offer",
            textTemplate = "{{profile.displayName}}, a waitlist slot is available ({{waitlist.reasonCode}}) from {{waitlist.startsAt}} to {{waitlist.endsAt}}.",
        )
    }
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

/** waitlist payload는 typed claim snapshot과 발송 시점 profile만으로 렌더링합니다. */
class WaitlistOfferNotificationTemplateRenderer(
    private val catalog: NotificationTemplateCatalog,
) {
    fun render(
        claim: WaitlistOfferNotificationClaim,
        profile: MemberNotificationProfile,
        channel: NotificationChannelType,
    ): RenderedNotificationTemplate {
        val template = catalog.findWaitlistOffer(channel)
            ?: throw NotificationTemplateException(
                NotificationFailureCode.TEMPLATE_NOT_FOUND,
                "waitlist notification template not found",
            )
        if (template.channel != channel) {
            throw NotificationTemplateException(
                NotificationFailureCode.TEMPLATE_PARAMETER_INVALID,
                "waitlist notification template channel mismatch",
            )
        }
        val values = mapOf(
            "profile.displayName" to profile.displayName,
            "profile.locale" to profile.locale.toLanguageTag(),
            "waitlist.reasonCode" to claim.reasonCode,
            "waitlist.startsAt" to claim.slotStartsAt.toString(),
            "waitlist.endsAt" to claim.slotEndsAt.toString(),
        )
        val unknown = template.fields - values.keys
        if (unknown.isNotEmpty()) {
            throw NotificationTemplateException(
                NotificationFailureCode.TEMPLATE_PARAMETER_INVALID,
                "unknown waitlist template parameter",
            )
        }
        values.values.forEach { value -> validateRuntimeText(value, "waitlistTemplateValue", 1_000) }
        return RenderedNotificationTemplate(
            title = template.titleTemplate?.let { renderText(it, values) },
            textBody = renderText(template.textTemplate, values),
            htmlBody = template.htmlTemplate?.let { renderHtml(it, values) },
        )
    }

    private fun renderText(template: String, values: Map<String, String>): String =
        escapeWaitlistText(replaceTokens(template, values))

    private fun renderHtml(template: String, values: Map<String, String>): String =
        replaceTokens(template, values.mapValues { (_, value) -> escapeWaitlistHtml(value) })

    private fun replaceTokens(template: String, values: Map<String, String>): String {
        var rendered = template
        WAITLIST_TOKEN_REGEX.findAll(template).forEach { match ->
            val value = values[match.groupValues[1]]
                ?: throw NotificationTemplateException(
                    NotificationFailureCode.TEMPLATE_PARAMETER_INVALID,
                    "missing waitlist template parameter",
                )
            rendered = rendered.replace(match.value, value)
        }
        if (rendered.contains("{{") || rendered.contains("}}")) {
            throw NotificationTemplateException(
                NotificationFailureCode.TEMPLATE_PARAMETER_INVALID,
                "unresolved waitlist template parameter",
            )
        }
        validateRuntimeText(rendered, "waitlistRenderedTemplate", 4_000)
        return rendered
    }

    private companion object {
        val WAITLIST_TOKEN_REGEX = Regex("\\{\\{([A-Za-z0-9_.-]+)}}")
    }
}

private fun escapeWaitlistText(value: String): String =
    value.replace("\r", " ").replace("\n", " ")

private fun escapeWaitlistHtml(value: String): String =
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
