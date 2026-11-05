// Módulo compartido entre Android e iOS.
//
// Aquí vive el dominio puro (sin Context, sin Room, sin Android), que ambas apps consumen sin
// duplicar la lógica financiera. En Windows los targets de iOS se desactivan solos; la compilación
// real de iOS la hace GitHub Actions en un runner macOS (ver .github/workflows/ios-build.yml).
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    // Compose Multiplatform: la pantalla mínima de iOS (MainViewController.kt).
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    // AGP 9 renombró el bloque `androidLibrary { }` a `android { }` dentro del plugin KMP.
    android {
        namespace = "com.cuentivo.shared"
        compileSdk = 36
        minSdk = 24

        // Pruebas del código común que corren en la JVM del host (`:shared:testAndroidHostTest`),
        // sin emulador. Es donde se ejecutan las pruebas de `commonTest` en esta máquina.
        withHostTestBuilder {}.configure {
        }
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { iosTarget ->
        // El framework que Xcode enlaza; el nombre coincide con `import Shared` en iosApp.
        iosTarget.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            // Fechas de negocio (`...EpochDay`) sin `java.time`, disponible en Android e iOS. Va en
            // `api` porque `kotlinx.datetime.LocalDate` aparece en la firma pública de
            // OverdueReminderPolicy, que la app llama.
            api(libs.kotlinx.datetime)

            // La pantalla mínima de iOS. En Android, Compose Multiplatform resuelve a los mismos
            // artefactos de androidx.compose que ya usa la app.
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
