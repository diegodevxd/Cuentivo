# Encargo iOS — C3: nombres reales del cinterop en F4-B

**Implementador:** OpenCode (DeepSeek v4.1 Flash). **SIN DELEGAR.** Sin pedir confirmación.
**Carpeta:** `C:\Users\pibed\orca\workspaces\OrganizationLabs-neo-fintech\cuentivo-ios`.
No hagas push. Commit `fix(ios): ...`.

`ios-build` (run 42139153490) falla por nombres escritos sin comprobarlos. Windows no compila iOS: **no adivines
ningún nombre, cópialo de la fuente indicada abajo.** Solo se tocan los dos archivos listados; la lógica, los casos y
los valores esperados no cambian.

## 1. `shared/src/iosTest/.../sync/FirestoreValueNormalizerTest.kt`

`Unresolved reference 'numberWithLongLong' / 'numberWithDouble' / 'numberWithBool'`. Kotlin/Native expone esos
inicializadores como constructores: `NSNumber(longLong = 5L)`, `NSNumber(double = 10.5)` y `NSNumber(bool = true)`.
Reemplázalos todos.

## 2. `iosKit/src/iosMain/.../sync/IosFirestoreSyncGateway.kt`

El paquete es **`cocoapods.FirebaseFirestoreInternal`**, no `platform.FirebaseFirestore`: lo confirma la línea 9 del
`firestore.kt` de GitLive. Corrige los imports. Casi todos los demás errores eran consecuencia de ese import, pero
revisa **cada** llamada nativa contra GitLive v2.8.0, que usa exactamente estas APIs:

- https://raw.githubusercontent.com/GitLiveApp/firebase-kotlin-sdk/v2.8.0/firebase-firestore/src/appleMain/kotlin/dev/gitlive/firebase/firestore/firestore.kt
- https://raw.githubusercontent.com/GitLiveApp/firebase-kotlin-sdk/v2.8.0/firebase-firestore/src/appleMain/kotlin/dev/gitlive/firebase/firestore/internal/NativeDocumentReference.kt
- https://raw.githubusercontent.com/GitLiveApp/firebase-kotlin-sdk/v2.8.0/firebase-firestore/src/appleMain/kotlin/dev/gitlive/firebase/firestore/internal/NativeQueryWrapper.kt
- https://raw.githubusercontent.com/GitLiveApp/firebase-kotlin-sdk/v2.8.0/firebase-firestore/src/appleMain/kotlin/dev/gitlive/firebase/firestore/internal/NativeCollectionReferenceWrapper.kt
- https://raw.githubusercontent.com/GitLiveApp/firebase-kotlin-sdk/v2.8.0/firebase-firestore/src/appleMain/kotlin/dev/gitlive/firebase/firestore/internal/NativeFirebaseFirestoreWrapper.kt

Comprueba contra ellos: `documentWithPath`, `collectionWithPath`, `getDocumentWithSource`, `getDocumentsWithSource`,
`FIRFirestoreSourceServer`, `setData` con y sin `merge`, `queryWhereField`, `queryOrderedByField`, `queryLimitedTo`,
`documentID`, `exists`, `documents`, `data()`. Si GitLive usa otra forma, usa la suya. Pon tipo explícito en los
parámetros de los callbacks (`Cannot infer type`): los errores de tipo restantes del log venían de ahí.

Si `cocoapods.FirebaseFirestoreInternal` no fuera visible para `:iosKit` con `api(libs.firebase.firestore)`, eso solo
se sabrá en el runner: no agregues dependencias para prevenirlo.

## Reporte

`C:/tmp/oc-ios-c3-reporte.md`: tabla de cada llamada nativa → la línea de GitLive que la respalda, y hash del commit.
Al terminar: `ENCARGO-IOS-C3-TERMINADO`. Sin Gradle: no cambia nada de Android.
