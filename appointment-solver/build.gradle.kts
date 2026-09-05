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
