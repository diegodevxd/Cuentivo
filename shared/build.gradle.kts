// Módulo compartido entre Android e iOS.
//
// Aquí vive el dominio puro (sin Context, sin Room, sin Android), que ambas apps consumen sin
// duplicar la lógica financiera. En Windows los targets de iOS se desactivan solos; la compilación
// real de iOS la hace GitHub Actions en un runner macOS (ver .github/workflows/ios-build.yml).
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

kotlin {
    // AGP 9 renombró el bloque `androidLibrary { }` a `android { }` dentro del plugin KMP.
    android {
        namespace = "com.cuentivo.shared"
        compileSdk = 36
        minSdk = 24
    }

    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
        }
    }
}
