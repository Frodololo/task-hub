# Modelo de datos de Task Hub

Este documento describe el modelo de datos real de Task Hub en Firestore:
qué colecciones y subcolecciones existen, qué campos tiene cada documento,
cómo se relacionan entre sí, y por qué `firestore.rules` está escrito como
está. Todo lo descrito aquí está verificado contra el código real —
`network/FirestoreDtos.kt`, `network/models/DTOs.kt`,
`network/FirestoreParsers.kt`, los repositorios de `network/` y
`firestore.rules` — no contra un diseño teórico.

No hay un backend propio de propósito general: la app cliente habla
directamente con la API REST de Firestore (Ktor, `network/FirestoreClient.kt`)
y con Firebase Auth (alta anónima + Google Sign-In), y `firestore.rules` es la
capa de autorización para esa vía. Existe, aparte, un paquete delgado de 5
Cloud Functions (`functions/src/`, ver `docs/ARQUITECTURA.md` sección 3bis)
para las operaciones de "completar tarea" que necesitan varias escrituras
atómicas — esas sí corren server-side con su propia validación de auth
(`requireAuth`/`requireTrusted`) y bypassean `firestore.rules` (usan la
Service Account admin). El resto de escrituras de la app (crear/editar tareas,
hogares, recompensas...) no pasan por ahí: siguen yendo directas del cliente a
Firestore, validadas solo por `firestore.rules`.

> `docs/specs.md` describe la visión de producto original con un backend
> Ktor + PostgreSQL y endpoints `POST /api/...`; ese diseño quedó obsoleto:
> la app actual no tiene backend propio y habla con Firestore vía REST. Este
> documento describe el modelo de datos **realmente implementado**, no el de
> `specs.md`.

## 1. Mapa de colecciones y subcolecciones

Rutas verificadas por uso real en los repositorios (`baseUrl` = endpoint REST
de Firestore para el proyecto `task-hub-62f98`):

```
users/{uid}
invites/{code}
households/{hid}
households/{hid}/members/{mid}
households/{hid}/members/{mid}/achievements/_meta   ← documento único fijo, no una colección de N logros
households/{hid}/tasks/{tid}
households/{hid}/tasks/{tid}/assignments/{aid}
households/{hid}/tasks/{tid}/comments/{cid}
households/{hid}/taskHistory/{thid}
households/{hid}/notifications/{nid}
households/{hid}/rewards/{rid}
households/{hid}/rewardRedemptions/{rrid}
households/{hid}/messages/{mid}
```

Notas sobre esta lista:

- **No hay colección de nivel superior separada para "espacios personales".**
  El espacio "Personal" de un usuario es un `households/{hid}` normal con
  `isPersonal = true` y un ID **determinista** `personal_{uid}` (ver
  `HouseholdRepository.getOrCreatePersonalHousehold` /
  `personalHouseholdId`). Esto hace que el mismo espacio personal sea
  resoluble desde cualquier dispositivo con la misma cuenta, sin necesitar
  invitación ni código (su `inviteCode` es el literal `"PERSONAL"`, nunca
  usado para unirse).
- **`achievements` no es una colección de un documento por logro.** El
  catálogo de logros (`AchievementChecker.ALL_ACHIEVEMENTS`, en
  `ui/models/Achievement.kt`) es fijo y vive en código, no en Firestore. Lo
  único persistido es **un documento fijo `_meta`** por miembro con el campo
  `unlocked` (array de IDs de logro ya desbloqueados). `firestore.rules`
  modela la subcolección en genérico (`{achid}`) porque a nivel de reglas no
  hay diferencia entre "un doc fijo" y "N docs".
- **Google Calendar no usa Firestore.** `GoogleCalendarRepository` habla
  directamente con `www.googleapis.com/calendar` usando el token OAuth del
  dispositivo; lo único que persiste en Firestore de esa integración es
  `TaskAssignmentResponse.googleEventId` (el ID del evento enlazado, dentro
  del documento de asignación ya existente). No hay colección de tokens de
  calendario en Firestore — el token vive solo en el cliente.

## 2. Colecciones: campos y significado

### `users/{uid}` — perfil global del usuario

