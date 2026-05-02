plugins {
    kotlin("plugin.spring")
}

dependencies {
    api(project(":appointment-core"))
    api(project(":appointment-event"))

    implementation(libs.exposed.jdbc)
    implementation(libs.bluetape4k.exposed.jdbc)
    implementation(libs.exposed.migration.jdbc)
    implementation("org.springframework.boot:spring-boot-starter-web")

    // HA: Leader election for scheduler throttling
    implementation(libs.bluetape4k.leader)
    implementation(libs.bluetape4k.lettuce)
    implementation(libs.lettuce.core)

    // Resilience4j: CircuitBreaker, Retry, Bulkhead
    implementation(libs.bluetape4k.resilience4j)
    implementation(libs.resilience4j.circuitbreaker)
    implementation(libs.resilience4j.retry)
    implementation(libs.resilience4j.bulkhead)
    implementation(libs.resilience4j.kotlin)

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.kluent)
    testImplementation(libs.mockk)

    runtimeOnly(libs.h2.v2)
}
