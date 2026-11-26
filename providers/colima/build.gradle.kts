// Colima through `colima ... --json` only.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin { jvmToolchain(21) }

dependencies {
    api(project(":shared"))
    api(project(":providers:process"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
    testImplementation(testFixtures(project(":providers:process")))
}

tasks.test { useJUnitPlatform() }