Documento independiente de cualquier hogar; `{uid}` es el UID de Firebase
Auth (anónimo o de Google). Representa "quién es la persona", no su
membresía en un hogar concreto — permite que el mismo usuario tenga perfil
consistente (nombre, foto, bio) en varios hogares a la vez.

Campos (`UserProfile`, `network/models/DTOs.kt`, + campos adicionales
escritos directamente por `FirestoreRepository`/`MemberRepository` que no
forman parte del DTO tipado):

| Campo | Tipo | Descripción |
|---|---|---|
| `displayName` | string | Nombre mostrado. |
| `avatarUrl` | string\|null | URL de foto de perfil. |
| `avatarEmoji` | string | Emoji como avatar alternativo sin foto. |
| `bio` | string | Bio corta libre. |
| `status` | string | Estado tipo "🍳 Preparando la cena". |
| `createdAt` / `updatedAt` | int (epoch millis) | Timestamps. |
| `householdIds` | array\<string\> | IDs de hogares a los que pertenece; permite restaurar hogares tras reinstalar (`saveUserHouseholds`/`loadUserHouseholds`). No forma parte del DTO `UserProfile`, se lee/escribe como campo suelto. |
| `fcmToken` | string\|null | Token de notificaciones push del dispositivo actual (`saveFcmToken`/`clearFcmToken`). Pensado para un futuro backend/Cloud Function que aún no existe. |
| `fcmTokenUpdatedAt` | int (epoch millis) | Cuándo se actualizó `fcmToken`. |

### `invites/{code}` — mapa código → hogar

Documento minúsculo, de un solo campo, que actúa como "secreto compartido
fuera de banda": conocer el código basta para leerlo (`get`), pero la
colección nunca es listable.

| Campo | Tipo | Descripción |
|---|---|---|
| `householdId` | string | Hogar al que da acceso este código de invitación. |

### `households/{hid}` — metadatos de un hogar

Un hogar es el contenedor de todo: miembros, tareas, recompensas, etc. Puede
ser un hogar "compartido" (con invitación real) o el espacio "Personal"
auto-creado de un usuario (ver arriba).

Campos (`HouseholdResponse`):

| Campo | Tipo | Descripción |
|---|---|---|
| `name` | string | Nombre visible del hogar. |
| `inviteCode` | string | Código para unirse vía `invites/{code}`; `"PERSONAL"` si `isPersonal`. |
| `createdAt` / `updatedAt` | int (epoch millis) | Timestamps. |
| `isPersonal` | boolean | `true` = espacio personal auto-creado (ID determinista `personal_{uid}`), no un hogar compartido real. |
| `ownerId` | string | UID de quien creó el hogar. Siempre "de confianza" (equivalente a admin) al margen de su rol de miembro — es la base de `isOwner(hid)` en las reglas. |
| `timezone` | string? | TZ IANA (p. ej. `"Europe/Madrid"`), D1 (2026-09-24). Por defecto la TZ del dispositivo de quien crea el hogar (`deviceTimezone` en `HouseholdRepository.createHousehold`). `null`/ausente en hogares creados antes de esta migración. La leen las Cloud Functions de `functions/src/` (`auth.loadHouseholdTimezone`) para calcular fin de día de vencimiento/rachas, con fallback a `Europe/Madrid` (`DEFAULT_TZ`) si no está poblado. |
| `spaceType` | string enum (`SpaceType`) | Tipo de espacio (casa/piso/estudio/club, 2026-09-27) — solo copy/emoji, no cambia lógica de negocio ni permisos. Valores `"home"/"flat"/"study"/"club"` (`SpaceType.firestoreValue`). `null`/ausente o valor no reconocido → `HOME` (`spaceTypeFromFirestoreValue`, `network/models/DTOs.kt`) — cubre hogares creados antes de este campo y el espacio Personal, que nunca lo escribe. |

### `households/{hid}/members/{mid}` — miembro de un hogar

Cada persona (con cuenta) o perfil "hijo/a" (sin cuenta) que pertenece a un
hogar tiene un documento aquí. `{mid}` es el UID de Firebase Auth cuando el
miembro está vinculado a una cuenta, o un ID autogenerado para perfiles
"hijo/a" sin cuenta propia (varios perfiles pueden compartir dispositivo/
sesión).

Campos (`MemberResponse`):

| Campo | Tipo | Descripción |
|---|---|---|
| `householdId` | string | Redundante con la ruta; se guarda también como campo para queries. |
| `displayName` | string | Nombre del miembro. |
| `avatarUrl` | string\|null | Foto de perfil. |
| `role` | string | `"admin"` \| `"child"` — valor interno fijo (no renombrado en datos aunque la UI llame "Miembro" a `"child"`). |
| `totalPoints` | int | Puntos acumulados totales (histórico). |
| `joinedAt` | int (epoch millis) | Cuándo se unió. |
| `userId` | string\|null | UID de Firebase Auth vinculado; `null` en perfiles "hijo/a" sin cuenta. |
| `currentStreak` / `bestStreak` | int | Racha de días consecutivos completando tareas (actual / mejor histórica). |
| `lastStreakDate` | int (epoch millis) | Último día de racha registrado; `0` = sin racha. |
| `leftAt` | int (epoch millis) | `0` = miembro activo; `>0` = abandonó el hogar (**soft-delete**, el documento nunca se borra). |
| `appreciationGiven` | int | Puntos ya dados "agradeciendo" a otros miembros esta semana. |
| `appreciationWeekStart` | int (epoch millis) | Lunes 00:00 local de la semana de `appreciationGiven` (tope semanal, ver `PointsRules`). |

### `households/{hid}/members/{mid}/achievements/_meta` — logros desbloqueados

Documento único (id literal `_meta`) por miembro; no una colección de un
documento por logro.

| Campo | Tipo | Descripción |
|---|---|---|
| `unlocked` | array\<string\> | IDs de logros ya desbloqueados (contra el catálogo fijo en `AchievementChecker.ALL_ACHIEVEMENTS`). |
| `updatedAt` | int (epoch millis) | Última escritura. |

Se actualiza con lectura-modifica-escritura y concurrencia optimista
(`currentDocument.updateTime`) para que dos logros desbloqueados casi a la
vez (misma tarea que dispara dos, o dos dispositivos) no se pisen entre sí.

### `households/{hid}/tasks/{tid}` — tarea

Modelo **sin instancias**: una tarea recurrente ("daily"/"weekly"/"monthly")
es un único documento que se recalcula al completarse, no N documentos por
ocurrencia.

Campos (`TaskResponse`):

| Campo | Tipo | Descripción |
|---|---|---|
| `householdId` | string | Redundante con la ruta. |
| `createdBy` | string | ID del miembro que creó la tarea. |
| `title` / `description` | string | Datos básicos. |
| `points` | int | Puntos que otorga al completarse (por defecto 10). |
| `frequency` | string | `"once"` \| `"daily"` \| `"weekly"` \| `"monthly"`. |
| `recurrenceDays` | array\<int\> | Días de la semana (1=lunes..7=domingo); solo para `"weekly"`. |
| `recurrenceDay` | int\|null | Día del mes (1..31); solo `"monthly"`. `null` = comportamiento legado (una vez al mes, cualquier día). |
| `tags` | array\<string\> | Etiquetas libres. |
| `subtasks` | array\<map\> | Checklist: `{id, text, completed}`. |
| `penaltyMode` | string\|null | `"fixed"` \| `"percentage"`; `null` = sin penalización por retraso. |
| `penaltyValue` / `penaltyInterval` / `penaltyMax` | int / string / int | Magnitud, unidad (`"day"`/`"week"`/`"month"`) y tope de la penalización. |
| `dueDate` | int (epoch millis) | Fecha límite; solo para `"once"`. `0` = sin fecha. |
| `lastCompletedDate` | int\|null | Última vez completada; motor del cálculo "¿toca hoy?". |
| `completedBy` | string\|null | ID del miembro que la marcó hecha la última vez — de ahí salen los puntos y de ahí sale "quién la hizo" en la UI. |
| `assignmentRotation` | array\<map\> | Rotación diaria de asignados: `{dayOfWeek, memberId}`. |
| `nextDueAt` | int\|null | Fecha límite de la ocurrencia recurrente pendiente (permite penalizar retrasos en recurrentes, donde `dueDate` vale 0). `null` en tareas `"once"` o en recurrentes creadas antes de este campo. |
| `createdAt` / `updatedAt` | int (epoch millis) | Timestamps. |

