// Kubernetes API (k3s in Colima, Lima k3s/k8s templates) over HTTPS with the Ktor client. No kubectl, no k8s or YAML library.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin { jvmToolchain(21) }

dependencies {
    api(project(":shared"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }
