import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.report.ReportMergeTask
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    base
    alias(libs.plugins.kotlin.jvm)

    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.kotlin.allopen) apply false
    alias(libs.plugins.kotlin.noarg) apply false
    alias(libs.plugins.kotlin.jpa) apply false
    alias(libs.plugins.kotlin.serialization) apply false

    alias(libs.plugins.detekt)

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
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    kotlin {
        jvmToolchain(21)
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
            mavenBom(rootLibs.bluetape4k.dependencies.get().toString())
            mavenBom(rootLibs.spring.boot4.dependencies.get().toString())
            // Override Spring Boot's lower Kotlin/Coroutines versions
            mavenBom(rootLibs.kotlin.bom.get().toString())
            mavenBom(rootLibs.kotlinx.coroutines.bom.get().toString())
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