### `households/{hid}/tasks/{tid}/assignments/{aid}` — asignación de una tarea a un miembro

Una tarea puede tener 0..N asignaciones, una por miembro.

| Campo | Tipo | Descripción |
|---|---|---|
| `taskId` | string | Redundante con la ruta. |
| `memberId` | string | Miembro asignado. |
| `mandatory` | boolean | Si es obligatoria, el miembro no puede rechazarla. |
| `dueDate` | int (epoch millis) | Fecha límite de esta asignación. |
| `status` | string | `"assigned"` \| `"completed"`. |
| `completedAt` | int\|null | Cuándo se completó. |
| `pointsAwarded` | int\|null | Puntos otorgados (puede ser menor que `task.points` por penalización). |
| `onTime` | boolean\|null | A tiempo / tarde / aún no completada. |
| `assignedAt` | int (epoch millis) | Cuándo se asignó. |
| `googleEventId` | string\|null | ID del evento de Google Calendar enlazado (la única huella de esa integración en Firestore). |

`updateTime` (RFC3339 de Firestore, no un campo del documento) se conserva
en memoria tras leer el doc para usarlo como precondición de concurrencia
optimista al cerrar asignaciones hermanas en `completeTask`/
`completeAssignment`.

### `households/{hid}/tasks/{tid}/comments/{cid}` — comentario en una tarea

| Campo | Tipo | Descripción |
|---|---|---|
| `authorName` | string | Nombre mostrado del autor en el momento de comentar. |
| `text` | string | Contenido. |
| `createdAt` | int (epoch millis) | Timestamp. |
| `memberId` | string\|null | ID del miembro autor — permite anonimizar `authorName` si el miembro es expulsado/abandona. `null` en comentarios creados antes de que existiera este campo (no migrable). |

### `households/{hid}/taskHistory/{thid}` — registro de auditoría de compleciones

Es la fuente de verdad para estadísticas (`StatsScreen`) y para poder
deshacer una compleción sin desincronizar `totalPoints`.

| Campo | Tipo | Descripción |
|---|---|---|
| `taskId` | string | Tarea completada. |
| `memberId` | string | Miembro que recibió los puntos. |
| `points` | int | Puntos otorgados en esa compleción concreta. |
| `completedAt` | int (epoch millis) | Timestamp de la compleción. |
| `onTime` | boolean | Si se completó a tiempo o tarde. |

### `households/{hid}/notifications/{nid}` — notificación dirigida a un miembro

| Campo | Tipo | Descripción |
|---|---|---|
| `memberId` | string | Destinatario. |
| `taskId` | string | Tarea relacionada. |
| `title` / `message` | string | Texto ya renderizado (idioma de quien la escribió). |
| `createdAt` | int (epoch millis) | Timestamp. |
| `read` | boolean | Leída/no leída. |
| `titleKey` / `messageKey` / `messageParams` | string\|null / string\|null / map\<string,string\>\|null | Claves de i18n + parámetros para renderizar el texto en el idioma del **lector**, no del autor. `null` en notificaciones antiguas (anteriores a este rediseño) o en mensajes de chat (contenido de usuario, no traducible) — en ambos casos se usa `title`/`message` tal cual. |

### `households/{hid}/rewards/{rid}` — recompensa canjeable

| Campo | Tipo | Descripción |
|---|---|---|
| `householdId` | string | Redundante con la ruta. |
| `title` / `description` | string | Datos de la recompensa. |
| `cost` | int | Puntos que cuesta canjearla. |
| `icon` | string | Emoji (`"🎁"` por defecto). |
| `createdBy` | string | Miembro que la creó (admin/owner). |
| `createdAt` | int (epoch millis) | Timestamp. |

### `households/{hid}/rewardRedemptions/{rrid}` — canje de una recompensa

