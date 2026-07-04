plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material)
            implementation(compose.components.resources)
            implementation(project(":p2p"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
