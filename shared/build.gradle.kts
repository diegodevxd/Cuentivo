// Módulo compartido entre Android e iOS.
//
// Aquí vive el dominio puro y, desde la Fase 3, también Room: entidades, DAOs y `CuentivoDatabase`
// en `commonMain`, con KSP por target. Lo que es de una sola plataforma —las migraciones en Android,
// la apertura con `BundledSQLiteDriver` en iOS— vive en su source set.
//
// En Windows los targets de iOS se desactivan solos; la compilación real de iOS la hace GitHub
// Actions en un runner macOS (ver .github/workflows/ios-build.yml).
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    // Compose Multiplatform: la pantalla mínima de iOS (MainViewController.kt).
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

// Los esquemas exportados siguen en `app/schemas`, en la misma ruta de siempre: el JSON de la
// version 8 lo lee `MigrationTestHelper` desde los assets de `androidTest` y no puede cambiar.
room {
    schemaDirectory("${rootProject.projectDir}/app/schemas")
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

            // Room multiplataforma: `CuentivoDatabase` y las entidades son la superficie que
            // consume la app de Android.
            api(libs.androidx.room.runtime)

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

// El procesador de Room corre por target: sin esto no se genera el `actual` de
// `CuentivoDatabaseConstructor` ni el esquema.
dependencies {
    add("kspAndroid", libs.androidx.room.compiler)
    add("kspIosArm64", libs.androidx.room.compiler)
    add("kspIosSimulatorArm64", libs.androidx.room.compiler)
}
