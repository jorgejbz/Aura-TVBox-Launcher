import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// Release signing comes from llave-firma/keystore.properties next to launcher/ (kept out of git).
// storeFile inside it is relative to that file.
val keystoreFile = listOf(rootProject.file("../llave-firma/keystore.properties"), rootProject.file("keystore.properties"))
    .firstOrNull { it.exists() }
val keystoreProps = Properties().apply {
    keystoreFile?.inputStream()?.use { load(it) }
}

android {
    namespace = "com.jajua.aura"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.jajua.aura"
        // Low minSdk on purpose: the TV box's real Android version is unverified.
        minSdk = 21
        targetSdk = 36
        // versionCode only ever grows (Android refuses downgrades); 1.0 is the first public release.
        versionCode = 6
        versionName = "1.0"
    }

    signingConfigs {
        if (keystoreProps.containsKey("storeFile")) {
            create("release") {
                storeFile = File(keystoreFile!!.parentFile, keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            optimization {
                enable = true
            }
            // Without keystore.properties (e.g. a fresh clone) fall back to the debug key so the APK
            // still installs. Updating an Aura signed with the release key then needs an uninstall.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
