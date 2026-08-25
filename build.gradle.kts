import dev.detekt.gradle.Detekt
import dev.detekt.gradle.report.ReportMergeTask
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.dsl.LockMode
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedVariantResult
import org.gradle.api.Task
import org.gradle.api.attributes.Bundling
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.gradle.api.attributes.java.TargetJvmVersion
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.TaskProvider
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant

plugins {
    base
    alias(libs.plugins.kotlin.jvm)

    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.kotlin.allopen) apply false
    alias(libs.plugins.kotlin.noarg) apply false
    alias(libs.plugins.kotlin.jpa) apply false
    alias(libs.plugins.kotlin.serialization) apply false

    alias(libs.plugins.detekt.dev)

    alias(libs.plugins.dependency.management)
    alias(libs.plugins.spring.boot) apply false

    alias(libs.plugins.dokka)
    alias(libs.plugins.test.logger)
    alias(libs.plugins.shadow) apply false
    alias(libs.plugins.gatling) apply false

    alias(libs.plugins.kover)
    alias(libs.plugins.exposed) apply false
}

val rootLibs = libs

private fun Configuration.configureApiConsumerFixtureClasspath() {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes {
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_API))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
        attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 25)
    }
}

val appointmentCoreConsumerFixtureClasspath = configurations.create("appointmentCoreConsumerFixtureClasspath") {
    configureApiConsumerFixtureClasspath()
}
val appointmentMessagingConsumerFixtureClasspath = configurations.create("appointmentMessagingConsumerFixtureClasspath") {
    configureApiConsumerFixtureClasspath()
}
val appointmentNotificationConsumerFixtureClasspath = configurations.create("appointmentNotificationConsumerFixtureClasspath") {
    configureApiConsumerFixtureClasspath()
}

dependencies {
    add(appointmentCoreConsumerFixtureClasspath.name, project(":appointment-core"))
    add(appointmentMessagingConsumerFixtureClasspath.name, project(":appointment-messaging"))
    add(appointmentNotificationConsumerFixtureClasspath.name, project(":appointment-notification"))
}

fun registerApiConsumerFixtureCompile(
    name: String,
    sourceDirectory: String,
    classpath: Configuration,
    outputPath: String,
    moduleJarTask: String,
): TaskProvider<out Task> {
    val sourceSetName = name.removePrefix("compile").replaceFirstChar(Char::lowercase)
    val sourceSet: SourceSet = sourceSets.create(sourceSetName)
    tasks.named<JavaCompile>(sourceSet.compileJavaTaskName) {
        options.release.set(25)
    }
    val kotlinCompile = tasks.named<KotlinJvmCompile>(sourceSet.getCompileTaskName("kotlin")) {
        source(fileTree(sourceDirectory) { include("**/*.kt") })
        libraries.setFrom(classpath)
        destinationDirectory.set(layout.buildDirectory.dir(outputPath))
        compilerOptions.jvmTarget.set(JvmTarget.JVM_25)
        compilerOptions.freeCompilerArgs.add("-jvm-default=enable")
        dependsOn(moduleJarTask)
    }
    return tasks.register(name) {
        description = "Compiles the $sourceDirectory API consumer fixture against apiElements."
        group = "verification"
        dependsOn(kotlinCompile)
    }
}

fun Project.registerDependencyGovernanceTask() = tasks.register("verifyDependencyGovernance") {
    group = "verification"
    description = "Resolves every resolvable configuration of this project under strict locking and verification."
    notCompatibleWithConfigurationCache("Configuration inventory is intentionally resolved at task execution time.")

    doLast {
        val configurationsToResolve = configurations
            .filter { it.isCanBeResolved }
            .sortedBy { it.name }

        require(configurationsToResolve.isNotEmpty()) {
            "No resolvable Gradle configurations were discovered for ${project.path}."
        }

        configurationsToResolve.forEach { configuration ->
            logger.lifecycle("Resolving ${project.path}:${configuration.name}")
            configuration.resolve()
        }
    }
}

val compileAppointmentCoreConsumerFixture = registerApiConsumerFixtureCompile(
    name = "compileAppointmentCoreConsumerFixture",
    sourceDirectory = "src/consumerFixture/core/kotlin",
    classpath = appointmentCoreConsumerFixtureClasspath,
    outputPath = "consumer-fixtures/core/classes",
    moduleJarTask = ":appointment-core:jar",
)
val compileAppointmentMessagingConsumerFixture = registerApiConsumerFixtureCompile(
    name = "compileAppointmentMessagingConsumerFixture",
    sourceDirectory = "src/consumerFixture/messaging/kotlin",
    classpath = appointmentMessagingConsumerFixtureClasspath,
    outputPath = "consumer-fixtures/messaging/classes",
    moduleJarTask = ":appointment-messaging:jar",
)
val compileAppointmentNotificationConsumerFixture = registerApiConsumerFixtureCompile(
    name = "compileAppointmentNotificationConsumerFixture",
    sourceDirectory = "src/consumerFixture/notification/kotlin",
    classpath = appointmentNotificationConsumerFixtureClasspath,
    outputPath = "consumer-fixtures/notification/classes",
    moduleJarTask = ":appointment-notification:jar",
)

val compileModuleConsumerFixtures = tasks.register("compileModuleConsumerFixtures") {
    description = "Compiles all Issue #336 API consumer fixtures."
    group = "verification"
    dependsOn(
        compileAppointmentCoreConsumerFixture,
        compileAppointmentMessagingConsumerFixture,
        compileAppointmentNotificationConsumerFixture,
    )
}

