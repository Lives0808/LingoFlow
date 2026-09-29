plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.jetbrains.compose)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":jvmCore"))
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.core)
    // Provides Dispatchers.Main on the desktop (Swing/AWT event thread).
    implementation(libs.kotlinx.coroutines.swing)
}

compose.desktop {
    application {
        mainClass = "dev.lingoflow.desktop.MainKt"

        nativeDistributions {
            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Dmg,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb,
            )
            packageName = "LingoFlow"
            packageVersion = "0.3.0"
            description = "Privacy-first translation workflow"
            vendor = "Lives0808"

            windows {
                menu = true
                shortcut = true
                perUserInstall = true
            }
            macOS {
                bundleID = "dev.lingoflow.desktop"
                dockName = "LingoFlow"
                // jpackage/DMG require MAJOR >= 1; the product version stays 0.3.0
                // and the release workflow renames the artifacts accordingly.
                packageVersion = "1.0.0"
                dmgPackageVersion = "1.0.0"
            }
            linux {
                packageName = "lingoflow"
            }
        }
    }
}
