package io.bluetape4k.clinic.appointment.service

import io.bluetape4k.clinic.appointment.model.policy.*
import java.time.Duration
import java.time.Instant

/**
 * Deterministically compiles active tenant policy and clinic overrides.
 *
 * Compilation never reads clocks, databases, caches, or authentication state.
 * Callers supply the evaluation instants, generation vector, and exact source
 * versions. Every resolved leaf is recorded in
 * [EffectiveSchedulingPolicy.sourceByPath], optional `Disable` instructions are
 * recorded in [EffectiveSchedulingPolicy.disabledFeatures], and the resulting
 * immutable snapshot receives a canonical hash.
 *
 * Platform and tenant safety ceilings are never overrideable. In the capacity
 * contract, [CapacityAndOverbookingPolicy.absoluteBookingLimit] remains sourced
 * from the tenant baseline and clinic values must fit below it.
 */
object SchedulingPolicyCompiler {

    /**
     * Compiles all eight active tenant policies with optional clinic overrides.
     *
     * [tenant] must contain every schema-one kind. [sourceVersions] must contain
     * the same eight kinds, with a positive tenant version and a clinic version
     * exactly when [clinic] contains that kind. This makes an incomplete read or
     * stale version vector fail before a snapshot can be persisted.
     *
     * Clinic values can narrow retry, delay, extension, and capacity bounds but
     * cannot raise them above the tenant value. Non-overrideable safety fields
     * remain sourced from the tenant definition.
     *
     * @param tenantGroupId Positive tenant boundary.
     * @param clinicId Positive clinic identity.
     * @param decisionAt UTC policy-decision instant.
     * @param serviceAt UTC appointment/service instant.
     * @param generation Generation vector observed while reading active heads.
     * @param sourceVersions Exact active definition versions for every kind.
     * @param tenant Complete active tenant policy set.
     * @param clinic Active clinic override set; defaults to no overrides.
     * @return Immutable effective snapshot with every leaf source recorded.
     * @throws IllegalArgumentException for incomplete inputs, inconsistent
     * versions, invalid overrides, required-field disable, or relaxed ceilings.
     */
    @Suppress("LongMethod", "LongParameterList")
    fun compile(
        tenantGroupId: Long,
        clinicId: Long,
        decisionAt: Instant,
        serviceAt: Instant,
        generation: PolicyGenerationVector,
        sourceVersions: Map<SchedulingPolicyKind, SourceVersion>,
        tenant: CompiledSchedulingPolicy,
        clinic: ClinicSchedulingPolicyOverrides = ClinicSchedulingPolicyOverrides(),
    ): EffectiveSchedulingPolicy {
        require(tenantGroupId > 0L) { "tenantGroupId must be positive" }
        require(clinicId > 0L) { "clinicId must be positive" }
        require(!serviceAt.isBefore(decisionAt)) { "serviceAt must not be before decisionAt" }
        validateGeneration(generation)

        val requiredKinds = SchedulingPolicyKind.entries.toSet()
        require(sourceVersions.keys == requiredKinds) {
            "sourceVersions must contain every scheduling policy kind"
        }
        val clinicKinds = clinic.kinds()
        sourceVersions.forEach { (kind, source) ->
            require(source.tenantVersion > 0L) { "$kind tenantVersion must be positive" }
            require((source.clinicVersion != null) == (kind in clinicKinds)) {
                "$kind clinicVersion must be present exactly when a clinic override is active"
            }
            source.clinicVersion?.let {
                require(it > 0L) { "$kind clinicVersion must be positive" }
            }
        }

        val sourceByPath = linkedMapOf<String, PolicyValueSource>()
        val disabled = linkedSetOf<String>()

        val tenantBooking = requireNotNull(tenant.bookingCommitment) {
            "tenant bookingCommitment policy is required"
        }.validatedTenant()
        val booking = compileBooking(
            tenant = tenantBooking,
            clinic = clinic.bookingCommitment?.validatedClinic(),
            sourceByPath = sourceByPath,
            disabled = disabled,
        ).validatedTenant()

        val tenantHold = requireNotNull(tenant.holdAndConsent) {
            "tenant holdAndConsent policy is required"
        }.validatedTenant()
        val hold = HoldAndConsentPolicy(
            consentEvidenceRequired = resolveOptionalBoolean(
                "holdAndConsent.consentEvidenceRequired",
                tenantHold.consentEvidenceRequired,
                clinic.holdAndConsent?.validatedClinic()?.consentEvidenceRequired,
                sourceByPath,
                disabled,
            ),
            maximumConsentAgeSeconds = resolveRequired(
                "holdAndConsent.maximumConsentAgeSeconds",
                tenantHold.maximumConsentAgeSeconds,
                clinic.holdAndConsent?.maximumConsentAgeSeconds,
                sourceByPath,
            ),
        ).validatedTenant()

        val tenantCapacity = requireNotNull(tenant.capacityAndOverbooking) {
            "tenant capacityAndOverbooking policy is required"
        }.validatedTenant()
        val capacityOverride = clinic.capacityAndOverbooking?.validatedClinic()
        val capacity = compileCapacityPayload(
            tenantCapacity,
            capacityOverride,
            sourceByPath,
            disabled,
        ).validatedTenant()

        val tenantPriority = requireNotNull(tenant.priorityAndReliability) {
            "tenant priorityAndReliability policy is required"
        }.validatedTenant()
        val priorityOverride = clinic.priorityAndReliability?.validatedClinic()
        val priority = PriorityAndReliabilityPolicy(
            priorityWeights = resolveRequired(
                "priorityAndReliability.priorityWeights",
                tenantPriority.priorityWeights,
                priorityOverride?.priorityWeights,
                sourceByPath,
            ),
            noShowPenalty = resolveRequired(
                "priorityAndReliability.noShowPenalty",
                tenantPriority.noShowPenalty,
                priorityOverride?.noShowPenalty,
                sourceByPath,
            ),
            sameDayCancellationPenalty = resolveRequired(
                "priorityAndReliability.sameDayCancellationPenalty",
                tenantPriority.sameDayCancellationPenalty,
                priorityOverride?.sameDayCancellationPenalty,
                sourceByPath,
            ),
            minimumPriorityScore = tenantPriority.minimumPriorityScore.also {
                sourceByPath["priorityAndReliability.minimumPriorityScore"] =
                    PolicyValueSource.TENANT
            },
        ).validatedTenant()

        val tenantReconfirmation = requireNotNull(tenant.reconfirmation) {
            "tenant reconfirmation policy is required"
        }.validatedTenant()
        val reconfirmationOverride = clinic.reconfirmation?.validatedClinic()
        val reconfirmation = ReconfirmationPolicy(
            required = resolveOptionalBoolean(
                "reconfirmation.required",
                tenantReconfirmation.required,
                reconfirmationOverride?.required,
                sourceByPath,
                disabled,
            ),
            leadTimeSeconds = resolveRequired(
                "reconfirmation.leadTimeSeconds",
                tenantReconfirmation.leadTimeSeconds,
                reconfirmationOverride?.leadTimeSeconds,
                sourceByPath,
            ),
            maximumAttempts = resolveUpperBound(
                "reconfirmation.maximumAttempts",
                tenantReconfirmation.maximumAttempts,
                reconfirmationOverride?.maximumAttempts,
                sourceByPath,
            ),
        ).validatedTenant()

        val tenantDisruption = requireNotNull(tenant.disruptionRecovery) {
            "tenant disruptionRecovery policy is required"
        }.validatedTenant()
        val disruptionOverride = clinic.disruptionRecovery?.validatedClinic()
        val disruption = DisruptionRecoveryPolicy(
            automaticProposalEnabled = resolveOptionalBoolean(
                "disruptionRecovery.automaticProposalEnabled",
                tenantDisruption.automaticProposalEnabled,
                disruptionOverride?.automaticProposalEnabled,
                sourceByPath,
                disabled,
            ),
            maximumProposalDelaySeconds = resolveUpperBound(
                "disruptionRecovery.maximumProposalDelaySeconds",
                tenantDisruption.maximumProposalDelaySeconds,
                disruptionOverride?.maximumProposalDelaySeconds,
                sourceByPath,
            ),
            preserveConfirmedAppointment = tenantDisruption.preserveConfirmedAppointment.also {
                sourceByPath["disruptionRecovery.preserveConfirmedAppointment"] =
                    PolicyValueSource.TENANT
            },
        ).validatedTenant()

        val tenantExtension = requireNotNull(tenant.operatingExtension) {
            "tenant operatingExtension policy is required"
        }.validatedTenant()
        val extensionOverride = clinic.operatingExtension?.validatedClinic()
        val extension = OperatingExtensionPolicy(
            extensionEnabled = resolveOptionalBoolean(
                "operatingExtension.extensionEnabled",
                tenantExtension.extensionEnabled,
                extensionOverride?.extensionEnabled,
                sourceByPath,
                disabled,
            ),
            maximumExtensionMinutes = resolveUpperBound(
                "operatingExtension.maximumExtensionMinutes",
                tenantExtension.maximumExtensionMinutes,
                extensionOverride?.maximumExtensionMinutes,
                sourceByPath,
            ),
            legalSafetyCeilingMinutes = tenantExtension.legalSafetyCeilingMinutes.also {
                sourceByPath["operatingExtension.legalSafetyCeilingMinutes"] =
                    PolicyValueSource.TENANT
            },
        ).validatedTenant()

        val tenantNotification = requireNotNull(tenant.notificationAndSla) {
            "tenant notificationAndSla policy is required"
        }.validatedTenant()
        val notificationOverride = clinic.notificationAndSla?.validatedClinic()
        val notification = NotificationAndSlaPolicy(
            notificationChannels = resolveRequired(
                "notificationAndSla.notificationChannels",
                tenantNotification.notificationChannels,
                notificationOverride?.notificationChannels,
                sourceByPath,
            ),
            disruptionNoticeSeconds = resolveUpperBound(
                "notificationAndSla.disruptionNoticeSeconds",
                tenantNotification.disruptionNoticeSeconds,
                notificationOverride?.disruptionNoticeSeconds,
                sourceByPath,
            ),
            mandatoryResponseSeconds = tenantNotification.mandatoryResponseSeconds.also {
                sourceByPath["notificationAndSla.mandatoryResponseSeconds"] =
                    PolicyValueSource.TENANT
            },
        ).validatedTenant()

        val payload = CompiledSchedulingPolicy(
            bookingCommitment = booking,
            holdAndConsent = hold,
            capacityAndOverbooking = capacity,
            priorityAndReliability = priority,
            reconfirmation = reconfirmation,
            disruptionRecovery = disruption,
            operatingExtension = extension,
            notificationAndSla = notification,
        )
        return snapshot(
            tenantGroupId = tenantGroupId,
            clinicId = clinicId,
            decisionAt = decisionAt,
            serviceAt = serviceAt,
            generation = generation,
            sourceVersions = sourceVersions,
            sourceByPath = sourceByPath,
            disabled = disabled,
            payload = payload,
        )
    }

