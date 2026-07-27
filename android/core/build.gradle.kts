import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.0.21"
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
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = false
    }
}
