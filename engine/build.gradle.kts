// Pure Kotlin/JVM module: the complete game simulation — numbers, merging, chains, movement, spawning,
// collisions, combat, AI, modes and save format. No Android dependency, so `./gradlew :engine:test`
// runs on any JVM in seconds.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}

tasks.test {
    testLogging { events("passed", "failed", "skipped"); showStandardStreams = true }
}
