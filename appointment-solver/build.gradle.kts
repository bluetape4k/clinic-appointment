dependencies {
    api(project(":appointment-core"))

    // Timefold Solver
    api(libs.timefold.solver.core)
    implementation(libs.timefold.solver.benchmark)

    implementation(libs.bluetape4k.exposed.jdbc)
    implementation(libs.exposed.jdbc)

    testImplementation(libs.timefold.solver.test)
    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.kluent)
    testImplementation(libs.h2.v2)
}
