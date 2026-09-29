import UIKit
import SwiftUI
import ComposeApp

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
    var body: some View {
        ComposeView()
            .ignoresSafeArea(.keyboard) // Compose maneja su propio teclado
            .onOpenURL { url in
                // Callback del flujo de Google Sign-In (ver
                // Platform.ios.kt launchGoogleSignIn / GoogleIosSignInHelper):
                // solo procesamos URLs con el scheme reverse-DNS del client
                // ID iOS; cualquier otro esquema se ignora.
                guard url.scheme == GoogleOAuthConfigKt.GOOGLE_IOS_REVERSED_CLIENT_ID else { return }
                // GoogleIosSignInHelper es un `object` de Kotlin (mismo
                // patrón que GoogleSignInResultHolder): extrae `code` de la
                // query string, lo canjea por un id_token (authorization-code
                // + PKCE — el flujo implícito anterior lo rechazaba Google con
                // "Error 400: unsupported_response_type") y publica el
                // resultado en GoogleSignInResultHolder. No bloquea: el canje
                // ocurre en segundo plano dentro de Kotlin.
                GoogleIosSignInHelper.shared.processCallback(callbackUrl: url.absoluteString)
            }
    }
}
