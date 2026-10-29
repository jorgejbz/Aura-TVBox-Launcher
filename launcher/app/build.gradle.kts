import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// Release signing comes from keystore.properties (kept out of git).
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
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
        versionCode = 5
        versionName = "1.3.1"
    }

    signingConfigs {
        if (keystoreProps.containsKey("storeFile")) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
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
