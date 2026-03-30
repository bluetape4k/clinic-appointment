import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.report.ReportMergeTask
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import java.util.concurrent.TimeUnit

plugins {
    base
    kotlin("jvm") version Versions.kotlin

    kotlin("plugin.spring") version Versions.kotlin apply false
    kotlin("plugin.allopen") version Versions.kotlin apply false
    kotlin("plugin.noarg") version Versions.kotlin apply false
    kotlin("plugin.jpa") version Versions.kotlin apply false
    kotlin("plugin.serialization") version Versions.kotlin apply false

    id(Plugins.detekt) version Plugins.Versions.detekt

    id(Plugins.dependency_management) version Plugins.Versions.dependency_management
    id(Plugins.spring_boot) version Plugins.Versions.spring_boot4 apply false

    id(Plugins.dokka) version Plugins.Versions.dokka
    id(Plugins.testLogger) version Plugins.Versions.testLogger
    id(Plugins.shadow) version Plugins.Versions.shadow apply false
    id(Plugins.gatling) version Plugins.Versions.gatling apply false
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
    // bluetape4k snapshot 버전 사용 시만 사용하세요.
    configurations.all {
        resolutionStrategy.cacheChangingModulesFor(1, TimeUnit.DAYS)
    }
}

subprojects {
    apply {
        plugin<JavaLibraryPlugin>()
        plugin("org.jetbrains.kotlin.jvm")
        plugin(Plugins.dependency_management)
        plugin(Plugins.dokka)
        plugin(Plugins.testLogger)
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
            finalizedBy(reportMerge)
            reportMerge.configure {
                input.from(this@detekt.xmlReportFile)
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
            mavenBom(Libs.bluetape4k_bom)
            mavenBom(Libs.spring_boot4_dependencies)

            mavenBom(Libs.testcontainers_bom)
            mavenBom(Libs.junit_bom)

            mavenBom(Libs.kotlinx_coroutines_bom)
            mavenBom(Libs.kotlin_bom)

            mavenBom(Libs.timefold_solver_bom)
        }

        dependencies {
            dependency(Libs.jetbrains_annotations)

            dependency(Libs.kotlinx_coroutines_core)
            dependency(Libs.kotlinx_coroutines_core_jvm)
            dependency(Libs.kotlinx_coroutines_reactor)
            dependency(Libs.kotlinx_coroutines_slf4j)
            dependency(Libs.kotlinx_coroutines_debug)
            dependency(Libs.kotlinx_coroutines_test)
            dependency(Libs.kotlinx_coroutines_test_jvm)

            dependency(Libs.junit_jupiter)
            dependency(Libs.junit_jupiter_api)
            dependency(Libs.junit_jupiter_engine)
            dependency(Libs.junit_jupiter_migrationsupport)
            dependency(Libs.junit_jupiter_params)
            dependency(Libs.junit_platform_commons)
            dependency(Libs.junit_platform_engine)
            dependency(Libs.junit_platform_launcher)
            dependency(Libs.junit_platform_runner)

            dependency(Libs.kluent)
            dependency(Libs.assertj_core)
            dependency(Libs.mockk)
            dependency(Libs.datafaker)
            dependency(Libs.random_beans)

            dependency(Libs.lettuce_core)
        }
    }

    dependencies {
        val implementation by configurations
        val testImplementation by configurations
        val compileOnly by configurations
        val testRuntimeOnly by configurations

        compileOnly(platform(Libs.bluetape4k_bom))
        compileOnly(platform(Libs.spring_boot4_dependencies))
        compileOnly(platform(Libs.kotlinx_coroutines_bom))

        implementation(Libs.kotlin_stdlib)
        implementation(Libs.kotlin_reflect)
        testImplementation(Libs.kotlin_test)
        testImplementation(Libs.kotlin_test_junit5)

        implementation(Libs.kotlinx_coroutines_core)

        implementation(Libs.slf4j_api)
        implementation(Libs.bluetape4k_logging)
        implementation(Libs.logback)
        testImplementation(Libs.jcl_over_slf4j)
        testImplementation(Libs.jul_to_slf4j)
        testImplementation(Libs.log4j_over_slf4j)

        // JUnit 5
        testImplementation(Libs.bluetape4k_junit5)
        testImplementation(Libs.junit_jupiter)
        testRuntimeOnly(Libs.junit_platform_engine)

        testImplementation(Libs.kluent)
        testImplementation(Libs.mockk)

        testImplementation(Libs.datafaker)
        testImplementation(Libs.random_beans)
    }
}
