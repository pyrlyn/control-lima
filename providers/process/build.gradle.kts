// Runs external commands (limactl, colima) with a hard timeout. JDK only, no third-party deps.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin { jvmToolchain(21) }

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }
