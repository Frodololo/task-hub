/**
 * Puente multiplataforma (expect/actual) hacia el SDK de analytics nativo
 * de cada target: Firebase Analytics en Android, no-op en iOS/JVM.
 */
package org.taskhub.platform

/**
 * Registro de eventos de analytics, independiente de plataforma.
 *
 * En Android delega en Firebase Analytics; en iOS y JVM (desktop) es no-op.
 * Se expone como `expect fun` (igual que [shareText]) para poder invocarse
 * directamente desde `commonMain` sin pasar por DI.
 *
 * `params` son pares clave-valor simples. Firebase Analytics permite hasta
 * 25 parámetros por evento y prohíbe PII (no loguear emails, nombres, ni IDs
 * de usuario). Mantenemos los eventos con parámetros mínimos o vacíos.
 *
 * Eventos que registra la app (métricas del plan de marketing):
 *   - `household_created`   → usuario crea un hogar (activación)
 *   - `household_joined`    → usuario entra con código de invitación (loop viral)
 *   - `invite_code_shared`  → usuario comparte su código (loop viral)
 *   - `task_completed`      → tarea marcada como hecha (engagement/racha)
 */
expect fun logAnalyticsEvent(eventName: String, params: Map<String, String> = emptyMap())

/**
 * Activa/desactiva la recolección de Firebase Analytics (RGPD: opt-out desde
 * Ajustes, ver [org.taskhub.storage.SettingsStore.isAnalyticsOptOut]). No-op
 * en plataformas sin Analytics integrado (iOS/JVM/wasmJs).
 */
expect fun setAnalyticsCollectionEnabled(enabled: Boolean)