| Campo | Tipo | Descripción |
|---|---|---|
| `rewardId` | string | Recompensa canjeada. |
| `memberId` | string | Miembro que la canjeó. |
| `redeemedAt` | int (epoch millis) | Timestamp del canje. |
| `pointsSpent` | int | Puntos gastados — debe coincidir exactamente con `rewards/{rewardId}.cost` en el momento del canje (lo exige la propia regla de `create`, ver sección 3). |

### `households/{hid}/messages/{mid}` — mensaje del chat del hogar

| Campo | Tipo | Descripción |
|---|---|---|
| `memberId` | string | Autor. |
| `authorName` | string | Nombre mostrado del autor en el momento de escribir. |
| `text` | string | Contenido. |
| `createdAt` | int (epoch millis) | Timestamp. |

## 3. `firestore.rules` explicado regla a regla

`firestore.rules` está en su versión v7 (2026-09-04). No se modifica en este
documento ni en este encargo — esta sección solo lo **explica**. El modelo
de acceso de fondo:

- Todo acceso requiere `request.auth != null` (`signedIn()`).
- Un usuario accede a un hogar si es **miembro activo** (`isMember`, existe
  `members/{uid}` con `leftAt == 0`) o es el **propietario**
  (`isOwner`, `request.auth.uid == households/{hid}.ownerId`).
- `isAdminMember(hid)` = miembro activo con `role == "admin"`.
- `isTrusted(hid)` = `isOwner(hid) || isAdminMember(hid)` — el conjunto que
  gestiona contenido sensible (roles, recompensas, moderar historial/
  notificaciones ajenas).
- `existsMemberDoc(hid, mid)` comprueba que un ID de miembro existe **sin**
  exigir que sea el usuario autenticado — se usa en `create` de
  `taskHistory`/`rewardRedemptions` porque "cualquier miembro puede
  completar/canjear en nombre de otro" es un flujo de producto real (un
  padre/madre actuando por un perfil "hijo/a" sin cuenta, desde el mismo
  dispositivo).

Funciones auxiliares (`householdDoc`, `myMemberDoc`) son simplemente
`get()` sobre la ruta correspondiente, usadas para no repetir la llamada.

### `users/{uid}`

```
allow read, write: if signedIn() && request.auth.uid == uid;
```
Perfil global — solo el propio dueño puede leerlo o escribirlo. Protege
`fcmToken`, `bio`, etc. de terceros. No hay distinción de campos porque no
hay ningún campo sensible que un tercero deba poder tocar (a diferencia de
`members/{mid}.role`, que sí distingue campos).

### `invites/{code}`

```
allow get: if signedIn();
allow list: if false;
allow create: if signedIn() && request.auth.uid == householdDoc(request.resource.data.householdId).data.ownerId;
allow delete: if signedIn() && request.auth.uid == householdDoc(resource.data.householdId).data.ownerId;
```
- `get` abierto a cualquier usuario autenticado: el código en sí (aleatorio,
  compartido fuera de banda — por WhatsApp, etc.) es el secreto, no el
  permiso de lectura. Por eso `list: false` — sin esto se podría enumerar
  todos los códigos de invitación existentes con una query vacía.
- `create`/`delete` solo al `ownerId` del hogar referenciado: nadie puede
  generar ni revocar invitaciones de un hogar ajeno.

### `households/{hid}`

```
allow get: if signedIn();
allow list: if false;
allow create: if signedIn() && request.resource.data.ownerId == request.auth.uid;
allow update, delete: if isOwner(hid);
```
- `get` abierto (el ID del hogar es un token aleatorio/no enumerable, igual
  razonamiento que `invites`); `list: false` evita enumerar todos los
  hogares existentes.
- `create` exige que el `ownerId` del documento nuevo sea quien lo crea
  (nadie puede crear un hogar y asignárselo a otra persona como dueña).
- `update`/`delete` solo el propietario — cambiar nombre, borrar el hogar
  entero.

### `households/{hid}/members/{mid}`

```
allow read: if isMember(hid) || isOwner(hid);
allow create: if signedIn() && ( <rama owner/admin> || <rama auto-alta> );
allow update: if isTrusted(hid) || (request.auth.uid == mid && role sin cambiar);
allow delete: if isTrusted(hid) || request.auth.uid == mid;
```

**`create`** tiene dos ramas, cada una cerrando un vector de abuso distinto:

