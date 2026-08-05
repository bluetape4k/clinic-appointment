plugins {
    alias(libs.plugins.exposed)
    kotlin("plugin.spring")
}

dependencies {
    api(project(":appointment-event"))

    implementation(libs.bluetape4k.kafka4)
    implementation(libs.kafka4.clients)
    implementation(libs.spring.kafka4)
    implementation(libs.jackson3.module.kotlin)
    implementation(libs.jackson3.databind)
    implementation(libs.micrometer.core)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.jetbrains.exposed.jdbc)
    implementation(libs.exposed.jdbc)

    compileOnly("org.springframework.boot:spring-boot-autoconfigure")
    compileOnly("org.springframework.boot:spring-boot-actuator")
    compileOnly("org.springframework.boot:spring-boot-health")
    compileOnly("org.springframework.boot:spring-boot-sql")
    compileOnly("org.springframework:spring-context")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-actuator")
    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.h2.v2)
    testImplementation(libs.spring.kafka4.test)
    testImplementation(libs.bluetape4k.testcontainers)
    testImplementation("org.testcontainers:testcontainers-kafka")
}
