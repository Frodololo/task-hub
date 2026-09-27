# Tipos de espacio (hogar / piso compartido / estudio / grupo) — 2026-09-27

Empaquetado y copy: añade un selector de tipo de espacio al crear un hogar y muestra su emoji
en las listas. Sin cambios de arquitectura ni de lógica de negocio (puntos, asignación,
completado, recurrencia, ranking).

## 1. Archivos modificados

### `network/models/DTOs.kt`
- Nuevo enum `SpaceType(emoji, defaultNameRes)` con 4 valores: `HOME`, `FLAT_SHARE`,
  `STUDY_GROUP`, `CLUB`, serializables con `@SerialName("home"|"flat"|"study"|"club")`.
- Nuevas funciones `SpaceType.firestoreValue` (enum → string persistido en Firestore) y
  `spaceTypeFromFirestoreValue(String?)` (inverso; desconocido/null → `HOME`) — ver decisión #1.
- `HouseholdResponse`: nuevo campo `spaceType: SpaceType = SpaceType.HOME`.

### `storage/HouseholdStore.kt`
- `SavedHousehold`: nuevo campo `spaceType: SpaceType = SpaceType.HOME`.
- `saveHousehold(...)`: nuevo parámetro `spaceType` (default `HOME`), persistido en la entrada.
- `replacePersonalHousehold`/espacio Personal: sin tocar, sigue con el default `HOME` implícito.

### `network/FirestoreParsers.kt`
- `toHouseholdResponse`: parsea `spaceType` desde el documento vía `spaceTypeFromFirestoreValue`.

### `network/HouseholdRepository.kt`
- `createHousehold(name, isPersonal, deviceTimezone, spaceType = SpaceType.HOME)`: escribe el
  campo `spaceType` en Firestore (`FirestoreValue(stringValue = spaceType.firestoreValue)`) y lo
  propaga al `HouseholdResponse` devuelto.
- `getOrCreatePersonalHousehold`: SIN TOCAR — no escribe `spaceType` (el espacio Personal sigue
  siendo `HOME` por el default del parser, tal como pide el encargo).

### `network/FirestoreRepository.kt`
- `createHousehold(name, isPersonal, spaceType = SpaceType.HOME)`: nuevo parámetro, delega en
  `HouseholdRepository`.

### `ui/models/HouseholdScreenModel.kt`
- `createHousehold(name, spaceType = SpaceType.HOME)`: nuevo parámetro, lo pasa al repo y a
  `householdStore.saveHousehold(...)`.
- `joinHousehold`: ahora pasa `spaceType = household.spaceType` a `saveHousehold` (antes se
  guardaba con el default `HOME` siempre, aunque el hogar al que te unes sea de otro tipo — ver
  decisión #2).

### `ui/models/GoogleAuthManager.kt`
- `restoreHouseholds` (reconstruye la caché local tras login/restauración): ahora propaga
  `spaceType = household.spaceType` a `saveHousehold` — mismo motivo que joinHousehold.

### `ui/screens/CreateHouseholdScreen.kt`
- Nuevo selector de tipo de espacio (4 `FilterChip` en 2 filas de 2, con
  `filterChipCheckIcon` como el resto de selectores de la app) antes del campo de nombre, con
  título `space_type_selector_title`.
- Estado `selectedType` (default `SpaceType.HOME`, sin opción "Personal").
- El placeholder del campo de nombre cambia según el tipo seleccionado
  (`space_type_placeholder_*`).
- `model.createHousehold(nombre, selectedType)`.

### `ui/components/HouseholdTaskSection.kt`
- Cabecera del card: `"{emoji} {nombre}"` para hogares compartidos (`!isPersonal`); el espacio
  Personal sigue mostrando solo el nombre (ya lleva su propia etiqueta 👤) — ver decisión #3.
  Usado por `HomeScreen.kt` (ambas secciones, Personal y compartidos, pasan por este componente).

### `ui/screens/ProfileScreen.kt`
- `HouseholdProfileCard`: mismo criterio que arriba — `"{emoji} {nombre}"` solo para hogares
  compartidos.

### `ui/i18n/AppStrings.kt`
- `household_invite_card_description` (ES/EN): `"Hogar/Household %1$s..."` →
  `"Espacio/Space %1$s..."`.
- Nuevas claves ES y EN: `space_type_selector_title`, `space_type_home`, `space_type_flat`,
  `space_type_study`, `space_type_club`, `space_type_placeholder_home`,
  `space_type_placeholder_flat`, `space_type_placeholder_study`, `space_type_placeholder_club`.

## 2. Verificaciones

```
./gradlew :composeApp:compileDebugKotlinAndroid --console=plain
→ BUILD SUCCESSFUL in 22s
```

```
./gradlew :composeApp:jvmTest --rerun-tasks --console=plain
→ BUILD SUCCESSFUL in 24s
```

XML de `composeApp/build/test-results/jvmTest/*.xml` (fuente de verdad):

```
tests: 289  failures: 0  errors: 0
```

## 3. Decisiones de implementación

1. **Serialización Firestore del enum.** El encargo pedía `@SerialName` en `SpaceType` (correcto
   para la serialización JSON de `SavedHousehold` en `HouseholdStore`, que sí usa
   `kotlinx.serialization.json.Json`). Pero los documentos de Firestore se escriben a mano como
   `FirestoreValue(stringValue = ...)` (sin pasar por el serializer de kotlinx), así que
   `@SerialName` por sí solo no resuelve la conversión enum↔string ahí. Se añadió una conversión
   explícita (`firestoreValue` / `spaceTypeFromFirestoreValue`) en vez de depender de reflexión
   sobre el descriptor de serialización — más simple y sin sorpresas en Kotlin/Native (iOS).
2. **`joinHousehold`/`restoreHouseholds` ahora propagan `spaceType`.** No estaba en la lista
   explícita de cambios, pero sin esto un usuario que se une a un piso compartido (o restaura
   sesión en otro dispositivo) vería siempre el emoji 🏠 en su caché local aunque el hogar real
   sea de otro tipo — inconsistente con el resto del cambio. Ajuste mínimo, mismo patrón que
   `createHousehold`.
3. **Emoji solo en espacios NO personales.** El encargo dice "en la sección de espacios
   compartidos" y "NO tocar la pantalla de espacio Personal" — se interpretó como: el espacio
   Personal (siempre `HOME` internamente, sin selector) no lleva el emoji de tipo, ya que ya
   tiene su propia etiqueta visual (👤) y añadir 🏠 encima sería ruido redundante, no informativo.
4. **`HomeScreen.kt` no se tocó directamente.** El renderizado del nombre de cada hogar en Home
   vive en `ui/components/HouseholdTaskSection.kt` (que `HomeScreen.kt` ya usa para ambas
   secciones, Personal y compartidos) — se editó ahí en vez de duplicar lógica en `HomeScreen.kt`.
5. **`firestore.rules` (paso opcional #11): NO tocado.** `getOrCreatePersonalHousehold` no
   escribe el campo `spaceType` a propósito (ver arriba), así que una regla estricta tipo
   `request.resource.data.spaceType is string && ... in [...]` habría roto la creación del
   espacio Personal salvo que se escribiera de forma condicional (`!('spaceType' in ...) || ...`).
   Al ser explícitamente opcional en el encargo y sin cobertura de tests para reglas de
   Firestore en este repo, se prefirió no arriesgar una regresión de seguridad sin verificación
   automatizada. Los valores maliciosos por REST directo no rompen nada (el parser cae a `HOME`
   ante cualquier string desconocido).

## 4. Commit

```
git log --oneline -1
```
(ver salida en el mensaje final de la conversación)
