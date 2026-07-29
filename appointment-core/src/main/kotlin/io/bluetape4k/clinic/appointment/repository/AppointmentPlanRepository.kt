package io.bluetape4k.clinic.appointment.repository

import io.bluetape4k.clinic.appointment.model.dto.AppointmentPlanAggregateRecord
import io.bluetape4k.clinic.appointment.model.dto.AppointmentPlanRecord
import io.bluetape4k.clinic.appointment.model.dto.PlannedTreatmentKey
import io.bluetape4k.clinic.appointment.model.dto.PlannedTreatmentRecord
import io.bluetape4k.clinic.appointment.model.dto.TreatmentDependencyRecord
import io.bluetape4k.clinic.appointment.model.tables.AppointmentPlans
import io.bluetape4k.clinic.appointment.model.tables.PlannedTreatments
import io.bluetape4k.clinic.appointment.model.tables.ProductCatalogProjections
import io.bluetape4k.clinic.appointment.model.tables.TreatmentDependencies
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll

/**
 * caller가 소유한 transaction 안에서 완전한 예약 계획 aggregate를 저장하고 읽습니다.
 *
 * 이 repository는 transaction을 열거나 commit하지 않습니다. caller는 모든 작업을 Exposed
 * `transaction {}` 안에서 실행해야 하며, 그래야 plan, treatment, dependency write가 함께
 * 보이거나 함께 rollback됩니다.
 *
 * storage 경계에서 scope check를 의도적으로 반복합니다. 인증된 테넌트와 병원 context는
 * caller가 전달해야 하며, 식별자 하나만으로 cross-tenant 조회를 허용하지 않습니다.
 */
class AppointmentPlanRepository {

    /**
     * command authorization에 필요한 Plan root만 정확한 tenant·clinic scope로 조회합니다.
     *
     * 시술 BOM aggregate를 조립하지 않으므로 환자 소유권과 scope를 먼저 확인하는
     * application 경계에서 사용합니다. 정책·revision·시술 조립은 검증 이후 별도
     * repository에서 수행해야 합니다.
     */
    fun findPlanByIdAndTenantClinic(
        id: Long,
        tenantGroupId: Long,
        clinicId: Long,
    ): AppointmentPlanRecord? =
        AppointmentPlans
            .selectAll()
            .where {
                (AppointmentPlans.id eq id) and
                    (AppointmentPlans.tenantGroupId eq tenantGroupId) and
                    (AppointmentPlans.clinicId eq clinicId)
            }
            .singleOrNull()
            ?.toAppointmentPlanRecord()

