# Panel de expertos v19 — Auditoría de cambios recientes + estado abierto (2026-09-27)

HEAD de partida: `f683bd5` (v0.7.51). Cambios desde v18 (`e1cd2bb`): `SpaceType`/tipos
de espacio (`2e09d71`) y logging Napier en catch blocks silenciosos de commonMain
(`55c69e7`). 9 especialistas, coordinados por Claude, en 2 oleadas (5+4). Foco
centrado en el código NUEVO de esta ventana — no se repite ningún hallazgo ya
documentado en `docs/review-panel-expertos-2026-09-26-v18.md` que siga sin
relación directa con estos dos commits.

## Resumen ejecutivo

El cambio de `SpaceType` es, tal como lo describe su propio informe
(`docs/espacios-multitipo-2026-09-27.md`), genuinamente de empaquetado/copy: los
9 especialistas (funcionalidad, UI, programador senior, arquitectura, QA,
accesibilidad, UX, rendimiento, seguridad) confirman de forma independiente que
no afecta lógica de negocio, permisos, puntos ni rendimiento. **4 hallazgos
menores APLICADOS en esta ronda** (ninguno crítico): 2 fixes de accesibilidad/UI
en el selector de `CreateHouseholdScreen.kt` (overflow de labels + semántica de
emoji para TalkBack), 1 fix de programador senior (log en el `else` no
reconocido de `spaceTypeFromFirestoreValue` + test unitario de roundtrip nuevo),
y 1 fix de documentación (fila `spaceType` en `docs/MODELO-DATOS.md`). El
logging Napier del commit `55c69e7` fue revisado exhaustivamente (los 15
archivos, ~110 sitios) por 3 especialistas distintos (funcionalidad, programador
senior, QA) sin encontrar ningún catch que trague `CancellationException`, use
`!!`, o introduzca side-effects — confirma que es aditivo y seguro.

**Verificación final:**
```
./gradlew :composeApp:compileDebugKotlinAndroid --console=plain → BUILD SUCCESSFUL
./gradlew :composeApp:jvmTest --rerun-tasks --console=plain     → BUILD SUCCESSFUL
```
Conteo real de `composeApp/build/test-results/jvmTest/*.xml`: **291/291 tests
verdes** (289 de v18 + 2 nuevos de `SpaceTypeTest`), 0 failures, 0 errors.

No se tocó `functions/` en esta ventana (ningún cambio de `SpaceType` ni de
logging afecta Cloud Functions — confirmado por el especialista de seguridad,
`grep -rn "spaceType" functions/src` sin resultados), así que no se re-ejecutó
`npm test`/`npm run test:integration`.

---

## 1. Funcionalidad end-to-end

Sin hallazgos nuevos de bug real. Verificado exhaustivamente:
- Flujo `createHousehold`/`joinHousehold`/`restoreHouseholds` propaga `spaceType`
  correctamente en los 4 call-sites reales de `HouseholdStore.saveHousehold`.
- Hogares legacy (sin campo) y valores desconocidos vía REST directo caen a
  `HOME` de forma segura.
- `getOrCreatePersonalHousehold` no escribe `spaceType`; el emoji se gatea por
  `isPersonal`, no por `spaceType == HOME` — sin acoplamiento frágil.
- Logging Napier añadido no altera ningún flujo de control ni tipo de
  excepción capturado.

**PROPUESTA (bajo impacto, no aplicada):** un dispositivo con caché
pre-migración (`SavedHousehold` en JSON sin `spaceType`) que recurra al
fallback offline de `HouseholdRepository.getHousehold` mostraría el emoji
incorrecto (default `HOME`) hasta la siguiente lectura de red exitosa — mismo
patrón de staleness que ya afecta a `name`/`inviteCode` en ese fallback, no
específico de `spaceType`. Autocorrectivo, no amerita fix puntual.

## 2. UI y componentes Material3

**APLICADO en esta ronda:** `CreateHouseholdScreen.kt` — el `FilterChip` del
selector de tipo de espacio no limitaba el texto del label; con 4 etiquetas de
longitud dispar en la misma fila ("🏠 Casa" vs "🏢 Piso compartido") y fuente de
accesibilidad grande, los dos chips de una fila podían quedar con altura
descuadrada. Fix: `maxLines = 1, overflow = TextOverflow.Ellipsis` en el `Text`
del label (combinado con el fix de accesibilidad #6, mismo bloque de código).

**PROPUESTA (nuevo, bajo impacto):** tipografía/espaciado del título del
selector (`titleSmall` + 12dp) no coincide con ninguna de las dos convenciones
ya existentes en la app (`CreateProfileScreen`: `bodyMedium`+8dp;
`CreateTaskScreen`/`EditTaskScreen`: `titleMedium` bold + color primary) —
requiere decidir cuál es la convención "canónica" antes de unificar, fuera de
alcance de un fix acotado.

Verificado sin hallazgos: i18n ES/EN completa y consistente (9 claves nuevas),
emojis del selector coinciden exactamente con `SpaceType.emoji`, placeholder
reactivo sin lag, sin overflow/truncamiento nuevo en las cards de
`HouseholdTaskSection.kt`/`ProfileScreen.kt` (mismo comportamiento de wrap que
ya existía antes de añadir el emoji).

## 3. Programador senior

**APLICADO en esta ronda:**
- `network/models/DTOs.kt` — `spaceTypeFromFirestoreValue` distinguía "campo
  ausente" (legítimo, hogares legacy) de "valor no reconocido" (anómalo: typo
  manual, migración futura de nombres, REST directo con basura) con el mismo
  `else -> HOME` silencioso. Ahora `null`/`"home"` van explícitos a `HOME` sin
  log, y cualquier otro valor no reconocido pasa por `AppLog.w` antes de caer
  a `HOME` — mismo comportamiento observable, con rastro para depurar.
