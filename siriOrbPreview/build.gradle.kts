plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    application
}

dependencies {
    implementation(projects.siriOrb)
    implementation(compose.desktop.currentOs)
}

application {
    mainClass = "com.jetbrains.kmpapp.siri.preview.RenderFramesKt"
}
