# Panel de expertos v24 — Auditoría integral (2026-10-01)

HEAD: `b65907c` (v0.7.56). Panel de 15 expertos auditando el código completo, contrastando contra `docs/review-panel-expertos-2026-09-30-v23.md`.

**Metodología:** 15 subagentes en paralelo (8 oleada A + 7 oleada B), cada uno con lectura completa de su área + verificación directa en código.

**Hallazgos totales: ~90** (18 CRÍTICOS/IMPORTANTES nuevos, ~60 APLICA YA de bajo riesgo, ~12 PROPUESTAS)

---

## Resumen ejecutivo

**Hallazgos más importantes de la ronda:**

1. **CRÍTICO — `enforceAppCheck: true` sin AppCheck en cliente**: las 6 Cloud Functions rechazan TODAS las peticiones porque `CloudFunctionsClient.call()` solo envía `Authorization: Bearer`, no `X-Firebase-AppCheck`. El flujo central de puntos (completeTask, donatePoints, redeemReward) está completamente roto. Detallado por 3 expertos (Seguridad, Funcionalidad, QA).

2. **CRÍTICO — Analytics recoge datos antes del consentimiento UMP**: `setAnalyticsCollectionEnabled` se ejecuta en `MainActivity.onCreate()` línea 190, ANTES que `ConsentManager.requestConsent()` línea 204. En EEE, datos de la sesión se envían sin consentimiento GDPR.

3. **CRÍTICO — iOS PKCE: 3 bugs de seguridad**: falta `state` OAuth (CSRF), `pendingCodeVerifier` sin `@Volatile`, stale callback race que rompe sign-in.

4. **CRÍTICO — CalendarSyncManager: `consecutiveTokenFailures` sin mutex**: read-modify-write no atómico desde múltiples coroutines. Data race que puede desvincular Calendar prematuramente.

5. **CRÍTICO — `App.kt:377` `CircularProgressIndicator(Teal600)` literal**: el primer spinner que ve el usuario ignora el tema elegido.

---

## Desglose por experto

### 1. Estética / Diseño visual
- **1 CRÍTICO APLICA YA** — `App.kt:377` spinner Teal600 literal → usar `MaterialTheme.colorScheme.primary`
- **2 IMPORTANTE APLICA YA** — KDoc "3 temas" desactualizados en `Theme.kt:24` y `SemanticColors.kt:13-15`
- **1 IMPORTANTE APLICA YA** — `CalendarScreen.kt:87-108` 3 funciones color sueltas → unificar en `taskColors()`
- **1 IMPORTANTE PROPUESTA** — Swatch Midnight engañoso en `SettingsSheet.kt:804`
- **1 MENOR APLICA YA** — Spinner sin `contentDescription` en App.kt
- **3 MENOR PROPUESTA** — Splash semántica, botón WelcomeScreen, puerta Midnight

### 2. Funcionalidad end-to-end
- **CRÍTICO (arrastrado) — `hasGoogleLinked` degradation blind** — CalendarSyncManager sin cambios
- **CRÍTICO — `enforceAppCheck: true` rompe CFs** — mismo que Seguridad #1
- **IMPORTANTE PROPUESTA** — Hogar fantasma desde CreateProfileScreen (arrastrado v23)
- **MEDIO PROPUESTA** — iOS PKCE callbackScope en Dispatchers.Main

### 3. Accesibilidad (WCAG AA)
- **APLICADOS POR SUBAGENTE**: 3 contrast fixes en Minimal Light outline/onSurfaceVariant + Minimal Dark containers
- **APLICADOS**: `heading()` en GroupHeader (TaskListScreen), CalendarScreen secciones Pendientes/Caducadas, StatsScreen Logros
- **4 hallazgos sin aplicar** — heading() en ExpandableSectionHeaders (conflicto role Button+heading, requiere decisión)

### 4. UI / Componentes
- **APLICADO**: EmojiPicker extraído como componente (CreateHouseholdScreen + EditProfileScreen), -98 líneas netas
- **APLICA YA**: 56 sitios `Icons.Default.*` → `Icons.Filled.*`
- **PROPUESTA**: ErrorRow duplicado ~15 veces, 6 pantallas sin Scaffold, `Color.Black` literal en HouseholdScreen

### 5. UX
- **APLICA YA (arrastrado v23)**: Eliminar hogar fantasma al pulsar atrás en CreateProfileScreen
- **APLICA YA**: JoinHouseholdScreen paso 2 no perder código al pulsar atrás
- **APLICA YA**: Feedback snackbar en delete/leave household
- **APLICA YA**: Banner offline en HomeScreen
- **APLICA YA**: AuthGateScreen spinner visible durante SigningIn
- **PROPUESTA**: iOS SigningIn sin cancelación fuera de AuthGateScreen