private data class ApiConsumerFixtureTarget(
    val module: String,
    val configuration: Configuration,
    val modulePath: String,
    val moduleJarTask: String,
    val compileTask: TaskProvider<out Task>,
)

private val apiConsumerFixtureTargets = listOf(
    ApiConsumerFixtureTarget(
        module = "core",
        configuration = appointmentCoreConsumerFixtureClasspath,
        modulePath = ":appointment-core",
        moduleJarTask = ":appointment-core:jar",
        compileTask = compileAppointmentCoreConsumerFixture,
    ),
    ApiConsumerFixtureTarget(
        module = "messaging",
        configuration = appointmentMessagingConsumerFixtureClasspath,
        modulePath = ":appointment-messaging",
        moduleJarTask = ":appointment-messaging:jar",
        compileTask = compileAppointmentMessagingConsumerFixture,
    ),
    ApiConsumerFixtureTarget(
        module = "notification",
        configuration = appointmentNotificationConsumerFixtureClasspath,
        modulePath = ":appointment-notification",
        moduleJarTask = ":appointment-notification:jar",
        compileTask = compileAppointmentNotificationConsumerFixture,
    ),
)

private data class ApiConsumerFixtureScope(
    val api: Set<String>,
    val compileOnlyApi: Set<String>,
) {
    val all: Set<String> get() = api + compileOnlyApi
}

private val apiConsumerFixtureExpectedScopes = mapOf(
    "core" to ApiConsumerFixtureScope(
        api = setOf(
            "io.github.bluetape4k.exposed:bluetape4k-exposed-core",
            "io.github.bluetape4k.exposed:bluetape4k-exposed-jdbc",
            "io.github.bluetape4k:bluetape4k-cache-core",
            "io.github.bluetape4k:bluetape4k-coroutines",
            "io.github.bluetape4k:bluetape4k-states",
            "org.jetbrains.exposed:exposed-core",
            "org.jetbrains.exposed:exposed-java-time",
            "org.jetbrains.exposed:spring7-transaction",
            "org.jetbrains.kotlinx:kotlinx-coroutines-core",
        ),
        compileOnlyApi = emptySet(),
    ),
    "messaging" to ApiConsumerFixtureScope(
        api = setOf(
            "io.micrometer:micrometer-core",
            "org.apache.kafka:kafka-clients",
            "org.jetbrains.exposed:exposed-jdbc",
            "org.springframework.kafka:spring-kafka",
            "project::appointment-event",
        ),
        compileOnlyApi = setOf(
            "org.springframework.boot:spring-boot-autoconfigure",
            "org.springframework.boot:spring-boot-health",
            "org.springframework.boot:spring-boot-sql",
            "org.springframework:spring-context",
        ),
    ),
    "notification" to ApiConsumerFixtureScope(
        api = setOf(
            "io.github.bluetape4k.leader:bluetape4k-leader-core",
            "io.github.bluetape4k.leader:bluetape4k-leader-spring-boot",
            "io.github.bluetape4k.leader:bluetape4k-leader-redis-lettuce",
            "io.github.resilience4j:resilience4j-bulkhead",
            "io.github.resilience4j:resilience4j-circuitbreaker",
            "io.github.resilience4j:resilience4j-retry",
            "io.lettuce:lettuce-core",
            "io.micrometer:micrometer-core",
            "org.apache.kafka:kafka-clients",
            "org.jetbrains.exposed:exposed-jdbc",
            "org.springframework.kafka:spring-kafka",
            "project::appointment-core",
            "project::appointment-event",
            "project::appointment-messaging",
        ),
        compileOnlyApi = setOf(
            "org.springframework.boot:spring-boot-autoconfigure",
            "org.springframework:spring-context",
        ),
    ),
)

private val apiConsumerFixtureApprovedCoordinates = apiConsumerFixtureExpectedScopes
    .mapValues { (_, scope) -> scope.all }

