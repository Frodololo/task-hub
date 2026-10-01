---
workdir: /home/liberto/task-hub
max_turns: 600
allowed_tools: Read,Edit,Write,Bash,Grep,Glob,Task
---

# Comisión: aplicar fixes APLICA YA panel v24 (15 expertos, 2026-10-01)

HEAD: b65907c (v0.7.56). Aplicar SOLO los bloques APLICA YA de bajo riesgo. 
Compilar y testear TRAS CADA BLOQUE (no esperar al final). No hacer bump, tag ni push.

## Bloque 1 — CRÍTICO: enforceAppCheck

Quitar `enforceAppCheck: true` de las 6 Cloud Functions (revertir a `{ region: REGION }`).
El cliente Ktor no envía AppCheck token, así que bloquea todas las CFs.

Archivos: functions/src/completeAssignment.ts, completeRecurringTask.ts, donatePoints.ts, reassignTaskCompletion.ts, redeemReward.ts, undoTaskCompletion.ts
Buscar: `{ region: REGION, enforceAppCheck: true },` → Reemplazar por `{ region: REGION },`

Compilar: cd ~/task-hub && cd functions && npm run build
No hace falta jvmTest.

---

## Bloque 2 — CRÍTICO: Analytics antes de consentimiento UMP

Archivo: composeApp/src/androidMain/kotlin/org/taskhub/MainActivity.kt
Línea ~188-204: Mover `setAnalyticsCollectionEnabled(!settingsStore.isAnalyticsOptOut())` 
DESPUÉS de `ConsentManager.requestConsent(this)`.
O en su defecto, poner `setAnalyticsCollectionEnabled(false)` por defecto y que 
ConsentManager onConsentReady lo active después.

Compilar: cd ~/task-hub && ./gradlew :composeApp:compileDebugKotlinAndroid --console=plain

---

## Bloque 3 — CRÍTICO: iOS PKCE (4 bugs)

### 3a. Archivo: composeApp/src/commonMain/kotlin/org/taskhub/platform/GoogleIosSignInHelper.kt
- Añadir `@Volatile` a `var pendingCodeVerifier: String? = null` (línea ~68)
- Envolver `buildAuthorizationUrl()` en `runCatching { ....getOrNull() }`
  Si devuelve null → Platform.ios.kt publica "" en vez de crashear

### 3b. Archivo: composeApp/src/commonMain/kotlin/org/taskhub/platform/Platform.ios.kt
- `cancelGoogleSignIn()` (línea ~132): añadir `GoogleIosSignInHelper.clearPending()` + `GoogleSignInResultHolder.setResult("")` antes del return

### 3c. GoogleIosSignInHelper.kt: cambiar `callbackScope` de `Dispatchers.Main` a `Dispatchers.Default`

### 3d. GoogleIosSignInHelper.kt: añadir `HttpTimeout` plugin al HttpClient:
```kotlin
install(HttpTimeout) {
    requestTimeoutMillis = 15_000
    connectTimeoutMillis = 10_000
    socketTimeoutMillis = 15_000
}
```

Compilar compileDebugKotlinAndroid

---

## Bloque 4 — CRÍTICO: CalendarSyncManager Mutex + isOnline

Archivo: composeApp/src/commonMain/kotlin/org/taskhub/network/CalendarSyncManagerImpl.kt
(El archivo real se llama CalendarSyncManagerImpl.kt o CalendarSyncManager.kt — buscar el que contenga `consecutiveTokenFailures`)

Proteger `consecutiveTokenFailures` con `Mutex`:
1. Añadir `private val tokenFailuresMutex = Mutex()` 
2. Envolver el acceso en `ensureCalendarAccessToken()` con `tokenFailuresMutex.withLock { ... }`
3. Dentro del lock, si token es null, llamar `repo.isOnline()` antes de incrementar:
   - Si no hay red → no incrementar, solo return@withLock null
   - Si hay red → incrementar y posiblemente desvincular

Compilar compileDebugKotlinAndroid

---

## Bloque 5 — CRÍTICO: App.kt spinner Teal600 literal

Archivo: composeApp/src/commonMain/kotlin/org/taskhub/App.kt
Línea ~377: `CircularProgressIndicator(color = Teal600)` → 
`CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)`

