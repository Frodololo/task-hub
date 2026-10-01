# Panel de expertos v23 — Auditoría integral (2026-09-30)

HEAD de partida: `e7de827` (v0.7.54 + fixes v22). Panel de 14 expertos, cada uno auditando
su área en el código actual completo (no solo el último diff), contrastando contra
`docs/review-panel-expertos-2026-09-29-v21.md` y `docs/review-panel-expertos-2026-09-30-v22.md`
para no duplicar hallazgos.

**Metodología**: 14 subagentes en paralelo, cada uno con lectura completa de su área +
verificación directa en código (grep, lectura de líneas, en varios casos ejecución de
tests/emuladores). No es un pase monolítico ni self-report: cada hallazgo cita
`archivo:línea` verificado.

**Fixes aplicados en esta ronda** (APLICA YA, ver detalle por experto): 10 fixes de bajo
riesgo, todos verificados con `BUILD SUCCESSFUL` + 303 tests JVM + 54 unit + 45 integration
(functions) en verde + arranque limpio del emulador de Firestore (sanity de sintaxis de
`firestore.rules`). Ningún bump de versión, ningún push — pendiente de decisión del dueño
sobre las PROPUESTAS.

---

## Resumen ejecutivo

**Hallazgo más importante de la ronda**: el experto 9 (Seguridad) encontró un hallazgo
NUEVO de severidad IMPORTANTE no visto en rondas anteriores — `firestore.rules` no validaba
el signo de `rewards/{rid}.cost`, permitiendo a un actor ya confiable (`isTrusted`, es decir
owner/admin) crear una recompensa de coste negativo que, al "canjearse", **incrementa** el
saldo de puntos de un miembro saltándose todos los topes por-transacción que sí protegen
`donatePoints`/`appreciateMember`. **Corregido en esta ronda** (ver Seguridad, hallazgo #1).

El experto 5 (UX) confirmó y documentó con más detalle que nunca el bug de "hogar fantasma"
(household huérfano en Firestore): es alcanzable con un simple tap en "atrás" desde
`CreateProfileScreen` en el flujo normal de alta, no solo matando el proceso. Sigue siendo
PROPUESTA (requiere decisión de arquitectura: creación transaccional vía Cloud Function, o
cascade-delete al abandonar el wizard).

El CRÍTICO de v22 (`CalendarSyncManager` desvincula Google Calendar tras fallos de RED, no
solo de auth) **sigue exactamente igual**, confirmado independientemente por 3 expertos
(Funcionalidad, QA, Red/offline). Sigue siendo la EXCEPCIÓN del encargo — documentado con
opciones de remediación, sin aplicar.

---

## 1. Estética / diseño visual

**Estado hallazgos v21/v22 (heredados):**
- [SIGUE ABIERTO] `SemanticColors.info` coincide 4/4 campos con `primary` de Océano claro
  (`SemanticColors.kt:48-51` vs `Theme.kt:465-468`) — confirmado como calco total en claro,
  parcial (1/4) en oscuro.
- [SIGUE ABIERTO] `try/catch valueOf` duplicado en `App.kt` — ver Programador senior, [APLICADO].
- [SIGUE ABIERTO] Medianoche + `enableEdgeToEdge()` sin reactividad en caliente — ver
  Programador senior.
- [SIGUE ABIERTO] Duplicación `household.emoji ?: spaceType.emoji` — ver UI/componentes.
- [SIGUE ABIERTO] Clave i18n `edit_profile_emoji_content_desc` reusada en
  `CreateHouseholdScreen.kt:219`.
- [CERRADO] Inconsistencia dropdown vs `RadioOptionRow` en `SettingsSheet.kt` — los 3
  selectores ya usan el mismo componente.

**Hallazgos NUEVOS:**
- **IMPORTANTE, PROPUESTA** — `SemanticColors.kt:37-41` + `Theme.kt:325-339`:
  `BadgeTone.Success` es un calco exacto (4/4 campos) de `BadgeTone.Teal` en Naturaleza
  claro. Impacto real en producción: `CalendarScreen.kt:902-918` renderiza un badge de
  estado y un badge de puntos en la misma fila — en Naturaleza claro (y parcialmente Océano
  claro, `Info` vs `Teal`) ambos badges son literalmente del mismo color, perdiendo la
  distinción visual "completada" vs "puntos". Requiere decisión de diseño: ¿mantener
  semántica universal (verde=éxito) aunque colisione con 2 de los 6 temas, o generar
  variantes de `SemanticColors` por tema?
- **MENOR — APLICADO** — KDoc/comentarios "3 themes/temas" desactualizados (sistema real
  son 6 temas) en `PointsBadge.kt:52`, `EmptyStateIllustrations.kt:26,97`,
  `AppLogo.kt:35,38`. Corregido en esta ronda (ver sección "Fixes aplicados").
- **MENOR** — `EmptyTasksIllustration` (`EmptyStateIllustrations.kt:23-88`) pierde variedad
  cromática en Naturaleza por la misma colisión `success == primary` (efecto colateral, se
  resuelve solo si se corrige el hallazgo IMPORTANTE de arriba).

**Nota de alcance:** los 6 `ColorScheme` de `Theme.kt` están completos (20 parámetros cada
uno, sin roles M3 huérfanos). Shimmer y empty states bien centralizados, sin hardcodeos de
padding.

---

## 2. Funcionalidad end-to-end

**Estado hallazgos v21/v22 (heredados):**
- [CERRADO] LiveRegion permanente en auto-relleno Google — fix de v22 sigue vigente y
  correcto.
- [CERRADO] Auto-relleno sin condición de carrera.
- [CERRADO] Botón emoji / fallback `emoji == ""`.
- [CERRADO] Ciclo completo de `idempotencyKey` (TaskScreenModel + MemberScreenModel,
  incluido `redeemReward`/`donatePoints`).
- **[SIGUE ABIERTO — CRÍTICO, EXCEPCIÓN]** `hasGoogleLinked` se degrada tras 3 fallos
  consecutivos sin distinguir "sin red" de "consentimiento revocado" — ver sección
  dedicada en Red/offline/sync (experto 12). Sin cambios respecto a v22.

**Hallazgos NUEVOS:**
- **MENOR — APLICADO** — `CreateProfileScreen.kt:75`, `JoinHouseholdScreen.kt:80`: condición
  de auto-relleno era `signedIn?.displayName != null` (no filtra string vacío), inconsistente
  con el filtro `isNullOrBlank()` que ya aplica `SettingsStore.setGoogleDisplayName`.
  Corregido a `!signedIn?.displayName.isNullOrBlank()` en ambos archivos.
- **MENOR, PROPUESTA (cosmético)** — Dos archivos `TaskListRules.kt` sin relación de
  contenido en paquetes `network/` (reglas de permisos) y `ui/components/` (agrupación de
  lista) — mismo nombre, confunde búsquedas por archivo. Sugerido renombrar
  `network/TaskListRules.kt` → `TaskPermissionRules.kt` (no aplicado: toca imports en
  varios archivos, requiere revisión de que no rompa nada).
- **MENOR** — Highlighting "falso seleccionado" del grid de emoji cuando el emoji por
  defecto del tipo de espacio coincide por casualidad con uno del grid manual — cosmético,
  sin fix necesario.

**Conclusión:** flujos end-to-end (crear hogar → invitar → unirse → crear tarea →
completar → puntos → canje) verificados sin regresiones nuevas.

---

## 3. Accesibilidad (WCAG AA)

**Estado hallazgos v21/v22 (heredados):**
- [CERRADO] LiveRegion permanente — fix correcto y vigente.
- [SIGUE ABIERTO, sin agravar] Botón de grid de emoji sin semántica expandido/colapsado
  (`CreateHouseholdScreen.kt:191-198`, `EditProfileScreen.kt:191-201`).
- [CERRADO] `RadioOptionRow` + `selectableGroup()` correcto en los 3 selectores de
  `SettingsSheet.kt`.

**Hallazgos NUEVOS:**
- **Informativo** — Conteo real de `.clickable()`: 19 en todo `commonMain`, **100% con
  `role` explícito asignado** (Button/Checkbox/RadioButton), 0 sin rol. Difiere del
  conteo de 11 citado en v21 (posible diferencia de metodología); estado real hoy es limpio.
- **Sin hallazgo** — 14 `contentDescription = null` en `Icon()`, todos legítimos (icono
  decorativo con texto hermano).
- **Sin hallazgo** — Touch targets: todos los `IconButton` en 48dp M3 por defecto, grid de
  emoji en `Surface(48.dp)` exacto.
- **Sin hallazgo (positivo)** — `shouldReduceMotion()` respeta preferencia real del sistema
  en Android (releído en cada `ON_RESUME`) e iOS, consumido en ~20 puntos de la UI.

**Tabla de contraste WCAG AA** (calculada independientemente, 59 pares texto/fondo en los 6
temas × claro/oscuro): **59/59 pasan AA-normal**, mínimo global 4.51:1 (Naturaleza oscuro,
onSecondary/secondary). Sin discrepancias con los ajustes ya documentados en `Theme.kt`.

---

## 4. UI / componentes

**Estado hallazgos v21/v22 (heredados):**
- [CERRADO] `RadioOptionRow` ya no duplicado — única implementación privada en
  `SettingsSheet.kt:816` reutilizada por los 3 selectores.
- [SIGUE ABIERTO] Duplicación `household.emoji ?: spaceType.emoji` en
  `HouseholdTaskSection.kt:91` / `ProfileScreen.kt:204`.
- [SIGUE ABIERTO] `try/catch valueOf` duplicado en `App.kt` — **APLICADO** parcialmente
  esta ronda (ver Programador senior; el resto de bloques `try/catch` idénticos migrados a
  `bestEffort()`, el `valueOf` en sí no se tocó por ser un caso distinto — sigue abierto
  como propuesta menor).
- [SIGUE ABIERTO] `SemanticColors.kt:48` (`info`) coincide con `OceanBlue800`.
- [SIGUE ABIERTO] Clave i18n `edit_profile_emoji_content_desc` reusada.
- [SIGUE ABIERTO, sin agravar] Botón de emoji sin semántica expandido/colapsado.

**Hallazgos NUEVOS:**
- **IMPORTANTE, PROPUESTA** — El selector de emoji completo (botón + grid `AnimatedVisibility`
  + lista literal de 24 emojis) está duplicado casi verbatim entre
  `CreateHouseholdScreen.kt:189-243` y `EditProfileScreen.kt:189-248`, incluyendo la
  constante `emojiOptions` repetida en ambos archivos — ya hay micro-divergencia
  (`BorderStroke` importado normal en uno, fully-qualified inline en el otro), señal de
  copy-paste sin limpiar. PROPUESTA: extraer `EmojiPickerGrid`/`EmojiPickerButton` a
  `ui/components/` con `emojiOptions` como constante compartida. No aplicado (refactor de
  UI compartido, requiere decisión sobre la API del componente y testing visual).
- **MENOR — APLICADO** — KDoc de `getThemeLabel`/`themeColorFor` en `SettingsSheet.kt:785,796`
  seguían diciendo "dropdown de tema" pese a que ya no hay ningún dropdown desde v22.
  Corregido a "RadioOptionRow del selector de tema".
- Confirmado sin hallazgo: `PointsBadge.kt`/`TaskHubTopBar.kt`/`AppSettings.kt` usan tokens
  consistentemente, sin literales de color/dp fuera de spacing estándar.

---

## 5. UX

**Estado hallazgos v21/v22 (heredados):**
- [SIGUE ABIERTO] Indicio VISUAL de autorelleno (no solo semántico) — el campo no cambia de
  estilo/borde/icono cuando se autorellena. Sigue siendo propuesta de bajo coste.
- [RESUELTO] Botón "Elegir emoji" ya muestra el emoji efectivo por defecto.
- **[SIGUE ABIERTO, con nueva evidencia — IMPORTANTE]** "Hogar fantasma": confirmado
  alcanzable con un tap normal, no solo matando el proceso. `HouseholdScreenModel.kt:110`
  crea el documento del hogar en Firestore ANTES de que exista ningún `member`; el botón
  "atrás" de `CreateProfileScreen.kt:117-119` permite abandonar el wizard entre esos dos
  puntos, dejando el hogar huérfano para siempre (sin reconciliación cliente ni servidor, y
  sin registro local que permita limpiarlo después). **PROPUESTA** (no aplicado, requiere
  decisión de arquitectura): (a) mover la creación de `household`+`member` a una única
  Cloud Function transaccional, o (b) interceptar la salida del wizard desde
  `CreateProfileScreen` y hacer cascade-delete del hogar recién creado si no llegó a
  completarse el alta.
- [MATIZADO] La descripción heredada "deep link sin autorización → snackbar transitorio"
  (repetida idéntica v17→v21) **no coincide con el código actual**: desde 2026-08-24
  (`4af61d3`) ya existe una pantalla de error dedicada (`HouseholdScreen.kt:775-813`) con
  botón "Quitar de mis espacios". El hallazgo real y distinto encontrado esta ronda:

**Hallazgos NUEVOS:**
- **MENOR, seguridad-adyacente, PROPUESTA** — `firestore.rules:489` permite `get` de
  `households/{hid}` a CUALQUIER usuario autenticado (no solo miembros), mientras
  `members/{mid}` sí exige pertenencia (`firestore.rules:501-502`). Con un deep link a un
  hogar ajeno, `HouseholdScreen` entra en `Success` mostrando nombre + `inviteCode` reales
  sin aviso de "sin acceso", mientras la lista de miembros falla aparte con una tarjeta de
  error inline — pantalla híbrida confusa + fuga menor de `inviteCode`. PROPUESTA: en
  `HouseholdScreenModel.loadHousehold`, verificar `isCurrentUserMember` tras el `get` y
  emitir un `Error` dedicado si es `false`. No aplicado (cambio de regla + de
  `HouseholdScreenModel` requiere verificar que no rompe el flujo legítimo de
  invitación/preview antes de unirse).
- **MENOR** — La descripción heredada de "deep link → snackbar" debería re-verificarse
  contra código en cada ronda futura antes de re-listarse (ya no aplica desde 2026-08-24).

Sin hallazgos de `onClick = {}` vacíos ni no-op. Validación de formularios correcta
(botones deshabilitados con `isNotBlank()` + estado `Loading`).

---

## 6. Programador senior

**Estado hallazgos v21/v22 (heredados):**
- [CERRADO] `completeAssignment` reutiliza `idempotencyKey` correctamente.
- [CERRADO] `TaskListRules.kt` extraído limpio, sin residuos.
- [CERRADO] `CalendarSyncManager` migrado a `bestEffort` estable.
- [SIGUE ABIERTO] `isOverdue` sigue en `TaskListScreen.kt:406-431` en vez de extraído a
  `TaskListRules.kt`.
- [SIGUE ABIERTO] Asimetría `donatePoints`/`redeemReward` en reutilización de
  `idempotencyKey` ante error determinista — inofensivo contra el servidor, inconsistente
  entre métodos gemelos. No aplicado (bajo impacto, tocar lógica de reintento de dinero
  merece más cautela que un fix cosmético).
- **[CERRADO, re-verificado]** `CalendarSyncManager.consecutiveTokenFailures` sin `Mutex`
  pero confirmado seguro: los `async` paralelos reciben el token ya resuelto como
  parámetro, nunca vuelven a llamar `ensureCalendarAccessToken()`; además la clase corre
  sobre `Dispatchers.Main.immediate` (single-threaded en todas las plataformas). Sin acción.
- [SIGUE ABIERTO, con dato nuevo] `MainActivity.kt:178-183` fuerza iconos claros para
  Medianoche solo en `onCreate`; **dato nuevo**: `AndroidManifest.xml:21` declara
  `configChanges` incluyendo `uiMode`, así que ni un cambio de modo oscuro/claro del propio
  SISTEMA recrea la Activity — el bug cubre 2 rutas de repro, no solo el cambio de tema
  in-app.

**Métricas de crecimiento:**
- `TaskScreenModel.completeTask`: **180 líneas** (v18: ~150, v21: 161-163) — sigue
  creciendo, refactor pendiente desde v18 se amplía.
- `TaskScreenModel.kt` completo: 1434 → **1446 líneas**.
- `pendingIdempotencyKeys` (`TaskScreenModel.kt:186`): namespacing implícito por tipo de ID
  de Firestore, documentado y razonado en KDoc, verificado sin colisión real (guard
  `_actionState == Loading` impide escritura concurrente).

**Hallazgos NUEVOS:**
- **IMPORTANTE — APLICADO parcialmente** — `App.kt` tenía 5 bloques idénticos
  `try { } catch (CancellationException) { throw e } catch (_: Exception) { }` sin migrar
  al helper `bestEffort()` ya existente en `platform/AppLog.kt` (que el propio KDoc dice que
  sustituye exactamente este patrón). Migrados en esta ronda: guardado de token FCM
  (línea ~179), `ensurePersonalMember` (~231), y los 2 `markNotificationRead` (~268, ~409).
  El bloque de resolución de `personalId` (líneas 206-226) NO se tocó: su rama `catch` no
  es un simple "ignorar", computa un fallback con efectos secundarios
  (`householdStore.savePersonalHousehold`), incompatible con el parámetro `default` de
  `bestEffort` (se evaluaría eager, ejecutando el efecto secundario siempre) — se deja como
  está para no cambiar de comportamiento.
- **MENOR** — Namespacing de `pendingIdempotencyKeys` con convención distinta entre
  `TaskScreenModel` (claves crudas) y `MemberScreenModel` (prefijo `"redeem:"`/`"donate:"`)
  — ambos seguros en la práctica (mapas de instancia separados), pero sin convención única
  documentada. Sugerido documentar en CLAUDE.md el estándar del proyecto. No aplicado (solo
  documentación, de baja prioridad).
- **MENOR** — 8 usos de `!!` en `commonMain`, todos precedidos de comprobación `!= null`
  sobre la misma variable en el mismo bloque — sin riesgo real de NPE, solo estilo. No
  aplicado (cambiarlos no reduce riesgo real, solo estética de código).

Sin hallazgos de inmutabilidad (100% `val` en `data class` de DTOs y `ui/models/`), ni de
`launch` sin manejo de excepciones, ni de código muerto/comentado.

---

## 7. Jefe de arquitectura

**Estado hallazgos v21/v22 (heredados):**
- [SIGUE ABIERTO, sin crecimiento] `FirestoreRepository.kt`: **1778 líneas**, sin cambios
  desde antes de v21 (`git diff` vacío). Fachada parcial: ~20 métodos delegan a
  sub-repositorios, pero `leaveHousehold` (~174 líneas), `deleteMember` (~127),
  `donatePoints` (~205), `deleteAssignments` (~155) siguen con lógica pesada propia.
- [SIGUE ABIERTO, agravado como predecía v21] `TaskScreenModel.kt`: 1434→1446 líneas.
- [SIGUE ABIERTO] `TaskRepository.getTaskHistory` sin `limit`/paginación.
- [SIGUE ABIERTO] `GoogleAuthManager.kt`: 657 líneas, sin cambios.
- [CERRADO, confirmado] Cero fugas de Ktor/HTTP hacia `ui/screens`/`ui/models` — límite de
  capa UI↔red respetado.

**Hallazgos NUEVOS:**
- **IMPORTANTE, PROPUESTA/documental — APLICADO** — `CLAUDE.md:13` seguía mencionando auth
  anónima, eliminada hace 18 días (commit `9e36410`, 2026-09-12; el propio informe v21 ya
  dice "Task Hub es Google-only"). Corregido en esta ronda.
- **MENOR, PROPUESTA (naming)** — Colisión de nombre `TaskListRules.kt` en dos paquetes sin
  relación de contenido — ver también experto Funcionalidad. No aplicado (rename toca
  imports en múltiples archivos, requiere pase dedicado sin mezclar con otros fixes).
  Sugerido: `network/TaskListRules.kt` → `TaskAssignmentAuthRules.kt`.
- **MENOR, PROPUESTA** — Desglose más granular de responsabilidades de `GoogleAuthManager`
  (657 líneas): 1) ciclo de vida de sign-in, 2) borrado de cuenta en cascada
  (`deleteAccount`/`reauthenticateForDeletion`, tan grande como las otras 3 juntas), 3)
  token/vínculo de Calendar, 4) sincronización de hogares con la nube. Solo observación de
  nomenclatura para futuras auditorías, sin bug.

