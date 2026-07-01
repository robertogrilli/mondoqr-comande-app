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
        versionCode = 3
        versionName = "0.3"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
