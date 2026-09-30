/**
 * Punto de entrada del target iOS. La app Swift (Xcode) instancia el
 * `UIViewController` devuelto por [MainViewController] como su vista raíz;
 * a partir de ahí todo el árbol es Compose Multiplatform compartido.
 */
package org.taskhub

import androidx.compose.ui.window.ComposeUIViewController
import org.taskhub.ui.theme.TaskHubTheme

/**
 * Crea el `UIViewController` que aloja [App]. `enforceStrictPlistSanity =
 * false` evita que Compose exija claves de Info.plist que este proyecto no
 * necesita. Sin deep link propio: iOS no genera hoy notificaciones del
 * sistema que abran la app en una pantalla concreta (ver KDoc de [App]).
 */
fun MainViewController() = ComposeUIViewController(
    configure = { enforceStrictPlistSanityCheck = false }
) {
    TaskHubTheme {
        App()
    }
}