    /**
     * 계획, 시술 의무, 물리화된 dependency edge를 atomic하게 insert합니다.
     *
     * 이 메서드는 참조한 catalog projection이 계획 스냅샷과 같은 테넌트, 병원, source
     * authority, 상품, 버전, 정규 payload hash, 표시명을 가지는지 검증합니다. 그런 뒤
     * edge를 insert하기 전에 논리 시술 키를 생성된 데이터베이스 식별자로 해석합니다.
     *
     * 환자 ciphertext와 fingerprint는 저장하지만 절대 로그에 남기지 않습니다.
     *
     * @param aggregate 아직 영속화되지 않은 aggregate입니다. plan과 child 식별자는 `null`일
     * 수 있지만, 시술 논리 키는 이미 유일해야 합니다.
     * @return 결정적 BOM, sequence, dependency 순서로 다시 읽은 완전한 영속 aggregate입니다.
     * @throws IllegalArgumentException 필수 보안 참조가 blank이거나, 시술 목록이 비어
     * 있거나 중복되거나, 카탈로그 출처가 다르거나, dependency가 알 수 없는 시술을
     * 참조할 때 발생합니다.
     * @throws IllegalStateException storage가 insert된 모든 시술을 반환하지 않거나,
     * insert된 aggregate를 다시 읽을 수 없을 때 발생합니다.
     */
    fun saveAggregate(aggregate: AppointmentPlanAggregateRecord): AppointmentPlanAggregateRecord {
        val plan = aggregate.plan
        require(plan.patientReferenceCiphertext.isNotBlank()) { "patientReferenceCiphertext must not be blank" }
        require(plan.patientReferenceKeyId.isNotBlank()) { "patientReferenceKeyId must not be blank" }
        require(plan.patientReferenceFingerprint.isNotBlank()) { "patientReferenceFingerprint must not be blank" }
        require(aggregate.treatments.isNotEmpty()) { "treatments must not be empty" }
        require(aggregate.treatments.map(PlannedTreatmentRecord::key).distinct().size == aggregate.treatments.size) {
            "treatments must have unique logical keys"
        }

        val catalogScope = ProductCatalogProjections
            .selectAll()
            .where { ProductCatalogProjections.id eq plan.catalogProjectionId }
            .singleOrNull()
        requireNotNull(catalogScope) { "catalog projection does not exist" }
        require(catalogScope[ProductCatalogProjections.tenantGroupId].value == plan.tenantGroupId) {
            "catalog tenant does not match plan tenant"
        }
        require(catalogScope[ProductCatalogProjections.clinicId].value == plan.clinicId) {
            "catalog clinic does not match plan clinic"
        }
        require(catalogScope[ProductCatalogProjections.sourceAuthority] == plan.catalogSourceAuthority) {
            "catalog source authority does not match plan catalogSourceAuthority"
        }
        require(catalogScope[ProductCatalogProjections.productId] == plan.productId) {
            "catalog product does not match plan product"
        }
        require(catalogScope[ProductCatalogProjections.catalogVersion] == plan.catalogVersion) {
            "catalog version does not match plan catalogVersion"
        }
        require(catalogScope[ProductCatalogProjections.payloadHash] == plan.catalogPayloadHash) {
            "catalog payload hash does not match plan catalogPayloadHash"
        }
        require(catalogScope[ProductCatalogProjections.productName] == plan.productName) {
            "catalog product name does not match plan productName"
        }

        val encodedPreference = encodeBookingPreference(plan.bookingPreference)
        val planId = AppointmentPlans.insertAndGetId {
            it[tenantGroupId] = plan.tenantGroupId
            it[clinicId] = plan.clinicId
            it[catalogProjectionId] = plan.catalogProjectionId
            it[sourcePurchaseAuthority] = plan.sourcePurchaseAuthority
            it[sourcePurchaseId] = plan.sourcePurchaseId
            it[patientReferenceCiphertext] = plan.patientReferenceCiphertext
            it[patientReferenceKeyId] = plan.patientReferenceKeyId
            it[patientReferenceFingerprint] = plan.patientReferenceFingerprint
            it[catalogSourceAuthority] = plan.catalogSourceAuthority
            it[productId] = plan.productId
            it[catalogVersion] = plan.catalogVersion
            it[catalogPayloadHash] = plan.catalogPayloadHash
            it[productName] = plan.productName
            it[bookingPreferenceType] = encodedPreference.first
            it[bookingPreferencePayload] = encodedPreference.second
            it[status] = plan.status
        }.value

        val insertedTreatments = PlannedTreatments.batchInsert(aggregate.treatments) { treatment ->
            this[PlannedTreatments.planId] = planId
            this[PlannedTreatments.bomItemId] = treatment.bomItemId
            this[PlannedTreatments.sequenceNo] = treatment.sequenceNo
            this[PlannedTreatments.bomOrder] = treatment.bomOrder
            this[PlannedTreatments.representativeTreatmentName] = treatment.representativeTreatmentName
            this[PlannedTreatments.detailedTreatmentCodesJson] = encodeStringList(treatment.detailedTreatmentCodes)
            this[PlannedTreatments.durationMinutes] = treatment.durationMinutes
            this[PlannedTreatments.minimumIntervalDays] = treatment.minimumIntervalDays
            this[PlannedTreatments.preferredIntervalDays] = treatment.preferredIntervalDays
            this[PlannedTreatments.maximumIntervalDays] = treatment.maximumIntervalDays
            this[PlannedTreatments.practitionerQualificationsJson] =
                encodeStringList(treatment.practitionerQualifications)
            this[PlannedTreatments.equipmentTypesJson] = encodeStringList(treatment.equipmentTypes)
            this[PlannedTreatments.roomTypesJson] = encodeStringList(treatment.roomTypes)
            this[PlannedTreatments.earliestStartAt] = treatment.earliestStartAt
            this[PlannedTreatments.latestStartAt] = treatment.latestStartAt
            this[PlannedTreatments.status] = treatment.status
        }
        require(insertedTreatments.size == aggregate.treatments.size) {
            "batch insert did not return every planned treatment"
        }
        val treatmentIds = aggregate.treatments
            .zip(insertedTreatments)
            .associateTo(LinkedHashMap(aggregate.treatments.size)) { (treatment, insertedRow) ->
                treatment.key to insertedRow[PlannedTreatments.id].value
            }

        TreatmentDependencies.batchInsert(
            aggregate.dependencies,
            shouldReturnGeneratedValues = false,
        ) { dependency ->
            val predecessorId = requireNotNull(treatmentIds[dependency.predecessor]) {
                "unknown predecessor treatment(${dependency.predecessor})"
            }
            val successorId = requireNotNull(treatmentIds[dependency.successor]) {
                "unknown successor treatment(${dependency.successor})"
            }
            this[TreatmentDependencies.planId] = planId
            this[TreatmentDependencies.predecessorTreatmentId] = predecessorId
            this[TreatmentDependencies.successorTreatmentId] = successorId
            this[TreatmentDependencies.minimumIntervalDays] = dependency.minimumIntervalDays
            this[TreatmentDependencies.preferredIntervalDays] = dependency.preferredIntervalDays
            this[TreatmentDependencies.maximumIntervalDays] = dependency.maximumIntervalDays
        }

        return requireNotNull(findByIdAndTenantClinic(planId, plan.tenantGroupId, plan.clinicId))
    }

