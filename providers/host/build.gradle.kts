// The host machine: TCP listeners (lsof -F), disk space and allocated file sizes, desktop
// notifications, the macOS Keychain. JDK + CommandRunner only.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin { jvmToolchain(21) }

dependencies {
    api(project(":shared"))
    api(project(":providers:process"))
    testImplementation(kotlin("test"))
    testImplementation(testFixtures(project(":providers:process")))
}

tasks.test { useJUnitPlatform() }