private val apiConsumerFixtureInventory = mapOf(
    "core" to listOf(
        "AppointmentRepository", "ClinicRepository", "DoctorRepository", "EquipmentRepository", "HolidayRepository",
        "PatientAccountRepository", "PatientLoginIdentityRepository", "RescheduleCandidateRepository",
        "TenantGroupRepository", "TreatmentTypeRepository", "LongJdbcRepository",
    ),
    "messaging" to listOf(
        "AppointmentConsumerRuntime", "JdbcAppointmentConsumerInboxStore", "AppointmentConsumerRetentionService",
        "AppointmentReplayService", "AppointmentKafkaConsumerListener", "KafkaAppointmentReplaySource",
        "SpringKafkaAppointmentPublisher", "AppointmentKafkaConsumerConfiguration", "AppointmentKafkaProducerConfiguration",
        "AppointmentMessagingAutoConfiguration", "AppointmentMessagingHealthIndicator", "AppointmentOutboxRelayLifecycle",
        "AppointmentMessagingStartupValidator", "AppointmentMessagingReadinessValidator", "MicrometerAppointmentConsumerMetrics",
        "MicrometerAppointmentOutboxMetrics", "AppointmentConsumerInboxTable", "AppointmentConsumerQuarantineTable",
        "ConsumerRecord", "Acknowledgment", "Consumer", "ConsumerFactory", "ConcurrentKafkaListenerContainerFactory",
        "KafkaTemplate", "KafkaAdmin", "ProducerFactory", "Database", "Table", "LongIdTable", "MeterRegistry",
        "ObjectProvider", "DataSource", "HealthIndicator", "SmartLifecycle", "SmartInitializingSingleton",
    ),
    "notification" to listOf(
        "NotificationAppointmentEventConsumer", "NotificationAppointmentEventKafkaListener", "NotificationSchemaReadiness",
        "JdbcNotificationOutboxWorkStore", "JdbcNotificationOutboxObservationStore", "NotificationOutboxWorkStore",
        "NotificationOutboxObservationStore", "NotificationOutboxRepository", "NotificationOutboxMetrics",
        "NotificationOutboxSchedulingRunner", "NotificationObservationSchedulingRunner", "NotificationRetentionSchedulingRunner",
        "NotificationReminderSchedulingRunner", "NotificationRetentionRunner", "AppointmentReminderScheduler",
        "ResilientNotificationChannel", "NotificationAutoConfiguration", "NotificationRuntimeHealthSignals",
        "NotificationDirectDeliveryPort", "ConsumerRecord", "Acknowledgment", "Database", "MeterRegistry",
        "LeaderGroupElector", "LeaderScheduled", "RedisClient", "StatefulRedisConnection", "CircuitBreaker", "Retry", "Bulkhead",
        "ConditionalOnClass",
    ),
)

private fun dependencyCoordinate(dependency: org.gradle.api.artifacts.Dependency): String = when (dependency) {
    is ProjectDependency -> "project:${dependency.path}"
    else -> listOfNotNull(dependency.group, dependency.name).joinToString(":")
}

private fun declaredCoordinates(modulePath: String, configurationName: String): Set<String> =
    project(modulePath).configurations.findByName(configurationName)?.dependencies.orEmpty()
        .map(::dependencyCoordinate)
        .toSortedSet()

private val API_CONSUMER_FIXTURE_CONTRACT_VERSION = "issue-336-v1"
private val apiConsumerFixtureReportDirectory = layout.buildDirectory.dir("reports/consumer-fixtures/issue-336")
private val apiConsumerFixtureVariantReport = apiConsumerFixtureReportDirectory.map { it.file("variants.json") }
private val apiConsumerFixtureClasspathReport = apiConsumerFixtureReportDirectory.map { it.file("classpath.json") }
private val apiConsumerFixtureDiagnosticsReport = apiConsumerFixtureReportDirectory.map { it.file("diagnostics.json") }

private fun sha256(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

private fun gitValue(vararg args: String): String =
    providers.exec {
        commandLine("git", *args)
    }.standardOutput.asText.get().trim()

private fun gitSourceRef(): String =
    runCatching { gitValue("symbolic-ref", "--short", "HEAD") }
        .getOrNull()
        ?.takeIf(String::isNotBlank)
        ?: runCatching { gitValue("rev-parse", "--abbrev-ref", "HEAD") }
            .getOrNull()
            ?.takeIf(String::isNotBlank)
        ?: System.getenv("GITHUB_REF_NAME")?.takeIf(String::isNotBlank)
        ?: "detached"

private fun redactDiagnostic(value: String, rootDirectory: File): String =
    value
        .replace(rootDirectory.absolutePath, "<repo>")
        .replace(Regex("https?://[^\\s\\\"]+"), "<url>")
        .replace(Regex("(?i)(?:/Users|/home|[A-Za-z]:\\\\)[^\\s\\\"]+"), "<path>")
        .replace(Regex("(?i)(password|secret|token|credential)\\s*[=:]\\s*[^,\\s]+"), "$1=<redacted>")
        .take(500)

private fun boundedCauseChain(failure: Throwable, rootDirectory: File): List<Map<String, String>> =
    generateSequence(failure) { it.cause }
        .take(3)
        .map { cause ->
            mapOf(
                "exception" to cause.javaClass.simpleName.take(120),
                "summary" to redactDiagnostic(cause.message ?: cause.javaClass.simpleName, rootDirectory).take(240),
            )
        }
        .toList()

private fun repositoryPath(rootDirectory: File, relativePath: String): File {
    require(!Path.of(relativePath).isAbsolute) { "repository-relative path required: $relativePath" }
    require(!relativePath.split('/').contains("..")) { "parent traversal is not allowed: $relativePath" }
    val candidate = rootDirectory.toPath().resolve(relativePath).normalize()
    val root = rootDirectory.toPath().toRealPath()
    require(candidate.startsWith(root)) { "path escapes repository root: $relativePath" }
    require(!Files.isSymbolicLink(candidate)) { "symbolic links are not allowed: $relativePath" }
    val real = candidate.toRealPath()
    require(real.startsWith(root)) { "real path escapes repository root: $relativePath" }
    return real.toFile()
}

private fun repositoryTree(rootDirectory: File, relativePath: String): File {
    val directory = repositoryPath(rootDirectory, relativePath)
    Files.walk(directory.toPath()).use { paths ->
        paths.forEach { path ->
            require(!Files.isSymbolicLink(path)) {
                "symbolic links are not allowed below $relativePath"
            }
            require(path.toRealPath().startsWith(rootDirectory.toPath().toRealPath())) {
                "real path escapes repository root below $relativePath"
            }
        }
    }
    return directory
}

private fun assertFixtureInventory(rootDirectory: File, target: ApiConsumerFixtureTarget) {
    val sourceDirectory = repositoryTree(rootDirectory, "src/consumerFixture/${target.module}/kotlin")
    val source = sourceDirectory.walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .joinToString("\n", transform = File::readText)
    val missing = apiConsumerFixtureInventory.getValue(target.module).filterNot(source::contains)
    require(missing.isEmpty()) {
        "${target.module} fixture inventory is missing symbols: ${missing.joinToString(", ")}"
    }
}

private fun resolvedComponentCoordinates(target: ApiConsumerFixtureTarget): List<String> {
    val coordinates = linkedSetOf<String>()
    val visited = linkedSetOf<String>()
    fun visit(component: ResolvedComponentResult) {
        val id = component.id
        if (!visited.add(id.displayName)) return
        if (id is ModuleComponentIdentifier) {
            coordinates += "${id.group}:${id.module}:${id.version}"
        }
        component.dependencies.filterIsInstance<ResolvedDependencyResult>().forEach { dependency ->
            visit(dependency.selected)
        }
    }
    target.configuration.incoming.resolutionResult.root.dependencies
        .filterIsInstance<ResolvedDependencyResult>()
        .forEach { visit(it.selected) }
    return coordinates.toList().sorted()
}

private fun componentCoordinate(component: ResolvedComponentResult): String = when (val id = component.id) {
    is ModuleComponentIdentifier -> "${id.group}:${id.module}"
    is ProjectComponentIdentifier -> "project:${id.projectPath}"
    else -> id.displayName
}

private fun producerDependency(target: ApiConsumerFixtureTarget): ResolvedDependencyResult? =
    target.configuration.incoming.resolutionResult.root.dependencies
        .filterIsInstance<ResolvedDependencyResult>()
        .singleOrNull { dependency ->
            (dependency.selected.id as? ProjectComponentIdentifier)?.projectPath == target.modulePath
        }

private fun resolvedApiRootCoordinates(target: ApiConsumerFixtureTarget): Set<String> {
    val producer = producerDependency(target)?.selected
        ?: return emptySet()
    return producer.dependencies
        .filterIsInstance<ResolvedDependencyResult>()
        .map { componentCoordinate(it.selected) }
        .toSortedSet()
}

private fun variantAttributes(variant: ResolvedVariantResult?): Map<String, String> =
    variant?.attributes?.keySet()?.associate { attribute ->
        attribute.name to (variant.attributes.getAttribute(attribute)?.toString() ?: "")
    }.orEmpty()

private fun configurationFingerprint(target: ApiConsumerFixtureTarget): String {
    val files = runCatching { target.configuration.incoming.artifacts.artifacts.map { it.file }.sortedBy { it.name } }
        .getOrDefault(emptyList())
    val input = buildString {
        append(target.module).append('|')
        append(target.configuration.attributes.keySet().joinToString(",") { attribute ->
                attribute.name + "=" + target.configuration.attributes.getAttribute(attribute)
        })
        append('|').append(resolvedApiRootCoordinates(target).joinToString(","))
        append('|').append(resolvedComponentCoordinates(target).joinToString(","))
        files.forEach { file -> append('|').append(file.name).append(':').append(file.length()) }
    }
    return sha256(input)
}

private fun resolvedVariant(target: ApiConsumerFixtureTarget): Pair<ResolvedDependencyResult?, ResolvedVariantResult?> {
    val dependency = producerDependency(target)
    return dependency to dependency?.resolvedVariant
}

private fun writeJson(file: File, value: Any) {
    file.parentFile.mkdirs()
    file.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(value)) + "\n")
}

private fun assertSafeEvidence(file: File) {
    val content = file.readText()
    require(!Regex("(?i)(password|secret|token|credential)\\s*[=:]\\s*[^,\\s]+")
        .containsMatchIn(content)) { "evidence contains a credential-like value: ${file.name}" }
    require(!Regex("(?i)https?://[^\\s\"]+").containsMatchIn(content)) {
        "evidence contains a URL: ${file.name}"
    }
    require(!Regex("(?i)(?:/Users|/home|[A-Za-z]:\\\\)[^\\s\"]+").containsMatchIn(content)) {
        "evidence contains an absolute path: ${file.name}"
    }
}

