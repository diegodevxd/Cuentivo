import AuthenticationServices
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
    private var revokeCompletion: ((Bool) -> Void)?

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

    /// Revoca el token de Apple con el código de autorización que devolvió el selector.
    func revoke(authorizationCode: String, onResult: @escaping (Bool) -> Void) {
        revokeCompletion = onResult
        let clientID = FirebaseApp.app()?.options.clientID ?? ""
        let urlString = "https://appleid.apple.com/auth/revoke"
        guard var request = URLComponents(string: urlString) else {
            onResult(false)
            return
        }
        var items = [
            URLQueryItem(name: "client_id", value: clientID),
            URLQueryItem(name: "token", value: authorizationCode),
            URLQueryItem(name: "token_type_hint", value: "authorization_code"),
        ]
        // Sin el secret de cliente el revoke no puede validar; se intenta igual y, si falla, el
        // borrado de la cuenta sigue (lo que no puede quedarse a medias es la cuenta de Firebase).
        items.append(URLQueryItem(name: "client_secret", value: ""))
        request.queryItems = items

        var urlRequest = URLRequest(url: URL(string: urlString)!)
        urlRequest.httpMethod = "POST"
        urlRequest.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        let body = items.map { "\($0.name)=\($0.value ?? "")" }.joined(separator: "&")
        urlRequest.httpBody = body.data(using: .utf8)

        URLSession.shared.dataTask(with: urlRequest) { _, response, _ in
            let ok = (response as? HTTPURLResponse)?.statusCode == 200
            DispatchQueue.main.async { self.revokeCompletion?(ok) }
        }.resume()
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
            pendingResult?(IosSignInOutcome.Cancelled())
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
        revokeCompletion = nil
    }
}
