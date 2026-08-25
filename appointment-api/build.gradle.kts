import org.gradle.api.tasks.JavaExec

plugins {
    alias(libs.plugins.exposed)
    kotlin("plugin.spring")
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.gatling)
}

exposed {
    migrations {
        tablesPackage = "io.bluetape4k.clinic.appointment.api.config"
        databaseUrl = "jdbc:h2:mem:appointment-api-migrations;DB_CLOSE_DELAY=-1;MODE=PostgreSQL"
        databaseUser = "sa"
        databasePassword = ""
    }
}

dependencies {
    api(project(":appointment-core"))
    api(project(":appointment-event"))
    implementation(project(":appointment-messaging"))
    implementation(libs.spring.kafka4)
    implementation(project(":appointment-notification"))
    api(project(":appointment-solver"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-security")

    // Cache: Lettuce NearCache (Caffeine local + Redis remote)
    implementation(libs.bluetape4k.cache.lettuce)
    implementation(libs.bluetape4k.io)
    implementation(libs.bluetape4k.lettuce)
    implementation(libs.lettuce.core)
    // NearCache 기본 코덱(LZ4 + Fory)이 optional 의존성이므로 명시적 추가 필요.
    // bluetape4k uses the at.yawk fork; keeping org.lz4:lz4-java here creates a
    // Gradle capability conflict with the messaging stack's LZ4 provider.
    implementation("at.yawk.lz4:lz4-java:1.11.0")
    implementation(libs.fory.kotlin)
    // Spring MVC suspend 함수 지원에 reactor-core 필요 (CoroutinesUtils 의존)
    implementation(libs.kotlinx.coroutines.reactor)
    implementation(libs.jetbrains.exposed.jdbc)
    implementation(libs.jetbrains.exposed.spring.boot4.starter)
    // DDD aggregate event publisher는 Spring transaction 완료 경계에서만 신호를 전달한다.
    testImplementation("io.github.bluetape4k.exposed:bluetape4k-exposed-spring-boot-jdbc")
    // Issue #313 파일럿은 운영 경로가 아닌 JDBC Caffeine 스냅샷 계약 검증에서만 사용한다.
    testImplementation(libs.exposed.jdbc.caffeine)
    testImplementation(libs.exposed.jdbc.lettuce)
    implementation(libs.exposed.jdbc)

    // Jackson 3
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
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.jetbrains.exposed.migration.jdbc)

    // Testcontainers
    testImplementation(libs.bluetape4k.testcontainers)
    testImplementation(libs.testcontainers)
    testImplementation("org.testcontainers:testcontainers-kafka")
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.mysql)
    testImplementation(libs.testcontainers.toxiproxy)

    // Gatling
    gatling(project(":appointment-core"))
    gatling(project(":appointment-event"))
    gatling(libs.gatling.charts.highcharts)
    gatling(libs.gatling.http.java)
    gatling(libs.bluetape4k.testcontainers)
    gatling(libs.testcontainers)
    gatling(libs.testcontainers.postgresql)
    gatling(libs.postgresql.driver)
    gatlingRuntimeOnly(libs.h2.v2)
}

// bluetape4k-junit5 1.12.1의 bounded-wait fixture는 Coroutines 1.11 ABI를 사용한다.
// Spring Boot BOM이 test runtime에 1.10.2를 강제하면 cancel$default 링크가 깨지므로,
// 이 모듈의 모든 구성에서 프로젝트가 선언한 Coroutines BOM 버전을 유지한다.
configurations.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlinx" && requested.name.startsWith("kotlinx-coroutines-")) {
            useVersion(libs.versions.kotlinx.coroutines.get())
            because("bluetape4k-junit5 bounded-wait fixture와 Coroutines ABI를 일치시킨다")
        }
    }
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

tasks.register<JavaExec>("jdbcCaffeineEffectivePolicyPilotBenchmark") {
    group = "benchmark"
    description = "Issue #313 JDBC Caffeine effective policy pilot benchmark"
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("io.bluetape4k.clinic.appointment.api.config.JdbcCaffeineEffectivePolicyPilotBenchmark")

    val output = providers.gradleProperty("issue313JdbcCaffeineBenchmarkOutput").orElse(
        layout.buildDirectory.file("reports/issue-313/jdbc-caffeine-pilot.json")
            .map { it.asFile.absolutePath },
    )
    systemProperty("issue313.jdbcCaffeineBenchmark.output", output.get())
    systemProperty(
        "issue313.jdbcCaffeineBenchmark.warmupRounds",
        providers.gradleProperty("issue313JdbcCaffeineBenchmarkWarmupRounds").orElse("5").get(),
    )
    systemProperty(
        "issue313.jdbcCaffeineBenchmark.measurementRounds",
        providers.gradleProperty("issue313JdbcCaffeineBenchmarkMeasurementRounds").orElse("20").get(),
    )
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
