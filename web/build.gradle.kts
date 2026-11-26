// Web UI: Compose Multiplatform for Web (wasmJs). Pages are described in docs/pages/.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose)
}

kotlin {
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        outputModuleName.set("controlLima")
        browser {
            commonWebpackConfig { outputFileName = "controlLima.js" }
            testTask { enabled = false }
        }
        binaries.executable()
    }
    sourceSets {
        wasmJsMain.dependencies {
            implementation(project(":shared"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.compose.material3)
        }
    }
}

kotlin.sourceSets.all {
    languageSettings.optIn("androidx.compose.material3.ExperimentalMaterial3Api")
    languageSettings.optIn("androidx.compose.foundation.layout.ExperimentalLayoutApi")
    languageSettings.optIn("kotlin.js.ExperimentalWasmJsInterop")
}

// The CLI serves the built app from its classpath; it consumes this directory instead of reaching into
// another project's tasks.
val webDistElements = configurations.create("webDistElements") {
    isCanBeConsumed = true
    isCanBeResolved = false
}
artifacts {
    add(webDistElements.name, layout.buildDirectory.dir("dist/wasmJs/productionExecutable")) {
        builtBy(tasks.named("wasmJsBrowserDistribution"))
    }
}