- Nuevo test `composeApp/src/commonTest/.../network/models/SpaceTypeTest.kt`:
  roundtrip `firestoreValue` ↔ `spaceTypeFromFirestoreValue` para los 4
  valores del enum + casos `null`/vacío/desconocido → `HOME`. Cierra el hueco
  de que `firestoreValue` (when exhaustivo, fuerza el compilador) y
  `spaceTypeFromFirestoreValue` (con `else`, no fuerza nada) podían
  desincronizarse sin que ningún test lo detectara al añadir un 5º valor al
  enum en el futuro.

**PROPUESTA (baja prioridad, no aplicada):** extraer un
`LabeledFilterChipGroup` genérico si aparece un 4º selector de este tipo en la
app (hoy solo hay 3 usos con formas distintas — no justificado todavía).

Verificado sin hallazgos: `firestoreValue`/`@SerialName` en sincronía exacta
hoy (4/4); patrón catch-cancellation intacto en los ~110 sitios nuevos de
logging.

## 4. Jefe de arquitectura

**APLICADO en esta ronda:** `docs/MODELO-DATOS.md` — faltaba la fila de
`spaceType` en la tabla de campos de `households/{hid}`, pese a que
`docs/INDICE.md` exige mantener ese documento como fuente de verdad de campos
de DTO. Añadida tras la fila `timezone`.

**PROPUESTA (confirmación razonada, no repetida como riesgo nuevo):**
`firestore.rules` no tiene whitelist de campos en `create`/`update` de
`households/{hid}` — pero esa superficie ya estaba completamente abierta antes
de este cambio (afecta a `name`/`timezone`/`inviteCode` igual que a
`spaceType`); no se propone tocar las reglas solo por este campo, coherente con
la decisión #5 ya documentada por el autor del cambio.

Verificado sin hallazgos: sin migración de datos necesaria (ningún
query/índice de Firestore depende de `spaceType`); `CLAUDE.md`/`docs/INDICE.md`
no necesitan mención (es un enum de copy/UI, no un concepto de arquitectura);
sin crecimiento material en `GoogleAuthManager`/`HouseholdScreenModel` (+1
línea neta cada uno, mismo patrón de propagación ya usado para otros campos).

## 5. QA y bugs

Sin hallazgos nuevos de bug real. Verificado explícitamente (no solo
documentado):
- `spaceTypeFromFirestoreValue("")` → `HOME`. Confirmado por trazado manual.
- `firestoreValue` vs `@SerialName`: comparación char a char de los 4 valores,
  sin divergencia.
- Build/test re-ejecutados de forma independiente (no solo confiando en
  `docs/espacios-multitipo-2026-09-27.md`): `compileDebugKotlinAndroid` y
  `jvmTest --rerun-tasks` ambos BUILD SUCCESSFUL, 289/289 tests verdes por
  conteo de XML (antes de añadir `SpaceTypeTest` en esta ronda).
- Logging Napier: 0 apariciones de `!!` en el diff completo del commit
  `55c69e7`; ninguna interpolación con side-effects salvo una llamada
  duplicada (no arriesgada) a una función pura ya invocada en la misma línea.

**PROPUESTA (confirmada, no nueva):** `spaceTypeFromFirestoreValue` es
case-sensitive y sin `trim()` — inalcanzable en la práctica porque el único
productor del string (`SpaceType.firestoreValue`) emite siempre minúsculas
fijas sin espacios; no amerita fix.

## 6. Accesibilidad

**APLICADO en esta ronda:** `CreateHouseholdScreen.kt` — los labels del
selector de `FilterChip` llevan el emoji embebido en el string localizado
(`"⚽ Grupo o club"`); TalkBack con verbosidad de emojis activada antepone la
descripción hablada del emoji al texto, sonando desconectado en casos como
"balón de fútbol, Grupo o club". Fix: `Modifier.clearAndSetSemantics` que
expone solo el nombre del tipo (emoji recortado del string) como
`contentDescription`, mismo patrón ya usado en `UserAvatar.kt` y
`NotificationListScreen.kt`.