    /**
     * Compiles the capacity/overbooking kind for one clinic.
     *
     * Resolution order is tenant baseline followed by clinic override. `INHERIT`
     * preserves the tenant value, `SET` records the clinic as source, and
     * `DISABLE` is accepted only for automatic reduction where it resolves to
     * `false`. Nominal capacity and quota are required and cannot be disabled.
     *
     * @param tenantGroupId Positive tenant boundary.
     * @param clinicId Positive clinic identity.
     * @param decisionAt UTC instant at which policy was selected.
     * @param serviceAt UTC appointment/service instant being evaluated.
     * @param generation Tenant/clinic generations read for this compilation.
     * @param tenantVersion Positive active tenant capacity-policy version.
     * @param clinicVersion Positive active clinic-override version, or `null`
     * when [clinic] is `null`.
     * @param tenant Valid complete tenant capacity policy.
     * @param clinic Optional clinic override. Its compiled nominal-plus-quota
     * sum cannot exceed [CapacityAndOverbookingPolicy.absoluteBookingLimit].
     * @return Immutable effective snapshot containing the resolved capacity kind.
     * @throws IllegalArgumentException for invalid identities, versions,
     * scope/range violations, required-field disable, or a relaxed hard ceiling.
     */
    @Suppress("LongParameterList")
    fun compileCapacity(
        tenantGroupId: Long,
        clinicId: Long,
        decisionAt: Instant,
        serviceAt: Instant,
        generation: PolicyGenerationVector,
        tenantVersion: Long,
        clinicVersion: Long?,
        tenant: CapacityAndOverbookingPolicy,
        clinic: CapacityAndOverbookingOverride?,
    ): EffectiveSchedulingPolicy {
        require(tenantGroupId > 0L) { "tenantGroupId must be positive" }
        require(clinicId > 0L) { "clinicId must be positive" }
        require(!serviceAt.isBefore(decisionAt)) { "serviceAt must not be before decisionAt" }
        validateGeneration(generation)
        require(tenantVersion > 0L) { "tenantVersion must be positive" }
        require((clinic == null) == (clinicVersion == null)) {
            "clinicVersion must be present exactly when a clinic override is supplied"
        }
        tenant.validatedTenant()
        clinic?.validatedClinic()

        val sourceByPath = linkedMapOf<String, PolicyValueSource>()
        val disabled = linkedSetOf<String>()
        val compiledCapacity = compileCapacityPayload(
            tenant,
            clinic,
            sourceByPath,
            disabled,
        ).validatedTenant()

        val payload = CompiledSchedulingPolicy(capacityAndOverbooking = compiledCapacity)
        val sourceVersions = mapOf(
            SchedulingPolicyKind.CAPACITY_AND_OVERBOOKING to SourceVersion(
                tenantVersion = tenantVersion,
                clinicVersion = clinicVersion,
            ),
        )
        return snapshot(
            tenantGroupId = tenantGroupId,
            clinicId = clinicId,
            decisionAt = decisionAt,
            serviceAt = serviceAt,
            generation = generation,
            sourceVersions = sourceVersions,
            sourceByPath = sourceByPath,
            disabled = disabled,
            payload = payload,
        )
    }

