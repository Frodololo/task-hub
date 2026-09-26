/**
 * `actual` JVM (desktop) de [logAnalyticsEvent]: no-op, ver detalle abajo.
 */
package org.taskhub.platform

/**
 * No-op en JVM (desktop): la distribución desktop es secundaria y no
 * está pensada para analytics en esta fase.
 */
actual fun logAnalyticsEvent(eventName: String, params: Map<String, String>) {
    // No-op
}

/** No-op en JVM (desktop): Analytics no está integrado en este target. */
actual fun setAnalyticsCollectionEnabled(enabled: Boolean) {
    // No-op
}