**Veredictos por subsistema:** DI (Koin) correcto (17 `single`/8 `factory`, sin ciclos);
Red bien encapsulada de cara a UI pero god-object híbrido intacto; Estado (ScreenModel +
Voyager) consistente en toda la app (56 sitios, cero `ViewModel`); Documentación de capas
en CLAUDE.md desactualizada en el punto de auth anónima (corregido esta ronda).

---

## 8. QA / bugs

**Estado hallazgos v21/v22 (heredados):**
- **[SIGUE ABIERTO, CRÍTICO, EXCEPCIÓN]** `hasGoogleLinked`/`CalendarSyncManager` — sin
  cambios, el commit `e7de827` no lo tocó. Ver Red/offline/sync.
- [CERRADO, verificado correcto] LiveRegion, `emoji == ""`, `completeAssignment`
  idempotencyKey.
- [SIGUE ABIERTO] Asimetría `donatePoints`/`redeemReward` — ver Programador senior.
- [Confirmado sin acción] `pendingIdempotencyKeys` sin necesidad de thread-safety:
  `MemberScreenModel`/`TaskScreenModel` son `factory` en Koin (instancia nueva por
  pantalla), sin riesgo de fuga.

**Hallazgos NUEVOS:**
- **MENOR/PROPUESTA — APLICADO** — `reassignTaskCompletion` no usa `idempotencyKey` a
  diferencia de sus 4 hermanas de dinero/puntos; investigado y confirmado que es
  auto-idempotente por diseño (lee `completedBy` fresco dentro de la transacción), pero sin
  ningún comentario que lo documente — riesgo de que un refactor futuro rompa esta
  propiedad sin que nadie lo note. Añadido KDoc explicativo en
  `functions/src/reassignTaskCompletion.ts` en esta ronda.
