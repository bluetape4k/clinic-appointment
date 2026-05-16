dependencies {
    api(project(":appointment-core"))

    // Timefold Solver
    api(libs.timefold.solver.core)
    implementation(libs.timefold.solver.benchmark)

    implementation(libs.exposed.jdbc)
    implementation(libs.jetbrains.exposed.jdbc)

    testImplementation(libs.timefold.solver.test)
    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.h2.v2)
}
