import SwiftUI
import FirebaseCore
import Shared

/// El delegado existe sólo para configurar Firebase antes de que arranque la vista.
///
/// SwiftUI no tiene un punto de entrada equivalente a `Application.onCreate`; este adaptador corre
/// `didFinishLaunchingWithOptions` antes de crear el `WindowGroup`, así que cuando
/// `MainViewController` consulta `Firebase.firestore`/`auth`/`functions` la app de Firebase ya está
/// configurada. El `GoogleService-Info.plist` lo deja CI en el bundle (es un secreto, no está en git).
class AppDelegate: NSObject, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        FirebaseApp.configure()
        return true
    }
}

@main
struct iOSApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) var delegate

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