- Sin hallazgos de crash (`!!` sin protección) ni de errores enmascarados nuevos —
  `HttpResponseValidator` sigue interceptando correctamente respuestas ≥400.

**Conclusión:** ninguna de las 7 propuestas del commit `976299b` introdujo una regresión de
datos (puntos/dinero) nueva.

---

## 9. Seguridad / AppSec (OWASP MASVS)

**Estado hallazgos v21 (heredados — v22 no tiene sección de Seguridad propia):**
- [SIGUE ABIERTO] App Check solo instalado en cliente Android, sin enforcement en Cloud
  Functions ni en `firestore.rules`; solo cubre Android (iOS/JVM/wasmJs sin proveedor).
- [SIGUE ABIERTO] `SecureStore.wasmJs.kt`: AES-256-CTR manual en vez de `SubtleCrypto` —
  decisión deliberada y re-evaluada (SubtleCrypto es async, contrato debe ser síncrono),
  riesgo residual aceptado conscientemente.
- [SIGUE ABIERTO, bajo impacto] `households/{hid}` `create`/`update` no valida tipo/longitud
  de `name`/`emoji`/`inviteCode` (solo `ownerId` en `create`).
- [SIGUE ABIERTO] `functions/src/idempotency.ts:63-65`: replay no comprueba
  `data.functionName === functionName` — autolesión posible, sin fuga entre usuarios.
