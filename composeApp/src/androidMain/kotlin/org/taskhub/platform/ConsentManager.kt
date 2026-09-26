/**
 * Flujo de consentimiento TCF v2 vía Google User Messaging Platform (UMP).
 * Obligatorio en EEE/Reino Unido antes de inicializar AdMob: sin un CMP
 * homologado, la cuenta de AdMob arriesga suspensión (aprobado 2026-09-26).
 */
package org.taskhub.platform

import android.app.Activity
import com.google.android.gms.ads.MobileAds
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform

/**
 * Punto único para pedir/comprobar el consentimiento de anuncios antes de
 * tocar el SDK de AdMob.
 *
 * [requestConsent] debe llamarse una vez por arranque, desde `MainActivity`,
 * antes de que cualquier otra parte de la app intente cargar un anuncio.
 * Mientras [canRequestAds] sea `false`, [AdControllerImpl] no debe cargar ni
 * mostrar nada (ver sus comprobaciones en `AdController.android.kt`).
 */
object ConsentManager {

    /** `true` una vez que hay consentimiento (o no hace falta) para pedir anuncios. */
    @Volatile
    var canRequestAds: Boolean = false
        private set

    /**
     * Actualiza la información de consentimiento con UMP y, si la región del
     * usuario lo exige (EEE/Reino Unido), muestra el formulario TCF v2. Al
     * terminar — con formulario mostrado, sin necesidad de mostrarlo, o si la
     * actualización falla pero ya había un consentimiento válido de una
     * sesión anterior — comprueba [ConsentInformation.canRequestAds]: solo si
     * es `true` se inicializa AdMob y se precarga el interstitial.
     */
    fun requestConsent(activity: Activity) {
        val consentInformation = UserMessagingPlatform.getConsentInformation(activity)

        // Para forzar el formulario en desarrollo (simula geografía EEE) sin
        // afectar producción, descomentar y añadir a ConsentRequestParameters
        // con .setConsentDebugSettings(debugSettings):
        // val debugSettings = ConsentDebugSettings.Builder(activity)
        //     .setDebugGeography(ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_EEA)
        //     .addTestDeviceHashedId(...)
        //     .build()
        val params = ConsentRequestParameters.Builder().build()

        consentInformation.requestConsentInfoUpdate(
            activity,
            params,
            {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) {
                    onConsentInfoReady(activity, consentInformation)
                }
            },
            { onConsentInfoReady(activity, consentInformation) }
        )
    }

    private fun onConsentInfoReady(activity: Activity, consentInformation: ConsentInformation) {
        if (!consentInformation.canRequestAds()) return
        canRequestAds = true
        // MobileAds.initialize() hace I/O de red — igual que antes (ver
        // comentario de TaskHubApplication), se difiere a un hilo de fondo.
        Thread { MobileAds.initialize(activity.applicationContext) }.start()
        AdControllerImpl.onConsentReady()
    }

    // Solo para desarrollo: reinicia el estado de consentimiento para volver
    // a ver el formulario UMP en el próximo requestConsent(). NO usar en
    // producción — descomentar solo temporalmente para probar el flujo.
    // fun resetForTesting(activity: Activity) {
    //     UserMessagingPlatform.getConsentInformation(activity).reset()
    // }
}
