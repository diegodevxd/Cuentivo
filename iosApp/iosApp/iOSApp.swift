import BackgroundTasks
import SwiftUI
import FirebaseCore
import FirebaseAppCheck
import Shared

/// El delegado configura Firebase y deja listo el trabajo de sincronización de fondo.
///
/// SwiftUI no tiene un punto de entrada equivalente a `Application.onCreate`; este adaptador corre
/// `didFinishLaunchingWithOptions` antes de crear el `WindowGroup`. El registro del
/// `BGProcessingTask` **tiene que ocurrir aquí**, antes de que el método devuelva, o iOS cancela la
/// tarea cuando el sistema la lanza.
class AppDelegate: NSObject, UIApplicationDelegate {
    static let syncTaskIdentifier = "com.cuentivo.app.sync"

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        // App Check primero, y por eso está aquí y no en un servicio: instalar el proveedor después
        // de configurar Firebase dejaría la primera petición sin token. Es el mismo reparto que en
        // Android (AppCheckInstaller corre antes de tocar Firestore o Auth): en depuración y en el
        // simulador no hay Secure Enclave que atestigüe, así que se usa el proveedor de depuración;
        // en un dispositivo real va App Attest.
        #if DEBUG || targetEnvironment(simulator)
        AppCheck.setAppCheckProviderFactory(AppCheckDebugProviderFactory())
        #else
        AppCheck.setAppCheckProviderFactory(AppAttestProviderFactory())
        #endif

        FirebaseApp.configure()
        registerSyncTask()
        return true
    }

    private func registerSyncTask() {
        BGTaskScheduler.shared.register(
            forTaskWithIdentifier: Self.syncTaskIdentifier,
            using: nil
        ) { task in
            guard let processingTask = task as? BGProcessingTask else {
                task.setTaskCompleted(success: false)
                return
            }
            self.handle(processingTask)
        }
    }

    /// Encadena la siguiente y corre una vez el motor. `setTaskCompleted` se llama exactamente una
    /// vez: el flag `completed` hace que el handler de expiración y el callback no se pisen.
    private func handle(_ task: BGProcessingTask) {
        scheduleSync()

        var completed = false
        let complete: (Bool) -> Void = { success in
            if completed { return }
            completed = true
            task.setTaskCompleted(success: success)
        }

        task.expirationHandler = {
            // iOS se lleva la tarea: se cierra como fallida y la próxima corrida reintenta.
            complete(false)
        }

        DispatchQueue.global(qos: .utility).async {
            IosSyncBridge.shared.syncNow(completion: { success in
                complete(success.boolValue)
            })
        }
    }

    /// Reprograma el trabajo de fondo. iOS no garantiza cuándo lo lanzará, por eso también se pide
    /// una corrida al volver a primer plano.
    func scheduleSync() {
        let request = BGProcessingTaskRequest(identifier: Self.syncTaskIdentifier)
        request.requiresNetworkConnectivity = true
        request.requiresExternalPower = false
        request.earliestBeginDate = Date(timeIntervalSinceNow: 15 * 60)
        do {
            try BGTaskScheduler.shared.submit(request)
        } catch {
            // Simulador y builds de depuración no tienen el scheduler disponible; no es fatal.
            print("BGTaskScheduler no pudo reprogramar: \(error.localizedDescription)")
        }
    }
}

@main
struct iOSApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) var delegate
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
        .onChange(of: scenePhase) { phase in
            // iOS no garantiza las tareas de fondo, así que abrir la app también sincroniza. El
            // bridge corre en Dispatchers.Default: el gateway bloquea y no puede tocar el main.
            if phase == .active {
                IosSyncBridge.shared.syncNow(completion: { _ in })
                delegate.scheduleSync()
            }
        }
    }
}