1. **Owner/admin crea un perfil** (`CreateProfileScreen`: el owner crea su
   propio primer perfil; alta de perfiles "hijo/a" sin cuenta). El rol es
   libre aquí a propósito (quien confía debe poder fijar/ascender roles),
   pero el documento nuevo no puede traer ya saldo/racha/agradecimiento
   inventados (`totalPoints`, `currentStreak`, `bestStreak`,
   `appreciationGiven`, `appreciationWeekStart` deben venir en 0, y
   `leftAt` en 0) ni un `userId` que suplante a otra persona (si se vincula
   una cuenta, tiene que ser la del propio creador). Sin esto, un cliente
   modificado podía dar de alta a un miembro ya "rico" en puntos o con la
   semana de agradecimiento ya agotada.
2. **Auto-alta con código de invitación** (`JoinHouseholdScreen`): el UID
   que se está creando debe aportar el `inviteCode` correcto del hogar,
   `userId` debe ser su propio UID (nadie se auto-registra suplantando a
   otro), el `role` se fuerza a `"child"` (nadie se autoproclama admin al
   unirse — un admin existente asciende después) y los mismos contadores
   deben venir en 0. Todo esto se exige en la regla, no solo en la UI, para
   que no se salte por REST directo.

**`update`**: `isTrusted` (owner/admin) puede tocar cualquier campo de
cualquier miembro. Un miembro normal solo puede tocar **su propio**
documento, y nunca su `role` (`request.resource.data.role ==
resource.data.role`) — así es como el cliente incrementa hoy sus propios
puntos/rachas/agradecimientos sin backend, pero no puede auto-ascenderse.

**`delete`**: `isTrusted` puede borrar cualquier miembro; el propio dueño
del documento también puede borrar el suyo — es la ruta de
`leaveHousehold` (abandonar hogar / eliminar cuenta). Sin esta cláusula
cualquier miembro con rol `"child"` recibía 403 al salir de un hogar
compartido (hallazgo crítico resuelto en v4). Nota: aunque `leaveHousehold`
en la práctica hace un **soft-delete** (`leftAt = now`, no un borrado real
— ver `FirestoreRepository.deleteMember`), la regla de `delete` sigue
existiendo porque también cubre el borrado real de cuenta.

### `households/{hid}/tasks/{tid}`

```
allow read, create, delete: if isMember(hid) || isOwner(hid);
allow update: if isTrusted(hid) || ((isMember(hid) || isOwner(hid)) && !(reasignación sin lastCompletedDate));
```
Cualquier miembro lee/crea/borra tareas libremente — el modelo de producto
es "transparencia radical dentro del hogar", no hay tareas privadas.
`update` tiene una excepción concreta: cambiar `completedBy` **sin** cambiar
a la vez `lastCompletedDate` es la firma exacta de
`FirestoreRepository.reassignTaskCompletion` (que hace un `updateMask` solo
sobre `completedBy`) — eso mueve puntos ya otorgados de un miembro a otro, y
solo lo puede hacer alguien de confianza. Completar (`completeTask`) y
deshacer (`revertTaskCompletion`) cambian ambos campos a la vez, así que un
miembro normal los sigue pudiendo hacer sin restricción.

### `tasks/{tid}/assignments/{aid}` y `tasks/{tid}/comments/{cid}`

```
allow read, write: if isMember(hid) || isOwner(hid);
```
Sin restricciones de campo — cualquier miembro puede asignar, completar,
comentar. No hay un vector de abuso análogo a `completedBy` documentado
aquí (asignar una tarea no mueve puntos ya otorgados).

### `households/{hid}/taskHistory/{thid}`

```
allow read: if isMember(hid) || isOwner(hid);
allow create: if (isMember(hid) || isOwner(hid)) && existsMemberDoc(hid, memberId) && points >= 0;
allow update: if isTrusted(hid);
allow delete: if isTrusted(hid) || (isMember(hid) && resource.data.memberId == request.auth.uid);
```
Es el registro de auditoría de puntos/estadísticas, así que `update`
(usado solo por `reassignTaskCompletion`) se restringe a `isTrusted`.
`create` (v7) exige que `memberId` sea un miembro real del hogar y que
`points` no sea negativo — cierra que un cliente modificado mande un
`memberId` inventado o puntos negativos vía REST directo; **no** exige
`memberId == request.auth.uid` porque "completar en nombre de otro" (perfil
"hijo/a") es un flujo real. `delete` permite además al propio miembro
borrar su propio registro — necesario para `undoCompleteTask` (deshacer
"completar tarea" es acción de un miembro normal, y sin esto el historial
quedaría desincronizado con `totalPoints` en cuanto se desplegaran las
reglas).

