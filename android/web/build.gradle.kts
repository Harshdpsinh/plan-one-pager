import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    // The same tested module the Android app uses. The Excel writing, parsing, matching and
    // routing rules are shared verbatim — there is no second implementation to drift.
    implementation(project(":core"))

    implementation("io.javalin:javalin:6.3.0")
    // Javalin's ctx.json() delegates to Jackson when it is present and throws when it is not.
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
    implementation("org.slf4j:slf4j-simple:2.0.16")

    // Desktop PDFBox. The Android app uses the pdfbox-android port of the same library.
    implementation("org.apache.pdfbox:pdfbox:3.0.3")

    testImplementation(kotlin("test"))
    testImplementation(testFixtures(project(":core")))
}

application {
    mainClass.set("com.gohil.bookkeeper.web.MainKt")
    applicationName = "gohil-bookkeeper"
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped") }
}
