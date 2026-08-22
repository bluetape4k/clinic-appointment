import org.gradle.api.tasks.JavaExec

plugins {
    alias(libs.plugins.exposed)
    kotlin("plugin.spring")
}

exposed {
    migrations {
        tablesPackage = "io.bluetape4k.clinic.appointment.notification"
        databaseUrl = "jdbc:h2:mem:appointment-notification-migrations;DB_CLOSE_DELAY=-1;MODE=PostgreSQL"
        databaseUser = "sa"
        databasePassword = ""
    }
}

dependencies {
    api(project(":appointment-core"))
    api(project(":appointment-event"))
    api(project(":appointment-messaging"))
    api(libs.kafka4.clients)
    api(libs.spring.kafka4)
    api(libs.micrometer.core)

    api(libs.jetbrains.exposed.jdbc)
    implementation(libs.exposed.jdbc)
    implementation(libs.jetbrains.exposed.migration.jdbc)
    implementation("org.springframework.boot:spring-boot-starter-web")

    // Reminder recovery trigger leader election (delivery correctness uses DB lease/fencing)
    api(libs.bluetape4k.leader)
    api(libs.bluetape4k.leader.spring.boot)
    implementation(libs.bluetape4k.leader.micrometer)
    implementation(libs.bluetape4k.lettuce)
    api(libs.lettuce.core)

    // Resilience4j: CircuitBreaker, Retry, Bulkhead
    implementation(libs.bluetape4k.resilience4j)
    api(libs.resilience4j.circuitbreaker)
    api(libs.resilience4j.retry)
    api(libs.resilience4j.bulkhead)
    implementation(libs.resilience4j.kotlin)

    compileOnlyApi("org.springframework.boot:spring-boot-autoconfigure")
    compileOnlyApi("org.springframework:spring-context")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.bluetape4k.testcontainers)
    testImplementation(libs.mockk)

    runtimeOnly(libs.h2.v2)
}

private fun registerRedisAdmissionBenchmark(
    name: String,
    configuration: String,
    defaultOperations: String,
    defaultCardinalities: String,
    defaultChurnRates: String,
) = tasks.register<JavaExec>(name) {
    group = "benchmark"
    description = "Redis 8.8 notification outbox admission benchmark ($configuration)"
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("io.bluetape4k.clinic.appointment.notification.RedisNotificationAdmissionBenchmark")

    val output = providers.gradleProperty("redisAdmissionBenchmarkOutput").orElse(
        layout.buildDirectory.file("reports/redis-admission/$configuration/redis-notification-admission.json")
            .map { it.asFile.absolutePath },
    )
    systemProperty("redis.admission.benchmark.output", output.get())
    systemProperty("redis.admission.benchmark.configuration", configuration)
    systemProperty(
        "redis.admission.benchmark.operations",
        providers.gradleProperty("redisAdmissionBenchmarkOperations").orElse(defaultOperations).get(),
    )
    systemProperty(
        "redis.admission.benchmark.cardinalities",
        providers.gradleProperty("redisAdmissionBenchmarkCardinalities").orElse(defaultCardinalities).get(),
    )
    systemProperty(
        "redis.admission.benchmark.churnRates",
        providers.gradleProperty("redisAdmissionBenchmarkChurnRates").orElse(defaultChurnRates).get(),
    )
    systemProperty(
        "redis.admission.benchmark.concurrency",
        providers.gradleProperty("redisAdmissionBenchmarkConcurrency").orElse("16").get(),
    )
    systemProperty(
        "redis.admission.benchmark.actionMillis",
        providers.gradleProperty("redisAdmissionBenchmarkActionMillis").orElse("2").get(),
    )
}

registerRedisAdmissionBenchmark(
    name = "redisAdmissionBenchmarkSmoke",
    configuration = "smoke",
    defaultOperations = "24",
    defaultCardinalities = "10,100",
    defaultChurnRates = "0.0,1.0",
)

registerRedisAdmissionBenchmark(
    name = "redisAdmissionBenchmark",
    configuration = "main",
    defaultOperations = "80",
    defaultCardinalities = "10,100,1000",
    defaultChurnRates = "0.0,0.5,1.0",
)

private fun registerRedisKeyLifecycleBenchmark(
    name: String,
    configuration: String,
    defaultOperations: String,
    defaultCardinalities: String,
    defaultChurnRates: String,
    defaultLongRunRounds: String,
) = tasks.register<JavaExec>(name) {
    group = "benchmark"
    description = "Redis 8.8 notification key lifecycle benchmark ($configuration)"
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("io.bluetape4k.clinic.appointment.notification.RedisNotificationKeyLifecycleBenchmark")

    val output = providers.gradleProperty("redisKeyLifecycleBenchmarkOutput").orElse(
        layout.buildDirectory.file("reports/redis-key-lifecycle/$configuration/redis-notification-key-lifecycle.json")
            .map { it.asFile.absolutePath },
    )
    systemProperty("redis.lifecycle.benchmark.output", output.get())
    systemProperty("redis.lifecycle.benchmark.configuration", configuration)
    systemProperty(
        "redis.lifecycle.benchmark.operations",
        providers.gradleProperty("redisKeyLifecycleBenchmarkOperations").orElse(defaultOperations).get(),
    )
    systemProperty(
        "redis.lifecycle.benchmark.cardinalities",
        providers.gradleProperty("redisKeyLifecycleBenchmarkCardinalities").orElse(defaultCardinalities).get(),
    )
    systemProperty(
        "redis.lifecycle.benchmark.churnRates",
        providers.gradleProperty("redisKeyLifecycleBenchmarkChurnRates").orElse(defaultChurnRates).get(),
    )
    systemProperty(
        "redis.lifecycle.benchmark.longRunRounds",
        providers.gradleProperty("redisKeyLifecycleBenchmarkLongRunRounds").orElse(defaultLongRunRounds).get(),
    )
    systemProperty(
        "redis.lifecycle.benchmark.retentionWaitMillis",
        providers.gradleProperty("redisKeyLifecycleBenchmarkRetentionWaitMillis").orElse("2500").get(),
    )
    systemProperty(
        "redis.lifecycle.benchmark.concurrency",
        providers.gradleProperty("redisKeyLifecycleBenchmarkConcurrency").orElse("16").get(),
    )
    systemProperty(
        "redis.lifecycle.benchmark.actionMillis",
        providers.gradleProperty("redisKeyLifecycleBenchmarkActionMillis").orElse("2").get(),
    )
}

registerRedisKeyLifecycleBenchmark(
    name = "redisKeyLifecycleBenchmarkSmoke",
    configuration = "smoke",
    defaultOperations = "24",
    defaultCardinalities = "10,100",
    defaultChurnRates = "0.0,1.0",
    defaultLongRunRounds = "1",
)

registerRedisKeyLifecycleBenchmark(
    name = "redisKeyLifecycleBenchmark",
    configuration = "main",
    defaultOperations = "80",
    defaultCardinalities = "10,100,1000",
    defaultChurnRates = "0.0,0.5,1.0",
    defaultLongRunRounds = "2",
)
