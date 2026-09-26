/**
 * `actual` Android de [AdController]: implementación real con el SDK de
 * Google Mobile Ads (AdMob) para interstitials, más el plumbing de
 * señalización de contenido dirigido a menores (TFCD).
 */
package org.taskhub.platform

import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import org.taskhub.ads.AdConfig

/**
 * Implementación Android del controlador de anuncios.
 *
 * Carga un [InterstitialAd] de test y lo muestra tras completar una tarea,
 * respetando un cooldown de [INTERSTITIAL_COOLDOWN_MS] entre impresiones para
 * no molestar al usuario. Al descartarse el anuncio, se recarga el siguiente.
 */
object AdControllerImpl : AdController {

    /** Cooldown entre impresiones de interstitial (120 segundos). */
    private const val INTERSTITIAL_COOLDOWN_MS = 120_000L

    /** Anuncio interstitial cargado y listo para mostrar (null si aún carga). */
    @Volatile
    private var interstitialAd: InterstitialAd? = null

    /** Timestamp (epoch ms) del último show. 0 = nunca mostrado. */
    @Volatile
    private var lastShownAtMs: Long = 0L

    /** Evita disparar cargas duplicadas mientras una ya está en curso. */
    @Volatile
    private var isLoading: Boolean = false

    /**
     * Se llama desde [ConsentManager] en cuanto hay consentimiento (o no hace
     * falta) para pedir anuncios. Antes de eso no se carga ningún interstitial:
     * ni AdMob está inicializado ni hay consentimiento TCF v2 (ver
     * ConsentManager.android.kt).
     */
    fun onConsentReady() {
        loadInterstitial()
    }

    /** Carga un interstitial nuevo y lo deja listo para mostrar. */
    private fun loadInterstitial() {
        if (isLoading || !ConsentManager.canRequestAds) return
        val context = AndroidContextHolder.context ?: return
        isLoading = true

        InterstitialAd.load(
            context,
            AdConfig.INTERSTITIAL_AD_UNIT_ID,
            AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    isLoading = false
                    interstitialAd = ad
                    // Al descartarse el anuncio, se recarga el siguiente. También
                    // hay que recargar si el anuncio falla al MOSTRARSE (Activity
                    // fuera de primer plano en ese instante, anuncio ya consumido,
                    // error del SDK): sin este callback, `interstitialAd` se queda
                    // apuntando a un anuncio inválido para siempre y
                    // maybeShowInterstitial() nunca vuelve a llamar a
                    // loadInterstitial() (la guarda `if (ad == null)` no se cumple).
                    ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                        override fun onAdDismissedFullScreenContent() {
                            interstitialAd = null
                            loadInterstitial()
                        }

                        override fun onAdFailedToShowFullScreenContent(error: com.google.android.gms.ads.AdError) {
                            interstitialAd = null
                            loadInterstitial()
                        }

                        override fun onAdShowedFullScreenContent() {
                            lastShownAtMs = System.currentTimeMillis()
                        }
                    }
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    // Antes, si la primera carga fallaba (típico sin red al
                    // arrancar), interstitialAd se quedaba en null para siempre:
                    // ningún llamador reintentaba. maybeShowInterstitial() ahora
                    // reintenta la carga la próxima vez que se necesite.
                    isLoading = false
                    interstitialAd = null
                }
            }
        )
    }

    override fun maybeShowInterstitial() {
        if (!ConsentManager.canRequestAds) return
        val ad = interstitialAd
        if (ad == null) {
            loadInterstitial()
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastShownAtMs < INTERSTITIAL_COOLDOWN_MS) return

        // Se necesita una Activity en primer plano para mostrar el anuncio
        val activity = AndroidContextHolder.activity ?: return

        // lastShownAtMs se fija en onAdShowedFullScreenContent(), no aquí: si
        // ad.show() falla (ver onAdFailedToShowFullScreenContent arriba), el
        // cooldown de 120s no debe arrancar para un anuncio que nunca se vio.
        ad.show(activity)
    }

    override fun isBannerEnabled(): Boolean = AdConfig.bannerEnabled && ConsentManager.canRequestAds
}

/** Implementación Android: siempre devuelve el singleton [AdControllerImpl]. */
actual fun createAdController(): AdController = AdControllerImpl