- [SIGUE ABIERTO, documentado por diseño] `members/{mid}.update`: un cliente REST directo
  puede escribir su propio `totalPoints` hasta 100.000 sin completar ninguna tarea —
  limitación arquitectónica conocida desde v9-v18, documentada en la cabecera de
  `firestore.rules`.
- [SIGUE ABIERTO] `SecureStore.ios.kt`: sin verificación en entorno sin toolchain Xcode.

**Verificado CERRADO / sin hallazgo:** `TaskCsvExporter.escapeCsvField` mitiga CSV
injection correctamente; sin secretos hardcodeados reales (la Web API Key de Firebase en
`google-services.json` es pública por diseño de Google, la seguridad recae en
rules/AppCheck).

**Hallazgos NUEVOS:**
- **IMPORTANTE — APLICADO** — `firestore.rules:683-686` (`rewards/{rid}`): `allow write: if
  isTrusted(hid);` sin validar signo de `cost`. `redeemReward.ts` comprueba
  `totalPoints < reward.cost`, trivialmente cierto si `cost` es negativo — un
  owner/admin con REST directo podía crear una recompensa `cost = -100000` y hacer que
  cualquier miembro la "canjeara" para inflar su saldo, saltándose los topes por-transacción
  que sí protegen `donatePoints`/`appreciateMember`. **Corregido en esta ronda**: regla
  dividida en `allow delete: if isTrusted(hid);` + `allow create, update: if isTrusted(hid)
  && cost > 0 && cost <= 100000` (mismo rango que `tasks/{tid}.points`, con `cost` además
  estrictamente positivo para reflejar la validación ya existente en
  `CreateRewardScreen.kt`). Verificado que el emulador de Firestore arranca limpio con la
  regla nueva (sin test dedicado de rules en el repo — ver Cobertura de pruebas).
