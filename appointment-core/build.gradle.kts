plugins {
    kotlin("plugin.spring")
}

dependencies {
    api(libs.exposed.core)
    api(libs.exposed.java.time)
    api(libs.exposed.spring7.transaction)

    api(libs.bluetape4k.exposed.core)
    api(libs.bluetape4k.coroutines)
    api(libs.kotlinx.coroutines.core)

    implementation(libs.exposed.jdbc)
    implementation(libs.bluetape4k.exposed.jdbc)

    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.bluetape4k.testcontainers)
    testImplementation(libs.bluetape4k.jdbc)
    testImplementation(libs.exposed.migration.jdbc)
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
