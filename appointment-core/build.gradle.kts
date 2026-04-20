plugins {
    kotlin("plugin.spring")
}

dependencies {
    api(Libs.exposed_core)
    api(Libs.exposed_java_time)
    api(Libs.exposed_spring7_transaction)

    api(Libs.bluetape4k_exposed_core)
    api(Libs.bluetape4k_coroutines)
    api(Libs.kotlinx_coroutines_core)

    implementation(Libs.exposed_jdbc)
    implementation(Libs.bluetape4k_exposed_jdbc)

    testImplementation(Libs.bluetape4k_junit5)
    testImplementation(Libs.bluetape4k_testcontainers)
    testImplementation(Libs.bluetape4k_jdbc)
    testImplementation(Libs.exposed_migration_jdbc)
    testImplementation(Libs.h2_v2)
    testImplementation(Libs.postgresql_driver)
    testImplementation(Libs.mysql_connector_j)
    testImplementation(Libs.testcontainers_postgresql)
    testImplementation(Libs.testcontainers_mysql)
    testImplementation(Libs.testcontainers_mariadb)
    testImplementation(Libs.testcontainers_cockroachdb)
    testImplementation(Libs.kotlinx_coroutines_test)
}

tasks.withType<Test>().configureEach {
    val useFastDB = (project.findProperty("useFastDB") as? String)?.toBoolean() ?: false
    systemProperty("exposed.test.useFastDB", useFastDB)
    val useDB = (project.findProperty("useDB") as? String) ?: ""
    if (useDB.isNotBlank()) systemProperty("exposed.test.useDB", useDB)
}