- **MENOR — APLICADO** — `SecureStore.android.kt:30`: el fallback por
  `AndroidContextHolder.context == null` caía a almacenamiento sin cifrar de forma
  completamente silenciosa, a diferencia del fallback hermano por fallo de Keystore que sí
  loguea con `AppLog.w`. Añadido el mismo logging en esta ronda.

**Alineación cliente↔reglas:** todas las rutas de colección usadas por el cliente tienen
`match` equivalente en `firestore.rules`, sin endpoints huérfanos; el único hueco real de
validación de campos era el de `rewards/{rid}` (corregido).

---

## 10. Privacidad / RGPD / menores

*(v21/v22 no tienen sección propia; línea base usada: `docs/auditoria-completa-2026-09-06.md §10`.)*

**Estado hallazgos previos (heredados):**
- [CERRADO] CMP/UMP para EEE/UK implementado (`platform/ConsentManager.kt`, flujo TCF v2
  real, aprobado 2026-09-26).
- [CERRADO] `docs/privacy.html` ya documenta scope OAuth Calendar y borrado autoservicio.
- [SIGUE ABIERTO] Anonimización incompleta: `households/{id}/notifications` sin cubrir —
  el nombre del autor de un mensaje de chat queda horneado permanentemente en el texto de
  la notificación histórica, sin repasarse tras anonimizar el chat.

