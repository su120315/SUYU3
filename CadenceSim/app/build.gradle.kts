plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.cadencesim"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.cadencesim"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
        setProperty("archivesBaseName", "CadenceSim")
    }

    signingConfigs {
        create("release") {
            val storeFileProperty = rootProject.file("app/release.jks")
            storeFile = storeFileProperty
            storePassword = "cadence123"
            keyAlias = "release"
            keyPassword = "cadence123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            isDebuggable = false
            isJniDebuggable = false
            isPseudoLocalesEnabled = false
            signingConfig = signingConfigs.getByName("release")
            isZipAlignEnabled = true
        }
        debug {
            isDebuggable = true
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
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
}
