plugins {
    kotlin("plugin.spring")
    id(Plugins.spring_boot)
    id(Plugins.gatling)
}

dependencies {
    api(project(":appointment-core"))
    api(project(":appointment-event"))
    api(project(":appointment-solver"))

    implementation(Libs.springBootStarter("web"))
    implementation(Libs.springBootStarter("validation"))
    implementation(Libs.springBootStarter("security"))
    // Spring MVC suspend 함수 지원에 reactor-core 필요 (CoroutinesUtils 의존)
    implementation(Libs.kotlinx_coroutines_reactor)
    implementation(Libs.exposed_jdbc)
    implementation(Libs.exposed_spring_boot4_starter)
    implementation(Libs.bluetape4k_exposed_jdbc)

    // Jackson 3
    implementation(Libs.bluetape4k_jackson3)
    implementation(Libs.jackson3_module_kotlin)
    implementation(Libs.jackson3_module_blackbird)

    // JWT
    implementation(Libs.jjwt_api)
    runtimeOnly(Libs.jjwt_impl)
    runtimeOnly(Libs.jjwt_jackson)

    // OpenAPI / Swagger
    implementation(Libs.springdoc_openapi_starter_webmvc_ui)

    // Flyway (spring-boot-flyway: Spring Boot 4.x에서 FlywayAutoConfiguration이 별도 모듈로 분리됨)
    implementation(Libs.springBoot("flyway"))
    implementation(Libs.flyway_core)
    runtimeOnly(Libs.flyway_database_postgresql)
    runtimeOnly(Libs.flyway_mysql)

    // Database drivers
    runtimeOnly(Libs.h2_v2)
    runtimeOnly(Libs.postgresql_driver)
    runtimeOnly(Libs.mysql_connector_j)

    testImplementation(Libs.springBootStarter("test"))
    testImplementation(Libs.bluetape4k_junit5)
    testImplementation(Libs.kluent)
    testImplementation(Libs.exposed_migration_jdbc)

    // Testcontainers
    testImplementation(Libs.bluetape4k_testcontainers)
    testImplementation(Libs.testcontainers)
    testImplementation(Libs.testcontainers_junit_jupiter)
    testImplementation(Libs.testcontainers_postgresql)
    testImplementation(Libs.testcontainers_mysql)

    // Gatling
    gatling(Libs.gatling_charts_highcharts)
    gatling(Libs.gatling_http_java)
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
