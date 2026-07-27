package io.bluetape4k.clinic.appointment.service

import io.bluetape4k.clinic.appointment.model.catalog.CatalogProjectionStatus
import io.bluetape4k.clinic.appointment.model.dto.AppointmentPlanAggregateRecord
import io.bluetape4k.clinic.appointment.model.dto.AppointmentPlanRecord
import io.bluetape4k.clinic.appointment.model.dto.PlannedTreatmentKey
import io.bluetape4k.clinic.appointment.model.dto.PlannedTreatmentRecord
import io.bluetape4k.clinic.appointment.model.dto.ProductCatalogProjectionRecord
import io.bluetape4k.clinic.appointment.model.dto.TreatmentDependencyRecord
import io.bluetape4k.clinic.appointment.model.plan.AppointmentPlanStatus
import io.bluetape4k.clinic.appointment.model.plan.BookingPreferenceSnapshot
import io.bluetape4k.clinic.appointment.model.plan.PlannedTreatmentStatus

/**
 * 예약 계획 생성에 필요한 구매 소유 사실입니다.
 *
 * 상품 내용은 의도적으로 포함하지 않습니다. 상품 내용은 [AppointmentPlanFactory.create]에
 * 전달되는 별도 영속 catalog projection에서 옵니다.
 *
 * @property sourcePurchaseAuthority 구매 사실을 소유한 서비스의 안정적인 논리 식별자입니다.
 * URL이나 표시명이 아닙니다.
 * @property sourcePurchaseId source, tenant, clinic scope 안의 안정적인 구매 식별자입니다.
 * 이벤트 replay가 두 번째 계획을 만들면 안 됩니다.
 * @property patientReferenceCiphertext 원본 환자 참조의 authenticated ciphertext입니다.
 * 로그나 API 응답에 절대 노출하면 안 됩니다.
 * @property patientReferenceKeyId [patientReferenceCiphertext] 복호화와 rotation에 사용하는
 * 비밀이 아닌 key-version 식별자입니다.
 * @property patientReferenceFingerprint 결정적이고 비가역적인 scoped correlation 값입니다.
 * 여전히 민감하므로 로그에 남기면 안 됩니다.
 * @property bookingPreference 구매 서비스가 캡처한 불변 고객 희망 일정입니다. `NotProvided`는
 * 상품 fallback 규칙 사용을 허용하는 명시적 sentinel이며, 희망 일정 없음은 확정으로
 * 해석되지 않습니다.
 */
data class AppointmentPlanFactoryInput(
    val sourcePurchaseAuthority: String,
    val sourcePurchaseId: String,
    val patientReferenceCiphertext: String,
    val patientReferenceKeyId: String,
    val patientReferenceFingerprint: String,
    val bookingPreference: BookingPreferenceSnapshot,
)

/**
 * 하나의 불변 카탈로그 스냅샷을 영속화 가능한 예약 계획 draft로 확장합니다.
 *
 * factory는 결정적이며 I/O를 수행하지 않습니다. 예약 일시는 비워 둡니다. 고객 희망
 * 일정은 hold나 확정 예약으로 해석하지 않고 그대로 복사합니다.
 */
class AppointmentPlanFactory {

