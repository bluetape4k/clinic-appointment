plugins {
    kotlin("plugin.spring")
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.gatling)
}

dependencies {
    api(project(":appointment-core"))
    api(project(":appointment-event"))
    api(project(":appointment-solver"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-security")
    // Spring MVC suspend 함수 지원에 reactor-core 필요 (CoroutinesUtils 의존)
    implementation(libs.kotlinx.coroutines.reactor)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.spring.boot4.starter)
    implementation(libs.bluetape4k.exposed.jdbc)

    // Jackson 3
    implementation(libs.bluetape4k.jackson3)
    implementation(libs.jackson3.module.kotlin)
    implementation(libs.jackson3.module.blackbird)

    // JWT
    implementation(libs.jjwt.api)
    runtimeOnly(libs.jjwt.impl)
    runtimeOnly(libs.jjwt.jackson)

    // OpenAPI / Swagger
    implementation(libs.springdoc.openapi.starter.webmvc.ui)

    // Flyway (spring-boot-flyway: Spring Boot 4.x에서 FlywayAutoConfiguration이 별도 모듈로 분리됨)
    implementation("org.springframework.boot:spring-boot-flyway")
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.database.postgresql)
    runtimeOnly(libs.flyway.mysql)

    // Database drivers
    runtimeOnly(libs.h2.v2)
    runtimeOnly(libs.postgresql.driver)
    runtimeOnly(libs.mysql.connector.j)

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.kluent)
    testImplementation(libs.exposed.migration.jdbc)

    // Testcontainers
    testImplementation(libs.bluetape4k.testcontainers)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.mysql)

    // Gatling
    gatling(libs.gatling.charts.highcharts)
    gatling(libs.gatling.http.java)
}

// spring.profiles.active 시스템 프로퍼티를 테스트 JVM에 전달 (multi-DB 테스트 지원)
tasks.withType<Test>().configureEach {
    val activeProfiles = System.getProperty("spring.profiles.active")
    if (activeProfiles != null) {
        systemProperty("spring.profiles.active", activeProfiles)
    }
}

// Gatling 런타임은 Java 21 기반이므로 Gatling 소스는 Java 21 타겟으로 컴파일
tasks.withType<JavaCompile>().configureEach {
    if (name.startsWith("compileGatling")) {
        options.release.set(21)
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    if (name.startsWith("compileGatling")) {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }
}

tasks.bootJar {
    enabled = true
}

tasks.jar {
    enabled = false
}

// Gatling 시뮬레이션 클래스 및 main() 진입점은 coverage 측정 대상에서 제외
kover {
    reports {
        filters {
            excludes {
                classes(
                    "io.bluetape4k.clinic.appointment.api.AppointmentApiApplicationKt",
                    "io.bluetape4k.clinic.appointment.api.*Simulation",
                    "io.bluetape4k.clinic.appointment.api.*Simulation\$*",
                )
            }
        }
    }
}
