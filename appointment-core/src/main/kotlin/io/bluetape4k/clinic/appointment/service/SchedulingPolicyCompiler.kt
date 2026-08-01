package io.bluetape4k.clinic.appointment.service

import io.bluetape4k.clinic.appointment.model.policy.*
import java.time.Duration
import java.time.Instant

/**
 * active tenant policy와 clinic override를 deterministic하게 compile합니다.
 *
 * compilation은 clock, database, cache, authentication state를 직접 읽지 않습니다.
 * caller가 evaluation instant, generation vector, 정확한 source version을 제공합니다.
 * 해석된 모든 leaf는 [EffectiveSchedulingPolicy.sourceByPath]에 기록되고, 선택 기능의
 * `Disable` 지시는 [EffectiveSchedulingPolicy.disabledFeatures]에 기록되며, 결과
 * immutable snapshot에는 canonical hash가 부여됩니다.
 *
 * platform과 tenant safety ceiling은 override할 수 없습니다. capacity 계약에서
 * [CapacityAndOverbookingPolicy.absoluteBookingLimit]는 tenant baseline source로 남고,
 * clinic 값은 이 한도 아래에 들어와야 합니다.
 */
object SchedulingPolicyCompiler {

    /**
     * 여덟 개 active tenant policy와 선택적 clinic override를 모두 compile합니다.
     *
     * [tenant]는 schema-one의 모든 kind를 포함해야 합니다. [sourceVersions]도 같은 여덟
     * kind를 포함해야 하며, 각 kind는 양수 tenant version을 가지고 [clinic]이 해당 kind를
     * 포함할 때만 clinic version을 가져야 합니다. 이 조건으로 incomplete read나 stale
     * version vector가 snapshot persistence 전에 실패하게 만듭니다.
     *
     * clinic 값은 retry, delay, extension, capacity bound를 좁힐 수 있지만 tenant 값보다
     * 높일 수 없습니다. override 불가 safety field는 tenant definition source로 유지됩니다.
     *
     * @param tenantGroupId 양수 tenant boundary입니다.
     * @param clinicId 양수 clinic identity입니다.
     * @param decisionAt UTC policy-decision instant입니다.
     * @param serviceAt UTC appointment/service instant입니다.
     * @param generation active head를 읽는 동안 관찰한 generation vector입니다.
     * @param sourceVersions 모든 kind에 대한 정확한 active definition version입니다.
     * @param tenant 완전한 active tenant policy set입니다.
     * @param clinic active clinic override set입니다. 기본값은 override 없음입니다.
     * @return 모든 leaf source가 기록된 immutable effective snapshot입니다.
     * @throws IllegalArgumentException 입력이 incomplete이거나, version이 일관되지 않거나,
     * override가 유효하지 않거나, 필수 field를 disable했거나, ceiling을 완화하려는 경우 발생합니다.
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
        if (!tenantPriority.thresholdsPresent) {
            disabled += reliabilityThresholdPaths
        }
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
            lookbackDays = resolveRequired(
                "priorityAndReliability.lookbackDays",
                tenantPriority.lookbackDays,
                priorityOverride?.lookbackDays,
                sourceByPath,
            ),
            lateCancellationWindowMinutes = resolveRequired(
                "priorityAndReliability.lateCancellationWindowMinutes",
                tenantPriority.lateCancellationWindowMinutes,
                priorityOverride?.lateCancellationWindowMinutes,
                sourceByPath,
            ),
            noShowThreshold = resolveDisableable(
                "priorityAndReliability.noShowThreshold",
                tenantPriority.noShowThreshold,
                priorityOverride?.noShowThreshold,
                sourceByPath,
                disabled,
            ),
            lateCancellationThreshold = resolveDisableable(
                "priorityAndReliability.lateCancellationThreshold",
                tenantPriority.lateCancellationThreshold,
                priorityOverride?.lateCancellationThreshold,
                sourceByPath,
                disabled,
            ),
            coolingOffHours = resolveRequired(
                "priorityAndReliability.coolingOffHours",
                tenantPriority.coolingOffHours,
                priorityOverride?.coolingOffHours,
                sourceByPath,
            ),
            minimumPriorityScore = tenantPriority.minimumPriorityScore.also {
                sourceByPath["priorityAndReliability.minimumPriorityScore"] =
                    PolicyValueSource.TENANT
            },
            thresholdsPresent = tenantPriority.thresholdsPresent,
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
            profileReevaluationHeldTargetSeconds = resolvePlatformBackedOptional(
                "notificationAndSla.profileReevaluationHeldTargetSeconds",
                tenantNotification.profileReevaluationHeldTargetSeconds,
                notificationOverride?.profileReevaluationHeldTargetSeconds,
                sourceByPath,
            ),
            profileReevaluationProposedTargetSeconds = resolvePlatformBackedOptional(
                "notificationAndSla.profileReevaluationProposedTargetSeconds",
                tenantNotification.profileReevaluationProposedTargetSeconds,
                notificationOverride?.profileReevaluationProposedTargetSeconds,
                sourceByPath,
            ),
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
     * 하나의 clinic에 대한 capacity/overbooking kind를 compile합니다.
     *
     * resolution 순서는 tenant baseline 다음 clinic override입니다. `INHERIT`는 tenant
     * 값을 보존하고, `SET`은 clinic을 source로 기록하며, `DISABLE`은 automatic reduction에서만
     * 허용되어 `false`로 해석됩니다. nominal capacity와 quota는 필수 값이므로 disable할
     * 수 없습니다.
     *
     * @param tenantGroupId 양수 tenant boundary입니다.
     * @param clinicId 양수 clinic identity입니다.
     * @param decisionAt policy가 선택된 UTC instant입니다.
     * @param serviceAt 평가 대상 appointment/service UTC instant입니다.
     * @param generation 이 compilation을 위해 읽은 tenant/clinic generation입니다.
     * @param tenantVersion active tenant capacity-policy의 양수 version입니다.
     * @param clinicVersion active clinic-override의 양수 version입니다. [clinic]이 `null`이면
     * 이 값도 `null`이어야 합니다.
     * @param tenant 유효한 완전 tenant capacity policy입니다.
     * @param clinic 선택적 clinic override입니다. 컴파일된 nominal-plus-quota 합은
     * [CapacityAndOverbookingPolicy.absoluteBookingLimit]를 초과할 수 없습니다.
     * @return resolved capacity kind를 포함하는 immutable effective snapshot입니다.
     * @throws IllegalArgumentException identity, version, scope/range 위반, 필수 field
     * disable, hard ceiling 완화가 있을 때 발생합니다.
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

    private fun <T> resolveDisableable(
        path: String,
        tenantValue: T,
        override: OverrideValue<T>?,
        sourceByPath: MutableMap<String, PolicyValueSource>,
        disabled: MutableSet<String>,
    ): T = when (override) {
        null,
        OverrideValue.Inherit,
        -> tenantValue.also { sourceByPath[path] = PolicyValueSource.TENANT }
        is OverrideValue.Set ->
            override.value.also { sourceByPath[path] = PolicyValueSource.CLINIC }
        OverrideValue.Disable -> tenantValue.also {
            sourceByPath[path] = PolicyValueSource.TENANT
            disabled += path
        }
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

    private fun <T> resolvePlatformBackedOptional(
        path: String,
        tenantValue: T?,
        override: OverrideValue<T>?,
        sourceByPath: MutableMap<String, PolicyValueSource>,
    ): T? = when (override) {
        is OverrideValue.Set ->
            override.value.also { sourceByPath[path] = PolicyValueSource.CLINIC }
        OverrideValue.Disable -> throw IllegalArgumentException("$path cannot be disabled")
        null,
        OverrideValue.Inherit,
        -> if (tenantValue != null) {
            sourceByPath[path] = PolicyValueSource.TENANT
            tenantValue
        } else {
            sourceByPath[path] = PolicyValueSource.PLATFORM
            null
        }
    }

    private val reliabilityThresholdPaths = setOf(
        "priorityAndReliability.lookbackDays",
        "priorityAndReliability.lateCancellationWindowMinutes",
        "priorityAndReliability.noShowThreshold",
        "priorityAndReliability.lateCancellationThreshold",
        "priorityAndReliability.coolingOffHours",
    )

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
