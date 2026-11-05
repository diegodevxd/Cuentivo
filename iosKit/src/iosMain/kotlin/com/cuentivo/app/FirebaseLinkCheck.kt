package com.cuentivo.app

import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.auth
import dev.gitlive.firebase.firestore.firestore
import dev.gitlive.firebase.functions.functions

/**
 * Obliga al enlazador a resolver los símbolos de Firebase.
 *
 * No lee ni escribe datos, ni abre ninguna conexión: sólo consulta las tres propiedades que GitLive
 * expone sobre `Firebase`. Sin esta referencia, el enlazador estático podría descartar los objetos de
 * Firebase que todavía nadie llama, y el framework compilaría sin ellos para luego fallar en
 * ejecución cuando el encargo siguiente los use de verdad.
 *
 * Se llama desde `MainViewController`, después de que `AppDelegate` haya corrido
 * `FirebaseApp.configure()`: las tres propiedades construyen su instancia y eso exige que la app de
 * Firebase ya esté configurada.
 */
internal fun firebaseLinkCheck(): List<Any> =
    listOf(Firebase.firestore, Firebase.auth, Firebase.functions)
