plugins {
    kotlin("jvm") version "2.2.20"
    application
}

repositories {
    mavenCentral()
}

dependencies {
    // Wire SDK
    implementation("com.wire:wire-apps-jvm-sdk:0.0.17")

    // Coroutines for timers
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(17)
}

application {
    // top-level main() in Main.kt
    mainClass.set("MainKt")
}
