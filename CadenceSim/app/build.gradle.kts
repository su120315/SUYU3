plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.cadencesim"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.cadencesim"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "1.2"
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
            // v1 + v2 + v3 全部开启，最大兼容老 ROM / 严苛的安装器策略
            signingConfig?.let { sc ->
                sc.enableV1Signing = true
                sc.enableV2Signing = true
                sc.enableV3Signing = true
            }
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