    /**
     * 정확한 카탈로그 스냅샷을 아직 저장되지 않은 예약 계획 aggregate 하나로 확장합니다.
     *
     * 각 BOM 항목은 `repeatCount`만큼의 회차를 만들고 sequence 번호는 1부터 시작합니다.
     * predecessor sequence가 없는 dependency는 선행 항목의 마지막 회차로 해석하고,
     * successor sequence가 없으면 후행 항목의 첫 회차로 해석합니다. 구매는 시술 의존성이
     * 아니고 고객 희망 일정은 예약이 아니므로 예약 일시는 비워 둡니다.
     *
     * @param catalog 양수 식별자, 정규 payload hash, 검증된 active 정의를 가진 영속
     * catalog projection입니다.
     * @param input 보안 처리된 구매 출처와 불변 예약 희망 정보입니다.
     * @return 식별자와 계산된 scheduling window가 `null`이고, plan status가 `ACTIVE`이며,
     * 모든 treatment status가 `PLANNED`인 결정적 미영속 aggregate입니다.
     * @throws IllegalArgumentException catalog가 retired 상태이거나, source 식별자/보안
     * 참조가 blank이거나, catalog 검증에 실패할 때 발생합니다.
     * @throws IllegalStateException 영속 catalog 식별자가 없거나 dependency가 참조하는
     * repeat count를 찾을 수 없을 때 발생합니다.
     */
    fun create(
        catalog: ProductCatalogProjectionRecord,
        input: AppointmentPlanFactoryInput,
    ): AppointmentPlanAggregateRecord {
        val catalogId = requireNotNull(catalog.id) { "catalog projection id is required" }
        val definition = CatalogDefinitionValidator.validate(catalog.definition)
        require(definition.status == CatalogProjectionStatus.ACTIVE) {
            "retired catalog projections cannot create appointment plans"
        }
        require(input.sourcePurchaseAuthority.isNotBlank()) { "sourcePurchaseAuthority must not be blank" }
        require(input.sourcePurchaseId.isNotBlank()) { "sourcePurchaseId must not be blank" }
        require(input.patientReferenceCiphertext.isNotBlank()) { "patientReferenceCiphertext must not be blank" }
        require(input.patientReferenceKeyId.isNotBlank()) { "patientReferenceKeyId must not be blank" }
        require(input.patientReferenceFingerprint.isNotBlank()) { "patientReferenceFingerprint must not be blank" }

        val treatments = definition.items.flatMapIndexed { bomOrder, item ->
            (1..item.repeatCount).map { sequenceNo ->
                PlannedTreatmentRecord(
                    bomItemId = item.bomItemId,
                    sequenceNo = sequenceNo,
                    bomOrder = bomOrder,
                    representativeTreatmentName = item.representativeTreatmentName,
                    detailedTreatmentCodes = item.detailedTreatmentCodes.toList(),
                    durationMinutes = item.durationMinutes,
                    minimumIntervalDays = item.minimumIntervalDays,
                    preferredIntervalDays = item.preferredIntervalDays,
                    maximumIntervalDays = item.maximumIntervalDays,
                    practitionerQualifications = item.practitionerQualifications.toList(),
                    equipmentTypes = item.equipmentTypes.toList(),
                    roomTypes = item.roomTypes.toList(),
                    earliestStartAt = null,
                    latestStartAt = null,
                    status = PlannedTreatmentStatus.PLANNED,
                )
            }
        }
        val repeatCounts = definition.items.associate { item -> item.bomItemId to item.repeatCount }
        val dependencies = definition.dependencies.map { dependency ->
            val predecessorSequence = dependency.predecessorSequenceNo
                ?: requireNotNull(repeatCounts[dependency.predecessorBomItemId])
            val successorSequence = dependency.successorSequenceNo ?: 1
            TreatmentDependencyRecord(
                predecessor = PlannedTreatmentKey(dependency.predecessorBomItemId, predecessorSequence),
                successor = PlannedTreatmentKey(dependency.successorBomItemId, successorSequence),
                minimumIntervalDays = dependency.minimumIntervalDays,
                preferredIntervalDays = dependency.preferredIntervalDays,
                maximumIntervalDays = dependency.maximumIntervalDays,
            )
        }

        return AppointmentPlanAggregateRecord(
            plan = AppointmentPlanRecord(
                tenantGroupId = definition.tenantGroupId,
                clinicId = definition.clinicId,
                catalogProjectionId = catalogId,
                sourcePurchaseAuthority = input.sourcePurchaseAuthority,
                sourcePurchaseId = input.sourcePurchaseId,
                patientReferenceCiphertext = input.patientReferenceCiphertext,
                patientReferenceKeyId = input.patientReferenceKeyId,
                patientReferenceFingerprint = input.patientReferenceFingerprint,
                catalogSourceAuthority = definition.sourceAuthority,
                productId = definition.productId,
                catalogVersion = definition.catalogVersion,
                catalogPayloadHash = catalog.payloadHash,
                productName = definition.productName,
                bookingPreference = input.bookingPreference,
                status = AppointmentPlanStatus.ACTIVE,
            ),
            treatments = treatments,
            dependencies = dependencies,
        )
    }
}
