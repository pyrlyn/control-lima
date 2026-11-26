pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "control-lima"

include(
    ":shared",
    ":server",
    ":providers:process",
    ":providers:lima",
    ":providers:colima",
    ":providers:docker",
    ":providers:kube",
    ":providers:host",
    ":web",
    ":cli",
)
