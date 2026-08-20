import org.gradle.api.artifacts.dsl.LockMode

repositories {
    mavenCentral()
    google()
}

plugins {
    `kotlin-dsl`
}

dependencyLocking {
    lockAllConfigurations()
    lockMode = LockMode.STRICT
}

kotlin {
    compilerOptions {
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_3)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_3)
    }
}