val generateModuleConsumerFixtureVariantReport = tasks.register("generateModuleConsumerFixtureVariantReport") {
    description = "Records the selected apiElements variant for each Issue #336 consumer fixture."
    group = "verification"
    notCompatibleWithConfigurationCache("Resolves project apiElements configurations while writing evidence.")
    dependsOn(apiConsumerFixtureTargets.map { it.moduleJarTask })
    inputs.property("variantContractVersion", API_CONSUMER_FIXTURE_CONTRACT_VERSION)
    inputs.files("build.gradle.kts", "appointment-core/build.gradle.kts", "appointment-messaging/build.gradle.kts", "appointment-notification/build.gradle.kts")
    inputs.files(apiConsumerFixtureTargets.map { it.configuration })
    apiConsumerFixtureTargets.forEach { target ->
        inputs.dir("src/consumerFixture/${target.module}/kotlin")
    }
    outputs.file(apiConsumerFixtureVariantReport)
    outputs.file(apiConsumerFixtureDiagnosticsReport)
    doLast {
        val rootDirectory = project.rootDir
        val diagnostics = mutableListOf<Map<String, Any?>>()
        val modules = apiConsumerFixtureTargets.map { target ->
            try {
                assertFixtureInventory(rootDirectory, target)
                val (dependency, variant) = resolvedVariant(target)
                val artifacts = target.configuration.incoming.artifacts.artifacts.map { artifact ->
                    mapOf(
                        "basename" to artifact.file.name,
                        "size" to artifact.file.length(),
                    )
                }
                val selectedComponent = dependency?.selected?.id?.displayName ?: "<missing>"
                mapOf(
                    "module" to target.module,
                    "status" to "resolved",
                    "selectedComponent" to selectedComponent,
                    "selectedVariant" to (variant?.displayName ?: "<missing>"),
                    "usage" to (variantAttributes(variant)[Usage.USAGE_ATTRIBUTE.name] ?: "<missing>"),
                    "attributes" to variantAttributes(variant),
                    "artifacts" to artifacts,
                    "resolvedApiRootCoordinates" to resolvedApiRootCoordinates(target),
                    "resolvedCoordinates" to resolvedComponentCoordinates(target),
                    "declaredApiCoordinates" to declaredCoordinates(target.modulePath, "api"),
                    "declaredCompileOnlyApiCoordinates" to declaredCoordinates(target.modulePath, "compileOnlyApi"),
                    "approvedCoordinates" to apiConsumerFixtureApprovedCoordinates.getValue(target.module),
                    "resolutionFingerprint" to configurationFingerprint(target),
                )
            } catch (failure: Throwable) {
                val summary = redactDiagnostic(failure.message ?: failure.javaClass.simpleName, rootDirectory)
                diagnostics += mapOf(
                    "module" to target.module,
                    "exception" to failure.javaClass.simpleName,
                    "summary" to summary,
                    "causes" to boundedCauseChain(failure, rootDirectory),
                )
                mapOf(
                    "module" to target.module,
                    "status" to "failed",
                    "selectedComponent" to null,
                    "selectedVariant" to null,
                    "usage" to null,
                    "attributes" to emptyMap<String, String>(),
                    "artifacts" to emptyList<Map<String, Any>>(),
                    "resolvedApiRootCoordinates" to emptySet<String>(),
                    "resolvedCoordinates" to emptyList<String>(),
                    "declaredApiCoordinates" to emptySet<String>(),
                    "declaredCompileOnlyApiCoordinates" to emptySet<String>(),
                    "approvedCoordinates" to apiConsumerFixtureApprovedCoordinates.getValue(target.module),
                    "resolutionFingerprint" to "failed",
                    "error" to summary,
                )
            }
        }
        val gitSha = gitValue("rev-parse", "HEAD")
        val report = mapOf(
            "variantContractVersion" to API_CONSUMER_FIXTURE_CONTRACT_VERSION,
            "runId" to (System.getenv("BLUETAPE_FLOW_RUN_ID") ?: "local-${Instant.now()}"),
            "sourceRef" to gitSourceRef(),
            "gitSha" to gitSha,
            "gradleVersion" to gradle.gradleVersion,
            "jdk" to System.getProperty("java.version"),
            "command" to "generateModuleConsumerFixtureVariantReport",
            "modules" to modules,
            "resolutionFingerprint" to sha256(modules.joinToString("|") { it.toString() }),
        )
        writeJson(apiConsumerFixtureVariantReport.get().asFile, report)
        writeJson(apiConsumerFixtureDiagnosticsReport.get().asFile, mapOf(
            "variantContractVersion" to API_CONSUMER_FIXTURE_CONTRACT_VERSION,
            "modules" to diagnostics,
        ))
        assertSafeEvidence(apiConsumerFixtureVariantReport.get().asFile)
        assertSafeEvidence(apiConsumerFixtureDiagnosticsReport.get().asFile)
    }
}

val generateModuleConsumerFixtureClasspathReport = tasks.register("generateModuleConsumerFixtureClasspathReport") {
    description = "Records the resolved apiElements classpath for each Issue #336 consumer fixture."
    group = "verification"
    notCompatibleWithConfigurationCache("Resolves project apiElements configurations while writing evidence.")
    dependsOn(apiConsumerFixtureTargets.map { it.moduleJarTask })
    inputs.property("variantContractVersion", API_CONSUMER_FIXTURE_CONTRACT_VERSION)
    inputs.files("build.gradle.kts", "appointment-core/build.gradle.kts", "appointment-messaging/build.gradle.kts", "appointment-notification/build.gradle.kts")
    inputs.files(apiConsumerFixtureTargets.map { it.configuration })
    apiConsumerFixtureTargets.forEach { target ->
        inputs.dir("src/consumerFixture/${target.module}/kotlin")
    }
    outputs.file(apiConsumerFixtureClasspathReport)
    doLast {
        val modules = apiConsumerFixtureTargets.map { target ->
            try {
                assertFixtureInventory(project.rootDir, target)
                val files = target.configuration.incoming.artifacts.artifacts.map { it.file }.sortedBy { it.name }
                mapOf(
                    "module" to target.module,
                    "status" to "resolved",
                    "artifactCount" to files.size,
                    "totalFileSize" to files.sumOf(File::length),
                    "artifacts" to files.map { mapOf("basename" to it.name, "size" to it.length()) },
                    "resolvedApiRootCoordinates" to resolvedApiRootCoordinates(target),
                    "resolvedCoordinates" to resolvedComponentCoordinates(target),
                    "classpathFingerprint" to configurationFingerprint(target),
                )
            } catch (failure: Throwable) {
                mapOf(
                    "module" to target.module,
                    "status" to "failed",
                    "artifactCount" to 0,
                    "totalFileSize" to 0,
                    "artifacts" to emptyList<Map<String, Any>>(),
                    "resolvedApiRootCoordinates" to emptySet<String>(),
                    "resolvedCoordinates" to emptyList<String>(),
                    "classpathFingerprint" to "failed",
                    "error" to redactDiagnostic(failure.message ?: failure.javaClass.simpleName, project.rootDir),
                    "causes" to boundedCauseChain(failure, project.rootDir),
                )
            }
        }
        writeJson(apiConsumerFixtureClasspathReport.get().asFile, mapOf(
            "variantContractVersion" to API_CONSUMER_FIXTURE_CONTRACT_VERSION,
            "runId" to (System.getenv("BLUETAPE_FLOW_RUN_ID") ?: "local-${Instant.now()}"),
            "sourceRef" to gitSourceRef(),
            "gitSha" to gitValue("rev-parse", "HEAD"),
            "modules" to modules,
        ))
        assertSafeEvidence(apiConsumerFixtureClasspathReport.get().asFile)
    }
}