**Hallazgos NUEVOS:**
- **IMPORTANTE, PROPUESTA (requiere decisión legal/producto)** — `TaskHubApplication.kt:56-61`
  fija `TAG_FOR_CHILD_DIRECTED_TREATMENT_TRUE` de forma incondicional para el 100% del
  tráfico de anuncios, mientras `ConsentManager.kt` ejecuta en paralelo el flujo TCF
  v2/UMP de consentimiento GDPR. Google documenta que ambos mecanismos son conceptualmente
  incompatibles (TFCD ya fuerza anuncios no personalizados; un CMP no debería usarse si toda
  la app se declara dirigida a menores). Contradice además la postura ya documentada de
  mantener el listado de Play como "Todas las edades, sin family flag". No aplicado
  (decisión de producto: opción A, TFCD global + retirar UMP; opción B, TFCD dinámico solo
  para perfil `child`).
- **MENOR, PROPUESTA** — Email de Google (`KEY_GOOGLE_EMAIL`) en `SettingsStore` sin cifrar
  (SharedPreferences en claro), a diferencia de los tokens que sí van a `SecureStore`. v21
  ya lo revisó desde óptica de seguridad pura y lo consideró aceptable; desde RGPD el email
  es identificador directo, candidato razonable a mover a `SecureStore` o documentar riesgo
  aceptado. No aplicado (cambio de storage requiere migración de datos existentes).
- **MENOR, PROPUESTA** — Sin fricción/registro explícito al elegir `role="child"` en el
  flujo de creación de perfil — no es infracción, pero un checkbox/texto breve dejaría
  constancia auditable de que el adulto fue informado. No aplicado.
- Confirmado correcto: eventos de Analytics sin PII (verificados los 5 call-sites reales,
  ninguno pasa `params`), opt-out de usuario implementado; borrado de cuenta en cascada real
  (hogares, mensajes, comentarios, Calendar) — único gap real es el de `notifications` ya
  señalado arriba.

---

## 11. Rendimiento

**Estado hallazgos v21/v22 (heredados):**
- [SIGUE ABIERTO] `CalendarScreen.kt` (1458 líneas, sin cambios): `Column().verticalScroll`
  en vez de `LazyColumn`, con `tasks.forEach` sin virtualización — impacto limitado porque
  las listas son pequeñas (decenas, no miles).
- [CERRADO, confirmado] `groupTasksByStatus`/`groupTasksByDate` siguen memoizados
  correctamente vía `remember(...)`.
- [CERRADO, confirmado] R8/minify + shrinkResources activos en release.
- [CERRADO, confirmado] Arranque no bloquea el hilo principal, con timeout + reintento.

**Hallazgos NUEVOS:**
- **MENOR — APLICADO** — `NotificationResponse` (`DTOs.kt:391`) no estaba anotada
  `@Immutable` a diferencia de sus 5 DTOs hermanas, por tener un campo `Map<String,String>?`
  que Compose infiere como inestable — impide el "skip" de recomposición en
  `NotificationListScreen`. Añadida la anotación en esta ronda.
- **IMPORTANTE, PROPUESTA** — El build wasmJs necesita `-Xmx6G` (documentado en
  `docs/login-web-2026-09-17.md`), pero ese flag vive SOLO en un doc, no en
  `gradle.properties` (que fija `-Xmx2048M`) ni en ningún workflow de CI — riesgo de OOM si
  alguien ejecuta el build "normal" sin recordar el flag manual. No aplicado (decisión de
  infraestructura: mover el flag a un perfil de Gradle específico de wasmJs).
- **Informativo** — Build wasm existente pesa 17 MB total (8.1M + 6.8M de `.wasm` + 567K JS),
  dato de referencia para futuras rondas.
- **MENOR, matiz sin regresión** — Patrón N+1 ya conocido desde v2-v18: entrar a Calendario
  tras la lista de tareas repite las mismas 2+N lecturas Firestore desde cero (sin
  caché-primero) — mitigado por `Semaphore(4)`, sin regresión nueva.

---

## 12. Fiabilidad de red / offline / sync

**Estado hallazgos v21/v22 (heredados):**
- [AGRAVADO respecto a la intención de v21] El contador de fallos de `hasGoogleLinked`
  propuesto en v21 se aplicó en `976299b` pero introdujo el CRÍTICO documentado en v22.
- [SIGUE ABIERTO] Sin test propio del contador de fallos.

### CRÍTICO v22 — CalendarSyncManager desvinculación por fallos de red (SIGUE ABIERTO, EXCEPCIÓN — NO aplicado sin decisión del dueño)

Confirmado línea a línea, sin cambios respecto a v22:
`CalendarSyncManager.kt:120-145` (`consecutiveTokenFailures`, `MAX_CONSECUTIVE_TOKEN_FAILURES = 3`)
incrementa el contador ante CUALQUIER `null` de `ensureCalendarAccessToken()`, y
`GoogleCalendarAuthHelper.kt:70-90` (Android) devuelve `null` indistintamente para
`IOException` (sin red/timeout) y `GoogleAuthException` (consentimiento revocado). A los 3
fallos, `unlinkGoogleCalendar()` borra `calendarId` cacheados y desactiva
`isCalendarSyncEnabled` — desvinculación completa e irreversible sin acción del usuario.
Agravante: de los 5 call-sites, solo `reconcile()` tiene throttle (15 min); los 4
interactivos (`onTaskAssigned`, `onDueDateChanged`, `syncNow`, `deleteEventForAssignment`)
se disparan de inmediato en cada acción del usuario, sin ninguna notificación de fallo.

**Opciones de remediación (sin aplicar):**
- **Opción A (mínimo coste):** contar el fallo SOLO dentro de `reconcile()` (que ya tiene
  throttle de 15 min, señal mucho más fiable de revocación real); los 4 call-sites
  interactivos llaman al token directamente sin pasar por el contador. Coste: una línea de
  refactor por call-site. Riesgo: sigue sin distinguir la causa real, solo reduce la ventana.
