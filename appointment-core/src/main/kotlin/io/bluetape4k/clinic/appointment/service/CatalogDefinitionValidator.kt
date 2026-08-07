package io.bluetape4k.clinic.appointment.service

import io.bluetape4k.clinic.appointment.model.catalog.CatalogBomDependency
import io.bluetape4k.clinic.appointment.model.catalog.CatalogBomItem
import io.bluetape4k.clinic.appointment.model.catalog.InitialBookingRule
import io.bluetape4k.clinic.appointment.model.catalog.ProductCatalogDefinition
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.Locale

/**
 * hash 계산이나 영속화 전에 불변 catalog definition을 검증합니다.
 */
object CatalogDefinitionValidator {
    const val MAX_PAYLOAD_BYTES = 256 * 1024
    const val MAX_IDENTIFIER_LENGTH = 128
    const val MAX_NAME_LENGTH = 256
    const val MAX_CODE_LENGTH = 128
    const val MAX_BOM_ITEMS = 200
    const val MAX_CATALOG_DEPENDENCIES = 1_000
    const val MAX_REPEATS_PER_ITEM = 100
    const val MAX_EXPANDED_TREATMENTS = 2_000
    const val MAX_VALIDATION_GRAPH_EDGES = 2_980
    const val MAX_REQUIREMENT_VALUES = 64
    const val MAX_DURATION_MINUTES = 480
    const val MAX_INTERVAL_DAYS = 3_650

    private val safeIdentifier = Regex("[A-Za-z0-9][A-Za-z0-9._:-]*")

    /**
     * 모든 식별자, 경계, 간격, DAG invariant가 유효하면 [definition]을 반환합니다.
     */
    fun validate(definition: ProductCatalogDefinition): ProductCatalogDefinition {
        require(definition.tenantGroupId > 0L) { "tenantGroupId must be positive" }
        require(definition.clinicId > 0L) { "clinicId must be positive" }
        validateIdentifier("sourceAuthority", definition.sourceAuthority)
        validateIdentifier("productId", definition.productId)
        require(definition.catalogVersion > 0L) { "catalogVersion must be positive" }
        validateName("productName", definition.productName)
        require(definition.schemaVersion > 0) { "schemaVersion must be positive" }
        require(definition.items.isNotEmpty()) { "items must not be empty" }
        require(definition.items.size <= MAX_BOM_ITEMS) {
            "items must contain at most $MAX_BOM_ITEMS values"
        }
        require(definition.dependencies.size <= MAX_CATALOG_DEPENDENCIES) {
            "dependencies must contain at most $MAX_CATALOG_DEPENDENCIES values"
        }

        val itemsById = LinkedHashMap<String, CatalogBomItem>(definition.items.size)
        definition.items.forEach { item ->
            validateItem(item)
            require(itemsById.put(item.bomItemId, item) == null) {
                "duplicate bomItemId(${item.bomItemId})"
            }
        }

        val expandedCount = definition.items.sumOf { item -> item.repeatCount.toLong() }
        require(expandedCount <= MAX_EXPANDED_TREATMENTS) {
            "expanded treatments must not exceed $MAX_EXPANDED_TREATMENTS"
        }

        validateInitialBookingRule(definition.initialBookingRule)
        validateDependenciesAndDag(definition.dependencies, itemsById)

        val payloadBytes = estimatePayloadBytes(definition)
        require(payloadBytes <= MAX_PAYLOAD_BYTES) {
            "catalog payload must not exceed $MAX_PAYLOAD_BYTES UTF-8 bytes"
        }
        return definition
    }

    private fun validateItem(item: CatalogBomItem) {
        validateIdentifier("bomItemId", item.bomItemId)
        validateName("representativeTreatmentName", item.representativeTreatmentName)
        require(item.repeatCount in 1..MAX_REPEATS_PER_ITEM) {
            "repeatCount must be between 1 and $MAX_REPEATS_PER_ITEM"
        }
        require(item.durationMinutes in 1..MAX_DURATION_MINUTES) {
            "durationMinutes must be between 1 and $MAX_DURATION_MINUTES"
        }
        validateOptionalIntervals(
            item.minimumIntervalDays,
            item.preferredIntervalDays,
            item.maximumIntervalDays,
            "item(${item.bomItemId})",
        )
        validateBoundedCodes("detailedTreatmentCodes", item.detailedTreatmentCodes)
        validateBoundedCodes("practitionerQualifications", item.practitionerQualifications)
        validateBoundedCodes("equipmentTypes", item.equipmentTypes)
        validateBoundedCodes("roomTypes", item.roomTypes)
    }

    private fun validateInitialBookingRule(rule: InitialBookingRule?) {
        when (rule) {
            null -> Unit
            is InitialBookingRule.WithinDaysAfterPurchase ->
                require(rule.maximumDays in 1..MAX_INTERVAL_DAYS) {
                    "initial booking maximumDays must be between 1 and $MAX_INTERVAL_DAYS"
                }
        }
    }

