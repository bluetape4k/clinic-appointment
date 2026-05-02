plugins {
    kotlin("plugin.spring")
}

dependencies {
    api(project(":appointment-core"))

    compileOnly("org.springframework.boot:spring-boot-autoconfigure")
    compileOnly("org.springframework:spring-context")

    api(libs.exposed.core)
    api(libs.exposed.jdbc)
    api(libs.exposed.r2dbc)
    api(libs.exposed.java.time)

    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.bluetape4k.exposed.r2dbc.tests)
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(libs.h2.v2)
    testImplementation(libs.r2dbc.h2)
    testImplementation(libs.kotlinx.coroutines.test)
}