val assertModuleConsumerFixtureApiVariants = tasks.register("assertModuleConsumerFixtureApiVariants") {
    description = "Asserts that Issue #336 fixtures select apiElements with Usage.JAVA_API."
    group = "verification"
    notCompatibleWithConfigurationCache("Reads the generated evidence and current Git revision.")
    dependsOn(generateModuleConsumerFixtureVariantReport)
    inputs.file(apiConsumerFixtureVariantReport)
    doLast {
        val report = JsonSlurper().parse(apiConsumerFixtureVariantReport.get().asFile) as Map<*, *>
        require(report["variantContractVersion"] == API_CONSUMER_FIXTURE_CONTRACT_VERSION) { "consumer fixture variant report contract is stale" }
        require(report["sourceRef"] == gitSourceRef()) { "consumer fixture variant report source ref is stale" }
        require(report["gitSha"] == gitValue("rev-parse", "HEAD")) { "consumer fixture variant report is stale" }
        val modules = report["modules"] as? List<*> ?: error("consumer fixture variant report has no modules")
        require(modules.size == apiConsumerFixtureTargets.size) { "consumer fixture variant report is incomplete" }
        apiConsumerFixtureTargets.forEach { target ->
            val module = modules.filterIsInstance<Map<*, *>>().singleOrNull { it["module"] == target.module }
                ?: error("missing consumer fixture variant for ${target.module}")
            require(module["status"] == "resolved") { "${target.module} apiElements resolution failed: ${module["error"]}" }
            require(module["selectedVariant"] == "apiElements") {
                "${target.module} selected ${module["selectedVariant"]}, expected apiElements"
            }
            require(module["usage"] == Usage.JAVA_API) {
                "${target.module} selected usage ${module["usage"]}, expected ${Usage.JAVA_API}"
            }
            val declaredApi = (module["declaredApiCoordinates"] as? List<*>)?.filterIsInstance<String>().orEmpty()
            val declaredCompileOnlyApi =
                (module["declaredCompileOnlyApiCoordinates"] as? List<*>)?.filterIsInstance<String>().orEmpty()
            val actualScope = ApiConsumerFixtureScope(declaredApi.toSet(), declaredCompileOnlyApi.toSet())
            val expectedScope = apiConsumerFixtureExpectedScopes.getValue(target.module)
            require(actualScope.api == expectedScope.api) {
                "${target.module} API scope differs: unexpected=${actualScope.api - expectedScope.api}, " +
                    "missing=${expectedScope.api - actualScope.api}"
            }
            require(actualScope.compileOnlyApi == expectedScope.compileOnlyApi) {
                "${target.module} compileOnlyApi scope differs: unexpected=${actualScope.compileOnlyApi - expectedScope.compileOnlyApi}, " +
                    "missing=${expectedScope.compileOnlyApi - actualScope.compileOnlyApi}"
            }
            val resolvedApiRoots =
                (module["resolvedApiRootCoordinates"] as? List<*>)?.filterIsInstance<String>()?.toSet().orEmpty()
            require(resolvedApiRoots == expectedScope.all) {
                "${target.module} resolved API roots differ: unexpected=${resolvedApiRoots - expectedScope.all}, " +
                    "missing=${expectedScope.all - resolvedApiRoots}"
            }
            val reportFingerprint = module["resolutionFingerprint"] as? String
            require(reportFingerprint == configurationFingerprint(target)) {
                "${target.module} resolution fingerprint is stale"
            }
        }
    }
}