    private fun validateDependenciesAndDag(
        dependencies: List<CatalogBomDependency>,
        itemsById: Map<String, CatalogBomItem>,
    ) {
        val nodes = itemsById.values.flatMap { item ->
            (1..item.repeatCount).map { sequenceNo -> Occurrence(item.bomItemId, sequenceNo) }
        }
        val edges = LinkedHashSet<Edge>()

        itemsById.values.forEach { item ->
            for (sequenceNo in 1 until item.repeatCount) {
                edges += Edge(
                    Occurrence(item.bomItemId, sequenceNo),
                    Occurrence(item.bomItemId, sequenceNo + 1),
                )
            }
        }

        dependencies.forEach { dependency ->
            validateIdentifier("predecessorBomItemId", dependency.predecessorBomItemId)
            validateIdentifier("successorBomItemId", dependency.successorBomItemId)
            val predecessor = itemsById[dependency.predecessorBomItemId]
            requireNotNull(predecessor) {
                "unknown predecessorBomItemId(${dependency.predecessorBomItemId})"
            }
            val successor = itemsById[dependency.successorBomItemId]
            requireNotNull(successor) {
                "unknown successorBomItemId(${dependency.successorBomItemId})"
            }
            validateRequiredIntervals(
                dependency.minimumIntervalDays,
                dependency.preferredIntervalDays,
                dependency.maximumIntervalDays,
                "dependency(${dependency.predecessorBomItemId}->${dependency.successorBomItemId})",
            )

            val predecessorSequence = dependency.predecessorSequenceNo ?: predecessor.repeatCount
            val successorSequence = dependency.successorSequenceNo ?: 1
            require(predecessorSequence in 1..predecessor.repeatCount) {
                "predecessorSequenceNo($predecessorSequence) exceeds repeatCount(${predecessor.repeatCount})"
            }
            require(successorSequence in 1..successor.repeatCount) {
                "successorSequenceNo($successorSequence) exceeds repeatCount(${successor.repeatCount})"
            }

            val edge = Edge(
                Occurrence(predecessor.bomItemId, predecessorSequence),
                Occurrence(successor.bomItemId, successorSequence),
            )
            require(edges.add(edge)) { "duplicate materialized dependency($edge)" }
        }

        require(edges.size <= MAX_VALIDATION_GRAPH_EDGES) {
            "dependency validation graph must not exceed $MAX_VALIDATION_GRAPH_EDGES edges"
        }
        require(isAcyclic(nodes, edges)) { "catalog dependency graph must be acyclic" }
    }

    private fun isAcyclic(
        nodes: List<Occurrence>,
        edges: Set<Edge>,
    ): Boolean {
        val indegree = nodes.associateWithTo(LinkedHashMap()) { 0 }
        val outgoing = nodes.associateWithTo(LinkedHashMap()) { mutableListOf<Occurrence>() }
        edges.forEach { edge ->
            outgoing.getValue(edge.predecessor) += edge.successor
            indegree[edge.successor] = indegree.getValue(edge.successor) + 1
        }

        val ready = ArrayDeque(indegree.filterValues { it == 0 }.keys)
        var visited = 0
        while (ready.isNotEmpty()) {
            val current = ready.removeFirst()
            visited++
            outgoing.getValue(current).forEach { successor ->
                val remaining = indegree.getValue(successor) - 1
                indegree[successor] = remaining
                if (remaining == 0) {
                    ready.addLast(successor)
                }
            }
        }
        return visited == nodes.size
    }

    private fun validateOptionalIntervals(
        minimum: Int?,
        preferred: Int?,
        maximum: Int?,
        owner: String,
    ) {
        listOfNotNull(minimum, preferred, maximum).forEach { interval ->
            require(interval in 0..MAX_INTERVAL_DAYS) {
                "$owner interval must be between 0 and $MAX_INTERVAL_DAYS"
            }
        }
        if (minimum != null && preferred != null) {
            require(minimum <= preferred) { "$owner minimum interval must not exceed preferred interval" }
        }
        if (preferred != null && maximum != null) {
            require(preferred <= maximum) { "$owner preferred interval must not exceed maximum interval" }
        }
        if (minimum != null && maximum != null) {
            require(minimum <= maximum) { "$owner minimum interval must not exceed maximum interval" }
        }
    }

    private fun validateRequiredIntervals(
        minimum: Int,
        preferred: Int,
        maximum: Int,
        owner: String,
    ) {
        validateOptionalIntervals(minimum, preferred, maximum, owner)
    }

    private fun validateIdentifier(
        fieldName: String,
        value: String,
    ) {
        require(value.isNotBlank()) { "$fieldName must not be blank" }
        require(value.length <= MAX_IDENTIFIER_LENGTH) {
            "$fieldName must not exceed $MAX_IDENTIFIER_LENGTH characters"
        }
        require(!value.hasControlCharacter()) { "$fieldName must not contain control characters" }
        require(safeIdentifier.matches(value)) { "$fieldName contains unsafe characters" }
    }