    private fun compileBooking(
        tenant: BookingCommitmentPolicy,
        clinic: BookingCommitmentOverride?,
        sourceByPath: MutableMap<String, PolicyValueSource>,
        disabled: MutableSet<String>,
    ): BookingCommitmentPolicy {
        val requestTtl = Duration.ofSeconds(
            resolveRequired(
                "bookingCommitment.provisionalRequestTtlSeconds",
                tenant.provisionalRequestTtl.seconds,
                clinic?.provisionalRequestTtlSeconds,
                sourceByPath,
            ),
        )
        val holdTtl = resolveOptionalLong(
            "bookingCommitment.resourceHoldTtlSeconds",
            tenant.resourceHoldTtl?.seconds,
            clinic?.resourceHoldTtlSeconds,
            sourceByPath,
            disabled,
        )?.let(Duration::ofSeconds)
        return BookingCommitmentPolicy(
            adminBookingMode = resolveRequired(
                "bookingCommitment.adminBookingMode",
                tenant.adminBookingMode,
                clinic?.adminBookingMode,
                sourceByPath,
            ),
            patientBookingMode = resolveRequired(
                "bookingCommitment.patientBookingMode",
                tenant.patientBookingMode,
                clinic?.patientBookingMode,
                sourceByPath,
            ),
            provisionalCapacityMode = resolveRequired(
                "bookingCommitment.provisionalCapacityMode",
                tenant.provisionalCapacityMode,
                clinic?.provisionalCapacityMode,
                sourceByPath,
            ),
            provisionalRequestTtl = requestTtl,
            resourceHoldTtl = holdTtl,
            approvalRoles = resolveRequired(
                "bookingCommitment.approvalRoles",
                tenant.approvalRoles,
                clinic?.approvalRoles,
                sourceByPath,
            ),
            adminConsentEvidence = resolveRequired(
                "bookingCommitment.adminConsentEvidence",
                tenant.adminConsentEvidence,
                clinic?.adminConsentEvidence,
                sourceByPath,
            ),
            confirmedChangeMode = resolveRequired(
                "bookingCommitment.confirmedChangeMode",
                tenant.confirmedChangeMode,
                clinic?.confirmedChangeMode,
                sourceByPath,
            ),
        )
    }

