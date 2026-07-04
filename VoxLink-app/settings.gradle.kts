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

rootProject.name = "VoxLink-app"

include(":p2p")
include(":shared")
include(":app-desktop")
include(":app-android")
