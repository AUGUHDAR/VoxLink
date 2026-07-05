import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.compose.compiler)
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":p2p"))
    implementation("com.google.code.gson:gson:2.11.0")
    implementation(compose.desktop.currentOs)
    runtimeOnly("org.slf4j:slf4j-simple:2.0.13")
}

compose.desktop {
    application {
        mainClass = "icu.wuhui.voxlink.app.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe, TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.Dmg)
            packageName = "VoxLinkCore"
            packageVersion = "1.0.0"
            // TLS/SSL HTTPS 必需, 缺了会 SSLHandshakeException
            modules("jdk.crypto.ec", "jdk.crypto.cryptoki")
        }
    }
}
