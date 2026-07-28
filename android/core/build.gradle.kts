import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

// Deliberately NOT using jvmToolchain(17): that makes the build fail outright on a machine
// that only has a different JDK installed, and this module's whole purpose is to stay
// buildable and testable anywhere. Targeting 17 bytecode from whatever modern JDK is
// present gives the Android module the class file version it needs without the constraint.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // The categorisation rules live in an editable JSON file rather than in code, so the
    // user can add a vendor keyword without a rebuild. Same file shape as the desktop app's
    // config/categories.json, so the two stay interchangeable.
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = false
    }
}
