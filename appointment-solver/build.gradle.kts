dependencyManagement {
    imports {
        // 예제의 Timefold 재정의는 jaxb 등 전이 의존성에도 같은 버전을 적용한다.
        mavenBom(libs.timefold.solver.bom.get().toString())
    }
}

dependencies {
    api(project(":appointment-core"))

    // Timefold Solver
    api(libs.timefold.solver.core)
    implementation(libs.timefold.solver.benchmark)

    implementation(libs.exposed.jdbc)
    implementation(libs.jetbrains.exposed.jdbc)

    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.bluetape4k.testcontainers)
    testImplementation(libs.h2.v2)
    testImplementation(libs.postgresql.driver)
    testImplementation(libs.testcontainers.postgresql)
}

tasks.test {
    useJUnitPlatform { excludeTags("version-comparison") }
}

// #455 비교 실험은 일반 테스트의 실행 시간과 분리한다.
tasks.register<Test>("timefoldVersionComparison") {
    description = "동일 fixture, seed, step으로 Timefold 전환 결과를 검증한다."
    group = "verification"
    mustRunAfter(tasks.test)
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("version-comparison") }
    maxParallelForks = 1
    minHeapSize = "2g"
    maxHeapSize = "4g"
    systemProperty("junit.jupiter.execution.parallel.enabled", "false")
    systemProperty("issue455.timefoldVersion", libs.versions.timefold.solver.get())
    systemProperty("issue455.output", layout.buildDirectory.file("issue-455/comparison.csv").get().asFile.absolutePath)
    outputs.upToDateWhen { false }
}
