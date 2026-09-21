import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.javafx)
}

version = "1.0.0"

javafx {
    version = libs.versions.javafx.get()
    modules = listOf("javafx.controls", "javafx.media", "javafx.web")
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

// Same treatment as the Android app: cloudstream3.jar is the JVM artifact of
// the CloudStream runtime, but its network.WebViewResolver and CloudStreamApp
// are stubs (desktop artifact) or Coil-3-compiled classes that die on the JVM
// with NoClassDefFoundError. Drop them (plus dead R classes) and shadow them
// with real implementations in this source tree.
val cloudstreamRawJar = file("libs/cloudstream3.jar")
val cloudstreamCleanJar = tasks.register<org.gradle.api.tasks.bundling.Jar>("cloudstreamJarClean") {
    archiveFileName.set("cloudstream3-clean.jar")
    destinationDirectory.set(layout.buildDirectory.dir("intermediates/cloudstream-clean"))
    from(zipTree(cloudstreamRawJar)) {
        exclude("**/R.class", "**/R$*.class")
        exclude("com/lagradost/cloudstream3/network/WebViewResolver*.class")
        exclude("com/lagradost/cloudstream3/CloudStreamApp*.class")
    }
    duplicatesStrategy = org.gradle.api.file.DuplicatesStrategy.EXCLUDE
}

// dex2jar + ASM, vendored from the official dex-tools-v2.4 release (Apache-2.0):
// translates a CloudStream dex plugin (classes.dex) into JVM .class bytecode so
// ANY .cs3 extension runs on the desktop, not just hand-ported ones.
val dex2jarLibs = fileTree("libs/dex2jar") { include("*.jar") }

dependencies {
    implementation(files(cloudstreamCleanJar))
    implementation(dex2jarLibs)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.okhttp)
    // Aniyomi's NetworkHelper installs an HttpLoggingInterceptor on the shared
    // client; the artifact is tiny and keeps that file a faithful copy.
    implementation(libs.okhttp.logging)
    implementation(libs.jsoup)
    implementation(libs.gson)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.nicehttp)
    implementation(libs.rhino)
    implementation(libs.ktor.http)
    implementation(libs.ksoup)
    implementation(libs.kotlinx.datetime)
    implementation(libs.atomicfu)
    implementation(libs.newpipeextractor)
    implementation(libs.cryptography.core)
    implementation(libs.cryptography.provider.optimal)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlin.reflect)
    implementation(libs.conscrypt.openjdk)
    implementation(libs.orgjson)
    implementation(libs.imageio.webp)
    // V8 for the JS engines: SkyStream `.sky` plugins and nuvio providers are
    // plain JS written with async/await. Rhino (also a dependency, used by the
    // CloudStream runtime) cannot parse an `async function` at all, so those
    // engines run on V8. The native library ships inside the platform artifact
    // and extracts itself at runtime, so jpackage only has to carry the jars.
    implementation(libs.javet)
    implementation(libs.javet.v8.windows)
    // Aniyomi (.apk anime extensions) shim tree — the tachiyomi interfaces use
    // RxJava 1, which is plain Java.
    implementation(libs.rxjava)
    // Aniyomi extensions that read their pages over a socket start a NanoHTTPD
    // server themselves (`createHttpServer()`); the extension API Hikari ships
    // is `fi.iki.elonen.NanoHTTPD`, which is exactly what this artifact holds.
    implementation(libs.nanohttpd)
}

// The exe launcher on Windows (jpackage) needs every runtime jar in one input
// folder plus the built app jar. This task stages them.
val jpackageInput by tasks.registering(Sync::class) {
    dependsOn(cloudstreamCleanJar, "classes")
    from(configurations.runtimeClasspath)
    from(tasks.jar)
    into(layout.buildDirectory.dir("jpackage-input"))
}

tasks.jar {
    archiveFileName.set("hikari-desktop.jar")
    manifest {
        attributes["Main-Class"] = "desktop.MainKt"
    }
}
