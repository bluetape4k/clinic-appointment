plugins {
    alias(libs.plugins.exposed)
    kotlin("plugin.spring")
}

dependencies {
    api(project(":appointment-core"))
    implementation(project(":appointment-event"))

    implementation(libs.bluetape4k.kafka4)
    api(libs.kafka4.clients)
    api(libs.spring.kafka4)
    implementation(libs.jackson3.module.kotlin)
    implementation(libs.jackson3.databind)
    api(libs.micrometer.core)
    implementation(libs.kotlinx.coroutines.core)
    api(libs.jetbrains.exposed.jdbc)
    implementation(libs.exposed.jdbc)

    compileOnlyApi("org.springframework.boot:spring-boot-autoconfigure")
    compileOnly("org.springframework.boot:spring-boot-actuator")
    compileOnlyApi("org.springframework.boot:spring-boot-health")
    compileOnlyApi("org.springframework.boot:spring-boot-sql")
    compileOnlyApi("org.springframework:spring-context")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-actuator")
    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.h2.v2)
    testImplementation(libs.spring.kafka4.test)
    testImplementation(libs.bluetape4k.testcontainers)
    testImplementation(libs.postgresql.driver)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation("org.testcontainers:testcontainers-kafka")
}
