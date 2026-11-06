import Foundation
import UIKit
import GoogleSignIn
import Shared

/// El selector de Google, implementado en Swift porque `GoogleSignIn-iOS` no existe en Kotlin.
///
/// Kotlin recibe sólo el material de credencial; la credencial de Firebase la arma allí. El
/// `CLIENT_ID` sale de la configuración de Firebase ya cargada (`FirebaseApp.app()?.options.clientID`),
/// no de una constante copiada a mano, para que no haya dos fuentes de verdad.
final class IosGoogleSignInProviderImpl: NSObject, IosGoogleSignInProvider {

    func signIn(host: PlatformAuthHost, onResult: @escaping (IosSignInOutcome) -> Void) {
        guard let clientID = FirebaseApp.app()?.options.clientID else {
            onResult(IosSignInOutcome.Failed(reason: "Firebase is not configured"))
            return
        }
        GIDSignIn.sharedInstance.configuration = GIDConfiguration(clientID: clientID)
        GIDSignIn.sharedInstance.signIn(withPresenting: host) { result, error in
            if let error = error {
                let nsError = error as NSError
                // kGIDSignInErrorCodeCanceled = -5: la persona cerró el selector.
                if nsError.domain == kGIDSignInErrorDomain, nsError.code == -5 {
                    onResult(IosSignInOutcome.Cancelled.shared)
                } else {
                    onResult(IosSignInOutcome.Failed(reason: "Google sign-in failed"))
                }
                return
            }
            guard let user = result?.user, let idToken = user.idToken?.tokenString else {
                onResult(IosSignInOutcome.Failed(reason: "Google returned no token"))
                return
            }
            onResult(
                IosSignInOutcome.Success(
                    idToken: idToken,
                    accessToken: user.accessToken.tokenString,
                    rawNonce: nil,
                    authorizationCode: nil
                )
            )
        }
    }
}
