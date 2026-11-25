// Plugin versions live in gradle/libs.versions.toml; modules apply plugins without a version.
plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.compose) apply false
}

subprojects {
    group = "controllima"
    version = "0.1.0-SNAPSHOT"
}
