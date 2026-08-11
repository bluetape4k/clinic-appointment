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
    implementation(project(":appointment-messaging"))
    implementation(libs.spring.kafka4)

    implementation(libs.jetbrains.exposed.jdbc)
    implementation(libs.exposed.jdbc)
    implementation(libs.jetbrains.exposed.migration.jdbc)
    implementation("org.springframework.boot:spring-boot-starter-web")

    // Reminder recovery trigger leader election (delivery correctness uses DB lease/fencing)
    implementation(libs.bluetape4k.leader)
    implementation(libs.bluetape4k.leader.micrometer)
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
    testImplementation(libs.mockk)

    runtimeOnly(libs.h2.v2)
}