    private fun compileCapacityPayload(
        tenant: CapacityAndOverbookingPolicy,
        clinic: CapacityAndOverbookingOverride?,
        sourceByPath: MutableMap<String, PolicyValueSource>,
        disabled: MutableSet<String>,
    ): CapacityAndOverbookingPolicy {
        val nominalCapacity = resolveRequired(
            "capacityAndOverbooking.nominalCapacity",
            tenant.nominalCapacity,
            clinic?.nominalCapacity,
            sourceByPath,
        )
        val overbookingQuota = resolveRequired(
            "capacityAndOverbooking.overbookingQuota",
            tenant.overbookingQuota,
            clinic?.overbookingQuota,
            sourceByPath,
        )
        sourceByPath["capacityAndOverbooking.absoluteBookingLimit"] = PolicyValueSource.TENANT
        return CapacityAndOverbookingPolicy(
            nominalCapacity = nominalCapacity,
            overbookingQuota = overbookingQuota,
            absoluteBookingLimit = tenant.absoluteBookingLimit,
            automaticReductionEnabled = resolveOptionalBoolean(
                "capacityAndOverbooking.automaticReductionEnabled",
                tenant.automaticReductionEnabled,
                clinic?.automaticReductionEnabled,
                sourceByPath,
                disabled,
            ),
        )
    }

