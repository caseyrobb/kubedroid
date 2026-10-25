// Companion QR export CLI (spec 03, dev plan stage 7): plain JVM, NOT part of
// the Android build. Shares the app's real codec/parser sources via srcDir so
// the export contract can never drift from what the scanner accepts.
plugins {
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.serialization") version "2.4.21"
    application
}

repositories {
    mavenCentral()
}

// App sources reused verbatim (spec 03 "Additional dependencies"): the QR
// codecs and the kubeconfig parser/validation layer are pure JVM code.
// tools/qr-export -> ../../ == repo root.
val appData = file("../../src/main/java/com/neozeon/kubedroid/data")

kotlin.sourceSets["main"].kotlin.apply {
    srcDir(appData.resolve("qr")) // QrChunkCodec, QrEnvelopeCodec
    // KubeconfigParser + models only: the other two files in that package
    // pull client-java/okhttp/Android types this plain-JVM tool must not see.
    srcDir(appData.resolve("kubernetes"))
    exclude("ApiClientProvider.kt", "TlsSupport.kt")
}

dependencies {
    // Pins deliberately mirror gradle/libs.versions.toml (app side).
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.yaml:snakeyaml:2.5")
    implementation("javax.inject:javax.inject:1")
    implementation("com.google.zxing:core:3.5.4")
    testImplementation("junit:junit:4.13.2")
}

application {
    mainClass.set("com.neozeon.kubedroid.qrexport.MainKt")
    applicationName = "kubedroid-qr"
}

tasks.withType<JavaExec>().configureEach {
    // CLI output goes to the real stdout; keep Gradle quiet about it.
    standardInput = System.`in`
}
