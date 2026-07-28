package io.bluetape4k.clinic.appointment.service

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.clinic.appointment.model.plan.ExecutionDependency
import io.bluetape4k.clinic.appointment.model.plan.ExecutionDependencyType
import org.junit.jupiter.api.Test

class PlanDirtySetResolverTest {

    private val resolver = PlanDirtySetResolver()

    @Test
    fun `변경 항목에서 전이적으로 연결된 BLOCKING 후속 항목만 dirty set에 포함한다`() {
        val dependencies = listOf(
            ExecutionDependency("a", "b", ExecutionDependencyType.BLOCKING),
            ExecutionDependency("b", "c", ExecutionDependencyType.BLOCKING),
            ExecutionDependency("a", "independent", ExecutionDependencyType.NON_BLOCKING),
            ExecutionDependency("independent", "downstream", ExecutionDependencyType.BLOCKING),
        )

        resolver.resolve(changedTreatmentKeys = setOf("a"), dependencies = dependencies) shouldBeEqualTo
            setOf("a", "b", "c")
    }

    @Test
    fun `여러 시작점과 cycle에도 각 항목을 한 번만 반환한다`() {
        val dependencies = listOf(
            ExecutionDependency("a", "b", ExecutionDependencyType.BLOCKING),
            ExecutionDependency("b", "a", ExecutionDependencyType.BLOCKING),
            ExecutionDependency("x", "y", ExecutionDependencyType.BLOCKING),
        )

        resolver.resolve(setOf("a", "x"), dependencies) shouldBeEqualTo setOf("a", "b", "x", "y")
    }
}