    private fun ClinicSchedulingPolicyOverrides.kinds(): Set<SchedulingPolicyKind> =
        buildSet {
            if (bookingCommitment != null) add(SchedulingPolicyKind.BOOKING_COMMITMENT)
            if (holdAndConsent != null) add(SchedulingPolicyKind.HOLD_AND_CONSENT)
            if (capacityAndOverbooking != null) add(SchedulingPolicyKind.CAPACITY_AND_OVERBOOKING)
            if (priorityAndReliability != null) add(SchedulingPolicyKind.PRIORITY_AND_RELIABILITY)
            if (reconfirmation != null) add(SchedulingPolicyKind.RECONFIRMATION)
            if (disruptionRecovery != null) add(SchedulingPolicyKind.DISRUPTION_RECOVERY)
            if (operatingExtension != null) add(SchedulingPolicyKind.OPERATING_EXTENSION)
            if (notificationAndSla != null) add(SchedulingPolicyKind.NOTIFICATION_AND_SLA)
        }

    private fun validateGeneration(generation: PolicyGenerationVector) {
        require(generation.tenantGeneration > 0L) { "tenantGeneration must be positive" }
        require(generation.clinicGeneration >= 0L) {
            "clinicGeneration must not be negative"
        }
    }

    private fun <T : SchedulingPolicyPayload> T.validatedTenant(): T = apply {
        SchedulingPolicyValidator.validatePayload(this, PolicyScope.TENANT_DEFAULT)
    }

