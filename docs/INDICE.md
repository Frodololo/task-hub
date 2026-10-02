# Índice de documentación — Task Hub

Mapa de toda la documentación del proyecto: qué contiene cada archivo y cuándo
consultarlo. Los documentos nuevos (creados en el encargo de documentación
exhaustiva de 2026-09-06) están al principio; el resto es histórico — se
mantiene tal cual, sin reescribir, como registro de decisiones y auditorías
pasadas.

## Para empezar

- **[README.md](../README.md)** — qué es Task Hub, características, cómo
  compilar/ejecutar/probar. El punto de entrada.
- **[PRIMEROS-PASOS.md](PRIMEROS-PASOS.md)** — onboarding completo para un
  desarrollador nuevo: requisitos, build, tests, bundle de release, setup de
  Firebase local, pipeline de CI/CD (`.github/workflows/release.yml`).
- **[ARQUITECTURA.md](ARQUITECTURA.md)** — visión de conjunto del stack,
  estructura de directorios módulo a módulo, flujo de datos, DI (Koin),
  navegación (Voyager), i18n, expect/actual por plataforma, y el porqué de
  cada decisión de arquitectura (REST vs SDK de Firestore, sin backend, etc.).
- **[MODELO-DATOS.md](MODELO-DATOS.md)** — colecciones y subcolecciones de
  Firestore, campos de cada DTO, y `firestore.rules` explicadas regla a regla.
- **[FLUJOS-PRINCIPALES.md](FLUJOS-PRINCIPALES.md)** — los flujos de usuario
  principales (alta de hogar, tareas, recompensas/ranking, notificaciones,
  calendario, chat, espacio personal, login, deep links) trazados con
  referencias `archivo:línea` reales.

Además de estos documentos, la mayoría de clases, funciones y archivos `.kt`
del código (`composeApp/src/`) tienen ahora cabecera de archivo y KDoc en
español explicando su rol; y `firestore.rules` tiene comentarios regla a
regla. Es la fuente más fiable para el detalle línea a línea — estos cinco
documentos dan la vista de conjunto y el mapa.

## Producto y negocio

- **[specs.md](specs.md)** — especificación de producto original (historias
  de usuario, criterios de aceptación). **Ojo:** contiene al menos una
  feature descrita que no está implementada tal cual (ver hallazgo H2 de
  `analisis-target-publico-2026-09-06.md`); no se ha corregido, se cita ahí.
- **[analisis-target-publico-2026-09-06.md](analisis-target-publico-2026-09-06.md)**
  — informe encargado por el dueño sobre a quién va dirigida realmente la
  app hoy (ya no solo "familias con niños"), las contradicciones entre
  marketing/UI/clasificación de Play, y recomendaciones de reposicionamiento
  con una propuesta concreta. Incluye una lista consolidada de hallazgos
  (bugs/inconsistencias) detectados durante esta ronda de documentación.
  **Lectura recomendada antes de tocar copy de marketing, naming de roles o
  el listado de Play.**
- **[guia-marketing.md](guia-marketing.md)** — guía de marketing y
  visibilidad (18-ago-2026). Enfocada en familias; contrastar con el análisis
  de target de arriba antes de usarla como referencia actual.
- **[guia-publicacion.md](guia-publicacion.md)** — guía de publicación en
  Play Store: proceso, checklist, clasificación de contenido (§2.3: la app
  está marcada explícitamente como NO dirigida a familias/niños).
- **[play-store-listing.md](play-store-listing.md)** — borradores del
  listado de Play (ES + EN), copia-pega directo a Play Console.
- **[competitors.md](competitors.md)** — análisis de la competencia.

## Histórico: auditorías, correcciones y paneles de expertos

Documentos de trabajo de rondas anteriores de revisión/corrección. Se
mantienen como registro — no se han fusionado ni reescrito. Útiles para
entender *por qué* el código quedó como quedó en ciertos puntos.

- **[audit-2026-08-30.md](audit-2026-08-30.md)** — auditoría exhaustiva
  (bugs + optimización) en 7 frentes paralelos.
- **[cambios-2026-08-17.md](cambios-2026-08-17.md)** — cambios aplicados
  tras la auditoría visual QA inicial.
- **[visual-qa-report.md](visual-qa-report.md)** — auditoría visual QA en
  dispositivo físico (Xiaomi Mi A2), 2026-08-17.
- **[ui-ux-review.md](ui-ux-review.md)** (+ `-1-resumen`, `-2-critico`,
  `-3-importante`, `-4-menor`) — revisión de UI/UX y accesibilidad (contraste
  WCAG), desglosada por severidad.
