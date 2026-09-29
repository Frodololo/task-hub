# iOS: de "no compila" a login de Google funcional — diagnóstico exhaustivo y desarrollo pedagógico

**Proyecto:** Task Hub (`iosApp`, target Compose Multiplatform → Kotlin/Native)
**Periodo cubierto:** sesión de depuración iniciada fuera de VSCode (documentada en
`Error compilación Xcode.md`, entregada como contexto) + sesión completa en
Claude Code/VSCode del 18 al 21 de septiembre de 2026.
**Entorno de Xcode del usuario:** Xcode con `LastUpgradeCheck = 2700` (Xcode 27),
SDK Simulator iOS 27.0, probado tanto en Simulador ("iPhone 17") como en un
iPhone físico real.
**Entorno de este agente:** Claude Code sin Xcode completo instalado (solo
Command Line Tools) — por tanto **todo el diagnóstico de la capa Kotlin/Native
se hizo compilando de verdad** con `./gradlew` contra el compilador real
(nunca por inspección de código sin ejecutar), mientras que la capa
Swift/Xcode/Simulador solo pudo auditarse estáticamente y verificarse
mediante los pantallazos y logs que el usuario fue compartiendo en cada
iteración.

Este documento tiene dos objetivos deliberadamente distintos:

1. **Ser un registro fiel** de cada síntoma, cada hipótesis (incluidas las
   que resultaron incorrectas y por qué), cada fix aplicado y su
   verificación — para que un desarrollador o un agente de IA que retome
   este trabajo no tenga que releer la conversación completa ni adivinar
   qué se probó y se descartó.
2. **Explicar el "por qué" a nivel de fundamentos**, no solo el "qué se
   cambió" — el objetivo explícito de este encargo es un desarrollo
   pedagógico exhaustivo, así que cada sección técnica incluye el marco
   conceptual necesario (interop de Kotlin/Native con Objective-C/C,
   OAuth 2.0, compatibilidad binaria de klibs, arquitectura de motores de
   Ktor) para que el fix se entienda, no solo se copie.

---

## Índice