### `households/{hid}/notifications/{nid}`

```
allow read, create: if isMember(hid) || isOwner(hid);
allow update, delete: if isTrusted(hid) || (isMember(hid) && resource.data.memberId == request.auth.uid);
```
Lectura/creación amplias (dashboard familiar). Modificar (marcar leída) o
borrar la notificación de **otro** miembro se restringe a `isTrusted`
(necesario para gestionar notificaciones de perfiles "hijo/a" sin cuenta);
el propio destinatario siempre puede gestionar las suyas.

### `households/{hid}/rewards/{rid}`

```
allow read: if isMember(hid) || isOwner(hid);
allow write: if isTrusted(hid);
```
Cualquier miembro lee el catálogo de recompensas; solo admin/owner puede
crear/editar/borrar recompensas (la UI ya lo restringía; la regla lo hace
cumplir también a nivel de datos).

### `households/{hid}/rewardRedemptions/{rrid}`

```
allow read: if isMember(hid) || isOwner(hid);
allow create: if (isMember(hid) || isOwner(hid)) && existsMemberDoc(hid, memberId)
  && pointsSpent == get(.../rewards/$(rewardId)).data.cost;
allow update, delete: if isTrusted(hid);
```
`create` (v7) exige `memberId` real y, sobre todo, que `pointsSpent`
coincida **exactamente** con el `cost` real de la recompensa referenciada
en ese instante — cierra la vía de canjear pagando menos puntos de los que
cuesta manipulando el payload REST directo. `update`/`delete` restringidos
a `isTrusted` (moderación de canjes).

### `households/{hid}/members/{mid}/achievements/{achid}`

```
allow read, write: if isMember(hid) || isOwner(hid);
```
Sin restricción de campos: cualquier miembro puede leer/escribir el
documento `_meta` de logros de cualquier miembro del hogar (incluidos los
de otros). Es una regla amplia comparada con el resto de la subcolección
`members` — no hay validación de que solo se pueda desbloquear un logro que
realmente corresponda a las estadísticas del miembro. En la práctica el
riesgo es bajo (logros son cosméticos, no mueven puntos), pero es la
colección con el control más laxo del árbol de reglas — ver "Hallazgos" al
final de esta sección para el detalle.

### `households/{hid}/messages/{mid}`

```
allow read, write: if isMember(hid) || isOwner(hid);
```
Chat del hogar: cualquier miembro lee/escribe libremente, sin distinción de
campos ni de autoría — un miembro podría en teoría editar/borrar el mensaje
de otro vía REST directo (la UI no lo permite, pero la regla no lo impide).

### ¿Hay denegación por defecto?

`firestore.rules` **no** declara una regla `match /{document=**} { allow
read, write: if false; }` explícita al final. Esto es seguro igualmente
porque Firestore deniega por defecto cualquier ruta que no matchee
**ninguna** regla del archivo — el comportamiento base de Firestore
Security Rules es "todo denegado salvo lo explícitamente permitido", así
que la ausencia de un catch-all no abre ningún hueco: cualquier colección no
listada aquí (o cualquier subcolección con un nombre distinto a las
listadas) es inaccesible por REST. Un catch-all explícito solo aportaría
documentación en el propio archivo de reglas, no una protección adicional.

### Qué hacer si se añade una subcolección nueva

Por analogía con las existentes, alguien añadiendo
`households/{hid}/loQueSea/{id}` debería decidir, en este orden:

1. **¿Quién debe poder leerla?** Casi siempre `isMember(hid) || isOwner(hid)`
   (patrón repetido en `tasks`, `assignments`, `comments`, `notifications`,
   `rewards`, `achievements`, `messages`) — el modelo de producto es
   transparencia total dentro del hogar, no privacidad entre miembros.
