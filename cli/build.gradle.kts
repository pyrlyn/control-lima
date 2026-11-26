// `control-lima` entry point: flag parsing, subcommands, passthrough to limactl/colima after `--`, starts the server.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin { jvmToolchain(21) }

application {
    mainClass.set("controllima.cli.MainKt")
    applicationName = "control-lima"
}

dependencies {
    implementation(project(":server"))
    implementation(project(":providers:lima"))
    implementation(project(":providers:colima"))
    implementation(project(":providers:docker"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }

// The web app goes into the jar under `web/`, where the server's StaticFiles looks for it.
val webDist = configurations.create("webDist") {
    isCanBeConsumed = false
    isCanBeResolved = true
}
dependencies { webDist(project(path = ":web", configuration = "webDistElements")) }
val packageWeb = tasks.register<Sync>("packageWeb") {
    from(webDist)
    into(layout.buildDirectory.dir("generated/webResources/web"))
}
sourceSets.main { resources.srcDir(files(layout.buildDirectory.dir("generated/webResources")).builtBy(packageWeb)) }
