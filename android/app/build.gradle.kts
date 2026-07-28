import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application") version "8.7.3"
    id("org.jetbrains.kotlin.android") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
}

/**
 * Release signing comes from a keystore supplied by CI secrets.
 *
 * When it is absent — a local checkout, or a fork without secrets — the release build falls
 * back to debug signing so the workflow still produces an installable APK. That matters
 * because the signing identity must stay stable: Android refuses to upgrade an installed app
 * whose signature changed, and reinstalling wipes the saved statement passwords.
 */
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) load(FileInputStream(keystorePropertiesFile))
}
val hasReleaseKeystore = keystoreProperties.getProperty("storeFile") != null

android {
    namespace = "com.gohil.bookkeeper"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.gohil.bookkeeper"
        // Android 8.0. Covers the Keystore-backed encryption and the PDF renderer this uses.
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/INDEX.LIST",
            )
        }
    }

    sourceSets["main"].java.srcDirs("src/main/kotlin")
}

dependencies {
    implementation(project(":core"))

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.documentfile:documentfile:1.0.1")

    // Statement passwords are held in the OS-backed encrypted store, never in a file or log.
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.biometric:biometric:1.1.0")

    // Long OCR batches run in a worker with a foreground notification so the system does
    // not kill the run when the screen goes off.
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // PDF text extraction and password-protected PDF unlocking, entirely on-device.
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    // On-device text recognition. The bundled variant ships the model inside the APK, so it
    // needs neither the network nor Google Play Services.
    implementation("com.google.mlkit:text-recognition:16.0.1")

    debugImplementation(composeBom)
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.ui:ui-tooling-preview")

    testImplementation(kotlin("test"))
}
