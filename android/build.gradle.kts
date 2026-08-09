// Intentionally minimal.
//
// The Android Gradle Plugin is NOT declared here, not even with `apply false`, because
// declaring it would force Gradle to resolve the AGP classpath on every build — including
// `gradle :core:test` runs on machines with no Android SDK. Each module declares the
// plugins it needs in its own build script instead.

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
