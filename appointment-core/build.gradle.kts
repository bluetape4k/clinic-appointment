plugins {
    alias(libs.plugins.exposed)
    kotlin("plugin.spring")
}

exposed {
    migrations {
        tablesPackage = "io.bluetape4k.clinic.appointment.model.tables"
        databaseUrl = "jdbc:h2:mem:appointment-core-migrations;DB_CLOSE_DELAY=-1;MODE=PostgreSQL"
        databaseUser = "sa"
        databasePassword = ""
    }
}

dependencies {
    api(libs.jetbrains.exposed.core)
    api(libs.jetbrains.exposed.java.time)
    api(libs.jetbrains.exposed.spring7.transaction)

    api(libs.bluetape4k.cache.core)
    api(libs.exposed.core)
    api(libs.bluetape4k.coroutines)
    api(libs.bluetape4k.states)
    api(libs.kotlinx.coroutines.core)

    implementation(libs.jetbrains.exposed.jdbc)
    implementation(libs.exposed.jdbc)
    implementation(libs.jackson3.module.kotlin)

    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.bluetape4k.testcontainers)
    testImplementation(libs.bluetape4k.io)
    testImplementation(libs.bluetape4k.cache.lettuce)
    testImplementation(libs.bluetape4k.lettuce)
    testImplementation(libs.lettuce.core)
    testImplementation(libs.lz4.java)
    testImplementation(libs.fory.kotlin)
    testImplementation(libs.bluetape4k.jdbc)
    testImplementation(libs.jetbrains.exposed.migration.jdbc)
    testImplementation(libs.h2.v2)
    testImplementation(libs.postgresql.driver)
    testImplementation(libs.mysql.connector.j)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.mysql)
    testImplementation(libs.testcontainers.mariadb)
    testImplementation(libs.testcontainers.cockroachdb)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.withType<Test>().configureEach {
    val useFastDB = (project.findProperty("useFastDB") as? String)?.toBoolean() ?: false
    systemProperty("exposed.test.useFastDB", useFastDB)
    val useDB = (project.findProperty("useDB") as? String) ?: ""
    if (useDB.isNotBlank()) systemProperty("exposed.test.useDB", useDB)
}
