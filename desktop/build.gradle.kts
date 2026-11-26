// The desktop app (docs/plan/desktop-app.md): the shared UI from :web in a window and a menu bar item,
// with the server running in-process.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose)
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":web"))
    implementation(project(":server"))
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.core)
}

compose.desktop {
    application {
        mainClass = "controllima.desktop.MainKt"
    }
}