val assertModuleConsumerFixtureTaskGraph = tasks.register("assertModuleConsumerFixtureTaskGraph") {
    description = "Asserts the producer jar, report, assertion, fixture and check task edges."
    group = "verification"
    notCompatibleWithConfigurationCache("Inspects the configured task dependency declarations.")
    dependsOn(assertModuleConsumerFixtureApiVariants, generateModuleConsumerFixtureClasspathReport)
    doLast {
        fun dependencyPaths(dependency: Any?): Sequence<String> = when (dependency) {
            is Iterable<*> -> dependency.asSequence().flatMap(::dependencyPaths)
            is Task -> sequenceOf(dependency.path)
            is TaskProvider<*> -> sequenceOf(":${dependency.name}")
            is String -> sequenceOf(dependency)
            else -> emptySequence()
        }

        fun dependenciesOf(taskName: String): Set<String> {
            val task = tasks.named(taskName).get()
            return task.dependsOn.asSequence().flatMap(::dependencyPaths).toSet()
        }
        require(dependenciesOf(generateModuleConsumerFixtureVariantReport.name).containsAll(apiConsumerFixtureTargets.map { it.moduleJarTask })) {
            "variant report must depend on all producer jar tasks"
        }
        require(dependenciesOf(assertModuleConsumerFixtureApiVariants.name).contains(generateModuleConsumerFixtureVariantReport.get().path)) {
            "variant assertion must depend on variant report"
        }
        apiConsumerFixtureTargets.forEach { target ->
            val dependencies = dependenciesOf(target.compileTask.name)
            require(dependencies.contains(assertModuleConsumerFixtureApiVariants.get().path)) {
                "${target.compileTask.name} must depend on variant assertion"
            }
            require(dependencies.contains(":assertModuleConsumerFixtureTaskGraph")) {
                "${target.compileTask.name} must depend on task graph assertion"
            }
        }
        require(dependenciesOf(compileModuleConsumerFixtures.name).containsAll(apiConsumerFixtureTargets.map { it.compileTask.get().path })) {
            "compileModuleConsumerFixtures must include all fixture compile tasks"
        }
    }
}

apiConsumerFixtureTargets.forEach { target ->
    target.compileTask.configure {
        dependsOn(assertModuleConsumerFixtureApiVariants, assertModuleConsumerFixtureTaskGraph)
    }
}

tasks.named("check") {
    dependsOn(compileModuleConsumerFixtures)
}

tasks.named("clean") {
    doLast {
        delete(layout.buildDirectory.dir("consumer-fixtures"))
        delete(layout.buildDirectory.dir("reports/consumer-fixtures/issue-336"))
    }
}

allprojects {
    repositories {
        mavenCentral()

        // bluetape4k snapshot 버전 사용 시만 사용하세요.
        maven {
            name = "central-snapshots"
            url = uri("https://central.sonatype.com/repository/maven-snapshots/")
        }
    }
    // SNAPSHOT artifacts must always be re-checked; caching stale metadata breaks CI
    configurations.all {
        resolutionStrategy.cacheChangingModulesFor(0, TimeUnit.SECONDS)
    }
    dependencyLocking {
        lockAllConfigurations()
        lockMode = LockMode.STRICT
    }
    registerDependencyGovernanceTask()
}

dependencyManagement {
    setApplyMavenExclusions(false)
    imports {
        mavenBom(rootLibs.bluetape4k.dependencies.get().toString())
        mavenBom(rootLibs.spring.boot4.dependencies.get().toString())
        mavenBom(rootLibs.kotlin.bom.get().toString())
        mavenBom(rootLibs.kotlinx.coroutines.bom.get().toString())
    }
    dependencies {
        val leaderScheduledPolicyVersion = rootLibs.bluetape4k.leader.core.get().versionConstraint.requiredVersion
        dependency("io.github.bluetape4k.leader:bluetape4k-leader-core:$leaderScheduledPolicyVersion")
        dependency("io.github.bluetape4k.leader:bluetape4k-leader-redis-lettuce:$leaderScheduledPolicyVersion")
        dependency("io.github.bluetape4k.leader:bluetape4k-leader-micrometer:$leaderScheduledPolicyVersion")
        dependency("io.github.bluetape4k.leader:bluetape4k-leader-spring-boot:$leaderScheduledPolicyVersion")
    }
}

