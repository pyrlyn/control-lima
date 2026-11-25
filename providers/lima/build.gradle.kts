// Lima through `limactl ... --json` only; never parses human-readable output.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin { jvmToolchain(21) }

dependencies {
    api(project(":shared"))
    api(project(":providers:process"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }
