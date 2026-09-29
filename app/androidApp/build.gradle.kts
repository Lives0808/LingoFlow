plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.lingoflow.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.lingoflow.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "0.3.0"
        vectorDrawables { useSupportLibrary = true }
        ndk {
            // Ship the two architectures that matter (phones + emulators); the
            // on-device OCR model is ~11 MB per ABI.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("keystore/lingoflow.jks")
            storePassword = System.getenv("LINGOFLOW_KEYSTORE_PASSWORD") ?: "lingoflow"
            keyAlias = System.getenv("LINGOFLOW_KEY_ALIAS") ?: "lingoflow"
            keyPassword = System.getenv("LINGOFLOW_KEY_PASSWORD") ?: "lingoflow"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }

    androidResources {
        localeFilters += listOf("en", "zh")
    }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":jvmCore"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)

    implementation(libs.mlkit.text)
    implementation(libs.mlkit.text.chinese)
    implementation(libs.kotlinx.coroutines.core)
}
