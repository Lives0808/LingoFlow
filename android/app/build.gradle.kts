import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.lingoflow.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.lingoflow.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "0.2.1"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        // Open-source release key. It protects update continuity only — it is not a
        // secret, and the password is documented in android/README.md.
        create("release") {
            storeFile = rootProject.file("keystore/lingoflow.jks")
            storePassword = System.getenv("LINGOFLOW_KEYSTORE_PASSWORD") ?: "lingoflow"
            keyAlias = System.getenv("LINGOFLOW_KEY_ALIAS") ?: "lingoflow"
            keyPassword = System.getenv("LINGOFLOW_KEY_PASSWORD") ?: "lingoflow"
        }
    }

    buildTypes {
        release {
            // Shrinking stays off so the shipped APK behaves exactly like the debug
            // build; the bundle is a single ~9 MB APK for all ABIs.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        localeFilters += listOf("en", "zh")
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.documentfile)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)

    debugImplementation(libs.compose.ui.tooling)
}
