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
    // A fake kubelet WebSocket for the port-forward stream.
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.server.websockets)
    // A TLS API server for the end-to-end port-forward test, like the real one.
    testImplementation(libs.ktor.server.netty)
}

tasks.test { useJUnitPlatform() }
