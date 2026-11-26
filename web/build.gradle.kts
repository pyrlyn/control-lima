// The UI: Compose Multiplatform for the browser (wasmJs) and the desktop app (jvm, used by :desktop).
// Pages are described in docs/pages/; only platform glue lives outside commonMain.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose)
}

kotlin {
    jvmToolchain(21)
    jvm()
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
        commonMain.dependencies {
            api(project(":shared"))
            api(libs.compose.runtime)
            api(libs.compose.foundation)
            api(libs.compose.ui)
            api(libs.compose.material3)
            implementation(libs.compose.components.resources)
            implementation(libs.kotlinx.coroutines.core)
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

// IBM Plex Mono ships with the app: the browser must not fetch fonts from a third party (CSP), and the
// desktop app should look the same.
compose.resources {
    packageOfResClass = "controllima.web.resources"
    publicResClass = false
}