- **Opción B (más robusta, más coste):** tipar el resultado de `getAccessToken()`/
  `ensureCalendarAccessToken()` como `sealed class TokenResult { Ok, NetworkFailure,
  AuthDenied }` para que solo `AuthDenied` incremente el contador. Coste: tocar la firma en
  3 plataformas y todos los call-sites.
- **Opción C (UX-first, coste medio):** no desvincular nunca automáticamente; mostrar un
  banner no bloqueante en Ajustes tras alcanzar el umbral, dejando que el usuario decida.
  Combina bien con A (subir el umbral + añadir notificación en vez de desvinculación
  silenciosa).

Recomendación de este experto: **A + C combinadas** (una tarde de trabajo, sin tocar código
de plataforma nativa); B es la solución correcta a medio plazo pero no urgente si A+C ya
eliminan el falso positivo por corte transitorio.

**Hallazgos NUEVOS (positivos, sin acción):**
- `isOnline()` (`FirestoreRepository.kt:375-391`) es un probe fiable — petición HTTP real,
  distingue respuesta del servidor de fallo de transporte, sin riesgo de falso positivo por
  red cautiva.
- `MemberRepository.addMemberPoints` usa optimistic locking real (`updateTime` + reintento
  acotado a 3), releyendo el valor fresco en cada intento — cubre correctamente dos
  dispositivos completando la misma tarea a la vez.
- `reconcileMissingTaskPoints` (Cloud Function) usa transacción real + `clampTotalPoints` +
  logging de cada fallo individual, alcance correctamente limitado a legado.
- `retryTransientReadFailure` (`FirestoreClient.kt:441-477`): backoff exponencial con
  jitter, correctamente limitado a lecturas (nunca escrituras).

---

## 13. Cobertura de pruebas

**Cifras actuales (verificadas ejecutando):** 303 tests JVM (`BUILD SUCCESSFUL`), 54 unit +
45 integration en `functions/` (`npm test` + `npm run test:integration`) — las tres cifras
citadas por el dueño quedan confirmadas al 100% por ejecución real en esta ronda.

**Verificación puntual de los 4 archivos críticos pedidos:**
- `CalendarSyncManagerTest.kt` → **NO EXISTE**.
- `MemberScreenModelTest.kt` → existe, 3 tests, **ninguno cubre `idempotencyKey`**.
- `HouseholdScreenModelTest.kt` → **NO EXISTE**.
- `GoogleAuthManagerTest.kt` → **NO EXISTE**.

**TOP-10 actualizado (riesgo dinero/puntos y sincronización primero):**
1. [SIGUE ABIERTO, v22, máxima prioridad] `CalendarSyncManager.consecutiveTokenFailures`
   sin ningún test — ligado directamente al CRÍTICO de esta ronda.
2. [SIGUE ABIERTO, v21] `idempotencyKey` de `MemberScreenModel.donatePoints`/`redeemReward`
   sin test.
3. [SIGUE ABIERTO, v21] `HouseholdScreenModel` (368 líneas) sin ningún test.
4. [SIGUE ABIERTO, v21, agravado] `GoogleAuthManager` (657 líneas) + `CalendarSyncManager`
   (377 líneas) sin test propio de sus call-sites.
5. **[NUEVO]** `NotificationScreenModel.kt`, `ProfileScreenModel.kt`,
   `TaskCommentsScreenModel.kt` (150-190 líneas c/u) — los únicos 3 `ScreenModel` restantes
   sin ningún archivo de test.
6. [HEREDADO v22] `emoji == ""` cerrado en código pero sin test que fije el contrato.
7. [HEREDADO v18/v20] `MemberRepository.updateMemberStreak` sin test de carrera.
8. [HEREDADO v17/v20] `TaskRepository.kt`/`FirestoreRepository.kt` sin test directo.
9. [HEREDADO v18] Cierre de asignaciones "hermanas" sin acotar por ciclo.
10. [HEREDADO v17] `RewardsRepository.kt` sin test.

