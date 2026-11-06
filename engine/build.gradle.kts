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
    environment("CUBECLASH_ONLINE_TESTS", System.getenv("CUBECLASH_ONLINE_TESTS") ?: "0")
}

// Headless multiplayer peer for CI end-to-end tests against the app: ./gradlew :engine:netbot --args="lan-join 127.0.0.1 47800 30"
tasks.register<JavaExec>("netbot") {
    group = "verification"
    description = "Runs a headless multiplayer peer (see NetBot.kt)"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.oxdh.cubeclash.engine.net.NetBotKt")
}