subprojects {
    apply {
        plugin<JavaLibraryPlugin>()
        plugin("org.jetbrains.kotlin.jvm")
        plugin("io.spring.dependency-management")
        plugin("org.jetbrains.dokka")
        plugin("com.adarshr.test-logger")
        if (project.path != ":appointment-messaging-benchmark") {
            plugin("org.jetbrains.kotlinx.kover")
        }
    }

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(25))
        }
    }

    kotlin {
        jvmToolchain(25)
        compilerOptions {
            languageVersion.set(KotlinVersion.KOTLIN_2_3)
            apiVersion.set(KotlinVersion.KOTLIN_2_3)
            freeCompilerArgs = listOf(
                "-Xjsr305=strict",
                "-jvm-default=enable",
                "-Xstring-concat=indy",
                "-Xcontext-parameters",
                "-Xannotation-default-target=param-property",
            )
            val experimentalAnnotations = listOf(
                "kotlin.RequiresOptIn",
                "kotlin.ExperimentalStdlibApi",
                "kotlin.contracts.ExperimentalContracts",
                "kotlin.experimental.ExperimentalTypeInference",
                "kotlinx.coroutines.ExperimentalCoroutinesApi",
                "kotlinx.coroutines.InternalCoroutinesApi",
                "kotlinx.coroutines.FlowPreview",
                "kotlinx.coroutines.DelicateCoroutinesApi",
            )
            freeCompilerArgs.addAll(experimentalAnnotations.map { "-opt-in=$it" })
        }
    }

    tasks {
        compileJava {
            options.isIncremental = true
        }

        compileKotlin {
            compilerOptions {
                incremental = true
            }
        }

        abstract class TestMutexService : BuildService<BuildServiceParameters.None>

        val testMutex = gradle.sharedServices.registerIfAbsent(
            "test-mutex",
            TestMutexService::class
        ) {
            maxParallelUsages.set(1)
        }

        test {
            usesService(testMutex)

            useJUnitPlatform()

            // Issue #34 codec gate is an explicit test lane. Forward only its
            // bounded properties to the forked JUnit JVM so a normal module
            // test remains a short smoke while the gate command can request
            // the fixed 10,000-row/30-second/5-minute window.
            if (project.path == ":appointment-event") {
                listOf(
                    "issue34.codec.benchmark",
                    "issue34.codec.mode",
                    "issue34.codec.mix",
                    "issue34.codec.run",
                    "issue34.codec.rows",
                    "issue34.codec.warmupSeconds",
                    "issue34.codec.measureSeconds",
                    "issue34.codec.artifact",
                    "issue34.sourceCommit",
                ).forEach { propertyName ->
                    System.getProperty(propertyName)?.let { propertyValue ->
                        systemProperty(propertyName, propertyValue)
                    }
                }
            }

            jvmArgs(
                "-Xshare:off",
                "-Xms2G",
                "-Xmx4G",
                "-XX:+UseG1GC",
                "-XX:+UnlockExperimentalVMOptions",
                "-XX:+EnableDynamicAgentLoading",
                "--enable-preview",
                "-Didea.io.use.nio2=true"
            )

            testLogging {
                showExceptions = true
                showCauses = true
                showStackTraces = true

                events("failed")
            }
        }

        testlogger {
            theme = com.adarshr.gradle.testlogger.theme.ThemeType.MOCHA_PARALLEL
            showFullStackTraces = true
        }

        val reportMerge by registering(ReportMergeTask::class) {
            val file = rootProject.layout.buildDirectory.asFile.get().resolve("reports/detekt/merge.xml")
            output.set(file)
        }
        withType<Detekt>().configureEach detekt@{
            reports.checkstyle.required.set(true)
            finalizedBy(reportMerge)
            reportMerge.configure {
                input.from(this@detekt.reports.checkstyle.outputLocation)
            }
        }

        dokka {
            configureEach {
                dokkaSourceSets {
                    configureEach {
                        includes.from("README.md")
                    }
                }
                dokkaPublications.html {
                    outputDirectory.set(project.file("docs/api"))
                }
            }
        }

        clean {
            doLast {
                delete("./.project")
                delete("./out")
                delete("./bin")
            }
        }
    }

    dependencyManagement {
        setApplyMavenExclusions(false)

        imports {
            mavenBom(rootLibs.bluetape4k.dependencies.get().toString())
            mavenBom(rootLibs.spring.boot4.dependencies.get().toString())
            // Override Spring Boot's lower Kotlin/Coroutines versions
            mavenBom(rootLibs.kotlin.bom.get().toString())
            mavenBom(rootLibs.kotlinx.coroutines.bom.get().toString())
        }
        dependencies {
            val leaderScheduledPolicyVersion = rootLibs.bluetape4k.leader.core.get().versionConstraint.requiredVersion
            dependency("io.github.bluetape4k.leader:bluetape4k-leader-core:$leaderScheduledPolicyVersion")
            dependency("io.github.bluetape4k.leader:bluetape4k-leader-redis-lettuce:$leaderScheduledPolicyVersion")
            dependency("io.github.bluetape4k.leader:bluetape4k-leader-micrometer:$leaderScheduledPolicyVersion")
            dependency("io.github.bluetape4k.leader:bluetape4k-leader-spring-boot:$leaderScheduledPolicyVersion")
        }
    }

    dependencies {
        val implementation by configurations
        val testImplementation by configurations
        val compileOnly by configurations
        val testRuntimeOnly by configurations

        compileOnly(platform(rootLibs.bluetape4k.dependencies))
        compileOnly(platform(rootLibs.spring.boot4.dependencies))

        implementation(rootLibs.kotlin.stdlib)
        implementation(rootLibs.kotlin.reflect)
        testImplementation(rootLibs.kotlin.test)
        testImplementation(rootLibs.kotlin.test.junit5)

        implementation(rootLibs.kotlinx.coroutines.core)

        implementation(rootLibs.slf4j.api)
        implementation(rootLibs.bluetape4k.logging)
        implementation(rootLibs.logback)
        testImplementation(rootLibs.jcl.over.slf4j)
        testImplementation(rootLibs.jul.to.slf4j)
        testImplementation(rootLibs.log4j.over.slf4j)

        // JUnit 5
        testImplementation(rootLibs.bluetape4k.junit5)
        testImplementation(rootLibs.junit.jupiter)
        testRuntimeOnly(rootLibs.junit.platform.engine)

        testImplementation(rootLibs.mockk)

        testImplementation(rootLibs.datafaker)
        testImplementation(rootLibs.random.beans)
    }
}

tasks.named("verifyDependencyGovernance") {
    dependsOn(subprojects.map { it.tasks.named("verifyDependencyGovernance") })
}

// ─── Kover 집계 설정 ────────────────────────────────────────────────────
// 루트에서 커버리지 측정 대상 서브모듈을 `kover` 의존성으로 등록하면
// `./gradlew koverXmlReport` / `koverHtmlReport` 실행 시 집계 리포트를 생성한다.
dependencies {
    // Benchmarks produce their own JSON evidence and must not dilute product
    // coverage aggregation with generated JMH classes.
    subprojects
        .filterNot { it.path == ":appointment-messaging-benchmark" }
        .forEach { sub -> kover(project(sub.path)) }
}
