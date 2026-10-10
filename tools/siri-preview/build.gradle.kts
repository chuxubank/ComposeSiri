plugins {
    kotlin("jvm") version "2.4.20"
    kotlin("plugin.compose") version "2.4.20"
    id("org.jetbrains.compose") version "1.12.0"
}

kotlin {
    jvmToolchain(21)
    sourceSets.named("main") {
        kotlin {
            srcDir("../../shared/src/commonMain/kotlin")
            include("**/SiriOrb.kt")
            include("**/SiriSurfaceMesh.kt")
            include("**/SiriPreview.kt")
        }
    }
}

dependencies {
    implementation(compose.desktop.currentOs)
}

compose.desktop {
    application {
        mainClass = "com.jetbrains.kmpapp.preview.SiriPreviewKt"
        jvmArgs += listOf("-Djava.awt.headless=true")
    }
}