    private fun <T : SchedulingPolicyPayload> T.validatedClinic(): T = apply {
        SchedulingPolicyValidator.validatePayload(this, PolicyScope.CLINIC_OVERRIDE)
    }

    private fun <T> resolveRequired(
        path: String,
        tenantValue: T,
        override: OverrideValue<T>?,
        sourceByPath: MutableMap<String, PolicyValueSource>,
    ): T = when (override) {
        null,
        OverrideValue.Inherit,
        -> tenantValue.also { sourceByPath[path] = PolicyValueSource.TENANT }
        is OverrideValue.Set ->
            override.value.also { sourceByPath[path] = PolicyValueSource.CLINIC }
        OverrideValue.Disable -> throw IllegalArgumentException("$path cannot be disabled")
    }

    private fun <T : Comparable<T>> resolveUpperBound(
        path: String,
        tenantValue: T,
        override: OverrideValue<T>?,
        sourceByPath: MutableMap<String, PolicyValueSource>,
    ): T {
        val resolved = resolveRequired(path, tenantValue, override, sourceByPath)
        require(resolved <= tenantValue) { "$path cannot exceed the tenant ceiling($tenantValue)" }
        return resolved
    }

    private fun resolveOptionalBoolean(
        path: String,
        tenantValue: Boolean,
        override: OverrideValue<Boolean>?,
        sourceByPath: MutableMap<String, PolicyValueSource>,
        disabled: MutableSet<String>,
    ): Boolean = when (override) {
        null,
        OverrideValue.Inherit,
        -> tenantValue.also { sourceByPath[path] = PolicyValueSource.TENANT }
        is OverrideValue.Set ->
            override.value.also { sourceByPath[path] = PolicyValueSource.CLINIC }
        OverrideValue.Disable -> false.also {
            sourceByPath[path] = PolicyValueSource.CLINIC
            disabled += path
        }
    }

    private fun resolveOptionalLong(
        path: String,
        tenantValue: Long?,
        override: OverrideValue<Long>?,
        sourceByPath: MutableMap<String, PolicyValueSource>,
        disabled: MutableSet<String>,
    ): Long? = when (override) {
        null,
        OverrideValue.Inherit,
        -> tenantValue.also { sourceByPath[path] = PolicyValueSource.TENANT }
        is OverrideValue.Set ->
            override.value.also { sourceByPath[path] = PolicyValueSource.CLINIC }
        OverrideValue.Disable -> null.also {
            sourceByPath[path] = PolicyValueSource.CLINIC
            disabled += path
        }
    }

    @Suppress("LongParameterList")
    private fun snapshot(
        tenantGroupId: Long,
        clinicId: Long,
        decisionAt: Instant,
        serviceAt: Instant,
        generation: PolicyGenerationVector,
        sourceVersions: Map<SchedulingPolicyKind, SourceVersion>,
        sourceByPath: Map<String, PolicyValueSource>,
        disabled: Set<String>,
        payload: CompiledSchedulingPolicy,
    ): EffectiveSchedulingPolicy {
        val hash = SchedulingPolicyHasher.snapshotHash(
            tenantGroupId = tenantGroupId,
            clinicId = clinicId,
            decisionAt = decisionAt,
            serviceAt = serviceAt,
            generation = generation,
            sourceVersions = sourceVersions,
            sourceByPath = sourceByPath,
            disabledFeatures = disabled,
            warnings = emptyList(),
            payload = payload,
        )
        return EffectiveSchedulingPolicy(
            id = hash,
            tenantGroupId = tenantGroupId,
            clinicId = clinicId,
            decisionAt = decisionAt,
            serviceAt = serviceAt,
            generation = generation,
            sourceVersions = sourceVersions,
            sourceByPath = sourceByPath,
            disabledFeatures = disabled,
            warnings = emptyList(),
            payload = payload,
            snapshotHash = hash,
        )
    }
}
