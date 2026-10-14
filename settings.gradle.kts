pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven {
            name = "JitPack"
            url = uri("https://jitpack.io")
        }
    }
}

rootProject.name = "light-sdk"

includeBuild("plugin")
include(":lint-rules")
include(":sdk:shared")
include(":sdk:ui")
include(":sdk:client")
include(":tool")

// The tool builder passes -DlightSdk.toolOnly=true to configure only what
// :tool needs.
if (providers.systemProperty("lightSdk.toolOnly").orNull != "true") {
    // The LightOS emulator app (and the SDK server it wraps), for testing the tool locally.
    include(":sdk:server")
    include(":sdk:emulator")
}
