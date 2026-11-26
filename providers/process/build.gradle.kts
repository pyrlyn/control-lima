// Runs external commands (limactl, colima, ssh) with a hard timeout. JDK only, no third-party deps.
// testFixtures share one scripted fake runner with the provider and server tests.
plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-test-fixtures`
}

kotlin { jvmToolchain(21) }

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }
