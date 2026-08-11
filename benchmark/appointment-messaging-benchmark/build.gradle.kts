plugins {
    alias(libs.plugins.kotlin.allopen)
    alias(libs.plugins.kotlinx.benchmark)
}

allOpen {
    annotation("org.openjdk.jmh.annotations.State")
}

dependencies {
    implementation(project(":appointment-messaging"))
    implementation(libs.kotlinx.benchmark.runtime)
    implementation(libs.flyway.core)
    implementation(libs.flyway.database.postgresql)
    implementation(libs.postgresql.driver)
    implementation(libs.bluetape4k.testcontainers)
    implementation(libs.testcontainers.postgresql)
    implementation("com.zaxxer:HikariCP")

    testImplementation(libs.junit.jupiter)
}

// appointment-api is a Spring Boot application and does not publish its plain
// `jar` in this build. Reuse the production Flyway resources without copying or
// maintaining a second migration tree.
sourceSets {
    main {
        resources.srcDir(rootProject.file("appointment-api/src/main/resources"))
    }
}

benchmark {
    targets {
        register("main")
    }

    configurations {
        named("main") {
            warmups = 2
            iterations = 5
            iterationTime = 1
            iterationTimeUnit = "s"
            outputTimeUnit = "ms"
            reportFormat = "json"
        }
        register("smoke") {
            warmups = 1
            iterations = 1
            iterationTime = 250
            iterationTimeUnit = "ms"
            outputTimeUnit = "ms"
            reportFormat = "json"
        }
    }
}
