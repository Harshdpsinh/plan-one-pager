pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }

    // Plugin versions are declared once here rather than in each module. Repeating a version
    // in more than one subproject's `plugins { }` block makes Gradle load the Kotlin plugin
    // twice in separate classloaders, which it warns "may break the build".
    plugins {
        kotlin("jvm") version "2.0.21"
        kotlin("plugin.serialization") version "2.0.21"
        id("org.jetbrains.kotlin.android") version "2.0.21"
        id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
        id("com.android.application") version "8.7.3"
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "GohilBookkeeper"

// :core is pure Kotlin/JVM — it builds and tests with nothing but a JDK.
include(":core")

// :app needs the Android SDK. Including it unconditionally would break `gradle :core:test`
// on any machine without the SDK installed (including CI sandboxes and this project's
// development container, where dl.google.com is unreachable). So it is opted into only
// when an SDK is actually present.
//
// GitHub Actions runners have the SDK pre-installed and export ANDROID_HOME, so CI picks
// :app up automatically. Android Studio writes local.properties, so it does too.
val androidSdkAvailable =
    System.getenv("ANDROID_HOME") != null ||
        System.getenv("ANDROID_SDK_ROOT") != null ||
        file("local.properties").exists()

if (androidSdkAvailable) {
    include(":app")
} else {
    logger.lifecycle(
        "No Android SDK detected — configuring :core only. " +
            "Set ANDROID_HOME or create android/local.properties to build the app."
    )
}