    private fun validateName(
        fieldName: String,
        value: String,
    ) {
        require(value.isNotBlank()) { "$fieldName must not be blank" }
        require(value.length <= MAX_NAME_LENGTH) {
            "$fieldName must not exceed $MAX_NAME_LENGTH characters"
        }
        require(!value.hasControlCharacter()) { "$fieldName must not contain control characters" }
    }

    private fun validateBoundedCodes(
        fieldName: String,
        values: List<String>,
    ) {
        require(values.size <= MAX_REQUIREMENT_VALUES) {
            "$fieldName must contain at most $MAX_REQUIREMENT_VALUES values"
        }
        values.forEach { value ->
            require(value.isNotBlank()) { "$fieldName must not contain blank values" }
            require(value.length <= MAX_CODE_LENGTH) {
                "$fieldName values must not exceed $MAX_CODE_LENGTH characters"
            }
            require(!value.hasControlCharacter()) { "$fieldName must not contain control characters" }
            require(safeIdentifier.matches(value)) { "$fieldName contains an unsafe value($value)" }
        }
        val normalized = values.map { value -> value.trim().lowercase(Locale.ROOT) }
        require(normalized.distinct().size == normalized.size) {
            "$fieldName must not contain duplicate normalized values"
        }
    }

    private fun estimatePayloadBytes(definition: ProductCatalogDefinition): Long {
        var bytes = 0L

        fun add(name: String, value: Any?) {
            // 보수적인 JSON 형태의 표현 크기를 센다. property 경로와 배열 index는
            // wire payload로 전송되지 않으므로 이를 포함하면 실제 256 KiB 경계보다 훨씬
            // 전에 유효한 compact graph를 거부하게 된다.
            bytes += jsonStringBytes(name) + 2L
            bytes += when (value) {
                null -> 4L
                is Number, is Boolean -> value.toString().toByteArray(StandardCharsets.UTF_8).size.toLong()
                else -> jsonStringBytes(value.toString())
            }
        }

        add("tenantGroupId", definition.tenantGroupId)
        add("clinicId", definition.clinicId)
        add("sourceAuthority", definition.sourceAuthority)
        add("productId", definition.productId)
        add("catalogVersion", definition.catalogVersion)
        add("productName", definition.productName)
        add("schemaVersion", definition.schemaVersion)
        add("sourceUpdatedAt", definition.sourceUpdatedAt)
        add("status", definition.status)
        add("items.size", definition.items.size)
        definition.items.forEach { item ->
            add("bomItemId", item.bomItemId)
            add("representativeTreatmentName", item.representativeTreatmentName)
            add("repeatCount", item.repeatCount)
            add("durationMinutes", item.durationMinutes)
            add("minimumIntervalDays", item.minimumIntervalDays)
            add("preferredIntervalDays", item.preferredIntervalDays)
            add("maximumIntervalDays", item.maximumIntervalDays)
            addList("detailedTreatmentCodes", item.detailedTreatmentCodes, ::add)
            addList("practitionerQualifications", item.practitionerQualifications, ::add)
            addList("equipmentTypes", item.equipmentTypes, ::add)
            addList("roomTypes", item.roomTypes, ::add)
        }
        add("dependencies.size", definition.dependencies.size)
        definition.dependencies.forEach { dependency ->
            add("predecessorBomItemId", dependency.predecessorBomItemId)
            add("predecessorSequenceNo", dependency.predecessorSequenceNo)
            add("successorBomItemId", dependency.successorBomItemId)
            add("successorSequenceNo", dependency.successorSequenceNo)
            add("minimumIntervalDays", dependency.minimumIntervalDays)
            add("preferredIntervalDays", dependency.preferredIntervalDays)
            add("maximumIntervalDays", dependency.maximumIntervalDays)
        }
        when (val rule = definition.initialBookingRule) {
            null -> add("initialBookingRule.type", null)
            is InitialBookingRule.WithinDaysAfterPurchase -> {
                add("initialBookingRule.type", "WITHIN_DAYS_AFTER_PURCHASE")
                add("initialBookingRule.maximumDays", rule.maximumDays)
            }
        }
        return bytes
    }

    private fun jsonStringBytes(value: String): Long {
        var escapedBytes = value.toByteArray(StandardCharsets.UTF_8).size.toLong() + 2L
        value.forEach { character ->
            escapedBytes += when (character) {
                '"', '\\', '\b', '\t', '\n', '\u000C', '\r' -> 1L
                in '\u0000'..'\u001F' -> 5L
                else -> 0L
            }
        }
        return escapedBytes
    }

    private fun addList(
        name: String,
        values: List<String>,
        add: (String, Any?) -> Unit,
    ) {
        add("$name.size", values.size)
        values.forEach { value -> add(name, value) }
    }

    private fun String.hasControlCharacter(): Boolean = any(Char::isISOControl)

    private data class Occurrence(
        val bomItemId: String,
        val sequenceNo: Int,
    )

    private data class Edge(
        val predecessor: Occurrence,
        val successor: Occurrence,
    )
}
