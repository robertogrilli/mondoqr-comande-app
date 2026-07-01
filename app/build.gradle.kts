plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "it.mondoqr.comande"
    compileSdk = 34

    defaultConfig {
        applicationId = "it.mondoqr.comande"
        minSdk = 24
        targetSdk = 34
        versionCode = 5
        versionName = "0.5"
    }

    signingConfigs {
        create("stable") {
            val pwd = System.getenv("SIGNING_STORE_PASSWORD")
            if (pwd != null) {
                storeFile = file("keystore.p12")
                storeType = "PKCS12"
                storePassword = pwd
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // firma con la chiave FISSA (secret CI) → gli update sostituiscono l'app senza "conflitto"
            if (System.getenv("SIGNING_STORE_PASSWORD") != null) signingConfig = signingConfigs.getByName("stable")
        }
        release {
            isMinifyEnabled = false
            if (System.getenv("SIGNING_STORE_PASSWORD") != null) signingConfig = signingConfigs.getByName("stable")
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
    // Libreria ESC/POS de-facto (BT/TCP/USB + formattazione + taglio). NON reinventata.
    implementation("com.github.DantSu:ESCPOS-ThermalPrinter-Android:3.3.0")
}
