import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "fr.radaralerte"
    compileSdk = 35

    defaultConfig {
        applicationId = "fr.radaralerte"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signée avec la clé de debug pour pouvoir l'installer directement sur votre téléphone.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Écran Android Auto
    implementation("androidx.car.app:app:1.4.0")
}

// ---------------------------------------------------------------------------
// Compilation du code Rust (rust/radar-jni) avec cargo-ndk avant chaque build.
// Désactivable avec -PskipRust si les .so sont déjà dans src/main/jniLibs.
// ---------------------------------------------------------------------------
val rustDir = rootProject.projectDir.resolve("../rust/radar-jni")
val jniLibsDir = project.projectDir.resolve("src/main/jniLibs")

val buildRust by tasks.registering(Exec::class) {
    description = "Compile la bibliothèque Rust pour Android (cargo ndk)"
    workingDir = rustDir
    val localProps = Properties().apply {
        val f = rootProject.file("local.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    if (System.getenv("ANDROID_HOME") == null) {
        localProps.getProperty("sdk.dir")?.let { environment("ANDROID_HOME", it) }
    }
    commandLine(
        "cargo", "ndk",
        "-t", "arm64-v8a", "-t", "armeabi-v7a", "-t", "x86_64",
        "--platform", "26",
        "-o", jniLibsDir.absolutePath,
        "build", "--release"
    )
}

if (!project.hasProperty("skipRust")) {
    tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(buildRust) }
}