    /**
     * 정확한 테넌트와 병원 scope 안에서만 계획을 조회합니다.
     *
     * @param id 양수 영속 계획 식별자입니다.
     * @param tenantGroupId 인증된 양수 SaaS 테넌트 경계입니다.
     * @param clinicId 인증된 양수 병원 경계입니다.
     * @return 세 식별자가 모두 일치하면 완전한 aggregate를 반환하고, 아니면 `null`을
     * 반환합니다. 다른 scope의 계획은 의도적으로 존재하지 않는 계획과 구분하지 않습니다.
     */
    fun findByIdAndTenantClinic(
        id: Long,
        tenantGroupId: Long,
        clinicId: Long,
    ): AppointmentPlanAggregateRecord? =
        AppointmentPlans
            .selectAll()
            .where {
                (AppointmentPlans.id eq id) and
                    (AppointmentPlans.tenantGroupId eq tenantGroupId) and
                    (AppointmentPlans.clinicId eq clinicId)
            }
            .singleOrNull()
            ?.let(::mapAggregate)

    /**
     * 정확한 테넌트와 병원 scope 안에서만 원본 구매 기준으로 계획을 조회합니다.
     *
     * 이 조회는 구매 이벤트 replay의 idempotency read path입니다. 데이터베이스 유일성
     * 계약은 같은 authority, purchase, tenant, clinic scope를 사용해야 합니다. 어떤
     * 필드도 단독으로 전역 유일하지 않습니다.
     *
     * @param sourcePurchaseAuthority 구매를 소유한 서비스의 안정적인 식별자입니다.
     * @param sourcePurchaseId 해당 authority 안의 안정적인 구매 식별자입니다.
     * @param tenantGroupId 인증된 양수 SaaS 테넌트 경계입니다.
     * @param clinicId 인증된 양수 병원 경계입니다.
     * @return 정확히 scope가 일치하는 원본 구매의 기존 aggregate, 또는 `null`입니다.
     */
    fun findBySourcePurchaseAndTenantClinic(
        sourcePurchaseAuthority: String,
        sourcePurchaseId: String,
        tenantGroupId: Long,
        clinicId: Long,
    ): AppointmentPlanAggregateRecord? =
        AppointmentPlans
            .selectAll()
            .where {
                (AppointmentPlans.tenantGroupId eq tenantGroupId) and
                    (AppointmentPlans.clinicId eq clinicId) and
                    (AppointmentPlans.sourcePurchaseAuthority eq sourcePurchaseAuthority) and
                    (AppointmentPlans.sourcePurchaseId eq sourcePurchaseId)
            }
            .singleOrNull()
            ?.let(::mapAggregate)

    /**
     * 현재 transaction 안에서 root row 하나를 완전한 aggregate로 재구성합니다.
     *
     * child row는 결정적으로 정렬합니다. caller와 snapshot test가 database 기본 정렬에
     * 의존하지 않게 하기 위함입니다.
     */
    private fun mapAggregate(row: org.jetbrains.exposed.v1.core.ResultRow): AppointmentPlanAggregateRecord {
        val plan = row.toAppointmentPlanRecord()
        val planId = requireNotNull(plan.id)
        val treatments = PlannedTreatments
            .selectAll()
            .where { PlannedTreatments.planId eq planId }
            .orderBy(PlannedTreatments.bomOrder to SortOrder.ASC, PlannedTreatments.sequenceNo to SortOrder.ASC)
            .map { treatmentRow -> treatmentRow.toPlannedTreatmentRecord() }
        val keysByTreatmentId = treatments.associate { treatment ->
            requireNotNull(treatment.id) to treatment.key
        }
        val dependencies = TreatmentDependencies
            .selectAll()
            .where { TreatmentDependencies.planId eq planId }
            .orderBy(
                TreatmentDependencies.predecessorTreatmentId to SortOrder.ASC,
                TreatmentDependencies.successorTreatmentId to SortOrder.ASC,
            )
            .map { dependencyRow -> dependencyRow.toTreatmentDependencyRecord(keysByTreatmentId) }
        return AppointmentPlanAggregateRecord(plan, treatments, dependencies)
    }
}
