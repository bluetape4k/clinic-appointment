pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
    plugins {
        // https://plugins.gradle.org/plugin/org.gradle.toolchains.foojay-resolver-convention
        id("org.gradle.toolchains.foojay-resolver-convention") version ("1.0.0")
    }
}

rootProject.name = "clinic-appointment"

// bluetape4k-projects 로컬 빌드 연결 (로컬에 있을 때만)
val bluetape4kProjectsDir = file("../bluetape4k-projects")
if (bluetape4kProjectsDir.exists()) {
    includeBuild(bluetape4kProjectsDir) {
        dependencySubstitution {
            // 모든 모듈이 bluetape4k-{name} 형식으로 등록되어 있음
            listOf(
                "bluetape4k-core",
                "bluetape4k-coroutines",
                "bluetape4k-exposed-core",
                "bluetape4k-exposed-jdbc",
                "bluetape4k-exposed-jdbc-tests",
                "bluetape4k-exposed-r2dbc",
                "bluetape4k-exposed-r2dbc-tests",
                "bluetape4k-junit5",
                "bluetape4k-leader",
                "bluetape4k-lettuce",
                "bluetape4k-logging",
                "bluetape4k-resilience4j",
                "bluetape4k-testcontainers",
            ).forEach { module ->
                substitute(module("io.github.bluetape4k:$module")).using(project(":$module"))
            }
        }
    }
}

// Gradle 예약 디렉토리 — 모듈 자동 등록에서 제외
val reservedDirs = setOf("buildSrc", "build", "gradle", "config", "frontend", ".gradle")

// 모듈 자동 등록 — 루트 직하의 build.gradle.kts가 있는 폴더를 모두 등록
fun includeModules(baseDir: String = ".") {
    val base = file(baseDir)
    base.listFiles()
        ?.filter { dir ->
            dir.isDirectory
                && !dir.name.startsWith(".")
                && dir.name !in reservedDirs
                && File(dir, "build.gradle.kts").exists()
        }
        ?.forEach { dir ->
            val moduleName = ":${dir.name}"
            include(moduleName)
            project(moduleName).projectDir = dir
        }
}

// frontend 서브모듈 처리
fun includeFrontendModules() {
    val frontendDir = file("frontend")
    if (frontendDir.exists()) {
        frontendDir.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") && File(it, "build.gradle.kts").exists() }
            ?.forEach { dir ->
                val moduleName = ":frontend:${dir.name}"
                include(moduleName)
                project(moduleName).projectDir = dir
            }
    }
}

includeModules()
includeFrontendModules()