Y añadir contentDescription al Box contenedor (línea ~373):
```kotlin
modifier = Modifier.fillMaxSize().semantics { contentDescription = "Cargando" }
```

Compilar compileDebugKotlinAndroid

---

## Bloque 6 — DTOs @Immutable (7 clases)

Archivo: composeApp/src/commonMain/kotlin/org/taskhub/network/models/DTOs.kt
Añadir `@Immutable` ANTES de `@Serializable` en:
- CommentResponse (línea ~320)
- MessageResponse (línea ~339)
- TaskHistoryResponse (línea ~356)
- RewardRedemption (línea ~442)
- UserProfile (línea ~155)
- AssignmentSlot (línea ~183)
- Subtask (línea ~195)

Compilar compileDebugKotlinAndroid

---

## Bloque 7 — gradle.properties wasmJs memory

Archivo: gradle.properties (raíz del proyecto)
Añadir: `org.gradle.jvmargs=-Xmx6G`

No necesita compilar.

---

## Bloque 8 — KDoc actualizar (Theme.kt + SemanticColors.kt)

Theme.kt línea ~24: "Los 3 temas visuales" → "Los 6 temas visuales"
SemanticColors.kt líneas ~13-15: "común a los 3 themes (DEFAULT, NATURALEZA, MINIMAL)" → 
"común a los 6 themes (DEFAULT, NATURALEZA, MINIMAL, OCEANO, ATARDECER, MEDIANOCHE)"

Compilar compileDebugKotlinAndroid

---

## Bloque 9 — CalendarScreen.kt unificar taskColors

Archivo: ui/screens/CalendarScreen.kt ~líneas 87-108
Las 3 funciones extensión `dotColor()`, `containerColor()`, `onContainerColor()` en DayTaskEntry 
son independientes con el mismo `when`. Crear una función `taskColors(): DayTaskEntryColors` 
con data class interna y que las 3 deleguen. Hay que mantener compatibilidad con los call-sites 
existentes (~12 usos).

Compilar compileDebugKotlinAndroid

---

## Bloque 10 — Dead code eliminar

DTOs.kt líneas ~63-77: eliminar `CreateHouseholdRequest`, `JoinHouseholdRequest`, `CreateMemberRequest`
FunctionDtos.kt: eliminar `CallableError` y `CallableErrorBody`
network/rules/: mover `TaskReconciliation.kt`, `PenaltyRules.kt`, `AssignmentCompletionRules.kt` 
  bajo `commonTest/` (no borrar, cambiar de directorio)

Compilar compileDebugKotlinAndroid + jvmTest (verificar que los tests siguen viendo las rules)

---

## Bloque 11 — App.kt resolveTheme()

App.kt: extraer los 2 bloques idénticos de `valueOf(settingsStore.getTheme())` catch 
IllegalArgumentException a una función privada `resolveTheme(settingsStore): TaskHubThemeType`.

Compilar compileDebugKotlinAndroid

---

## Bloque 12 — FirestoreRepository extraer anonymizeMemberData

Archivo: network/FirestoreRepository.kt ~líneas 890-1008 (deleteMember)
Extraer los 4 bloques try/catch de anonimización (messages, comments, taskHistory, redemptions) 
a un helper privado `anonymizeMemberData(householdId, memberId)`.

Compilar compileDebugKotlinAndroid

---

## Bloque 13 — CalendarSyncManager deleteEventForAssignment filtrar memberId

Archivo: CalendarSyncManagerImpl.kt (buscar `deleteEventForAssignment`)
Añadir guard: `if (assignment.memberId != repo.resolveCurrentMember(householdId)) return@launch`
antes de la operación.

Compilar compileDebugKotlinAndroid

---

## Bloque 14 — CloudFunctionsClient error handling

Archivo: network/CloudFunctionsClient.kt ~línea 59
Si `e.code` es null pero `httpStatusCode == 409`, mapear a `"ABORTED"`.

Compilar compileDebugKotlinAndroid

---

## Bloque 15 — ROLLBACK_FAILED dead code

MemberScreenModel.kt: eliminar `ROLLBACK_FAILED` del enum y de `donateErrorKey` 
(no hay AppStrings key para él).

MemberRepository: eliminar `DonateErrorReason.ROLLBACK_FAILED`