### 6. Programador senior (clean code)
- **APLICA YA**: Extraer `resolveTheme()` para valueOf duplicado en App.kt
- **APLICA YA**: Eliminar dead code: `CreateHouseholdRequest`, `JoinHouseholdRequest`, `CreateMemberRequest` (DTOs.kt), `TaskReconciliation.kt`, `PenaltyRules.kt`, `AssignmentCompletionRules.kt`, `CallableError`/`CallableErrorBody` (FunctionDtos.kt)
- **APLICA YA**: Extraer `anonymizeMemberData()` helper en FirestoreRepository.deleteMember
- **PROPUESTA**: Añadir `HouseholdResponse.displayEmoji()` centralizado

### 7. Jefe de arquitectura
- **APLICA YA**: API key en build config (no código fuente) — conocido, documentado
- **APLICA YA**: `CloudFunctionsClient` + `FirestoreClient` ciclo potencial — añadir HttpClient separado en Koin
- **APLICA YA**: SettingsStore lazy SecureStore — dependencia explícita
- **PROPUESTA**: Separar `AccountDeletionOrchestrator` de `GoogleAuthManager` (10 roles)
- **PROPUESTA**: Dividir `TaskScreenModel` (1447 líneas) en TaskList + TaskDetail
- **PROPUESTA**: `rawTasksByHousehold` → usar `TaskCache` existente (eliminar lógica de join+cancel)

### 8. QA / Bugs
- **3 CRÍTICOS iOS PKCE**: stale callback race (C1), `SecRandomCopyBytes` crash (C2), `pendingCodeVerifier` sin `@Volatile` (C3)
- **4 ALTOS**: Calendar deleteEventForAssignment limpia eventId ajeno (A1), CloudFunctionsClient null code (A2), reassignTaskCompletion no recarga detalle (A3), ROLLBACK_FAILED dead code (A4)
- **5 MEDIOS**: appreciateMember orden, completeTask stale totalPoints, Ktor R8 keep, enforceAppCheck inactivo, iOS token exchange error_description

### 9. Seguridad / AppSec (OWASP MASVS)
- **CRÍTICO — `enforceAppCheck: true` sin AppCheck token en cliente**: implementar `expect fun getAppCheckToken()` en Platform + `X-Firebase-AppCheck` header, O quitar `enforceAppCheck`
- **CRÍTICO — iOS PKCE sin state OAuth**: generar `state` + validar en callback
- **IMPORTANTE — iOS PKCE sin nonce**: añadir `nonce` + verificar en JWT
- **MENOR**: CWE-1236 defensa básica funcional, SettingsStore PII email en texto plano

### 10. Privacidad / RGPD / Menores
- **CRÍTICO APLICA YA**: Analytics recoge datos ANTES de consentimiento UMP (MainActivity.kt:190 vs 204)
- **CRÍTICO PROPUESTA**: TFCD=true + UMP TCF v2 simultáneos (contradicción legal)
- **CRÍTICO PROPUESTA**: Cero gating de edad en toda la app
- **ALTA PROPUESTA**: Política de privacidad desactualizada, sin consentimiento GDPR inicial, Data Safety form
- **MEDIA**: TTL 90 días oportunista (no worker programado)

### 11. Rendimiento
- **APLICA YA**: 7 DTOs sin `@Immutable` (CommentResponse, MessageResponse, TaskHistoryResponse, RewardRedemption, UserProfile, AssignmentSlot, Subtask)
- **APLICA YA**: `org.gradle.jvmargs=-Xmx6G` en `gradle.properties` para build web estable
- **PROPUESTA**: Cache de miembros/tareas en ScreenModels con TTL, refactor sendMessage() readmembers, web dist wasm de 15MB

### 12. Fiabilidad de red / offline / sync
- **CRÍTICO APLICA YA**: CalendarSyncManager `consecutiveTokenFailures` con `Mutex` + `isOnline()` check (mismo que QA)
- **CRÍTICO APLICA YA**: `enforceAppCheck: true` — opción A (quitar) o B (implementar en cliente)
- **ALTO PROPUESTA**: Sin cola offline — escrituras se pierden sin recuperación
- **MENOR PROPUESTA**: cachear createTask/createMember individualmente tras clearTasks/clearMembers

### 13. Cobertura de pruebas
- Top-10 actualizado: 1. CalendarSyncManager+GoogleAuthManager (CRÍTICO), 2. idempotencyKey MemberScreenModel, 3. HouseholdScreenModel, 4. GoogleIosSignInHelper (iOS PKCE, NUEVO), 5. enforceAppCheck CFs (NUEVO), 6-10: heredados
- 0% repositories test, 40-50% ScreenModels, 61% CFs, 0% firestore.rules, 0% Compose UI tests
- Cobertura general: ~16% (JVM) + 61% (CFs)

