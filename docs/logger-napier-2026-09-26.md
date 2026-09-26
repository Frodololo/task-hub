# Logger multiplataforma (Napier) — 2026-09-26

## Estado encontrado

La tarjeta pedía introducir un logger mínimo multiplataforma con Napier para que
los `catch` de `commonMain` dejaran de tragar excepciones en silencio. Al
empezar a trabajar en la tarjeta, la implementación **ya existía en el árbol de
trabajo** (working tree limpio, `git status` sin cambios pendientes):

- Dependencia `io.github.aakira:napier:2.7.1` ya declarada en
  `gradle/libs.versions.toml` (`napier = "2.7.1"`) y añadida a `commonMain` en
  `composeApp/build.gradle.kts` (`implementation(libs.napier)`).
- `AppLog` (`composeApp/src/commonMain/kotlin/org/taskhub/platform/AppLog.kt`)
  envuelve Napier con `d()/w()/e()`:
  - `d()` solo emite si `DebugFlags.isEnabled` (equivalente al `println`
    condicionado que sustituye).
  - `w()`/`e()` emiten siempre, también en release, para dejar rastro de
    fallos reales.
  - La inicialización (`Napier.base(DebugAntilog())`) es **perezosa y
    centralizada** en `AppLog.ensureInit()` (guardada con `@Volatile
    initialized`), en vez de repetirse en cada plataforma. Es equivalente en
    efecto a inicializar desde cada `*Main`, pero evita duplicar el `Napier.base(...)`
    cuatro veces.
- No quedan usos de `println()` / `printStackTrace()` / `System.out` en
  `commonMain` (verificado con grep recursivo) — las únicas coincidencias son
  menciones en comentarios KDoc que describen qué sustituyó `AppLog`.
- Ya migrados a `AppLog`: `TaskScreenModel.kt` (commonMain),
  `Platform.jvm.kt` (fallo de Google Sign-In desktop), `Platform.wasmJs.kt`
  (share no implementado en web) y `MainActivity.kt` (Android, eventos de
  In-App Update).
- No se ha tocado Firebase Analytics ni Crashlytics (androidMain los sigue
  usando tal cual).

## Hallazgo sobre el commit

El código ya estaba commiteado, pero mezclado dentro de un commit
`wip: checkpoint kanban-refactor-ux.md` (`da49aa8`) junto con cambios no
relacionados (`TaskListRules.kt`, `TaskDetailScreen.kt`,
`functions/completeRecurringTask.ts`, etc.). **No existe** un commit aislado
`feat: logger multiplataforma Napier` como pedía la tarjeta.

Como el árbol de trabajo está limpio (no hay diff que commitear), no se ha
creado un commit nuevo — habría sido un commit vacío. Si se quiere un historial
más limpio, sería necesario reescribir `da49aa8` (rebase interactivo /
`git reset` selectivo) para separar el logger del resto de cambios de ese wip;
no se ha hecho porque implica reescribir historia ya existente sin
confirmación previa del usuario.

## Verificación

1. `./gradlew :composeApp:compileDebugKotlinAndroid --console=plain` →
   `BUILD SUCCESSFUL` (tareas `UP-TO-DATE`, sin cambios que compilar).
2. `./gradlew :composeApp:jvmTest --rerun-tasks --console=plain` →
   `BUILD SUCCESSFUL`, todos los XML de resultados con `failures="0"
   errors="0"`.

## Pendiente fuera de mi alcance

No se ha encontrado ningún archivo de kanban en el repo (`docs/` no tiene
tablero), así que no he podido mover la tarjeta a "Completado" — si el kanban
vive en una herramienta externa, hay que actualizarlo manualmente.