**Nota:** sin ningún test dedicado de `firestore.rules` (ni `@firebase/rules-unit-testing`
ni equivalente) — el fix de `rewards/{rid}.cost` aplicado esta ronda (Seguridad #1) solo se
verificó por lectura + arranque limpio del emulador, no por un test que fije el contrato.
Candidato natural a añadir al TOP-10 de la próxima ronda. Cero Compose UI testing (0
`createComposeRule`), consistente con v21/v22.

---

## 14. Build / CI / Publicación

*(v21/v22 sin sección propia; línea base: `docs/audit-2026-08-30.md`, `docs/PRIMEROS-PASOS.md`.)*

**Estado hallazgos previos (heredados):**
- [SIGUE ABIERTO] `.github/workflows/ci.yml:47-49` ejecuta `detekt` con
  `continue-on-error: true`, pero detekt NO está aplicado en ningún `build.gradle.kts` —
  confirmado con `Cannot locate tasks that match ':composeApp:detekt'`. CI nunca ha
  ejecutado detekt realmente pese a "verse verde".
- [CONFIRMADO] Sin entorno macOS/Xcode disponible para validar `project.pbxproj` en
  ejecución real; sanity check manual de balanceo de llaves/paréntesis OK.

**Hallazgos NUEVOS:**
- **IMPORTANTE, PROPUESTA** — `.github/workflows/release.yml:81-89` ejecuta
  `bundleRelease` sin ningún paso previo de `jvmTest`/`compileDebugKotlinAndroid`/`detekt`,
  ni dependencia de `ci.yml` — es posible publicar en Play Store sin que los 303 tests se
  hayan ejecutado en ese pipeline si el tag se crea sobre un commit no verificado. No
  aplicado (cambio de CI, requiere decisión sobre el enfoque: pasos inline vs
  `workflow_run`).
- **MENOR/IMPORTANTE, PROPUESTA** — Sin ningún job de CI para `wasmJs` ni iOS — un cambio
  que rompa `wasmJsMain` (ya ocurrió una vez, fix `SecureStore.wasmJs.kt`) solo se detecta
  manualmente.
- **MENOR, PROPUESTA** — Sin herramienta de cobertura de código (Kover/JaCoCo) — el TOP-10
  de cobertura se construye enteramente por lectura manual.
- **MENOR — APLICADO** — `CLAUDE.md:7` decía "CMP 1.7.3" cuando `libs.versions.toml` fija
  `1.8.0` (el 1.7.3 real es el pin de `material-icons-core`, no de CMP); tampoco mencionaba
  el target `wasmJs`/web pese a tener login Google real. Corregido en esta ronda.
- **No verificable** — "warning de webpack 544 KiB" y `taskhub-web.service` no localizables
  en el repo ni en este entorno — posiblemente viven fuera del repo/entorno de este panel.

**Verificado correcto, sin hallazgo:** `jvmTest` corre en 12s (303 tests); wrapper Gradle
8.12 compatible con AGP 8.10.1; `release.yml` instala `platforms;android-36` explícitamente;
signing sin secretos hardcodeados, limpieza de `.jks` con `if: always()`.

---

## Fixes aplicados en esta ronda (APLICA YA)

Todos verificados con `BUILD SUCCESSFUL` (`compileDebugKotlinAndroid` + `jvmTest`, 303/303
tests) + `functions`: 54/54 unit + 45/45 integration + arranque limpio del emulador de
Firestore (sanity de `firestore.rules`).

1. **`firestore.rules`** (Seguridad, IMPORTANTE) — `rewards/{rid}`: `create`/`update` ahora
   exigen `cost > 0 && cost <= 100000`; `delete` separado de `create`/`update` para no
   romper con `request.resource` nulo.
2. **`SecureStore.android.kt`** (Seguridad, MENOR) — logging del fallback silencioso por
   `AndroidContextHolder.context == null`.
3. **`CreateProfileScreen.kt` + `JoinHouseholdScreen.kt`** (Funcionalidad, MENOR) —
   condición de auto-relleno alineada con `isNullOrBlank()`.
4. **`App.kt`** (Programador senior, IMPORTANTE) — 4 de los 5 bloques `try/catch` idénticos
   migrados a `bestEffort()` (el 5º, resolución de `personalId`, se deja intacto por tener
   lógica de fallback con efectos secundarios, incompatible con el patrón simple).
5. **`functions/src/reassignTaskCompletion.ts`** (QA, MENOR) — KDoc explicando por qué esta
   función es auto-idempotente sin `idempotencyKey`.
6. **`DTOs.kt`** (Rendimiento, MENOR) — `@Immutable` en `NotificationResponse`.
7. **`CLAUDE.md`** (Arquitectura + Build/CI) — elimina mención de auth anónima (removida
   hace 18 días), corrige versión de CMP (1.8.0, no 1.7.3), añade mención del target web.
8. **`SettingsSheet.kt`** (UI/componentes, MENOR) — KDoc de `getThemeLabel`/`themeColorFor`
   actualizado de "dropdown" a "RadioOptionRow".
9. **`PointsBadge.kt`, `EmptyStateIllustrations.kt`, `AppLogo.kt`** (Estética, MENOR) —
   comentarios "3 themes/temas" actualizados a "6 themes/temas".

## PROPUESTAS pendientes de decisión (no aplicadas)

Ordenadas por severidad:

- **CRÍTICO (excepción del encargo)** — `CalendarSyncManager` desvincula Calendar por
  fallos de red, no solo de auth. Ver sección 12, opciones A/B/C.
- **IMPORTANTE** — Hogar fantasma alcanzable por navegación normal (sección 5/UX).
- **IMPORTANTE** — Colisión de color `BadgeTone.Success`/`BadgeTone.Teal` en Naturaleza
  (sección 1/Estética).
- **IMPORTANTE** — TFCD global + UMP simultáneos, conflicto de cumplimiento AdMob
  (sección 10/Privacidad).
- **IMPORTANTE** — `release.yml` publica sin ejecutar tests (sección 14/Build-CI).
- **IMPORTANTE** — Duplicación del selector de emoji completo entre 2 pantallas
  (sección 4/UI).
- **IMPORTANTE** — Build wasmJs con `-Xmx6G` solo documentado, no configurado
  (sección 11/Rendimiento).
- **MENOR** — Firestore rules asimetría de lectura de `households/{hid}` expone
  nombre+inviteCode a no-miembros vía deep link (sección 5/UX).
- **MENOR** — Resto de hallazgos menores listados por experto (naming de `TaskListRules.kt`,
  email sin cifrar en `SettingsStore`, sin CI de wasmJs/iOS, sin code coverage tooling,
  asimetría `donatePoints`/`redeemReward`, etc.)

Sin bump de versión, sin push — pendiente de revisión y decisión del dueño sobre las
PROPUESTAS antes del siguiente ciclo.