### 14. Build / CI / Publicación
- **APLICADOS POR SUBAGENTE**: detekt eliminado de ci.yml, setup Android SDK añadido, 6 dependencias ktor-server muertas eliminadas, import SpaceEvenly corregido
- **PROPUESTA (arrastrado v23)**: release.yml publica sin tests previos, sin CI wasmJs/iOS, sin cobertura
- Ktor 3.2.2 correcto (bug R8 resuelto)

### 15. iOS nativo (Google Sign-In PKCE)
- **8 bugs APLICA YA**: state OAuth (B1), Dispatchers.Main→Default (B2), cancel no-op (B3), HttpTimeout (B4), decodeUrlComponent + (B5), error técnico vs cancelación (B6), println diagnóstico (B7), keyWindow→connectedScenes (B8)
- **4 propuestas**: prompt=select_account, kSecAttrAccessibleWhenUnlockedThisDeviceOnly, SecItemDelete check, @OptIn BetaInteropApi
- **Core PKCE correcto**: S256, sin client_secret, pendingCodeVerifier se limpia, interop Swift↔Kotlin correcto

---

## Fixes aplicados en esta ronda (APLICA YA)

**Pendiente de aplicar — los ejecuta el orquestador tras consolidar este informe.**

Prioridad 0 (CRÍTICO — bloquea producción):
1. Quitar `enforceAppCheck: true` de las 6 Cloud Functions (o implementar envío de token)
2. Mover `setAnalyticsCollectionEnabled` detrás de `ConsentManager.requestConsent`
3. iOS PKCE: `@Volatile` en `pendingCodeVerifier` + `sessionToken` + envolver `buildAuthorizationUrl()` en `runCatching`
4. CalendarSyncManager: `Mutex` + `isOnline()` check en `ensureCalendarAccessToken()`
5. `App.kt:377` `CircularProgressIndicator(Teal600)` → `MaterialTheme.colorScheme.primary`
6. iOS PKCE: `state` OAuth parameter

Prioridad 1 (bugs objetivos, bajo riesgo):
- 7 DTOs `@Immutable`
- `org.gradle.jvmargs=-Xmx6G` en gradle.properties
- Theme.kt + SemanticColors.kt KDoc actualizar
- CalendarScreen.kt unificar taskColors()
- Extraer `resolveTheme()` en App.kt
- Eliminar dead code (5 archivos)
- Extraer `anonymizeMemberData()` helper
- Icons.Default.* → Icons.Filled.* (56 sitios)
- iOS PKCE: Dispatchers.Default, HttpTimeout, cancel no-op fix, decodeUrlComponent, keyWindow
- CalendarSyncManager deleteEventForAssignment filtrar por memberId
- CloudFunctionsClient null code → ABORTED en 409
- ROLLBACK_FAILED dead code eliminar
- AuthGateScreen spinner color primary
- Feedback snackbar en delete/leave household
- Spinner App.kt contentDescription

Prioridad 2 (menor):
- KDoc desactualizados restantes
- println diagnóstico retirar
- Import limpieza tras emoji picker

**Sin bump de versión, sin push — pendiente de aplicar fixes y verificar build.**

---

## PROPUESTAS pendientes de decisión

Las propuestas de v23 que siguen abiertas:
- CRÍTICO (excepción): CalendarSyncManager desvincula Calendar por fallos de red
- IMPORTANTE: Hogar fantasma (CreateProfileScreen atrás) — requiere decisión de arquitectura
- IMPORTANTE: Colisión BadgeTone.Success/Teal en Naturaleza
- IMPORTANTE: TFCD+UMP conflicto legal GDPR
- IMPORTANTE: release.yml publica sin tests
- IMPORTANTE: Selector emoji duplicado (YA APLICADO como EmojiPicker)
- IMPORTANTE: Build wasmJs -Xmx6G no configurado (SE APLICA en esta ronda)
- MENOR: firestore.rules asimetría households lectura

Nuevas de v24:
- Dividir TaskScreenModel (1447 líneas) en 2 ScreenModels
- Separar AccountDeletionOrchestrator de GoogleAuthManager
- rawTasksByHousehold → usar TaskCache
- API key a build config
- ErrorRow como componente reusable (~15 instancias)
- 6 pantallas sin Scaffold
- Cola offline para escrituras
- prompt=select_account en iOS PKCE
- Política de privacidad actualizada
- Data Safety form Play Console
- Age gating
- UMP+Analytics orden definitivo
- tests de firestore.rules y GoogleIosSignInHelper