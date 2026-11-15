plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Release signing is configured from environment variables so that the keystore and its
// passwords are never committed to the repository. GitHub Actions provides them as secrets.
// For local builds, put keystore/release.jks in place and export:
//   MOUSE_POINTER_STORE_PASSWORD=...
//   MOUSE_POINTER_KEY_ALIAS=...
//   MOUSE_POINTER_KEY_PASSWORD=...
val keystoreFile = rootProject.file("keystore/release.jks")
val releaseStorePassword = System.getenv("MOUSE_POINTER_STORE_PASSWORD")?.takeIf { it.isNotBlank() }
val releaseKeyAlias = System.getenv("MOUSE_POINTER_KEY_ALIAS")?.takeIf { it.isNotBlank() }
val releaseKeyPassword = System.getenv("MOUSE_POINTER_KEY_PASSWORD")?.takeIf { it.isNotBlank() }
val canSignRelease = keystoreFile.exists() &&
    releaseStorePassword != null &&
    releaseKeyPassword != null

android {
    namespace = Configs.namespace
    compileSdk = Configs.compileSdk

    defaultConfig {
        applicationId = Configs.applicationId
        minSdk = Configs.minSdk
        targetSdk = Configs.targetSdk
        versionCode = Configs.versionCode
        versionName = Configs.versionName
    }

    signingConfigs {
        if (canSignRelease) {
            create("release") {
                storeFile = keystoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias ?: "mousepointer"
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            // default debug signing
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            if (canSignRelease) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = Configs.javaVersion
        targetCompatibility = Configs.javaVersion
    }

    kotlinOptions {
        jvmTarget = Configs.jvmTarget
    }
}

dependencies {
    // Only the Xposed API is needed. Everything runs inside system_server; the module uses
    // plain android.* classes for the single informational activity as well.
    compileOnly("de.robv.android.xposed:api:82")
}