2. **¿Escribir un documento nuevo puede regalar algo (puntos, saldo,
   permisos)?** Si sí (como `taskHistory`/`rewardRedemptions`), añadir en
   `create` una validación de campos: como mínimo que cualquier `memberId`
   referenciado exista de verdad (`existsMemberDoc`), y que cualquier
   cantidad numérica sensible no sea negativa o coincida con un valor de
   referencia ya existente en Firestore (patrón `pointsSpent == cost`).
3. **¿Modificar/borrar un documento ya escrito puede perjudicar a otro
   miembro** (robarle puntos, borrar su historial, leer su notificación)?
   Si sí, restringir `update`/`delete` a `isTrusted(hid)` salvo que el
   propio dueño del documento (`resource.data.memberId ==
   request.auth.uid`) necesite poder tocar el suyo — patrón repetido en
   `taskHistory` (delete propio) y `notifications` (update/delete propio).
4. **¿Hay un campo que decide privilegios** (como `members.role`)? Si sí,
   impedir que el propio dueño lo cambie en su `update`
   (`request.resource.data.campo == resource.data.campo`) y dejarlo solo en
   manos de `isTrusted(hid)`.
5. Documentar el motivo del cambio en el bloque de cabecera de
   `firestore.rules` (versión vN, fecha, qué vector cierra) — es el patrón
   que sigue todo el historial v1→v7 del archivo.

## 4. Relaciones entre colecciones

Todas las relaciones son por **ID de documento guardado como string**, no
hay referencias tipadas (`reference`) de Firestore — el cliente REST
siempre las resuelve con una segunda lectura o con `existsMemberDoc`/`get()`
en las reglas.

- `households/{hid}.ownerId` → UID de un usuario (no necesariamente un doc
  `members/{uid}` — el propietario puede o no tener perfil de miembro).
- `households/{hid}/members/{mid}.userId` → UID de Firebase Auth (o `null`
  si es un perfil "hijo/a" sin cuenta); `{mid}` mismo coincide con ese UID
  cuando el miembro está vinculado a una cuenta.
- `households/{hid}/tasks/{tid}.createdBy` → `members/{mid}` que la creó.
- `tasks/{tid}.completedBy` / `tasks/{tid}/assignments/{aid}.memberId` →
  `members/{mid}` que la completó / a quien está asignada.
- `tasks/{tid}/assignments/{aid}.taskId` → redundante con la ruta, se
  guarda también como campo plano para queries/collection group.
- `taskHistory/{thid}.taskId` → `tasks/{tid}`; `taskHistory/{thid}.memberId`
  → `members/{mid}` que recibió los puntos (validado en la regla `create`
  con `existsMemberDoc`).
- `notifications/{nid}.memberId` → `members/{mid}` destinatario;
  `notifications/{nid}.taskId` → `tasks/{tid}` relacionada.
- `rewards/{rid}.createdBy` → `members/{mid}` que la creó (admin/owner).
- `rewardRedemptions/{rrid}.rewardId` → `rewards/{rid}` canjeada;
  `rewardRedemptions/{rrid}.memberId` → `members/{mid}` que canjeó (ambos
  validados en la regla `create`: el miembro debe existir y `pointsSpent`
  debe coincidir con `rewards/{rewardId}.cost`).
- `messages/{mid}.memberId` → `members/{mid}` autor (permite anonimizar
  `authorName` si el miembro abandona, mismo patrón que
  `comments/{cid}.memberId`).
- `invites/{code}.householdId` → `households/{hid}` al que da acceso.
- `users/{uid}.householdIds` → lista de `households/{hid}` a los que
  pertenece ese usuario (across hogares, para restaurar tras reinstalar).

## Ver también

- `docs/ARQUITECTURA.md` — visión general del stack y la estructura del
  código.
- `docs/FLUJOS-PRINCIPALES.md` — flujos de usuario de punta a punta
  (pendiente de creación).
- `docs/PRIMEROS-PASOS.md` — cómo levantar el proyecto localmente.
- `docs/INDICE.md` — mapa completo de la documentación del proyecto
  (pendiente de creación).
