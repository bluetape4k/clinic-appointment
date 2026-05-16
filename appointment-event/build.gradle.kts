plugins {
    kotlin("plugin.spring")
}

dependencies {
    api(project(":appointment-core"))

    compileOnly("org.springframework.boot:spring-boot-autoconfigure")
    compileOnly("org.springframework:spring-context")

    api(libs.jetbrains.exposed.core)
    api(libs.jetbrains.exposed.jdbc)
    api(libs.jetbrains.exposed.r2dbc)
    api(libs.jetbrains.exposed.java.time)

    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.exposed.r2dbc.tests)
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(libs.h2.v2)
    testImplementation(libs.r2dbc.h2)
    testImplementation(libs.kotlinx.coroutines.test)
}