**PROPUESTA (mismo problema, mayor alcance):** el emoji también aparece
concatenado en las cards de hogar (`HouseholdTaskSection.kt`,
`ProfileScreen.kt`), con el mismo riesgo de lectura TalkBack confusa — pero ahí
el nombre es libre (elegido por el usuario) y el `Text` está dentro de un
`ExpandableSectionHeader` con semántica ya densa (fusión de rol botón + estado
expandido/colapsado + contador). Aplicar el mismo `clearAndSetSemantics`
requeriría partir el `Text` único en dos composables dentro de una `Row` con
`weight(1f)`, con riesgo de descuadre visual en los 2 puntos de uso —
requiere verificación visual antes de aplicar, no se hizo en esta ronda.

Verificado sin hallazgos: orden de foco del selector (izquierda-derecha,
arriba-abajo, coincide con visual); contraste del chip seleccionado en los 6
temas/modo, todos ≥4.5:1 (el más ajustado, Naturaleza oscuro, ≈5.93:1).

## 7. UX

Sin hallazgos con acción segura y acotada. Verificado sin problema: placeholder
reactivo al cambiar tipo (state hoisting correcto, sin lag), sin riesgo de
sobreescribir un nombre ya escrito, orden selector→nombre intencional y
coherente con el placeholder contextual, sin confusión relevante por la
ausencia de opción "Personal" (el espacio Personal nunca pasa por este flujo
de creación).

**PROPUESTA (copy, no aplicada):** el título "¿Qué tipo de espacio quieres
crear?" no aclara que el tipo es puramente organizativo (emoji + placeholder),
sin afectar permisos ni reglas — un usuario nuevo podría asumir lo contrario.
Añadir un subtítulo aclaratorio es una decisión de copy/espacio visual, no un
fix seguro por sí solo.

## 8. Rendimiento

Sin hallazgos. Verificado con evidencia (no especulación): `spaceTypeFromFirestoreValue`
es un `when` O(1) sobre 4 literales, fuera del hot path real de carga de
hogares (`loadAllTasks` no lo invoca; el único call-site, `getHousehold`,
está dominado por el round-trip de red); propiedades del enum (`emoji`,
`defaultNameRes`) son constantes de constructor sin alocación por acceso;
`SPACE_TYPE_OPTIONS` en `CreateHouseholdScreen.kt` es una propiedad top-level
de archivo, no recompuesta en cada recomposición; el `remember(households)`
de v18 en `ProfileScreen.kt` sigue intacto tras añadir el emoji, sin
regresión.

## 9. Seguridad / AppSec

Sin hallazgos nuevos explotables. Confirmado con evidencia: ninguna Cloud
Function lee o valida `spaceType` (`grep -rn "spaceType" functions/src` → 0
resultados); el campo nunca participa en decisiones de lógica de
negocio/permisos en el cliente (único consumo: emoji de cabecera); el
fallback `HOME` no da ninguna ventaja a un atacante porque no hay tratamiento
especial para ningún valor; el invariante `isPersonal=true ⟹ spaceType=HOME`
se sostiene en todo el código actual (único creador de hogares personales,
`getOrCreatePersonalHousehold`, nunca escribe el campo); `spaceType` no
aparece en ningún evento de analytics ni log con el valor crudo. La ausencia
de whitelist en `firestore.rules` para `households/{hid}` (ya reportada por
arquitectura) no es una superficie nueva: ya afectaba a otros campos antes de
este cambio, y aquí no tiene ningún consumidor que la haga explotable.

---

## Archivos modificados en esta ronda

**Composeapp (`composeApp/src/commonMain/`):**
- `network/models/DTOs.kt`: `spaceTypeFromFirestoreValue` distingue
  `null`/`"home"` (sin log) de valores no reconocidos (con `AppLog.w`).
- `ui/screens/CreateHouseholdScreen.kt`: labels del selector con
  `maxLines=1`/`overflow=Ellipsis` + `clearAndSetSemantics` (emoji oculto a
  TalkBack).

**Tests (`composeApp/src/commonTest/`):**
- `network/models/SpaceTypeTest.kt` (nuevo): roundtrip `firestoreValue` ↔
  `spaceTypeFromFirestoreValue` + casos `null`/vacío/desconocido.

**Documentación:**
- `docs/MODELO-DATOS.md`: fila `spaceType` añadida a la tabla de
  `households/{hid}`.

## Verificación

```
./gradlew :composeApp:compileDebugKotlinAndroid --console=plain   → BUILD SUCCESSFUL
./gradlew :composeApp:jvmTest --rerun-tasks --console=plain       → BUILD SUCCESSFUL
```
Conteo real de `composeApp/build/test-results/jvmTest/*.xml`: **291/291 tests
verdes**, 0 failures, 0 errors (incluye el nuevo `SpaceTypeTest`).

No se hizo bump de versión ni push, según el encargo.
