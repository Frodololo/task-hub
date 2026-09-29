// Capa de persistencia (storage/), implementación iOS del contrato
// [SecureStore] (`actual` de la `expect fun createSecureStore()` común).

package org.taskhub.storage

import kotlinx.cinterop.CPointed
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.interpretCPointer
import kotlinx.cinterop.interpretObjCPointer
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.objcPtr
import kotlinx.cinterop.ptr
import kotlinx.cinterop.rawValue
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFTypeRefVar
import platform.Foundation.NSData
import platform.Foundation.NSMutableDictionary
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

private const val KEYCHAIN_SERVICE = "org.taskhub.secure"

/**
 * Ver [SecureStore]. Respaldado por el Keychain de iOS (`kSecClassGenericPassword`),
 * accedido con las funciones C `SecItem*` de Security.framework y un
 * `NSMutableDictionary` como query.
 *
 * `CFStringRef`/`CFDictionaryRef` son "toll-free bridged" con `NSString`/
 * `NSDictionary` en el runtime de Objective-C/CoreFoundation (el mismo
 * objeto en memoria), pero Kotlin/Native los expone como tipos Kotlin
 * distintos e incompatibles entre sí (`CPointer<...>` vs las clases
 * `NSString`/`NSMutableDictionary` generadas para el interop con Obj-C).
 * Un `as NSString`/`as CFDictionaryRef` normal comprueba la jerarquía real
 * de clases Kotlin (que no las relaciona) y lanza `TypeCastException` en
 * tiempo de ejecución — confirmado en build real ("class
 * kotlinx.cinterop.CPointer cannot be cast to class platform.Foundation.NSString"),
 * ver [asNSString]. [reinterpret] sí es correcto: reinterpreta el puntero
 * sin comprobar la jerarquía, que es justo lo que hace falta para un tipo
 * realmente compatible a nivel de runtime pero no a nivel del sistema de
 * tipos estático de Kotlin.
 */
@OptIn(ExperimentalForeignApi::class)
actual fun createSecureStore(): SecureStore = KeychainSecureStore()

@OptIn(ExperimentalForeignApi::class)
private class KeychainSecureStore : SecureStore {

    private fun baseQuery(key: String): NSMutableDictionary {
        val dict = NSMutableDictionary()
        dict.setObject(kSecClassGenericPassword.asNSString(), forKey = kSecClass.asNSString())
        dict.setObject(KEYCHAIN_SERVICE, forKey = kSecAttrService.asNSString())
        dict.setObject(key, forKey = kSecAttrAccount.asNSString())
        return dict
    }

    override fun getString(key: String): String? = memScoped {
        val query = baseQuery(key)
        query.setObject(true, forKey = kSecReturnData.asNSString())
        query.setObject(kSecMatchLimitOne.asNSString(), forKey = kSecMatchLimit.asNSString())

        val resultVar = alloc<CFTypeRefVar>()
        val status = SecItemCopyMatching(query.asCFDictionaryRef(), resultVar.ptr)
        if (status != errSecSuccess) return@memScoped null
        val data = resultVar.value?.let { interpretObjCPointer<NSData>(it.rawValue) } ?: return@memScoped null
        NSString.create(data, NSUTF8StringEncoding) as? String
    }

    override fun putString(key: String, value: String) {
        // Borra cualquier valor previo primero: así no hace falta distinguir
        // "crear" de "actualizar" (SecItemAdd falla si el ítem ya existe).
        remove(key)
        val data = (value as NSString).dataUsingEncoding(NSUTF8StringEncoding) ?: return
        val attributes = baseQuery(key)
        attributes.setObject(data, forKey = kSecValueData.asNSString())
        SecItemAdd(attributes.asCFDictionaryRef(), null)
    }

    override fun remove(key: String) {
        SecItemDelete(baseQuery(key).asCFDictionaryRef())
    }
}

/**
 * Ver el KDoc de [createSecureStore]: bridging CFStringRef → NSString.
 * `reinterpret()` NO aplica aquí (solo funciona entre `CPointer<CPointed>`,
 * es decir structs C, no hacia clases envoltorio de Objective-C como
 * `NSString` — confirmado por el compilador). [interpretObjCPointer] sí es
 * la función correcta de Kotlin/Native para tratar un puntero crudo como una
 * referencia a una clase de Objective-C.
 */
@OptIn(ExperimentalForeignApi::class)
private fun CPointer<*>?.asNSString(): NSString = interpretObjCPointer(this!!.rawValue)

/**
 * Bridging en la dirección contraria a [asNSString]: `NSMutableDictionary` →
 * `CFDictionaryRef`. Un `as CFDictionaryRef` normal también falla en tiempo
 * de ejecución — confirmado en build real ("class
 * kotlin.native.internal.NSDictionaryAsKMap cannot be cast to class
 * kotlinx.cinterop.CPointer"), porque Kotlin/Native ve las instancias de
 * `NSMutableDictionary` a través de un wrapper (`NSDictionaryAsKMap`) que
 * tampoco es un `CPointer` desde el sistema de tipos de Kotlin. [objcPtr]
 * obtiene el puntero crudo del objeto Objective-C, e [interpretCPointer]
 * lo reinterpreta como el `CPointer` de CoreFoundation correspondiente.
 */
@OptIn(ExperimentalForeignApi::class)
private fun NSMutableDictionary.asCFDictionaryRef(): CFDictionaryRef =
    interpretCPointer<CPointed>(this.objcPtr())!!.reinterpret()
