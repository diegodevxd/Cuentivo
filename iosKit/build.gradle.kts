// Módulo iOS-only: aquí vive lo que enlaza Firebase.
//
// Va aparte de :shared a propósito. GitLive hace cinterop contra los frameworks de Firebase, y si eso
// viviera dentro de :shared, el binario de pruebas de iOS se quedaría con símbolos sin resolver y
// perderíamos las pruebas comunes en iOS. :shared no depende de GitLive ni de Firebase.
//
// El framework se sigue llamando "Shared" para no cambiar el `import Shared` de Swift.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }

    sourceSets {
        iosMain.dependencies {
            // `api`: las entidades, el dominio y la base tienen que llegar al framework que Swift ve.
            api(project(":shared"))

            api(libs.firebase.auth)
            api(libs.firebase.firestore)
            api(libs.firebase.functions)

            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
        }
    }
}
