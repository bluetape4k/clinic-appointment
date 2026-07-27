package io.bluetape4k.clinic.appointment.model.policy

import java.io.Serializable

/**
 * Explicit clinic-level instruction for one tenant policy value.
 *
 * The three states are intentionally different from Kotlin `null`: [Inherit]
 * preserves the effective tenant or platform value, [Set] supplies a clinic
 * value, and [Disable] turns off only a feature that the field contract marks
 * as disableable. Validators and compilers must reject [Disable] for required
 * values and safety ceilings.
 *
 * @param T Value type accepted by the corresponding policy property. Public
 * policy payloads use serializable scalar, enum, set, map, or value-object
 * types so the containing payload remains a durable contract.
 */
sealed interface OverrideValue<out T> : Serializable {
    /** Keep the value resolved from the tenant baseline or platform default. */
    data object Inherit : OverrideValue<Nothing> {
        private const val serialVersionUID = 1L
    }

    /**
     * Replace the inherited value with an explicitly supplied clinic value.
     *
     * @property value Candidate clinic value. It is still subject to the
     * property's range and non-relaxation rules during validation/compilation.
     */
    data class Set<T>(
        val value: T,
    ) : OverrideValue<T> {
        companion object {
            private const val serialVersionUID = 1L
        }
    }

    /**
     * Disable an optional feature at clinic scope.
     *
     * This state is invalid for mandatory fields, confirmed-appointment
     * consent, legal/safety ceilings, and mandatory SLA bounds.
     */
    data object Disable : OverrideValue<Nothing> {
        private const val serialVersionUID = 1L
    }
}