- **[ui-ux-refactor-contract.md](ui-ux-refactor-contract.md)** — contrato
  que fijó las reglas del refactor de UI/UX (sin cambiar lógica de negocio).
- **[refactor-arquitectura-2026-08-31.md](refactor-arquitectura-2026-08-31.md)**
  — segunda pasada de cierre de pendientes arquitectónicos.
- **[atomicidad-commit-pendiente.md](atomicidad-commit-pendiente.md)** —
  evaluación (y descarte razonado) de usar `:commit` transaccional de
  Firestore REST para `completeTask`/`reassignTaskCompletion`. Relevante para
  entender los límites de atomicidad mencionados en el hallazgo H9 de
  `analisis-target-publico-2026-09-06.md`.
- **[review-panel-expertos-2026-08-31.md](review-panel-expertos-2026-08-31.md)**
  (+ `-v2-2026-08-31`, `-v3-2026-09-01`, `-v4`, `-2026-09-02`, `-2026-09-03`,
  `-2026-09-04`, `-notificaciones-2026-09-05`, `-2026-09-10`, `-2026-09-11`,
  `-2026-09-12`, `-2026-09-13`) — sucesivas rondas del panel de expertos
  (subagentes en paralelo simulando especialistas) sobre UI/UX,
  accesibilidad, funcionalidad, arquitectura, rendimiento, seguridad,
  recurrencia y notificaciones. Cada archivo es una ronda distinta;
  ordenados cronológicamente por la fecha en el nombre. Las rondas
  `-2026-09-12` y `-2026-09-13` documentan que el panel de 13 subagentes en
  paralelo falló por completo (límite de sesión de la API) dos veces
  seguidas — sin hallazgos de panel en esos dos informes, solo verificación
  directa del coordinador.
- **[correcciones-2026-09-04-integridad-seguridad.md](correcciones-2026-09-04-integridad-seguridad.md)**
  y **[correcciones-2026-09-04-arquitectura-ux-tests.md](correcciones-2026-09-04-arquitectura-ux-tests.md)**
  — aplicación de los hallazgos de los paneles del 04-sep (integridad,
  concurrencia, privacidad, seguridad, arquitectura, rendimiento, UX, tests).
- **[auditoria-sync-dispositivos-2026-09-12.md](auditoria-sync-dispositivos-2026-09-12.md)**
  — causa raíz de los hogares compartidos huérfanos al iniciar sesión con
  Google desde modo anónimo (login siempre pedía un UID nuevo en vez de
  vincular la sesión anónima activa) y el fix aplicado (vinculación de
  cuenta vía Identity Toolkit, sin tocar `firestore.rules`).
- **[google-only-auth-2026-09-12.md](google-only-auth-2026-09-12.md)** —
  eliminación completa de la auth anónima (decisión de producto): login con
  Google obligatorio, gate de login en `App.kt` (`AuthGateScreen`), y por qué
  el fix de vinculación del informe anterior quedó obsoleto/eliminado.
- **[propuestas-delight-visual-satisfaccion-2026-09-19.md](propuestas-delight-visual-satisfaccion-2026-09-19.md)**
  — informe de propuestas (sin implementar) de diseño visual estático y
  gamificación para hacer la app más vistosa/satisfactoria, complementario a
  `revision-delight-experiencia-2026-09-18.md` (aquel cubrió motion/háptica/
  celebraciones; este cubre identidad visual, economía de puntos, logros,
  ranking y Home).
- **[login-web-2026-09-17.md](login-web-2026-09-17.md)** — implementación de
  Google Sign-In real en la web (target wasmJs) vía Google Identity Services
  (GIS): el login era un no-op hasta este encargo, lo que dejaba la web
  inutilizable tras la decisión Google-only de arriba. Puente JS↔Kotlin/Wasm
  por polling (sin pasar lambdas Kotlin a JS) y dónde vive ahora el
  `WEB_CLIENT_ID` compartido con Android.
- **[ios-instalacion-local-2026-09-18.md](ios-instalacion-local-2026-09-18.md)**
  — Google Sign-In real en iOS (Safari + URL scheme, sin `GIDSignIn`/SDK),
  share nativo (`UIActivityViewController`) y dos fixes al build de Xcode
  (sandboxing de scripts, `env -i` en "Compile Kotlin Framework") que iban a
  romper el primer build real en Mac. Incluye guía completa de instalación
  local (sideload) y troubleshooting para el miembro del equipo con Mac.
