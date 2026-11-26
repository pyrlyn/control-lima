// HTTP API and the services behind it. Ktor sits behind our HttpServer interface (http/KtorHttpServer.kt)
// and the HttpPoster interface (alerts/KtorHttpPoster.kt); nothing else touches Ktor types.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin { jvmToolchain(21) }

dependencies {
    api(project(":shared"))
    api(project(":providers:process"))
    api(project(":providers:lima"))
    api(project(":providers:colima"))
    api(project(":providers:docker"))
    api(project(":providers:host"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.server.test.host)
    testImplementation(testFixtures(project(":providers:process")))
}

tasks.test { useJUnitPlatform() }
