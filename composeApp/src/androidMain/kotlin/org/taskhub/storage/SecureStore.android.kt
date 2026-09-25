// Implementación Android de `createSecureStore` (expect/actual multiplatform):
// ver [SecureStore] para el contrato y el KDoc de `createSecureStore` para el
// cifrado y el fallback si el Keystore del dispositivo no está disponible.
package org.taskhub.storage

import org.taskhub.platform.AppLog
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.russhwolf.settings.Settings
import com.russhwolf.settings.SharedPreferencesSettings
import org.taskhub.platform.AndroidContextHolder

private const val SECURE_PREFS_FILE_NAME = "taskhub_secure_prefs"

/**
 * Ver [SecureStore]. Respaldado por `EncryptedSharedPreferences` (Jetpack
 * Security, AES-256-GCM para valores + AES-256-SIV para claves), en un
 * fichero de preferencias SEPARADO del resto de ajustes (que siguen sin
 * cifrar — no hace falta cifrar el tema o el idioma).
 *
 * [AndroidContextHolder.context] ya está fijado por `MainActivity.onCreate()`
 * antes de que Compose monte `App()` (que es lo que dispara la resolución de
 * este singleton de Koin) — ver el resto de usos de `AndroidContextHolder` en
 * `platform/Platform.android.kt`. Si por algún motivo aún no lo estuviera, o
 * si el Keystore del dispositivo falla, cae a `Settings()` sin cifrar en vez
 * de crashear: peor que perder la confidencialidad del token es dejar al
 * usuario sin poder iniciar sesión.
 */
actual fun createSecureStore(): SecureStore {
    val context = AndroidContextHolder.context ?: return SettingsSecureStore(Settings())
    return try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        val encryptedPrefs = EncryptedSharedPreferences.create(
            context,
            SECURE_PREFS_FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        SettingsSecureStore(SharedPreferencesSettings(encryptedPrefs))
    } catch (e: Exception) {
        // Antes este fallback era completamente silencioso: si el Keystore
        // del dispositivo falla, el refresh token pasa a guardarse sin
        // cifrar sin que quede ningún rastro para diagnosticarlo (panel v4,
        // Seguridad — hallazgo MEDIO). No se sube a Analytics (podría
        // filtrar detalles del fallo de Keystore de dispositivos concretos);
        // solo logcat, igual que otros catches best-effort del proyecto.
        AppLog.w("SecureStore", "Keystore no disponible, usando almacenamiento sin cifrar", e)
        SettingsSecureStore(Settings())
    }
}

private class SettingsSecureStore(private val settings: Settings) : SecureStore {
    override fun getString(key: String): String? = settings.getStringOrNull(key)
    override fun putString(key: String, value: String) = settings.putString(key, value)
    override fun remove(key: String) = settings.remove(key)
}
