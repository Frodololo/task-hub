/**
 * `actual` web (wasmJs) de [logAnalyticsEvent]: no-op, ver detalle abajo.
 */
package org.taskhub.platform

/**
 * No-op en web: no hay SDK de Firebase Analytics integrado en el build
 * estático todavía.
 */
actual fun logAnalyticsEvent(eventName: String, params: Map<String, String>) {
    // No-op
}

/** No-op en web: no hay SDK de Firebase Analytics integrado en el build estático todavía. */
actual fun setAnalyticsCollectionEnabled(enabled: Boolean) {
    // No-op
}
