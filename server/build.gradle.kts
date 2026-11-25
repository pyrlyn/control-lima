// HTTP API. Ktor sits behind our HttpServer interface (KtorHttpServer is the only Ktor-aware file set).
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin { jvmToolchain(21) }

dependencies {
    api(project(":shared"))
    api(project(":providers:lima"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.server.test.host)
}

tasks.test { useJUnitPlatform() }
