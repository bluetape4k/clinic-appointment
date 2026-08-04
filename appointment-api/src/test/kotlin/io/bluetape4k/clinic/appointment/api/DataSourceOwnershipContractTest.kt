package io.bluetape4k.clinic.appointment.api

import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/** Spring runtime의 DataSource/Exposed 연결 경계를 repository source에서 고정합니다. */
class DataSourceOwnershipContractTest {

    @Test
    fun `production database registration stays inside the shared factory`() {
        val productionSources = productionSources()
        val directDatabaseConnections = productionSources
            .filter { source(it).contains(DATABASE_CONNECT_PATTERN) }
            .map(::relativePath)

        directDatabaseConnections shouldBeEqualTo listOf(
            "appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/ExposedDatabaseFactory.kt",
        )
    }

    @Test
    fun `production Kotlin and Java source does not create pools or JDBC URLs`() {
        val forbiddenMatches = productionSources().flatMap { path ->
            FORBIDDEN_PATTERNS.mapNotNull { (name, pattern) ->
                if (pattern in source(path)) name to relativePath(path) else null
            }
        }

        forbiddenMatches shouldBeEqualTo emptyList()
    }

    private fun productionSources(): List<Path> =
        moduleRoots()
            .flatMap { root ->
                Files.walk(root).use { stream ->
                    stream
                        .filter(Files::isRegularFile)
                        .filter { it.fileName.toString().substringAfterLast('.') in setOf("kt", "java") }
                        .toList()
                }
            }
            .sorted()

    private fun moduleRoots(): List<Path> =
        listOf(Path.of("appointment-api/src/main"), Path.of("../appointment-api/src/main"))
            .filter(Files::exists)
            .distinct()

    private fun source(path: Path): String = Files.readString(path)

    private fun relativePath(path: Path): String =
        path.normalize().toString().removePrefix("../")

    private companion object {
        const val DATABASE_CONNECT_PATTERN = "Database.connect("
        val FORBIDDEN_PATTERNS = listOf(
            "HikariDataSource" to "HikariDataSource",
            "SimpleDriverDataSource" to "SimpleDriverDataSource",
            "DriverManager.getConnection" to "DriverManager.getConnection",
            "jdbc:" to "jdbc:",
        )
    }
}