- **[ios-build-y-google-signin-2026-09-21.md](ios-build-y-google-signin-2026-09-21.md)**
  — continuación directa del informe anterior: primer build real verificado
  con el compilador (6 errores de compilación por un import ausente de una
  categoría de Objective-C), el login de Google seguía completamente roto
  en runtime (Google rechazaba el implicit flow con `Error 400:
  unsupported_response_type` — migración completa a Authorization Code +
  PKCE, nuevo `GoogleIosSignInHelper.kt`), un crash de enlazado en el motor
  Darwin de Ktor (bug de la versión 3.0.3, resuelto con bump a 3.2.0 tras
  descartar la última estable por incompatibilidad de ABI con el compilador
  del proyecto), y dos bugs de bridging CFStringRef/NSDictionary↔CoreFoundation
  en el Keychain (`SecureStore.ios.kt`, nunca antes ejecutado) que se
  disfrazaban de "Sin conexión a internet". Desarrollo pedagógico extenso de
  cada causa raíz (interop de Kotlin/Native, OAuth 2.0/PKCE, compatibilidad
  de ABI de klibs). Cierra con una página de Safari en blanco sin resolver
  (probable flakiness del Simulador) y una lista explícita de deuda técnica
  (`println` de diagnóstico sin retirar, `TEAM_ID` vacío).
- **[ios-build-fix-post-merge-2026-10-02.md](ios-build-fix-post-merge-2026-10-02.md)**
  — `main` dejó de compilar para iOS justo tras mergear el PR anterior: dos
  desarrolladores tocaron las mismas funciones (`Platform.ios.kt`,
  `GoogleIosSignInHelper.kt`) en paralelo. Un `@Volatile` sin import, y un
  `topMostViewController()` reescrito con `UIWindow.isKeyWindow`/`keyWindow`
  — símbolos que existen en el SDK real de iOS pero no están expuestos en
  el binding de Kotlin/Native de este proyecto (confirmado contra el
  compilador, no por inspección). Mismo patrón que el caso de
  `popoverPresentationController` del informe anterior.
- **[correcciones-2026-09-05-propuestas-aprobadas.md](correcciones-2026-09-05-propuestas-aprobadas.md)**
  y **[correcciones-2026-09-05-notificaciones.md](correcciones-2026-09-05-notificaciones.md)**
  — ronda de correcciones aprobadas y el cierre del flujo de notificaciones
  end-to-end (asignación de tarea / mensaje nuevo → notificación del
  sistema).
- **[refactor-limpieza-2026-09-26.md](refactor-limpieza-2026-09-26.md)** —
  opt-out de Firebase Analytics en Ajustes (RGPD) y migración de
  `GoogleSignInResultHolder` a un `sealed class GoogleSignInResult`
  (Loading/Success/Cancelled) por el lado del consumidor
  (`GoogleAuthManager`), manteniendo `setResult(String?)` sin cambios en los
  8 call-sites de plataforma para no arriesgar el build de iOS sin poder
  verificarlo (sin Xcode disponible).
- **[admob-ump-tcf-2026-09-26.md](admob-ump-tcf-2026-09-26.md)** —
  implementación de un CMP compatible con TCF v2 (Google User Messaging
  Platform) para evitar el riesgo de suspensión de la cuenta de AdMob en
  EEE/Reino Unido: `MobileAds.initialize()` se difiere de
  `TaskHubApplication` a `ConsentManager` (androidMain), que solo lo dispara
  tras obtener consentimiento vía UMP desde `MainActivity`.
- **[encargos/](encargos/)** — encargos puntuales más pequeños (mensajería,
  avatares, puntos de agradecer/donar, arreglos de compilación/imports,
  cobertura de tests críticos, propuestas de recurrencia/privacidad/
  arquitectura). Cada archivo es un encargo autocontenido.
- **[qa-screenshots/](qa-screenshots/)** — capturas de pantalla de bugs
  visuales encontrados durante QA, referenciadas desde los informes de
  arriba.

## Legal

- **[privacy.html](privacy.html)** — política de privacidad publicada
  (HTML, no Markdown).

## Convención para nueva documentación

Si añades un documento nuevo a `docs/`:
- Si es un documento **vivo** que describe el estado actual del proyecto
  (arquitectura, modelo de datos, flujos, onboarding), actualiza el
  documento existente correspondiente en vez de crear uno nuevo.
- Si es un **informe puntual** (auditoría, encargo, análisis con fecha),
  créalo como archivo nuevo con la fecha en el nombre (`nombre-YYYY-MM-DD.md`)
  y añade una entrada aquí, en la sección histórica correspondiente.