1. [Resumen ejecutivo](#1-resumen-ejecutivo)
2. [Parte I — Herencia de la sesión previa (fuera de VSCode)](#2-parte-i--herencia-de-la-sesión-previa-fuera-de-vscode)
3. [Parte II — Cronología detallada de esta sesión](#3-parte-ii--cronología-detallada-de-esta-sesión)
   - [3.1 El error de compilación Kotlin real: `popoverPresentationController`](#31-el-error-de-compilación-kotlin-real-popoverpresentationcontroller)
   - [3.2 Consistencia de `IPHONEOS_DEPLOYMENT_TARGET`](#32-consistencia-de-iphoneos_deployment_target)
   - [3.3 El login de Google estaba roto de raíz: migración de Implicit Flow a Authorization Code + PKCE](#33-el-login-de-google-estaba-roto-de-raíz-migración-de-implicit-flow-a-authorization-code--pkce)
   - [3.4 Crash en tiempo de ejecución: `ThrowIrLinkageError` en el motor Darwin de Ktor](#34-crash-en-tiempo-de-ejecución-throwirlinkageerror-en-el-motor-darwin-de-ktor)
   - [3.5 Configuración de entorno: Android SDK / `local.properties`](#35-configuración-de-entorno-android-sdk--localproperties)
   - [3.6 "Sin conexión a internet": un mensaje de error completamente engañoso](#36-sin-conexión-a-internet-un-mensaje-de-error-completamente-engañoso)
   - [3.7 Estado abierto al cierre de este documento](#37-estado-abierto-al-cierre-de-este-documento)
4. [Parte III — Inventario completo de archivos tocados](#4-parte-iii--inventario-completo-de-archivos-tocados)
5. [Parte IV — Deuda técnica y pendientes explícitos](#5-parte-iv--deuda-técnica-y-pendientes-explícitos)
6. [Parte V — Apéndice conceptual (para quien quiera profundizar)](#6-parte-v--apéndice-conceptual)

---

## 1. Resumen ejecutivo

| # | Problema | Causa raíz | Fix | Estado |
|---|---|---|---|---|
| 1 | Xcode no arrancaba Gradle (`IllegalArgumentException: 25.0.2`) | El Kotlin embebido de Gradle 8.12 no reconoce el string de versión de JDK 25 | `org.gradle.java.home` en `gradle.properties` apuntando a JDK 23 | ✅ Resuelto (heredado, confirmado) |
| 2 | `IPHONEOS_DEPLOYMENT_TARGET` inconsistente (Project vs Target) | Xcode dejó valores distintos a nivel Project (`13.0`→`17.6`) y Target (variable sin resolver) | Unificado a `17.6` en las 4 configuraciones | ✅ Resuelto |
| 3 | 6 errores de compilación Kotlin reales (`popoverPresentationController`, `sourceView`, `sourceRect`) | Import ausente de una *extension property* generada a partir de una categoría de Objective-C | `import platform.UIKit.popoverPresentationController` | ✅ Resuelto |
| 4 | `Error 400: unsupported_response_type` al iniciar sesión con Google | El flujo OAuth 2.0 Implicit (`response_type=id_token`) está deprecado por Google para clientes tipo iOS | Reescritura completa del flujo a Authorization Code + PKCE (`GoogleIosSignInHelper.kt`, nuevo) | ✅ Resuelto |
| 5 | Safari nunca se abría aunque el código no diera error | `UIApplication.openURL(url:)` (variante deprecada de 1 parámetro) devuelve `false` sin abrir nada en SDKs recientes | Migración a `openURL(url:options:completionHandler:)` | ✅ Resuelto |
| 6 | Crash `ThrowIrLinkageError` dentro de `io.ktor.client.engine.darwin` | Ktor 3.0.3 tiene un bug de enlazado conocido con el motor Darwin, nunca antes ejercitado en esta app (el login llevaba roto toda la vida del proyecto) | Bump de Ktor 3.0.3 → 3.2.0 (verificado compatible en ABI con Kotlin 2.1.21, tras descartar 3.5.2 por incompatibilidad) | ✅ Resuelto |
| 7 | Android SDK no configurado en la máquina | Android Studio instalado pero SDK nunca descargado; sin `ANDROID_HOME`/`local.properties` | `local.properties` con `sdk.dir` a la ruta real del SDK | ✅ Resuelto |
| 8 | Mensaje "Sin conexión a internet" tras un login que sí completaba en Safari | Catch-all de `ErrorCategory` que etiqueta como "sin conexión" cualquier excepción sin código HTTP real | Diagnóstico dirigido → 2 bugs reales encontrados (ver #9) | ✅ Causa raíz encontrada |
| 9a | `TypeCastException`: `CPointer` → `NSString` | Bridging CFStringRef↔NSString mal hecho con `as` en `SecureStore.ios.kt` (Keychain), código nunca antes ejecutado | `interpretObjCPointer<NSString>()` | ✅ Resuelto |
| 9b | `ClassCastException`: `NSDictionaryAsKMap` → `CPointer` | Mismo problema en la dirección contraria (`NSMutableDictionary` → `CFDictionaryRef`) | `objcPtr()` + `interpretCPointer<CPointed>()!!.reinterpret()` | ✅ Resuelto |
| 10 | Página de Safari (`accounts.google.com`) en blanco indefinidamente | Sin evidencia en logs de que sea código de la app — indicios de flakiness del Simulador (ya visto antes con `DeviceKitError`) | Pasos de troubleshooting entregados, sin confirmación final del usuario | 🟡 **Abierto** al cierre de este documento |

Además quedan **dos `println` de diagnóstico temporal sin retirar** (ver
[§5](#5-parte-iv--deuda-técnica-y-pendientes-explícitos)) y el campo
`TEAM_ID` de `Config.xcconfig` sigue vacío en el repositorio (aunque el
usuario ya consiguió ejecutar en un iPhone físico, lo que sugiere que lo
rellenó directamente desde la UI de Xcode en algún punto, sin que ese cambio
llegara al `.xcconfig` versionado).

---

## 2. Parte I — Herencia de la sesión previa (fuera de VSCode)

El usuario llegó a esta sesión con un documento (`Error compilación Xcode.md`)
que resumía una sesión de depuración anterior, hecha directamente en Xcode
sin la ayuda de un agente de código. Antes de tocar nada, la primera tarea
fue **contrastar ese documento contra el estado real del repositorio**, con
el compilador, no fiándose del texto — el propio documento contenía una
advertencia metodológica explícita: *"Antes de aplicar el fix de la sección
6 [...] es imprescindible releer el estado ACTUAL de los archivos [...]
porque los adjuntos usados para escribir este documento pueden no reflejar
cambios ya aplicados manualmente por el usuario en Xcode fuera de esta
conversación."*

Esa advertencia resultó ser acertada. La tabla siguiente resume las 6
secciones de ese documento y lo que se encontró realmente al verificarlas:

| Sección del documento heredado | Lo que decía | Lo que se encontró al verificar |
|---|---|---|
| 1. `IPHONEOS_DEPLOYMENT_TARGET` inconsistente | "No confirmado como resuelto" | **Cierto y confirmado**: 2 valores en `13.0`/`17.6` (Project) y 2 en `$(RECOMMENDED_IPHONEOS_DEPLOYMENT_TARGET)` sin resolver (Target). Corregido de verdad en esta sesión (§3.2). |
| 2. JDK 25 incompatible con Gradle | "Resuelto y confirmado" | **Confirmado**, `gradle.properties` ya tenía el fix aplicado. |
| 3. Falso positivo "Could not infer iOS target architectures" | "Resuelto — no es un bug real" | **Confirmado**: es un efecto esperado de invocar tareas de Gradle fuera del contexto de Xcode (faltan variables de entorno que solo Xcode inyecta). |
| 4. `enforceStrictPlistSanity` sin el sufijo `Check` | "Pendiente de aplicar — el archivo adjunto todavía lo tiene mal" | **Falso** — el archivo real en disco ya tenía `enforceStrictPlistSanityCheck` correctamente. El documento estaba describiendo un adjunto desactualizado. |
| 5. Falta `@OptIn(ExperimentalForeignApi::class)` | "Pendiente de aplicar en disco" | **Falso** — ya estaba presente en el archivo real (`shareText`, `secureRandomInt`). |
| 5b. Archivo duplicado con el mismo contenido | "Confirmar con `grep` cuántos duplicados existen" | **No existe ningún duplicado** — un único archivo contiene ese código. |
| 6. Hipótesis: error de inferencia de tipos en `UIActivityViewController(listOf(text as NSString), null)` | "Bloqueador principal, fix propuesto: `listOf<Any>(...)`" | **Hipótesis incorrecta**, descartada con evidencia del compilador real (§3.1) — la causa real era un import ausente, no un problema de inferencia de tipos en esos dos parámetros. |

**Lección metodológica que se repite en todo este documento:** cuando no se
puede ejecutar el compilador real, cualquier "diagnóstico" es una hipótesis,
no un hecho — y las hipótesis, por plausibles que suenen, pueden estar
completamente equivocadas incluso señalando el archivo correcto. La sección
6 de ese documento señalaba el archivo correcto (`Platform.ios.kt`) y la
función correcta (`shareText`), pero la línea y el mecanismo exactos estaban
mal. Todo el trabajo de esta sesión se apoyó, en cambio, en compilar de
verdad contra el toolchain de Kotlin/Native cada vez que fue posible, y en
pedir logs/capturas reales de Xcode para todo lo que el compilador de este
entorno no podía ejecutar (el propio Xcode, el Simulador, el runtime).

---

## 3. Parte II — Cronología detallada de esta sesión

### 3.1 El error de compilación Kotlin real: `popoverPresentationController`

**Síntoma.** El primer pantallazo de Xcode mostraba la fase de script
"Compile Kotlin Framework" terminando con **6 errores** (badge rojo `❌6`),
sin que el log visible llegara a mostrar el texto de los errores — solo el
árbol de tareas de Gradle cortado antes de la fase de compilación Kotlin en
sí.

**Método de diagnóstico.** En vez de especular sobre esos 6 errores, se
reprodujo la fase "Compile Kotlin Framework" **de verdad**, invocando
directamente la tarea de Gradle que Xcode ejecuta en ese script phase, con
las mismas variables de entorno que Xcode inyecta (`ARCHS`, `PLATFORM_NAME`,
`SDK_NAME`, `CONFIGURATION`, etc. — Xcode se las pasa al script como
variables de entorno del proceso, no como argumentos de línea de comandos, y
sin ellas Gradle no sabe para qué arquitectura/SDK compilar):

```bash
ARCHS=arm64 PLATFORM_NAME=iphonesimulator CONFIGURATION=Debug \
SDK_NAME=iphonesimulator17.0 TARGET_BUILD_DIR=build/xcode \
FRAMEWORKS_FOLDER_PATH=Frameworks BUILT_PRODUCTS_DIR=build/xcode \
./gradlew :composeApp:embedAndSignAppleFrameworkForXcode
```

Esto sí reprodujo los 6 errores exactos, con archivo y línea:

```
Platform.ios.kt:35:28: error: Unresolved reference 'popoverPresentationController'.
Platform.ios.kt:35:59: error: Cannot infer type for this parameter. Specify it explicitly.
Platform.ios.kt:35:65: error: Cannot infer type for this parameter. Specify it explicitly.
Platform.ios.kt:36:17: error: Unresolved reference 'sourceView'.
Platform.ios.kt:37:17: error: Unresolved reference 'sourceRect'.
```

(el sexto "error" que completaba el `❌6` de Xcode era en realidad un
problema de configuración de una tarea distinta —
`syncComposeResourcesForIos`— que solo aparece cuando se invoca Gradle fuera
del contexto real de Xcode, no un error de código).

**Explicación de fondo: por qué un import ausente produce 5 errores en
cascada.** El código en cuestión era:

```kotlin
val activityViewController = UIActivityViewController(
    activityItems = listOf(text as NSString),
    applicationActivities = null,
)
activityViewController.popoverPresentationController?.let { popover ->
    popover.sourceView = rootViewController.view
    popover.sourceRect = rootViewController.view.bounds
}
```

`popoverPresentationController` es una propiedad de solo lectura de
`UIViewController` en el SDK de iOS — pero **no está declarada en el
`@interface UIViewController` principal**, sino en un archivo de cabecera
distinto (`UIPopoverPresentationController.h`), como una **categoría**
(`@interface UIViewController (UIPopoverPresentationControllerSupport)`).
Las categorías de Objective-C permiten añadir métodos/propiedades a una
clase ya existente desde un módulo distinto, sin herencia ni modificar la
clase original — es el mecanismo de extensión de Objective-C, análogo
conceptualmente a las *extension functions* de Kotlin.

Cuando la herramienta `cinterop` de Kotlin/Native procesa los headers de
UIKit para generar los bindings de Kotlin, traduce los métodos/propiedades
declarados **directamente en el `@interface` de una clase** como miembros de
la clase Kotlin correspondiente (heredados automáticamente al importar la
clase), pero traduce los métodos/propiedades declarados **en una categoría**
como **extension properties/functions de nivel de paquete** —
sintácticamente independientes de la clase, exactamente como cualquier otra
extension function de Kotlin. Y las extension functions de Kotlin, a
diferencia de los miembros de clase, **no se heredan por el mero hecho de
importar la clase que extienden**: hace falta importarlas explícitamente por
su nombre completo (o con un import de comodín que las cubra).

El archivo tenía:

```kotlin
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIViewController
```

Pero le faltaba:

```kotlin
import platform.UIKit.popoverPresentationController
```

Sin ese import, el compilador no encuentra el símbolo
`popoverPresentationController` en absoluto (`Unresolved reference`). Y como
Kotlin no puede resolver el tipo de la expresión
`activityViewController.popoverPresentationController`, tampoco puede
inferir el tipo del parámetro lambda de `.let { popover -> ... }`
("Cannot infer type for this parameter" — dos veces, porque el compilador
también intenta inferir el tipo del propio receptor implícito de la lambda),
y dentro de esa lambda, `popover` queda con un **tipo de error interno**, así
que `popover.sourceView` y `popover.sourceRect` también fallan como
referencias no resueltas. **Los 5 errores eran, en realidad, un único
error propagándose en cascada** — exactamente el patrón que el documento
heredado había intuido correctamente (bloqueo único, síntomas derivados),
solo que había señalado el mecanismo equivocado (inferencia de tipos en los
argumentos del constructor, en vez de un import ausente de una extensión de
categoría).

**Fix aplicado** (`Platform.ios.kt`):

```kotlin
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIViewController
import platform.UIKit.popoverPresentationController   // ← añadido
```

**Verificación.** `./gradlew :composeApp:compileKotlinIosSimulatorArm64`
→ `BUILD SUCCESSFUL`, sin ningún error (solo warnings preexistentes sin
relación). Confirmado también en `iosArm64` y `iosX64`.

---

### 3.2 Consistencia de `IPHONEOS_DEPLOYMENT_TARGET`

**Contexto conceptual.** En un proyecto de Xcode, casi todo build setting
(deployment target, flags del compilador, rutas de búsqueda…) puede fijarse
en dos niveles distintos, con herencia entre ellos:

- **Nivel Project** — el valor por defecto para *todos* los targets del
  `.xcodeproj` que no lo sobrescriban.
- **Nivel Target** — específico de un target (`iosApp` en este caso), y si
  está definido, **gana** sobre el valor de Project.

Cada nivel, a su vez, tiene una configuración por *build configuration*
(`Debug`/`Release`), así que un solo setting como
`IPHONEOS_DEPLOYMENT_TARGET` puede tener hasta 4 valores independientes en
el mismo `.pbxproj`.

**Lo que se encontró** en `project.pbxproj`, tras el fix de la sección 1
del documento heredado (nunca verificado hasta esta sesión):

```
Project / Debug:    IPHONEOS_DEPLOYMENT_TARGET = 13.0;
Project / Release:  IPHONEOS_DEPLOYMENT_TARGET = 13.0;
Target  / Debug:    IPHONEOS_DEPLOYMENT_TARGET = "$(RECOMMENDED_IPHONEOS_DEPLOYMENT_TARGET)";
Target  / Release:  IPHONEOS_DEPLOYMENT_TARGET = "$(RECOMMENDED_IPHONEOS_DEPLOYMENT_TARGET)";
```

`$(RECOMMENDED_IPHONEOS_DEPLOYMENT_TARGET)` es una **macro de build
setting** que Xcode resuelve internamente al mínimo recomendado para el SDK
activo — no es un valor "roto" per se, pero al convivir con un valor
explícito distinto (`13.0`) a nivel Project, Xcode muestra la advertencia de
inconsistencia que motivó la sección 1 del documento heredado ("iOS 17.0 -
$(RECOMMENDED_IPHONEOS_DEPLOYMENT_TARGET)").

**Fix aplicado.** Se unificaron los 4 valores a `17.6` (un valor explícito,
consistente con el Simulador iOS 27.0 usado en las pruebas, y muy por
encima del mínimo `15.0` sugerido en el propio documento heredado):

```bash
# Antes de tocar nada, git diff confirmó que solo estos 4 puntos usaban la macro/valor antiguo
IPHONEOS_DEPLOYMENT_TARGET = 17.6;   # (los 4, tras el fix)
```

**Verificación.** `plutil -lint iosApp/iosApp.xcodeproj/project.pbxproj` →
`OK` (el `.pbxproj` es en realidad un property list; `plutil -lint` valida
que su sintaxis siga siendo correcta tras la edición manual — un paso barato
pero importante, porque un `.pbxproj` corrupto impide a Xcode abrir el
proyecto en absoluto).

**Nota sobre "ruido" posterior del propio Xcode.** En un diff completo
posterior de `project.pbxproj` se observaron varios cambios que Xcode aplica
por su cuenta cada vez que abre y reguarda el proyecto, sin que sean bugs ni
ediciones nuestras:

- `objectVersion 54 → 56` y `LastUpgradeCheck 1520 → 2700` — Xcode migra el
  formato del proyecto a la versión que entiende su propia versión (Xcode
  27 aquí).
- El nombre del producto pasó de `iosApp.app` a `"Task Hub.app"` — Xcode
  sincroniza el nombre del artefacto con `PRODUCT_NAME = "$(APP_NAME)"`
  ("Task Hub" en `Config.xcconfig`).
- `OTHER_LDFLAGS` pasó de un string plano (`"$(inherited) -framework
  ComposeApp"`) a un array (`("$(inherited)", "-framework", ComposeApp)`) —
  Xcode siempre serializa en formato array al regrabar el archivo,
  funcionalmente idéntico.
- `DEVELOPMENT_TEAM = "$(TEAM_ID)"` se movió de nivel Target a nivel
  Project — comportamiento normal de herencia cuando se cambia el Team desde
  Signing & Capabilities con "All targets" seleccionado.

Ninguno de estos cambios requirió intervención: son la "casa" propia de
Xcode, y se documentan aquí solo para que quien vea un diff grande en
`project.pbxproj` en el futuro no lo confunda con una intervención manual
sospechosa.

---

### 3.3 El login de Google estaba roto de raíz: migración de Implicit Flow a Authorization Code + PKCE

Esta fue, con diferencia, la intervención más profunda de la sesión — no un
fix puntual, sino la sustitución completa del mecanismo de autenticación en
iOS.

#### 3.3.1 Síntoma y diagnóstico

Tras resolver los errores de compilación (§3.1) y poder ejecutar la app,
tocar "Iniciar sesión con Google" abría Safari y mostraba, en la propia
página de Google, una pantalla de error del propio servidor OAuth de
Google:

```
Acceso bloqueado: error de autorización
Error 400: unsupported_response_type
Detalles de la solicitud: flowName=GeneralOAuthFlow
```

Esto **no es un bug de esta app**, sino un rechazo explícito y determinista
del servidor de autorización de Google — Google identifica el
`response_type` solicitado como no soportado para ese tipo de cliente OAuth,
punto.

#### 3.3.2 Fundamento: ¿qué son el Implicit Flow y el Authorization Code Flow, y por qué Google rechaza el primero?

OAuth 2.0 (RFC 6749) define varios *grant types* (flujos) para que una
aplicación obtenga un token en nombre de un usuario. Los dos relevantes
aquí:

- **Implicit Flow** (`response_type=token` o, en la variante de OpenID
  Connect usada aquí, `response_type=id_token`): la aplicación redirige al
  usuario al servidor de autorización, y **el servidor devuelve el token
  directamente en el fragmento (`#...`) de la URL de redirección**, sin
  ningún paso intermedio de intercambio servidor-a-servidor. Se diseñó en
  2012 pensando en aplicaciones JavaScript de una sola página que no podían
  guardar un secreto de cliente ni hacer peticiones de servidor.
- **Authorization Code Flow**: el servidor de autorización, tras el
  consentimiento del usuario, no devuelve el token — devuelve un **código
  de un solo uso** (`code`) de vida muy corta. La aplicación cliente debe
  entonces hacer una petición POST adicional (servidor-a-servidor, o en este
  caso app-a-servidor) al *token endpoint*, canjeando ese código por el
  token real.

El Implicit Flow tiene problemas de seguridad bien documentados y hoy se
considera obsoleto por la propia *IETF OAuth 2.0 Security Best Current
Practice*: el token viaja expuesto en la URL (queda en el historial del
navegador, en logs de proxies/servidores intermedios, en el header
`Referer` de peticiones posteriores desde esa página), y **no hay ningún
mecanismo para verificar que quien está canjeando el `code` es la misma
aplicación que lo solicitó** — porque en el Implicit Flow no hay canje en
absoluto, el token llega directamente. Por eso las guías modernas de OAuth
(incluida la política actual de Google para clientes de tipo iOS/Android)
ya no lo permiten para nuevos flujos: de ahí el `Error 400:
unsupported_response_type` — Google simplemente ha dejado de aceptar
`response_type=id_token`/`token` en su endpoint de autorización para este
tipo de cliente.

#### 3.3.3 PKCE: el reemplazo para aplicaciones nativas (RFC 7636 + RFC 8252)

El problema del Authorization Code Flow "clásico" (pensado originalmente
para aplicaciones web con backend) es que el paso de canjear el `code` por
el token normalmente requiere autenticar a la aplicación cliente con un
**client secret** — una credencial compartida entre la aplicación y el
servidor de autorización. Pero una app móvil/nativa **no puede guardar un
secreto de forma segura**: cualquiera puede descompilar el binario y
extraerlo. La RFC 8252 ("OAuth 2.0 for Native Apps") formaliza esto
llamando a las apps nativas **"public clients"** (clientes públicos, sin
secreto), en contraposición a los **"confidential clients"** (con backend,
que sí pueden guardar un secreto).

**PKCE** (Proof Key for Code Exchange, RFC 7636, se pronuncia "pixy") es el
mecanismo que permite a un cliente público usar el Authorization Code Flow
de forma segura, sin necesitar un client secret:

1. La app genera un valor aleatorio criptográficamente seguro: el
   **`code_verifier`**.
2. Calcula su hash SHA-256 y lo codifica en Base64 URL-safe: el
   **`code_challenge`**.
3. En la petición de autorización inicial (la que abre el navegador), envía
   el `code_challenge` (nunca el `code_verifier`).
4. Cuando la app canjea el `code` por el token, envía el `code_verifier`
   original (en texto plano) junto al `code`.
5. El servidor de autorización recalcula el hash del `code_verifier`
   recibido y comprueba que coincide con el `code_challenge` de la petición
   original.

Como el `code_verifier` nunca viaja en la URL de redirección (solo su hash,
en el primer paso), un atacante que interceptara el `code` de camino de
vuelta a la app **no podría canjearlo por un token**, porque no conoce el
`code_verifier` que lo generó. Esto sustituye la función que antes cumplía
el client secret, sin necesitar guardar ningún secreto de larga duración en
el binario de la app.

#### 3.3.4 Decisión de diseño: reutilizar el patrón ya probado en desktop

El proyecto **ya tenía** una implementación de Authorization Code + PKCE
funcionando en JVM/desktop (`GoogleDesktopSignInHelper.kt`), porque desktop
tampoco puede usar un SDK nativo de Google (no hay `GoogleSignIn` SDK para
JVM plano) y ya había pasado por este mismo problema. La estrategia elegida
fue **espejar esa implementación**, no reinventarla, con una diferencia
estructural importante:

| | **Desktop** (`GoogleDesktopSignInHelper`) | **iOS** (`GoogleIosSignInHelper`) |
|---|---|---|
| Cómo recibe el callback | Levanta un servidor HTTP local (`ServerSocket` en `127.0.0.1:<puerto libre>`), y el propio `signIn()` **bloquea** esperando la conexión entrante en ese socket | El sistema operativo entrega el callback como una apertura de URL con un *custom scheme* (`onOpenURL` en SwiftUI), en una invocación de proceso completamente distinta y posterior |
| Redirect URI | `http://127.0.0.1:<puerto>/callback` (loopback, RFC 8252 §7.3) | `com.googleusercontent.apps.<id>:/oauth2redirect` (custom URL scheme, RFC 8252 §7.1) |
| Forma de la función | Una única `suspend fun signIn(): String?` que hace todo el flujo de principio a fin en una sola llamada | Dos funciones separadas: `buildAuthorizationUrl()` (antes de abrir el navegador) y `processCallback(url)` (cuando el navegador devuelve el control, minutos después, potencialmente con la app ya relanzada desde background) |
| Dónde vive el `code_verifier` entre ambos pasos | En una variable local de la función (la pila de la corrutina no se pierde porque nunca hay un cambio de proceso) | En un campo mutable del propio `object` (`pendingCodeVerifier`), porque el estado tiene que sobrevivir a que la app pase a segundo plano y Safari tome el control |
| `client_secret` | Sí lo usa (`GoogleOAuthConfig.CLIENT_SECRET`) — es válido para desktop porque ese flujo concreto SÍ registra un client de tipo "Desktop app" en Google Cloud Console, que Google trata como confidencial en la práctica pese a RFC 8252 | No — el client ID de tipo "iOS" es estrictamente público, Google no emite secreto para él |

Esta asimetría (una función bloqueante vs. dos funciones separadas por una
frontera de proceso) es la diferencia arquitectónica central entre
implementar OAuth en una app de escritorio de proceso único y en una app
móvil cuyo ciclo de vida el sistema operativo puede suspender en cualquier
momento entre el paso 1 (abrir navegador) y el paso 2 (recibir el
callback).

#### 3.3.5 El nuevo archivo: `GoogleIosSignInHelper.kt`

Archivo nuevo en `composeApp/src/iosMain/kotlin/org/taskhub/platform/`.
Recorrido completo, función por función:

**Construcción de la URL de autorización:**

```kotlin
fun buildAuthorizationUrl(): String {
    val codeVerifier = randomUrlSafeString(32)
    pendingCodeVerifier = codeVerifier
    val codeChallenge = codeChallengeFor(codeVerifier)
    return "$AUTH_ENDPOINT?client_id=$GOOGLE_IOS_CLIENT_ID" +
        "&redirect_uri=$REDIRECT_URI" +
        "&response_type=code" +                 // ← ya no "id_token"
        "&scope=openid%20email%20profile" +
        "&code_challenge=$codeChallenge" +
        "&code_challenge_method=S256"
}
```

El cambio de fondo frente a la versión anterior no es solo `response_type`:
desaparece también el parámetro `nonce` (un mecanismo anti-replay específico
del flujo implícito de OpenID Connect, sin sentido en Authorization Code,
donde el propio `code` de un solo uso ya cumple esa función) y aparecen
`code_challenge`/`code_challenge_method=S256`, el par de parámetros PKCE
descritos arriba.

**Generación criptográfica del `code_verifier` y el `code_challenge`:**

```kotlin
@OptIn(ExperimentalForeignApi::class, ExperimentalEncodingApi::class)
private fun randomUrlSafeString(byteLength: Int): String {
    val bytes = ByteArray(byteLength)
    val status = SecRandomCopyBytes(kSecRandomDefault, bytes.size.toULong(), bytes.refTo(0))
    check(status == 0) { "SecRandomCopyBytes falló con status $status" }
    return Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(bytes)
}

@OptIn(ExperimentalForeignApi::class, ExperimentalEncodingApi::class, ExperimentalUnsignedTypes::class)
private fun codeChallengeFor(codeVerifier: String): String {
    val input = codeVerifier.encodeToByteArray().toUByteArray()
    val digest = UByteArray(32) // CC_SHA256_DIGEST_LENGTH
    CC_SHA256(input.refTo(0), input.size.toUInt(), digest.refTo(0))
    return Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(digest.toByteArray())
}
```

Dos detalles de interoperabilidad con C/Objective-C que merecen explicarse,
porque son un patrón que se repite en varios sitios de este proyecto:

- **`SecRandomCopyBytes`** es la función C del framework `Security.framework`
  para obtener bytes de un generador criptográficamente seguro (CSPRNG) del
  sistema — el mismo mecanismo ya usado en el proyecto para
  `secureRandomInt` en `Platform.ios.kt`. `bytes.refTo(0)` es la forma que
  ofrece `kotlinx.cinterop` de pasar un `ByteArray` de Kotlin como un
  puntero C (`CPointer<ByteVar>`) al primer elemento del array, para que la
  función C pueda escribir los bytes aleatorios directamente en la memoria
  de ese array — sin copias intermedias.
- **`CC_SHA256`** es la función C del framework `CommonCrypto` (parte de
  `Security.framework`) para calcular un hash SHA-256. No forma parte de
  `kotlinx-crypto` ni de ninguna librería multiplataforma: se usa
  directamente vía cinterop porque Apple ya expone esta función en el SDK
  del sistema, sin necesidad de añadir ninguna dependencia extra —
  exactamente el mismo criterio que llevó a usar `java.security.MessageDigest`
  en la versión de desktop del mismo cálculo. Cada plataforma usa el
  primitivo criptográfico nativo que el propio sistema operativo ya ofrece.
- **`Base64.UrlSafe`** es la API de Base64 de la librería estándar de
  Kotlin (`kotlin.io.encoding.Base64`, estable desde Kotlin 2.0), **pura,
  multiplataforma, sin interop**. PKCE (RFC 7636 §4.1-4.2) exige
  específicamente Base64 **URL-safe sin padding** (usa `-`/`_` en vez de
  `+`/`/`, y omite los caracteres `=` de relleno al final) — de ahí
  `.withPadding(Base64.PaddingOption.ABSENT)`.

**El punto de entrada llamado desde Swift, y por qué es un método de
`object` y no una función suelta:**

```kotlin
fun processCallback(callbackUrl: String) {
    callbackScope.launch {
        GoogleSignInResultHolder.setResult(handleCallback(callbackUrl) ?: "")
    }
}
```

Esta función se decidió **deliberadamente** como miembro del `object
GoogleIosSignInHelper` (un singleton de Kotlin) en vez de como una función
suelta de nivel de archivo, por una razón puramente de interoperabilidad con
Swift, no de estilo: Kotlin/Native expone un `object` a Swift/Objective-C
como una clase con una instancia singleton accesible vía `.shared`
(`GoogleIosSignInHelper.shared.processCallback(callbackUrl:)`), una
convención ya validada en este mismo proyecto para
`GoogleSignInResultHolder.shared.setResult(token:)`. Las funciones sueltas
de nivel de archivo, en cambio, se exponen bajo una clase sintética con
nombre derivado del **nombre del archivo** (`<NombreArchivo>Kt`), y como
este entorno no tenía Xcode completo disponible para generar y confirmar el
header Objective-C real, se prefirió el patrón ya demostrado y verificado
en producción (el de `object`), en vez de introducir una incertidumbre de
naming que solo se podría haber validado en la máquina del usuario, con el
consiguiente ciclo de ida y vuelta de "recompila y dime si compila".

**El intercambio del `code` por el `id_token`:**

```kotlin
private suspend fun exchangeCodeForIdToken(code: String, codeVerifier: String): String? {
    val response: TokenResponse = client.submitForm(
        url = TOKEN_ENDPOINT,
        formParameters = Parameters.build {
            append("code", code)
            append("client_id", GOOGLE_IOS_CLIENT_ID)
            append("redirect_uri", REDIRECT_URI)
            append("grant_type", "authorization_code")
            append("code_verifier", codeVerifier)
            // Sin "client_secret": cliente OAuth de tipo iOS = público (RFC 8252)
        }
    ).body()
    return response.id_token?.takeIf { it.isNotBlank() }
}
```

Esta es una petición POST estándar `application/x-www-form-urlencoded` al
*token endpoint* de Google (`https://oauth2.googleapis.com/token`), usando
Ktor (la librería HTTP ya usada en todo el proyecto para hablar con
Firestore/Identity Toolkit — ver §3.4 para el problema que surgió,
precisamente, en esta llamada).

#### 3.3.6 Cambios en `Platform.ios.kt`: `launchGoogleSignIn` y el `openURL` deprecado

**Antes:**

```kotlin
actual fun launchGoogleSignIn() {
    val nonce = (1..32).joinToString("") { secureRandomInt(16).toString(16) }
    val redirectUri = "$GOOGLE_IOS_REVERSED_CLIENT_ID:/oauth2redirect"
    val authUrl = "https://accounts.google.com/o/oauth2/v2/auth" +
        "?client_id=$GOOGLE_IOS_CLIENT_ID" +
        "&redirect_uri=$redirectUri" +
        "&response_type=id_token" +
        "&scope=openid%20email%20profile" +
        "&nonce=$nonce" +
        "&prompt=select_account"
    val url = NSURL(string = authUrl)
    val opened = url != null && UIApplication.sharedApplication.openURL(url)
    if (!opened) {
        GoogleSignInResultHolder.setResult("")
    }
}
```

**Después:**

```kotlin
actual fun launchGoogleSignIn() {
    val authUrl = GoogleIosSignInHelper.buildAuthorizationUrl()
    val url = NSURL(string = authUrl)
    UIApplication.sharedApplication.openURL(
        url = url,
        options = emptyMap<Any?, Any>(),
        completionHandler = { opened ->
            if (!opened) {
                GoogleIosSignInHelper.clearPending()
                GoogleSignInResultHolder.setResult("")
            }
        },
    )
}
```

Más allá de delegar la construcción de la URL al nuevo helper, hay un
**segundo bug independiente** corregido aquí, descubierto ya avanzada la
sesión al leer un log de consola de Xcode que el usuario compartió, con la
línea:

```
BUG IN CLIENT OF UIKIT: The caller of UIApplication.openURL(_:) needs to
migrate to the non-deprecated UIApplication.open(_:options:completionHandler:).
Force returning false (NO).
```

`UIApplication.openURL(_:)` (la variante de un único parámetro) está
deprecada desde iOS 10 en favor de la variante con `options`/
`completionHandler`, introducida para dar soporte a apertura asíncrona con
opciones adicionales (como especificar si debe abrirse en modo universal
link, etc.). Durante años, la variante deprecada siguió funcionando con una
simple advertencia de compilador en Objective-C/Swift. Pero en SDKs de iOS
recientes (el comportamiento se confirmó aquí en iOS 27), Apple ha
convertido esa deprecación en un **"hard fail"**: la llamada ya ni siquiera
intenta abrir la URL, y **siempre devuelve `false`** — de ahí el log "Force
returning false (NO)". El código anterior interpretaba ese `false` como "no
se pudo abrir Safari" y publicaba inmediatamente un resultado vacío
(`GoogleSignInResultHolder.setResult("")`), lo que hacía que el flujo se
"cancelara solo" sin que el usuario llegara siquiera a ver la pantalla de
Google — un fallo silencioso, sin ningún mensaje de error visible, que
además coexistía con (y quedó parcialmente enmascarado por) el problema
distinto del `response_type` descrito arriba: **había dos bugs
independientes bloqueando el mismo flujo, uno encima del otro**.

La variante moderna (`openURL(url:options:completionHandler:)`) es
asíncrona: el booleano de éxito llega más tarde, en el `completionHandler`,
en vez de como valor de retorno directo. El resto de la lógica (publicar
`""` solo si falla, para no dejar el estado colgado en "Conectando con
Google...") se conserva igual, simplemente movida dentro de esa lambda.

#### 3.3.7 Cambios en `ContentView.swift`: de parsear la URL en Swift a delegar en Kotlin

**Antes** (Swift extraía el `id_token` del *fragmento* de la URL, porque el
flujo implícito devuelve el token ahí):

```swift
.onOpenURL { url in
    guard url.scheme == GoogleOAuthConfigKt.GOOGLE_IOS_REVERSED_CLIENT_ID else { return }
    let idToken = extractIdToken(from: url)
    GoogleSignInResultHolder.shared.setResult(token: idToken ?? "")
}

private func extractIdToken(from url: URL) -> String? {
    guard let fragment = url.fragment else { return nil }
    for pair in fragment.split(separator: "&") {
        let parts = pair.split(separator: "=", maxSplits: 1)
        if parts.count == 2, parts[0] == "id_token" {
            return String(parts[1]).removingPercentEncoding
        }
    }
    return nil
}
```

**Después** (Swift solo reenvía la URL completa; Kotlin hace todo el
trabajo — extracción del `code` de la *query string*, no del fragmento, y
el canje completo):

```swift
.onOpenURL { url in
    guard url.scheme == GoogleOAuthConfigKt.GOOGLE_IOS_REVERSED_CLIENT_ID else { return }
    GoogleIosSignInHelper.shared.processCallback(callbackUrl: url.absoluteString)
}
```

Este cambio no es solo una simplificación cosmética. El Authorization Code
Flow devuelve el `code` como parámetro de la **query string**
(`?code=...&scope=...`), no del fragmento (`#...`) como hacía el flujo
implícito con el `id_token` — son partes sintácticamente distintas de una
URL, y la función `extractIdToken` original, escrita para leer el
fragmento, habría sido simplemente incorrecta para el nuevo flujo (habría
devuelto `nil` siempre, porque el nuevo flujo no pone nada en el fragmento).
Mover toda la lógica de parseo y de intercambio de tokens a Kotlin, además,
concentra toda la lógica específica de OAuth en un único lugar
multiplataforma-consciente (aunque este helper concreto sea `iosMain`, el
patrón de "Swift no sabe nada de OAuth, solo reenvía la URL cruda" es más
fácil de mantener y de razonar que tener lógica de parsing de protocolo
duplicada en dos lenguajes).

---

### 3.4 Crash en tiempo de ejecución: `ThrowIrLinkageError` en el motor Darwin de Ktor

Con el flujo de PKCE ya en el sitio, el siguiente intento de login abrió
Safari, mostró la pantalla real de consentimiento de Google (confirmando
que el `Error 400` estaba resuelto) — y al volver a la app, esta **crasheó**
con un `SIGABRT`.

#### 3.4.1 Lectura del stack trace y primer diagnóstico (parcialmente erróneo, mantenido en el documento por su valor pedagógico)

El debugger de Xcode (LLDB, integrado en el panel inferior) mostró:

```
Exception = kfun:kotlin.native.internal#ThrowIrLinkageError(kotlin.String?){}kotlin.Nothing
```

con un stack de frames que incluía, entre otros:

```
13 kfun:kotlin.native.internal#ThrowIrLinkageError...
14 kfun:io.ktor.client.engine.d...
15 kfun:io.ktor.client.engine.d...
...
22 kfun:io.ktor.client.engine.d...
```

Es decir: el crash ocurre **dentro del propio motor Darwin de Ktor**
(`io.ktor.client.engine.darwin`), no en código de la app.

**¿Qué es un `IrLinkageError` en Kotlin/Native, y por qué puede pasar en
tiempo de ejecución en vez de en tiempo de compilación?** Kotlin/Native
compila a binarios nativos AOT (ahead-of-time, sin JIT ni classloading
dinámico como la JVM), a través de una representación intermedia (IR —
Intermediate Representation) que se serializa en las `.klib` (las
"bibliotecas" de Kotlin/Native, análogas conceptualmente a un `.jar` pero
para este mundo). Cuando el compilador enlaza (*link*) el binario final, no
siempre resuelve **todas** las referencias simbólicas entre módulos de
forma estricta en tiempo de compilación — algunas quedan como referencias
diferidas que se resuelven la primera vez que ese código path se ejecuta de
verdad. Si en ese momento el símbolo referenciado no existe (por ejemplo,
porque la `.klib` que lo declara se compiló con una versión de Kotlin
incompatible, o porque contiene un bug de enlazado real), el runtime lanza
`ThrowIrLinkageError` — un fallo que **no es detectable en tiempo de
compilación** en ciertas condiciones, algo conceptualmente parecido a un
`NoSuchMethodError` de la JVM en clasificación de "compila pero falla en
runtime", pero con una causa raíz distinta (aquí es sobre todo un problema
de compatibilidad de formato binario entre klibs, no de classpath).

**Primera hipótesis probada.** El helper nuevo (§3.3.5) creaba su propio
`HttpClient` especificando el motor explícitamente:

```kotlin
private val client by lazy {
    HttpClient(Darwin) {   // ← motor explícito
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }
}
```

Mientras que el único otro sitio del proyecto que crea un `HttpClient`
(`FirestoreClient.kt`, usado en producción para todas las llamadas a
Firestore/Identity Toolkit) **no especifica motor**:

```kotlin
val client = HttpClient {
    install(ContentNegotiation) { ... }
    ...
}
```

En Ktor Multiplatform, cuando el `HttpClient()` se construye sin pasar un
`HttpClientEngineFactory` explícito, la librería resuelve automáticamente el
motor disponible en el *classpath* de esa plataforma — mecanismo que
funciona sin ambigüedad siempre que haya **exactamente una** dependencia de
motor en el source set (aquí, `ktor-client-darwin` es la única declarada en
`iosMain.dependencies`). Se alineó el helper nuevo con el patrón ya probado
(`HttpClient { }`, sin motor explícito), razonando que la especificación
manual podría estar tirando de un camino de inicialización distinto y
potencialmente mal enlazado del motor Darwin.

**Resultado de esa hipótesis: el crash persistió, exactamente igual,
byte a byte del mismo stack trace.** Es decir, la hipótesis era razonable
pero incorrecta como causa única — aunque el cambio en sí (alinear con el
patrón ya probado del proyecto) se mantuvo por ser, de todos modos, la
forma más consistente y menos sorprendente de construir el cliente.

#### 3.4.2 La causa raíz real: una versión de Ktor con un bug de enlazado conocido, nunca antes ejercitada

Dado que el crash sobrevivió al cambio anterior, se investigó con búsqueda
web si existían incompatibilidades conocidas entre Ktor y el motor Darwin en
la franja de versiones del proyecto. Se encontraron varios reportes
públicos (issues de GitHub del propio proyecto Ktor, hilos de Slack de la
comunidad de Kotlin) describiendo exactamente esta familia de error
(`IrLinkageError: Can not get instance of singleton 'Plugin': No class
found for symbol 'io.ktor.client.plugins/HttpTimeout.Plugin...'`, y
variantes) para combinaciones específicas de versión de Ktor + Kotlin/Native
+ motor Darwin, con reportes de que ciertas versiones de Ktor 3.0.x/3.1.x
tenían bugs de enlazado en el motor Darwin, corregidos en releases
posteriores.

El detalle crucial que conectó esto con el caso concreto de este proyecto:
**el flujo de login de Google llevaba roto desde el principio del proyecto
en iOS** (primero por el `response_type` incorrecto, corregido en §3.3;
antes de eso, según los documentos heredados, ni siquiera compilaba). Eso
significa que **esta era, literalmente, la primera vez en la vida del
proyecto que se ejecutaba una petición POST autenticada real a través del
motor Darwin de Ktor en iOS** — cualquier bug latente en esa ruta de código
nunca había tenido ocasión de manifestarse, porque nunca se había llegado a
ejecutar.

**Bump de versión, y por qué no fue trivial.** La versión pinada en el
catálogo de versiones era `ktor = "3.0.3"` (`gradle/libs.versions.toml`).
Se probó primero la última estable disponible en ese momento (`3.5.2`), que
falló con un error completamente distinto y mucho más fundamental:

```
KLIB resolver: Skipping '.../ktor-client-content-negotiation-iosSimulatorArm64Main-3.5.2.klib'
having incompatible ABI version '2.3.0'. The library was produced by '2.3.21' compiler.
The current Kotlin compiler can consume libraries having ABI version <= '1.201.0'.
```

**¿Qué es la "ABI version" de una klib, y por qué importa?** Cada versión
del compilador de Kotlin/Native serializa la representación intermedia (IR)
de una klib usando un formato binario versionado (la ABI — Application
Binary Interface — de la propia klib). A diferencia del bytecode de la JVM,
que tiene décadas de compromiso de compatibilidad hacia atrás muy amplio,
el formato de klibs de Kotlin/Native es más estricto: **un compilador solo
puede consumir klibs cuya versión de ABI sea igual o anterior a la que él
mismo entiende**, porque el compilador necesita poder leer y reinterpretar
esa representación intermedia para generar el binario final (a diferencia
de la JVM, donde el bytecode ya es prácticamente el producto final y solo
hace falta un verificador, no un recompilador). Ktor 3.5.2 se publicó
compilada con Kotlin 2.3.21 (ABI 2.3.0); este proyecto usa el compilador de
Kotlin 2.1.21 (que solo acepta klibs hasta ABI 1.201.0) — así que Ktor
3.5.2 es sencillamente **demasiado nueva** para el compilador de este
proyecto, y punto: no hay ningún flag ni configuración que salve esa
incompatibilidad sin subir también la versión de Kotlin (una migración
mucho más invasiva, fuera del alcance de esta sesión).

**Metodología: "búsqueda binaria contra el compilador real".** En vez de
seguir adivinando qué versión intermedia de Ktor sería compatible por
fechas de publicación, se aprovechó tener el compilador real disponible en
este entorno para simplemente **probar candidatas y dejar que el propio
compilador certifique la compatibilidad de ABI**, en vez de basarse en
documentación de terceros (a menudo incompleta para combinaciones tan
específicas de versiones). Se probó `3.2.0`:

```toml
ktor = "3.2.0"   # antes: "3.0.3"
```

Y compiló limpio en los 3 targets de iOS. A partir de ahí, se verificó
sistemáticamente que el bump **no rompiera ninguna otra plataforma** del
proyecto (algo que un cambio de versión de una dependencia tan
transversal —usada en Android, JVM/desktop, wasmJs y las 3 arquitecturas de
iOS— podía romper de formas no evidentes):

| Plataforma | Comando | Resultado |
|---|---|---|
| iOS Simulator arm64 | `compileKotlinIosSimulatorArm64` | ✅ `BUILD SUCCESSFUL` |
| iOS device arm64 | `compileKotlinIosArm64` | ✅ `BUILD SUCCESSFUL` |
| iOS Simulator x64 | `compileKotlinIosX64` | ✅ `BUILD SUCCESSFUL` |
| JVM (desktop) | `compileKotlinJvm` | ✅ `BUILD SUCCESSFUL` |
| wasmJs (web) | `compileKotlinWasmJs` | ✅ `BUILD SUCCESSFUL` |
| Android | `compileDebugKotlinAndroid` | ✅ `BUILD SUCCESSFUL` (tras resolver §3.5) |

**Verificación final tras el bump, ya con la app reinstalada limpia en el
Simulador**: el `ThrowIrLinkageError` desapareció por completo, confirmando
que la causa raíz era efectivamente un bug de la versión 3.0.3 del motor
Darwin de Ktor, no un error en el código de la app ni en la forma de
construir el `HttpClient`.

---

### 3.5 Configuración de entorno: Android SDK / `local.properties`

Al intentar verificar el bump de Ktor también en Android
(`./gradlew :composeApp:compileDebugKotlinAndroid`), tanto en este entorno
como en la máquina real del usuario, el build falló con:

```
SDK location not found. Define a valid SDK location with an ANDROID_HOME
environment variable or by setting the sdk.dir path in your project's local
properties file at '.../local.properties'.
```

**Explicación de fondo.** Gradle para Android necesita saber dónde está
instalado el Android SDK (las herramientas `aapt`, `d8`, las plataformas
`android-XX`, etc.) para poder compilar cualquier módulo Android. Esta ruta
**no se guarda en el repositorio** — cada máquina de desarrollo puede tener
el SDK en un sitio distinto — sino en un archivo `local.properties` en la
raíz del proyecto, con una única línea `sdk.dir=<ruta>`. Este archivo está
(correctamente) en `.gitignore`, porque es configuración de máquina local,
no del proyecto.

Se comprobó que Android Studio estaba instalado en la máquina del usuario,
pero el SDK nunca se había llegado a descargar (ni `~/Library/Android/sdk`
ni ninguna variante existían en el disco). El usuario localizó la ruta real
desde el propio SDK Manager de Android Studio
(`/Users/fredicrespo/Library/Android/sdk`), y se creó:

```properties
# local.properties (nuevo)
sdk.dir=/Users/fredicrespo/Library/Android/sdk
```

confirmando antes que la línea `local.properties` ya existía en
`.gitignore` (por tanto, este archivo nunca se subirá al repositorio).

---

### 3.6 "Sin conexión a internet": un mensaje de error completamente engañoso

Con el crash de Ktor resuelto, el siguiente intento de login **completó de
verdad** en Safari (el usuario pudo elegir su cuenta y dar consentimiento),
pero al volver a la app apareció, en rojo, junto al botón de login:

> *Sin conexión a internet. Comprueba tu conexión y vuelve a intentarlo.*

El propio usuario ya sospechaba que el mensaje era engañoso ("es raro,
porque el enlace a Google sí se abrió y pude loguearme, pero en la app
salta el error de conexión") — sospecha que resultó completamente
fundada.

#### 3.6.1 Por qué el mensaje mentía: el diseño de `ErrorCategory`

El proyecto tiene un sistema centralizado de traducción de excepciones a
mensajes de usuario (`ErrorCategory.kt`), pensado para no mostrar nunca
texto crudo de excepción al usuario final. Su lógica de clasificación:

```kotlin
enum class ErrorCategory { NO_CONNECTION, GONE_OR_FORBIDDEN, SERVER, OPERATION }

fun Throwable.errorCategory(): ErrorCategory = when (this) {
    is FirestoreException -> when {
        isGoneOrForbidden -> ErrorCategory.GONE_OR_FORBIDDEN
        statusCode >= 500 -> ErrorCategory.SERVER
        else -> ErrorCategory.OPERATION
    }
    is CloudFunctionException -> /* análogo, por código HTTP */
    else -> ErrorCategory.NO_CONNECTION   // ← el catch-all problemático
}
```

**El defecto de diseño**, señalado explícitamente en este documento porque
tiene valor pedagógico más allá de este bug concreto: **cualquier
excepción que no sea, específicamente, una `FirestoreException` o
`CloudFunctionException` con un código HTTP real cae, por defecto, en
"sin conexión"** — sea o no un problema de red. Esto es una elección de
diseño defendible cuando el 90% de las excepciones "no HTTP" que un
cliente REST puede lanzar son, en efecto, problemas de transporte (timeout,
DNS, sin red). Pero es frágil precisamente en el 10% restante: cualquier
excepción de programación, de deserialización, o de lógica de negocio
lanzada *antes* de recibir una respuesta HTTP válida (como en este caso)
queda **indistinguible, para el usuario, de un problema de conectividad
real** — un antipatrón de observabilidad: el sistema "sabe" internamente
qué fue lo que falló (el tipo y mensaje exactos de la excepción están ahí,
en `e`), pero **descarta esa información deliberadamente** antes de
mostrarla, dejando al desarrollador sin ninguna pista salvo instrumentar el
código a mano — que es exactamente lo que hizo falta hacer aquí.

#### 3.6.2 Metodología de diagnóstico: instrumentación dirigida, iterativa

Como el mensaje de error no aportaba ninguna información real, y este
entorno no puede ejecutar la app para inspeccionar el error en vivo, la
única vía fue añadir logs de diagnóstico temporales (`println`, que en
Kotlin/Native se envía a `stdout` y aparece en la consola de Xcode) e
iterar con el usuario probando y reportando. El proceso, honesto sobre sus
dos intentos fallidos antes de acertar:

1. **Primer intento**: `println` dentro del `catch` de
   `requestSignInWithIdp` (`FirestoreRepository.kt`) — la función que hace
   el POST a `accounts:signInWithIdp` de Identity Toolkit. **No se disparó
   nunca** — la petición HTTP en sí no estaba fallando.
2. **Segundo intento**: `println` justo antes de lanzar la excepción de
   "respuesta incompleta" en `signInWithGoogle` (la comprobación de que
   `idToken`/`localId`/`expiresIn` vinieran no-nulos en la respuesta de
   Firebase). **Tampoco se disparó** — la respuesta de Firebase Auth
   *sí* venía completa.
3. **Tercer intento, definitivo**: en vez de seguir adivinando en qué punto
   exacto de la cadena de llamadas estaba el fallo, se razonó al revés —
   se buscó, mediante una exploración exhaustiva del código (agente de
   búsqueda dedicado), **el único sitio de todo el código de la app que
   produce `GoogleAuthState.Error`** (el estado que dispara ese mensaje en
   la UI), y se puso el `println` ahí, en `GoogleAuthManager.kt`:

   ```kotlin
   } catch (e: Exception) {
       println("DEBUG handleGoogleToken falló: ${e::class.simpleName}: ${e.message}")
       _state.value = GoogleAuthState.Error(
           e.toUserMessage(settingsStore.getLanguage(), "google_auth_error_sign_in")
       )
   }
   ```

   Esto garantiza, por construcción, que el `println` se ejecute **siempre**
   que aparezca ese mensaje en pantalla — sin necesidad de acertar a la
   primera en qué línea exacta ocurre el fallo. La primera prueba con este
   log sí lo confirmó:

   ```
   DEBUG handleGoogleToken falló: TypeCastException: class kotlinx.cinterop.CPointer
   cannot be cast to class platform.Foundation.NSString
   ```

Este cambio de estrategia — de instrumentar puntos concretos adivinados a
instrumentar el único punto garantizado por la estructura del propio código
— es en sí mismo un principio de depuración generalizable: cuando no se
sabe dónde falla algo, es más eficiente encontrar el **cuello de botella
estructural** por el que necesariamente tiene que pasar cualquier camino
que produzca el síntoma observado, que ir probando puntos intermedios uno
a uno.

#### 3.6.3 Bug real #1: `CFStringRef` → `NSString` con `as` en el Keychain

El `TypeCastException` señalaba `SecureStore.ios.kt`, la implementación del
Keychain de iOS (usada para persistir la sesión de Google de forma segura).
El propio archivo tenía, en su KDoc, una advertencia explícita y
premonitoria escrita por quien lo redactó originalmente:

> *"⚠️ IMPORTANTE: este archivo NO se pudo compilar ni ejecutar en el
> entorno donde se escribió (sin toolchain de Xcode/macOS)... Verificar con
> un build nativo de iOS antes de publicar."*

Esa verificación nunca se había hecho — y esta sesión fue, literalmente, la
primera ejecución real de este código.

**Fundamento: qué es el "toll-free bridging" y por qué engaña al sistema de
tipos de Kotlin.** Desde los tiempos de NeXTSTEP, Apple diseñó Foundation
(el framework Objective-C de alto nivel: `NSString`, `NSDictionary`,
`NSData`...) y Core Foundation (el framework C de bajo nivel:
`CFStringRef`, `CFDictionaryRef`, `CFDataRef`...) para que ciertos pares de
tipos correspondientes sean **el mismo objeto en memoria, con el mismo
layout binario** — un `CFStringRef` y un `NSString` que representan el
mismo valor son, en tiempo de ejecución, literalmente el mismo puntero,
solo interpretado con un "vocabulario" de API distinto (funciones C en un
caso, mensajes Objective-C en el otro). A esto se le llama *toll-free
bridging*: convertir entre ambos "no cuesta nada" (no hay copia, no hay
conversión real) porque no hay nada que convertir.

El problema es que **Kotlin/Native no sabe nada de esto**. Cuando la
herramienta `cinterop` procesa el header de `Security.framework`, genera un
tipo `CFStringRef` como un simple `typealias` de `CPointer<...>` — un
puntero C genérico, sin ninguna relación declarada con la clase
Objective-C `NSString` (que sí se genera aparte, como una clase Kotlin real
que envuelve el mecanismo de mensajes de Objective-C). Desde la perspectiva
del sistema de tipos **estático** de Kotlin, `CPointer<...>` y `NSString`
son dos jerarquías de clases completamente distintas y no relacionadas,
aunque en tiempo de ejecución compartan la misma dirección de memoria.

El código original hacía, para las constantes de `Security.framework` como
`kSecClass`/`kSecClassGenericPassword` (que son, en efecto, `CFStringRef`
a nivel de C):

```kotlin
dict.setObject(kSecClassGenericPassword as NSString, forKey = kSecClass as NSString)
```

El operador `as` de Kotlin, cuando se usa entre dos tipos que el
compilador no puede relacionar estáticamente, genera una comprobación **en
tiempo de ejecución** (equivalente a `isInstance`/`instanceof`): "¿es esta
referencia, de verdad, una instancia de la clase Kotlin `NSString`?". Y la
respuesta, desde el punto de vista del sistema de tipos de Kotlin/Native
(que no entiende de toll-free bridging), es **no** — aunque a nivel de
Objective-C/CoreFoundation *sí* lo sea. De ahí el crash:

```
TypeCastException: class kotlinx.cinterop.CPointer cannot be cast to class platform.Foundation.NSString
```

**El fix correcto: `interpretObjCPointer`, no `reinterpret`.** El primer
intento de arreglo usó `kotlinx.cinterop.reinterpret()`, que también falló
en compilación (no en runtime esta vez, directamente el compilador lo
rechazó):

```
e: None of the following candidates is applicable:
fun <reified T : NativePointed> NativePointed.reinterpret(): T
fun <T : CPointed> CPointer<*>.reinterpret(): CPointer<T>
```

`reinterpret()` solo sirve para reinterpretar un `CPointer<A>` como
`CPointer<B>`, donde tanto `A` como `B` son subtipos de `CPointed` — es
decir, **structs de C**, no clases Objective-C. No es la herramienta
adecuada para cruzar la frontera entre un puntero C crudo y una clase
generada para interop con Objective-C.

La función correcta, provista específicamente por `kotlinx.cinterop` para
este caso, es **`interpretObjCPointer<T>(rawValue: NativePtr): T`** — toma
un puntero crudo y lo trata directamente como una referencia a la clase
Objective-C `T`, **sin ninguna comprobación de tipo en tiempo de
ejecución** (a diferencia de `as`), confiando en que el programador sabe,
por el contexto (aquí, por el propio diseño de Core
Foundation/Foundation), que ese puntero es realmente compatible:

```kotlin
@OptIn(ExperimentalForeignApi::class)
private fun CPointer<*>?.asNSString(): NSString = interpretObjCPointer(this!!.rawValue)
```

(`.rawValue` es la propiedad de `CPointer` que da acceso al puntero nativo
crudo subyacente, sin el envoltorio de tipo de Kotlin).

#### 3.6.4 Bug real #2: la dirección contraria, `NSMutableDictionary` → `CFDictionaryRef`

Tras aplicar el fix anterior y recompilar, la siguiente prueba del usuario
reveló **el mismo problema, en la dirección opuesta**:

```
DEBUG handleGoogleToken falló: ClassCastException: class
kotlin.native.internal.NSDictionaryAsKMap cannot be cast to class
kotlinx.cinterop.CPointer
```

El código original, para pasar el diccionario de consulta a las funciones C
`SecItemCopyMatching`/`SecItemAdd`/`SecItemDelete` (que esperan un
`CFDictionaryRef`, no un `NSDictionary`), hacía lo simétricamente inverso:

```kotlin
SecItemCopyMatching(query as CFDictionaryRef, resultVar.ptr)
```

Aquí el mensaje de error es aún más revelador sobre cómo Kotlin/Native
representa internamente los objetos Objective-C que también son
enumerables como colecciones: la instancia real de `query` en tiempo de
ejecución no es una clase "NSMutableDictionary" pura desde la óptica
interna del runtime de Kotlin, sino `kotlin.native.internal.NSDictionaryAsKMap`
— un wrapper que Kotlin/Native usa para que un `NSDictionary` de
Objective-C también pueda tratarse, transparentemente, como un
`kotlin.collections.Map` desde código Kotlin (una conveniencia de interop:
poder iterar/consultar un `NSDictionary` con la API de `Map` de Kotlin).
Ese wrapper, evidentemente, tampoco es un `CPointer` desde la jerarquía de
tipos de Kotlin, así que `as CFDictionaryRef` falla exactamente por el
mismo motivo estructural que el caso anterior, solo que cruzando la
frontera en el sentido contrario (de objeto Objective-C a puntero C, en vez
de puntero C a objeto Objective-C).

**El fix, simétrico en espíritu pero con funciones distintas** (porque
aquí no partimos de un puntero crudo, sino de un objeto Kotlin/Objective-C
del que hay que *extraer* el puntero crudo primero):

```kotlin
@OptIn(ExperimentalForeignApi::class)
private fun NSMutableDictionary.asCFDictionaryRef(): CFDictionaryRef =
    interpretCPointer<CPointed>(this.objcPtr())!!.reinterpret()
```

Paso a paso:

1. **`this.objcPtr()`** — extensión de `kotlinx.cinterop` sobre cualquier
   objeto de interop con Objective-C, que devuelve su puntero nativo crudo
   (`NativePtr`) — la operación exactamente inversa a lo que
   `interpretObjCPointer` hace en el otro sentido.
2. **`interpretCPointer<CPointed>(...)`** — la contraparte de
   `interpretObjCPointer`, pero para el mundo C puro: toma un `NativePtr` y
   lo envuelve como un `CPointer<T>` genérico (aquí, `T = CPointed`, el
   tipo C más genérico posible, sin estructura conocida todavía), sin
   comprobación de tipos.
3. **`.reinterpret()`** — ahora sí es la herramienta correcta, porque
   estamos moviéndonos entre dos tipos `CPointer<CPointed>` (de `CPointed`
   genérico a la estructura C específica que representa `CFDictionaryRef`
   internamente). Nótese que aquí no hace falta nombrar explícitamente esa
   estructura interna (`__CFDictionary`, que además resultó ser un símbolo
   **no exportado/no referenciable** directamente desde este paquete,
   según confirmó el propio compilador con `Unresolved reference
   '__CFDictionary'`): Kotlin infiere el tipo genérico `T` de
   `reinterpret()` a partir del tipo de retorno declarado de la función
   (`CFDictionaryRef`), sin que el código tenga que nombrarlo de forma
   explícita.

**Comparación de las dos funciones de bridging, lado a lado, para fijar el
patrón general:**

| Dirección | Función(es) | Cuándo usarla |
|---|---|---|
| Puntero C crudo → clase Objective-C | `interpretObjCPointer<T>(rawValue)` | Tienes un `CFStringRef`/`CFTypeRef`/similar y necesitas tratarlo como `NSString`/`NSData`/etc. |
| Objeto Objective-C → puntero C crudo | `objeto.objcPtr()` seguido de `interpretCPointer<CPointed>(...)` (+ `.reinterpret()` si hace falta el tipo C específico) | Tienes un `NSDictionary`/`NSString`/etc. y una función C espera un `CFDictionaryRef`/`CFStringRef`/similar |
| Puntero C → puntero C de otro tipo | `puntero.reinterpret<OtroTipoC>()` | Ambos lados son `CPointer<CPointed>` (structs C), nunca clases Objective-C |
| `String` de Kotlin → `NSString` | `kotlinString as NSString` (el `as` normal SÍ funciona aquí) | `kotlin.String` está genuinamente reconocido como toll-free-bridged con `NSString` por el propio compilador de Kotlin/Native — caso especial, distinto de los `CFStringRef` de Core Foundation de arriba |

Este último punto de la tabla merece una aclaración final, porque en el
mismo archivo hay una línea que usa `as NSString` y **sí es correcta**:

```kotlin
val data = (value as NSString).dataUsingEncoding(NSUTF8StringEncoding) ?: return
```

Aquí `value` es un `kotlin.String` normal (no un `CFStringRef` de C) — y el
propio compilador de Kotlin/Native **sí** reconoce, de forma especial y
verificada, la equivalencia entre `kotlin.String` y `NSString` (es una de
las pocas conversiones de tipo que Kotlin/Native soporta de forma nativa,
precisamente para hacer cómodo trabajar con Strings al hacer interop). El
problema nunca fueron los `String` de Kotlin — fueron específicamente las
constantes `CFStringRef` provenientes de una API de C.

**Verificación.** `BUILD SUCCESSFUL` en las 3 arquitecturas de iOS tras
ambos fixes. Pendiente de confirmación final en runtime por el usuario (ver
§3.7).

---

### 3.7 Estado abierto al cierre de este documento

En la última prueba reportada, tras aplicar los dos fixes de `SecureStore`,
Safari se quedó **mostrando una página en blanco de forma indefinida** en
`accounts.google.com`, sin llegar a cargar el formulario de login.

Lo que se descartó activamente:

- **No es un crash de Kotlin/Swift**: el log de consola de esa prueba no
  contenía ningún `ThrowIrLinkageError`, `TypeCastException`,
  `ClassCastException` ni ninguna línea `DEBUG` de los `println` de
  diagnóstico — es decir, la app no llegó siquiera a intentar procesar
  nada, porque Safari nunca completó de cargar la página.
- Las únicas líneas del log eran ruido habitual ya visto en pruebas
  anteriores de esta misma sesión (telemetría de lanzamiento de Apple,
  ruido de `PointerUI`, `nw_connection_copy_*` de `Network.framework`), más
  una nueva: `Snapshot request ... BSActionErrorDomain code 6 ("anulled")`
  — un error del propio sistema de snapshots del Simulador (usado para las
  miniaturas del multitarea), consistente con un problema de
  infraestructura del Simulador, no de la app.

Es la **segunda vez en esta sesión** que aparece un síntoma de este tipo
(la primera fue un `DeviceKitError`/"Live device view took longer than
expected to connect" al lanzar la app por primera vez tras el fix inicial
de compilación, resuelto entonces reiniciando `CoreSimulatorService` y/o el
propio Simulador) — un patrón de fragilidad de la infraestructura del
Simulador de Xcode 27 (posiblemente aún en fase temprana/beta) que conviene
tener presente para el futuro: cuando un síntoma no deja ningún rastro en
el log de la app, la primera sospecha razonable es el Simulador, no el
código.

**Pasos de troubleshooting entregados al usuario, pendientes de resultado:**

1. Comprobar si *cualquier* página carga en esa misma ventana de Safari del
   Simulador (por ejemplo `google.com` a secas) — para distinguir un
   problema de red general del Simulador de un problema específico de esa
   URL/dominio.
2. Recargar la página (`Cmd+R` dentro de Safari) — el proceso "Web Content"
   del Simulador a veces se cuelga silenciosamente tras un cambio de
   estado de red.
3. Reiniciar el dispositivo Simulador (Device → Restart en Simulator.app).
4. Como último recurso, repetir lo que ya funcionó antes:
   `killall -9 com.apple.CoreSimulator.CoreSimulatorService` + reinicio del
   Simulador.

---

## 4. Parte III — Inventario completo de archivos tocados

| Archivo | Tipo de cambio | Resumen |
|---|---|---|
| `composeApp/src/iosMain/kotlin/org/taskhub/platform/Platform.ios.kt` | Modificado | Import de `popoverPresentationController` (§3.1); `launchGoogleSignIn()` reescrito para usar PKCE + `openURL` moderno (§3.3.6) |
| `composeApp/src/iosMain/kotlin/org/taskhub/platform/GoogleIosSignInHelper.kt` | **Nuevo** | Flujo completo Authorization Code + PKCE para Google Sign-In en iOS (§3.3.5) |
| `iosApp/iosApp/ContentView.swift` | Modificado | `onOpenURL` reenvía la URL cruda a Kotlin en vez de parsear el fragmento en Swift (§3.3.7) |
| `composeApp/src/iosMain/kotlin/org/taskhub/storage/SecureStore.ios.kt` | Modificado | Bridging CFStringRef↔NSString y NSDictionary↔CFDictionaryRef corregido con `interpretObjCPointer`/`objcPtr`+`interpretCPointer` (§3.6.3, §3.6.4) |
| `gradle/libs.versions.toml` | Modificado | `ktor = "3.0.3"` → `"3.2.0"` (§3.4.2) |
| `gradle.properties` | Modificado (parcialmente heredado) | `org.gradle.java.home` (heredado, confirmado); `org.gradle.tooling.parallel=true` añadido por Android Studio al configurar el SDK |
| `local.properties` | **Nuevo**, gitignored | `sdk.dir` al SDK de Android real de la máquina (§3.5) |
| `iosApp/iosApp.xcodeproj/project.pbxproj` | Modificado (mixto: manual + Xcode) | `IPHONEOS_DEPLOYMENT_TARGET` unificado a `17.6` (manual, §3.2); resto de cambios son normalización automática de Xcode al reabrir el proyecto (§3.2) |
| `iosApp/iosApp.xcodeproj/xcshareddata/xcschemes/iosApp.xcscheme` | Modificado (solo Xcode) | `BuildableName` actualizado a `"Task Hub.app"`, versión de scheme — cambios automáticos de Xcode, no manuales |
| `composeApp/src/commonMain/kotlin/org/taskhub/network/FirestoreRepository.kt` | Modificado (diagnóstico) | `println` temporal en `signInWithGoogle` (§3.6.2) — **sin retirar**, ver §5 |
| `composeApp/src/commonMain/kotlin/org/taskhub/ui/models/GoogleAuthManager.kt` | Modificado (diagnóstico) | `println` temporal en `handleGoogleToken` (§3.6.2) — **sin retirar**, ver §5 |

---

## 5. Parte IV — Deuda técnica y pendientes explícitos

Listado explícito, sin adornos, de todo lo que queda abierto:

1. **Dos `println` de diagnóstico temporal sin retirar:**
   - `FirestoreRepository.kt`, dentro de `signInWithGoogle`, justo antes de
     lanzar `IllegalStateException("Google sign-in falló: respuesta de
     Firebase Auth incompleta...")`. Se dejó ahí porque el primer intento
     de diagnóstico apuntaba a ese sitio (§3.6.2, paso 2) — resultó no ser
     el culpable, pero el log en sí es inofensivo y podría ser útil dejarlo
     como diagnóstico permanente de bajo coste, o retirarlo por limpieza.
   - `GoogleAuthManager.kt`, dentro del `catch` de `handleGoogleToken`
     — el log que sí llevó a encontrar ambos bugs de `SecureStore.ios.kt`.
     **Recomendación:** no retirarlo hasta que el usuario confirme que el
     login de Google completa el flujo end-to-end sin errores en una
     prueba limpia (tras resolver §3.7) — sirve como red de seguridad por
     si aparece un tercer bug de la misma familia en otro punto de
     `handleGoogleToken` (`restoreHouseholds`, `repointPersonalHousehold`,
     `syncHouseholdsToCloud`, `syncGoogleAvatar` — funciones que, según el
     análisis de §3.6.2, ya tragan sus propias excepciones internamente,
     pero no está de más tener la red de seguridad hasta la confirmación
     final).
2. **`TEAM_ID` vacío en `iosApp/Configuration/Config.xcconfig`** —
   detectado ya al inicio de la sesión como posible bloqueante para builds
   de dispositivo físico con firma automática. El usuario consiguió
   ejecutar en un iPhone físico real en algún punto de la sesión (ver la
   conversación sobre Developer Mode y confianza del certificado), lo que
   sugiere que rellenó el Team directamente desde Xcode (Signing &
   Capabilities), pero ese cambio **no se propagó al `.xcconfig`
   versionado** — sigue apareciendo vacío en el repositorio. No bloquea
   nada hoy, pero conviene rellenarlo explícitamente para que un clon
   limpio del repositorio no tenga que redescubrir este paso.
3. **El defecto de diseño de `ErrorCategory`** (§3.6.1): el catch-all
   `else -> ErrorCategory.NO_CONNECTION` etiqueta cualquier excepción sin
   código HTTP como "sin conexión", con independencia de la causa real.
   Esto **ya ha costado tiempo de diagnóstico una vez** (esta sesión
   entera) y volverá a costarlo la próxima vez que una excepción no-HTTP
   se cuele en cualquiera de los flujos que pasan por `toUserMessage`. Una
   mejora razonable a futuro: añadir una categoría explícita para
   "excepción inesperada/de programación" (con su propio mensaje genérico,
   distinto de "sin conexión"), reservando `NO_CONNECTION` solo para tipos
   de excepción específicamente reconocidos como de transporte (p. ej.
   `kotlinx.io.IOException` y subtipos, ya usados en otras partes del
   código de este mismo archivo para `isTransientReadFailure`).
4. **Warning preexistente sin resolver** en
   `SecureStore.ios.kt` (`NSString.create(data, NSUTF8StringEncoding)`
   necesita `@OptIn(BetaInteropApi::class)`) — presente desde antes de esta
   sesión, no bloqueante, no tocado.
5. **Verificación de Android incompleta**: solo se confirmó
   `compileDebugKotlinAndroid` (compilación de Kotlin). No se verificó un
   build completo (`assembleDebug`/`bundleRelease`) ni una ejecución real
   en un dispositivo/emulador Android tras el bump de Ktor — riesgo bajo
   dado que Android usa el motor `ktor-client-okhttp`, no el `Darwin`
   afectado, pero no verificado de extremo a extremo en esta sesión.
6. **La página en blanco de Safari (§3.7)** — sin confirmación final de
   causa ni de resolución al cierre de este documento.

---

## 6. Parte V — Apéndice conceptual

Sección de referencia rápida para quien quiera repasar, de forma
autocontenida, los fundamentos que sustentan los fixes de este documento.

### 6.1 Kotlin/Native e interoperabilidad con C/Objective-C

Kotlin/Native compila Kotlin a binarios nativos usando LLVM como backend,
sin máquina virtual. Para interactuar con APIs de C u Objective-C (como
todo el SDK de iOS), usa la herramienta `cinterop`, que procesa headers
(`.h`) y genera *bindings* de Kotlin automáticamente:

- Las **funciones C** se exponen como funciones Kotlin de nivel de paquete.
- Los **structs C** se exponen como subtipos de `CPointed`, accesibles a
  través de `CPointer<T : CPointed>` — un puntero tipado.
- Las **clases Objective-C** (`@interface`) se exponen como clases Kotlin
  reales, con sus métodos como funciones/propiedades miembro.
- Las **categorías de Objective-C** (extensiones de una clase existente
  declaradas en otro header) se exponen como *extension
  functions/properties* de Kotlin — requieren import explícito, a
  diferencia de los miembros declarados en el `@interface` principal
  (§3.1).
- Los **singletons de Kotlin (`object`)** se exponen a Objective-C/Swift
  como una clase con una propiedad estática `.shared` que da acceso a la
  única instancia (§3.3.5).

Todo el código que usa estos mecanismos requiere el opt-in
`@OptIn(ExperimentalForeignApi::class)`, porque en el momento de escribir
este documento la API de interop de Kotlin/Native sigue marcada como
experimental por JetBrains (sujeta a cambios entre versiones del
compilador, aunque en la práctica es muy estable desde hace varias
versiones).

**El "toll-free bridging" de Apple** (§3.6.3) es un caso particular:
Core Foundation (C) y Foundation (Objective-C) comparten representación
binaria para ciertos pares de tipos, pero Kotlin/Native, al generar
bindings independientes para cada framework, no modela esa relación en su
sistema de tipos. Las funciones `interpretObjCPointer`/`interpretCPointer`/
`objcPtr`/`reinterpret` son las herramientas de bajo nivel para cruzar esas
fronteras manualmente cuando el programador sabe (por conocimiento externo
al compilador) que dos tipos son, en efecto, compatibles a nivel de
runtime.

### 6.2 OAuth 2.0: de Implicit Flow a Authorization Code + PKCE

Ver el desarrollo completo en §3.3.2–§3.3.3. Resumen de referencia rápida:

| | Implicit Flow (obsoleto) | Authorization Code + PKCE (actual) |
|---|---|---|
| `response_type` | `token` / `id_token` | `code` |
| Dónde llega el token | Directamente, en el fragmento de la URL de redirect | Nunca directamente — llega un `code` de un solo uso |
| Paso adicional | Ninguno | POST al *token endpoint*, canjeando `code` (+ `code_verifier`) por el token |
| Protección contra interceptación del `code`/token en tránsito | Ninguna | El `code_verifier` (necesario para el canje) nunca viaja en la URL, solo su hash (`code_challenge`) |
| Apto para "public clients" (apps nativas, sin backend) | Sí, pero inseguro | Sí, y es el estándar recomendado (RFC 8252) |
| Estado en Google (clientes tipo iOS/Android) a 2026 | Rechazado (`Error 400: unsupported_response_type`) | Soportado y requerido |

### 6.3 Compatibilidad binaria de klibs en Kotlin/Native (ABI versioning)

Cada compilador de Kotlin/Native entiende un rango de versiones de ABI de
klib. Una dependencia publicada con un compilador más nuevo que el del
proyecto consumidor **no se puede usar**, sin excepción, hasta que se
actualice también el compilador del proyecto — a diferencia del ecosistema
JVM, donde el bytecode tiene una compatibilidad hacia atrás mucho más
amplia y permisiva. Por eso, al elegir la versión de una librería
multiplataforma para un proyecto Kotlin/Native, **la versión del compilador
de Kotlin del proyecto acota, de facto, qué versiones de esa librería son
siquiera instalables** — no basta con mirar el *changelog* de la librería
para ver qué versión trae el fix que se busca; hay que cruzarlo contra qué
versión de Kotlin exige esa release.

### 6.4 Resolución de motores en Ktor Multiplatform

`HttpClient { ... }`, sin argumento de motor, resuelve automáticamente el
motor disponible en el *classpath* de cada plataforma (Darwin en iosMain,
OkHttp/Java en Android/JVM, un motor basado en `fetch`/XHR en wasmJs...),
siempre que haya exactamente un candidato en las dependencias declaradas
para ese source set. Especificar el motor explícitamente
(`HttpClient(Darwin) { ... }`) es válido y a veces necesario (por ejemplo,
si hay varios motores en el mismo classpath y hace falta desambiguar, o
para acceder a configuración específica del motor), pero introduce una vía
de código distinta a la resolución automática — en este proyecto, el
patrón establecido y verificado en producción es dejar que Ktor resuelva el
motor automáticamente, y así se ha mantenido en el nuevo código (§3.4.1).
