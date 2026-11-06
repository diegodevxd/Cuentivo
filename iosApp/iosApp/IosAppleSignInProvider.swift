import AuthenticationServices
import FirebaseAuth
import Foundation
import UIKit
import Shared

/// El selector de Apple, implementado en Swift con `AuthenticationServices`.
///
/// El `rawNonce` lo genera Kotlin con `SecRandomCopyBytes` y aquí sólo se hashea para la petición; el
/// valor en claro vuelve a Kotlin junto con el `idToken` para que Firebase lo verifique. El
/// `authorizationCode` se guarda para poder revocar el token al borrar la cuenta, que es lo que exige
/// la App Store.
final class IosAppleSignInProviderImpl: NSObject, IosAppleSignInProvider,
    ASAuthorizationControllerDelegate, ASAuthorizationControllerPresentationContextProviding {

    private var pendingResult: ((IosSignInOutcome) -> Void)?
    private var pendingRawNonce: String?
    private var pendingHost: UIViewController?

    func signIn(host: PlatformAuthHost, onResult: @escaping (IosSignInOutcome) -> Void) {
        let rawNonce = AppleNonce.shared.raw(length: AppleNonce.shared.DEFAULT_BYTE_LENGTH)
        let hashed = AppleNonce.shared.hashed(rawNonce: rawNonce)

        pendingResult = onResult
        pendingRawNonce = rawNonce
        pendingHost = host

        let request = ASAuthorizationAppleIDProvider().createRequest()
        request.requestedScopes = [.fullName, .email]
        request.nonce = hashed

        let controller = ASAuthorizationController(authorizationRequests: [request])
        controller.delegate = self
        controller.presentationContextProvider = self
        controller.performRequests()
    }

    /// Revoca el token de Apple con el SDK de Firebase, que guarda el secreto de cliente en su backend
    /// (proveedor Apple de Firebase Auth). Exige sesión viva: hay que llamarla antes de borrar el usuario.
    func revoke(authorizationCode: String, onResult: @escaping (KotlinBoolean) -> Void) {
        Auth.auth().revokeToken(withAuthorizationCode: authorizationCode) { error in
            DispatchQueue.main.async { onResult(KotlinBoolean(bool: error == nil)) }
        }
    }

    // MARK: - ASAuthorizationControllerDelegate

    func authorizationController(
        controller: ASAuthorizationController,
        didCompleteWithAuthorization authorization: ASAuthorization
    ) {
        defer { clearPending() }
        guard let credential = authorization.credential as? ASAuthorizationAppleIDCredential,
              let tokenData = credential.identityToken,
              let idToken = String(data: tokenData, encoding: .utf8) else {
            pendingResult?(IosSignInOutcome.Failed(reason: "Apple returned no token"))
            return
        }
        let code = credential.authorizationCode.flatMap { String(data: $0, encoding: .utf8) }
        pendingResult?(
            IosSignInOutcome.Success(
                idToken: idToken,
                accessToken: nil,
                rawNonce: pendingRawNonce,
                authorizationCode: code
            )
        )
    }

    func authorizationController(controller: ASAuthorizationController, didCompleteWithError error: Error) {
        defer { clearPending() }
        let nsError = error as NSError
        // ASAuthorizationError.canceled = 1001
        if nsError.domain == ASAuthorizationError.errorDomain, nsError.code == 1001 {
            pendingResult?(IosSignInOutcome.Cancelled.shared)
        } else {
            pendingResult?(IosSignInOutcome.Failed(reason: "Apple sign-in failed"))
        }
    }

    // MARK: - ASAuthorizationControllerPresentationContextProviding

    func presentationAnchor(for controller: ASAuthorizationController) -> ASPresentationAnchor {
        pendingHost?.view.window ?? ASPresentationAnchor()
    }

    private func clearPending() {
        pendingResult = nil
        pendingRawNonce = nil
        pendingHost = nil
    }
}
