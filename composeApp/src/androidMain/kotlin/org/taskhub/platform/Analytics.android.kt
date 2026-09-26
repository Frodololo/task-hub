/**
 * `actual` Android de [logAnalyticsEvent]: delega en Firebase Analytics.
 */
package org.taskhub.platform

import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics

/**
 * Implementación Android de [logAnalyticsEvent] vía Firebase Analytics.
 *
 * Usa el `AndroidContextHolder.context` (applicationContext, seteado en
 * `MainActivity.onCreate`). Todos los eventos se disparan desde interacciones
 * del usuario (tras el arranque), así que el contexto siempre está disponible.
 */
actual fun logAnalyticsEvent(eventName: String, params: Map<String, String>) {
    val context = AndroidContextHolder.context ?: return
    val firebaseAnalytics = FirebaseAnalytics.getInstance(context)
    val bundle = Bundle().apply {
        params.forEach { (key, value) -> putString(key, value) }
    }
    firebaseAnalytics.logEvent(eventName, bundle)
}

/** Implementación Android de [setAnalyticsCollectionEnabled] vía Firebase Analytics. */
actual fun setAnalyticsCollectionEnabled(enabled: Boolean) {
    val context = AndroidContextHolder.context ?: return
    FirebaseAnalytics.getInstance(context).setAnalyticsCollectionEnabled(enabled)
}
