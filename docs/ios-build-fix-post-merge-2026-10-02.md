# iOS: `main` dejó de compilar tras el merge de PR #1 — diagnóstico y fix

**Fecha:** 2026-10-02
**PR relacionado:** [#2](https://github.com/LibertoBaltasar/task-hub/pull/2) (sobre `main`, tras el merge de [#1](https://github.com/LibertoBaltasar/task-hub/pull/1))
**Contexto:** continuación directa de `docs/ios-build-y-google-signin-2026-09-21.md`. Tras mergear el PR del login de Google (#1), `main` incorporó también cambios en paralelo de otro desarrollador que tocaban los mismos archivos iOS (`Platform.ios.kt`, `GoogleIosSignInHelper.kt`) — y la combinación dejó de compilar.

## Síntoma

```
./gradlew :composeApp:compileKotlinIosSimulatorArm64

e: GoogleIosSignInHelper.kt:74:6 Unresolved reference 'Volatile'.
e: Platform.ios.kt:50:51 Unresolved reference 'isKeyWindow'.
e: Platform.ios.kt:51:33 Unresolved reference 'rootViewController'.
e: Platform.ios.kt:53:39 Unresolved reference 'presentedViewController'.
```

Confirmado por compilación real tras `git pull origin main` (no por inspección de código) — y repetido en los 3 targets de iOS (`iosSimulatorArm64`, `iosArm64`, `iosX64`).

## Causa raíz — dos bugs independientes, en archivos distintos

### 1. `GoogleIosSignInHelper.kt` — `@Volatile` sin import

Un compañero añadió `@Volatile` sobre `pendingCodeVerifier` (mejora correcta:
hace que el campo sea visible entre hilos/dispatchers, relevante porque el
callback de Safari puede llegar en un hilo distinto al que lanzó el login).
Pero `@Volatile` en Kotlin Multiplatform es `kotlin.concurrent.Volatile`, y
no se añadió el import.

**Fix:**
```kotlin
import kotlin.concurrent.Volatile
```

### 2. `Platform.ios.kt` — `topMostViewController()` reescrito con un binding de UIKit que no expone lo esperado

El código anterior (de la sesión del PR #1) usaba el `UIApplication.sharedApplication.keyWindow`
deprecado. Un compañero lo reescribió, correctamente en espíritu, para usar
el patrón moderno basado en `UIWindowScene` (el recomendado por Apple desde
iOS 13 para apps con soporte multi-escena):

```kotlin
// Versión que llegó a main (no compila):
val window = UIApplication.sharedApplication.connectedScenes
    .filterIsInstance<UIWindowScene>()
    .firstOrNull()?.windows?.firstOrNull { it.isKeyWindow }
```

El problema: **`UIWindow.isKeyWindow`/`keyWindow` existen en el SDK real de
iOS, pero no están expuestos en el binding de Kotlin/Native de
`platform.UIKit` que usa este proyecto.** Se confirmó de dos formas, contra
el compilador real (no por suposición):

- Ni siquiera `import platform.UIKit.keyWindow` resuelve — no es un
  problema de import ausente (como sí lo fue el caso de
  `popoverPresentationController` en el PR #1), el símbolo simplemente no
  existe como tal en este binding para `UIWindow`.
- `windows` (la lista de ventanas de la escena) devuelve `List<*>` **sin
  tipo genérico concreto** en este binding — confirmado forzando
  deliberadamente un error de tipos contra el compilador (asignar el
  resultado a una variable de tipo incompatible y leer el mensaje de
  error exacto). Por eso `.firstOrNull()` sobre esa lista da `Any?`, y
  `Any?` no tiene `rootViewController` ni `presentedViewController` — de
  ahí que esos dos también aparecieran como "Unresolved reference",
  aunque sí son miembros reales y sin problema de esos tipos (es un efecto
  en cascada, no tres bugs independientes).

**Fix** — se mantiene el enfoque `UIWindowScene` (más correcto que el
`keyWindow` deprecado), pero sin depender de `isKeyWindow`: se toma la
primera ventana de la escena, convertida explícitamente con
`filterIsInstance<UIWindow>()` para recuperar el tipo concreto que el
binding no infiere solo:

```kotlin
val window = UIApplication.sharedApplication.connectedScenes
    .filterIsInstance<UIWindowScene>()
    .firstOrNull()?.windows?.filterIsInstance<UIWindow>()?.firstOrNull()
```

Para una app de una sola ventana como Task Hub (sin soporte de Stage
Manager/multi-window), la primera ventana de la escena **es** la key window
en la práctica — el resultado es equivalente sin depender de un símbolo que
este binding no expone.

## Verificación

| Target | Resultado |
|---|---|
| `compileKotlinIosSimulatorArm64` | ✅ `BUILD SUCCESSFUL` |
| `compileKotlinIosArm64` | ✅ `BUILD SUCCESSFUL` |
| `compileKotlinIosX64` | ✅ `BUILD SUCCESSFUL` |

## Lección para el equipo

Dos desarrolladores tocaron las mismas funciones iOS en paralelo sin que
ninguno de los dos pudiera compilar/verificar contra el compilador real en
el momento de escribir el cambio (o lo verificaron en un entorno distinto
al que reveló el problema). El patrón ya se había visto en
`docs/ios-build-y-google-signin-2026-09-21.md` (§3.1, §3.6): **el binding
de Kotlin/Native para UIKit no siempre expone un símbolo que sí existe en
el SDK real de iOS**, y el único diagnóstico fiable es compilar de verdad,
no inspeccionar el código. Antes de mergear cambios en `composeApp/src/iosMain/`,
conviene correr al menos:

```bash
./gradlew :composeApp:compileKotlinIosSimulatorArm64
```
