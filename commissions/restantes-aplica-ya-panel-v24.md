---
workdir: /home/liberto/task-hub
max_turns: 300
allowed_tools: Read,Edit,Write,Bash,Grep,Glob,Task
---

# Comisión: aplicar fixes APLICA YA restantes panel v24 (no relacionados con PROPUESTAS)

HEAD: 881de88 (v0.7.57). Aplicar los APLICA YA que no requieren decisión de producto/diseño/legal.
Compilar y testear TRAS CADA BLOQUE.

## Bloque 1 — Icons.Default.* → Icons.Filled.*

Buscar en TODO commonMain: `Icons.Default.` → reemplazar por `Icons.Filled.`
Son ~56 ocurrencias repartidas en ~30 archivos de ui/screens/ y ui/components/.

Regla: `Icons.Default.ArrowBack` → `Icons.AutoMirrored.Filled.ArrowBack`
(ArrowBack y ExitToApp deben usar AutoMirrored, no solo Filled).
El resto de iconos: `Icons.Default.*` → `Icons.Filled.*`

Archivos principales: screens/ (HomeScreen, ProfileScreen, CreateHouseholdScreen, 
HouseholdScreen, JoinHouseholdScreen, CreateProfileScreen, TaskDetailScreen, etc.)

Compilar: cd ~/task-hub && ./gradlew :composeApp:compileDebugKotlinAndroid --console=plain

---

## Bloque 2 — UX: JoinHouseholdScreen no perder código en paso 2

Archivo: ui/screens/JoinHouseholdScreen.kt
Línea ~120: `onBack = { navigator.pop() }`

En paso 2 (joinedHouseholdId != null), en vez de pop(), poner joinedHouseholdId = null
y resetear el estado para volver al paso 1 (re-escribir código no necesario, solo
retroceder el estado).

Compilar compileDebugKotlinAndroid

---

## Bloque 3 — UX: AuthGateScreen spinner color primary

Archivo: ui/screens/AuthGateScreen.kt ~línea 176
`CircularProgressIndicator(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))`
→ `CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)`

Compilar compileDebugKotlinAndroid

---

## Bloque 4 — iOS: decodeUrlComponent '+' como espacio

Archivo: composeApp/src/iosMain/kotlin/org/taskhub/platform/GoogleIosSignInHelper.kt
En la función decodeUrlComponent (aprox línea 163), añadir antes del bucle:
```
if (c == '+') {
    bytes.add(' '.code.toByte())
    continue
}
```

Compilar compileDebugKotlinAndroid

---

## Bloque 5 — iOS: retirar println diagnóstico

Archivo: composeApp/src/commonMain/kotlin/org/taskhub/network/FirestoreRepository.kt
Buscar `println("›"` o `println("Google Sign-In` — retirar los println
de diagnóstico de la sesión anterior.

Archivo: GoogleAuthManager.kt — mismo patrón, buscar println y retirar.

Compilar compileDebugKotlinAndroid

---

## Bloque 6 — iOS: keyWindow → connectedScenes

Archivo: composeApp/src/iosMain/kotlin/org/taskhub/platform/Platform.ios.kt
Línea ~47: Cambiar:
```kotlin
var topController = UIApplication.sharedApplication.keyWindow?.rootViewController
```
por:
```kotlin
val window = UIApplication.sharedApplication.connectedScenes
    .filterIsInstance<UIWindowScene>()
    .firstOrNull()?.windows?.firstOrNull { it.isKeyWindow }
var topController = window?.rootViewController
```

Compilar compileDebugKotlinAndroid

---

## Bloque 7 — QA: reassignTaskCompletion recarga detalle en error ambiguo

Archivo: ui/models/TaskScreenModel.kt ~líneas 856-864
En el catch de reassignTaskCompletion, tras poner _reassignState = Error,
añadir: `if (e.errorCategory() == ErrorCategory.AMBIGUOUS) loadTaskDetail(...)`

Misma lógica que ya existe en completeTask (líneas ~722-735 y ~951-955) para AMBIGUOUS.

Compilar compileDebugKotlinAndroid + jvmTest

---

## Bloque 8 — UX: Feedback snackbar en delete/leave household

Archivo: ui/screens/HouseholdScreen.kt ~líneas 297-298 y 320-321
Antes del `navigator.replaceAll(HomeScreen())` en onSuccess, añadir snackbar:
```
coroutineScope.launch {
    snackbarHostState.showSnackbar(
        message = s("household_deleted_success"),
        duration = SnackbarDuration.Short
    )
}
```
Necesita: coroutineScope disponible (capturar entorno), import SnackbarDuration,
y añadir claves i18n "household_deleted_success" y "household_left_success" 
en AppStrings.kt para ES y EN.

Compilar compileDebugKotlinAndroid