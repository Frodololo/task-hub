// DTOs (`@Serializable`) que modelan los documentos de Firestore leídos/
// escritos por FirestoreRepository/HouseholdRepository/MemberRepository/
// TaskRepository/RewardsRepository/NotificationRepository vía la API REST
// (Ktor + kotlinx.serialization) — NO son entidades del SDK de Firestore.
// Cada tipo documenta, en su KDoc, la colección/documento que representa.

package org.taskhub.network.models

import androidx.compose.runtime.Immutable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.taskhub.platform.AppLog

/**
 * Tipo de espacio (empaquetado/copy, no cambia la lógica de negocio): decide
 * qué emoji/copy contextual se muestra al crear y listar un hogar. El valor
 * Firestore serializado ([SerialName]) es el usado en el campo
 * `spaceType` del documento `households/{id}` — ver [HouseholdResponse.spaceType].
 * [defaultNameRes] es orientativo (sugerencia de placeholder), NO una clave
 * de [org.taskhub.ui.i18n.AppStrings] — los strings reales usan las claves
 * `space_type_*`.
 */
@Serializable
enum class SpaceType(val emoji: String, val defaultNameRes: String) {
    @SerialName("home") HOME("🏠", "Tipo casa"),
    @SerialName("flat") FLAT_SHARE("🏢", "Tipo piso compartido"),
    @SerialName("study") STUDY_GROUP("📚", "Tipo trabajo universidad"),
    @SerialName("club") CLUB("⚽", "Tipo grupo / club");
}

/** Valor persistido en el campo `spaceType` del documento `households/{id}` en Firestore REST. */
val SpaceType.firestoreValue: String
    get() = when (this) {
        SpaceType.HOME -> "home"
        SpaceType.FLAT_SHARE -> "flat"
        SpaceType.STUDY_GROUP -> "study"
        SpaceType.CLUB -> "club"
    }

/** Inverso de [SpaceType.firestoreValue]. Desconocido/null → [SpaceType.HOME] (hogares legacy). */
fun spaceTypeFromFirestoreValue(value: String?): SpaceType = when (value) {
    null, "home" -> SpaceType.HOME
    "flat" -> SpaceType.FLAT_SHARE
    "study" -> SpaceType.STUDY_GROUP
    "club" -> SpaceType.CLUB
    else -> {
        // null/ausente es el caso legítimo de hogares legacy (sin log); un string
        // no reconocido aquí sí es anómalo (typo manual, migración futura de
        // nombres, o REST directo) y conviene dejar rastro para depurarlo.
        AppLog.w("SpaceType", "spaceTypeFromFirestoreValue: valor no reconocido \"$value\", usando HOME")
        SpaceType.HOME
    }
}

// ── Request DTOs ──────────────────────────────────────────
// NOTA: estos 3 DTOs no tienen ningún call-site en el resto del código (no
// hay referencias a CreateHouseholdRequest/JoinHouseholdRequest/
// CreateMemberRequest fuera de este archivo) — parecen vestigios de un diseño
// previo con un backend intermedio (antes de hablar con Firestore REST
// directamente, ver KDoc de FirestoreRepository: "no Ktor server needed").
// Se documentan tal cual pero podrían eliminarse en una limpieza futura.

/** Vestigio sin uso — ver nota de arriba. Cuerpo de una petición de creación de hogar. */
@Serializable
data class CreateHouseholdRequest(val name: String)

/** Vestigio sin uso — ver nota de arriba. Cuerpo de una petición de unión a hogar por código de invitación. */
@Serializable
data class JoinHouseholdRequest(val inviteCode: String)

/** Vestigio sin uso — ver nota de arriba. Cuerpo de una petición de creación de miembro. */
@Serializable
data class CreateMemberRequest(
    val displayName: String,
    val role: String = "child",
    val avatarUrl: String? = null
)

// ── Response DTOs ─────────────────────────────────────────

/** Documento `households/{id}`. Un hogar (espacio compartido de tareas/miembros/recompensas). */
@Immutable
@Serializable
data class HouseholdResponse(
    val id: String,
    val name: String,
    val inviteCode: String,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    /** True si es el espacio "Personal" auto-creado (sin invitaciones). */
    val isPersonal: Boolean = false,
    /**
     * UID de quien creó el hogar. Siempre "de confianza" (equivalente a admin)
     * independientemente de su [MemberResponse.role] — coincide con `isOwner(hid)`
     * en firestore.rules. Ver `isAdmin` en HouseholdScreen/TaskDetailScreen.
     */
    val ownerId: String = "",
    /**
     * TZ IANA (ej. "Europe/Madrid") usada por las Cloud Functions para
     * calcular fin de día de vencimiento/rachas (D1). `null` en hogares
     * creados antes de esta migración — las CF caen a `Europe/Madrid`.
     * Por defecto, la TZ del dispositivo de quien crea el hogar.
     */
    val timezone: String? = null,
    /** Tipo de espacio (casa/piso/estudio/grupo). Default HOME para hogares creados antes de este campo. */
    val spaceType: SpaceType = SpaceType.HOME,
    /** Emoji elegido manualmente por el creador. `null` = usar el de [spaceType] (hogares legacy o sin elección). */
    val emoji: String? = null
)

/**
 * Documento `households/{householdId}/members/{id}`.
 * Miembro de un hogar. Cada usuario que se une crea un Member doc.
 */
@Immutable
@Serializable
data class MemberResponse(
    val id: String,
    val householdId: String,
    val displayName: String,
    val avatarUrl: String? = null,
    /**
     * "admin" | "child". Valor interno en código/Firestore — NO se
     * renombra (evita migración de datos). El nombre de display para
     * "child" en la UI es "Miembro" (`member_role_child_*` en
     * [org.taskhub.ui.i18n.AppStrings]), no "Niño/a".
     */
    val role: String,
    /** Puntos acumulados totales (histórico). */
    val totalPoints: Int = 0,
    val joinedAt: Long = 0,
    /** UID de Firebase Auth (Google) de la cuenta vinculada, o `null` si es un perfil sin cuenta (p.ej. "hijo/a"). */
    val userId: String? = null,
    /** Racha actual de días consecutivos completando tareas. */
    val currentStreak: Int = 0,
    /** Mejor racha histórica. */
    val bestStreak: Int = 0,
    /** Último día de racha registrado (epoch millis). 0 = sin racha. */
    val lastStreakDate: Long = 0,
    /** Epoch millis en que el miembro abandonó el hogar (soft-delete). 0 = activo. */
    val leftAt: Long = 0,
    /** Puntos ya DADOS agradeciendo a otros durante la semana de [appreciationWeekStart]. */
    val appreciationGiven: Int = 0,
    /** Epoch millis del lunes 00:00 local de la semana a la que corresponde [appreciationGiven]. */
    val appreciationWeekStart: Long = 0
)

/**
 * Perfil GLOBAL de un usuario, independiente de su membresía en hogares.
 * Vive en la colección `users/{userId}` y es la base del "perfilado creciente":
 * añadir foto, bio, preferencias, etc. solo requiere añadir campos aquí y en
 * [org.taskhub.network.FirestoreRepository.upsertUserProfile].
 */
@Serializable
data class UserProfile(
    /** UID de Firebase Auth (Google). */
    val id: String,
    val displayName: String = "",
    /** URL de la foto de perfil. null = sin foto todavía. */
    val avatarUrl: String? = null,
    /** Emoji para el avatar rápido (alternativa sin foto). p.ej. "🧑", "👩", "🐱". */
    val avatarEmoji: String = "",
    /** Bio corta estilo "Papá, profe de mates y cocinero". */
    val bio: String = "",
    /** Estado tipo "🍳 Preparando la cena" o "📚 Estudiando". */
    val status: String = "",
    val createdAt: Long = 0,
    val updatedAt: Long = 0
)

/** Cuerpo de una respuesta de error genérica de la API REST de Firestore. */
@Serializable
data class ErrorResponse(val error: String)

// ── Task DTOs ────────────────────────────────────────────

/**
 * Un slot de la rotación semanal de asignados de una tarea recurrente —
 * elemento de la lista [TaskResponse.assignmentRotation] (NO es un documento
 * propio de Firestore; se serializa embebido dentro del documento de la tarea).
 */
@Serializable
data class AssignmentSlot(
    /** 1=Lunes..7=Domingo */
    val dayOfWeek: Int,
    val memberId: String
)

/**
 * Un ítem de la checklist de una tarea — elemento de la lista
 * [TaskResponse.subtasks] (NO es un documento propio de Firestore; se
 * serializa embebido dentro del documento de la tarea).
 */
@Serializable
data class Subtask(
    val id: String,
    val text: String,
    val completed: Boolean = false
)

/**
 * Documento `households/{householdId}/tasks/{id}`.
 * Representa una tarea en Firestore y en la UI.
 *
 * Modelo simplificado sin instancias:
 * - Una tarea "daily" es UN solo documento, no N documentos por día.
 * - La recurrencia se calcula en cliente vía [frequency] + [recurrenceDays].
 * - [lastCompletedDate] marca la última vez completada; sirve para saber si
 *   toca hoy (isTaskDueToday en TaskListScreen.kt).
 * - [dueDate] es la fecha límite para tareas "once" (0 = sin fecha).
 */
@Immutable
@Serializable
data class TaskResponse(
    val id: String,
    val householdId: String,
    val createdBy: String,
    val title: String,
    val description: String = "",
    val points: Int = 10,
    /** "once" | "daily" | "weekly" | "monthly" */
    val frequency: String = "once",
    /** Días de la semana en que aplica (1=Lunes..7=Domingo). Solo para "weekly". */
    val recurrenceDays: List<Int> = emptyList(),
    /**
     * Día del mes en que aplica (1..31). Solo para "monthly".
     * null = comportamiento legado: toca una vez al mes, cualquier día.
     * Si el mes no tiene ese día (p.ej. 31 en febrero), se ajusta al último
     * día del mes — ver [org.taskhub.network.RecurrenceRules.clampDayOfMonth].
     */
    val recurrenceDay: Int? = null,
    val tags: List<String> = emptyList(),
    /** Checklist de subtareas dentro de la tarea. */
    val subtasks: List<Subtask> = emptyList(),
    /** Modo de penalización por retraso: "fixed" (puntos fijos) o "percentage" (% de points). null = sin penalización. */
    val penaltyMode: String? = null,
    val penaltyValue: Int = 0,
    /** Unidad de intervalo de penalización: "day", "week", "month". */
    val penaltyInterval: String = "day",
    /** Tope máximo de penalización (0 = sin tope). */
    val penaltyMax: Int = 0,
    /**
     * Fecha límite en epoch millis (solo para tareas "once").
     * 0 = sin fecha límite.
     */
    val dueDate: Long = 0,
    /**
     * Última vez que se completó esta tarea (epoch millis).
     * null = nunca completada.
     * Es el campo central para el cálculo de "¿toca hoy?".
     */
    val lastCompletedDate: Long? = null,
    /**
     * ID del miembro que marcó la tarea como hecha la última vez.
     * null = nunca completada. Es la base de "quien marca hecho recibe los
     * puntos" (al margen de quién esté asignado) y de "editar quién la hizo".
     */
    val completedBy: String? = null,
    /** Rotación diaria de asignados: quién le toca cada día de la semana. */
    val assignmentRotation: List<AssignmentSlot> = emptyList(),
    /**
     * Fecha límite de la ocurrencia recurrente PENDIENTE (epoch millis), solo
     * para "daily"/"weekly"/"monthly". Se calcula con
     * [org.taskhub.network.RecurrenceRules.nextOccurrence] al crear la tarea y
     * se recalcula en el mismo PATCH que [lastCompletedDate] cada vez que se
     * completa — da a `resolveCompletionOutcome` una fecha límite real para
     * penalizar retrasos en recurrentes (antes imposible: [dueDate] vale 0
     * para estas frecuencias). null para tareas "once" (usan [dueDate]) y
     * para tareas recurrentes creadas antes de este campo — en ese caso se
     * cae al comportamiento previo (sin penalización) hasta la próxima
     * compleción, que ya lo puebla.
     */
    val nextDueAt: Long? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0
)

/**
 * Documento `households/{householdId}/tasks/{taskId}/assignments/{id}`.
 * Asignación de una tarea a un miembro.
 * Una tarea puede tener 0..N asignaciones (una por miembro).
 */
@Immutable
@Serializable
data class TaskAssignmentResponse(
    val id: String,
    val taskId: String,
    val memberId: String,
    /** Si es obligatoria, el miembro no puede rechazarla. */
    val mandatory: Boolean = false,
    /** Fecha límite (epoch millis). */
    val dueDate: Long = 0,
    /** "assigned" | "completed" */
    val status: String = "assigned",
    /** Timestamp de compleción (epoch millis). null = no completada. */
    val completedAt: Long? = null,
    /** Puntos otorgados (puede ser menor por penalización). */
    val pointsAwarded: Int? = null,
    /** true = a tiempo, false = tarde, null = no completada aún. */
    val onTime: Boolean? = null,
    val assignedAt: Long = 0,
    /** ID del evento en Google Calendar vinculado, o null si no está sincronizada. */
    val googleEventId: String? = null,
    /**
     * `updateTime` de Firestore de ESTE documento en el momento en que se
     * leyó (RFC3339, tal cual lo devuelve la API REST) — usado como
     * `currentDocument.updateTime` (precondition de concurrencia optimista)
     * al cerrar asignaciones hermanas en `completeTask`/`completeAssignment`
     * (panel de revisión 2026-09-03/04, Experto 12). `null` si el documento
     * no se leyó de Firestore (p.ej. instancias construidas en memoria tras
     * un `assignTask`).
     */
    val updateTime: String? = null
)

// ── Comments DTO ─────────────────────────────────────────

/** Documento `households/{householdId}/tasks/{taskId}/comments/{id}`. Comentario de un miembro en una tarea. */
@Serializable
data class CommentResponse(
    val id: String,
    val authorName: String,
    val text: String,
    val createdAt: Long = 0,
    /**
     * ID del miembro autor, para poder anonimizar `authorName` al expulsar/
     * abandonar (mismo patrón que [MessageResponse.memberId]) — panel de
     * revisión 2026-09-03/04, Experto 2/10. `null` en comentarios creados
     * ANTES de este campo: no hay forma de retro-migrarlos (el documento no
     * guardaba memberId), así que quedan sin poder anonimizarse.
     */
    val memberId: String? = null
)

// ── Message DTO ────────────────────────────────────────────

/** Documento `households/{householdId}/messages/{id}`. Mensaje del chat del hogar. */
@Serializable
data class MessageResponse(
    val id: String,
    val memberId: String,
    val authorName: String,
    val text: String,
    val createdAt: Long = 0
)

// ── Task History DTO ──────────────────────────────────────

/**
 * Documento `households/{householdId}/taskHistory/{id}`.
 * Registro histórico de una compleción de tarea — fuente de agregación para
 * StatsScreen (independiente del estado actual de la tarea/asignación, que
 * puede haberse revertido/reasignado después).
 */
@Serializable
data class TaskHistoryResponse(
    val id: String,
    val taskId: String,
    val memberId: String,
    val points: Int = 0,
    val completedAt: Long = 0,
    val onTime: Boolean = true,
    /**
     * `false` mientras `completeTask`/`completeAssignment` aún no han
     * confirmado el `addMemberPoints` correspondiente a este registro — el
     * rastro que permite a `TaskReconciliation`/`FirestoreRepository.
     * reconcileMissingTaskPoints` detectar y reparar una tarea "completada
     * sin puntos" (fallo parcial entre marcar la tarea completada y otorgar
     * los puntos) sin volver a otorgarlos si el registro ya quedó en `true`.
     * `true` por defecto para registros ANTIGUOS sin este campo: el código
     * previo otorgaba los puntos ANTES de guardar el historial, así que su
     * sola existencia ya implicaba puntos aplicados.
     */
    val pointsApplied: Boolean = true
)

// ── Notification DTO ──────────────────────────────────────

/**
 * Documento `households/{householdId}/notifications/{id}`.
 *
 * [titleKey]/[messageKey]/[messageParams] permiten renderizar el texto en el
 * idioma del LECTOR en vez de quedar fijado al idioma de quien la escribió
 * (panel de notificaciones 2026-09-05, IMPORTANTE) — ver
 * `ui/i18n/NotificationText.kt`. `null` en notificaciones ANTIGUAS (creadas
 * antes de este cambio) o en mensajes de chat (el cuerpo es contenido de
 * usuario, no traducible: `"$authorName: $preview"`), donde se sigue usando
 * [title]/[message] tal cual, igual que antes.
 */
@Serializable
@Immutable
data class NotificationResponse(
    val id: String,
    val memberId: String,
    val taskId: String,
    val title: String,
    val message: String,
    val createdAt: Long = 0,
    val read: Boolean = false,
    val titleKey: String? = null,
    val messageKey: String? = null,
    val messageParams: Map<String, String>? = null,
    /**
     * ID del miembro AUTOR de un mensaje de chat que generó esta
     * notificación (`null` para notificaciones de tarea, y para chat
     * ANTIGUO creado antes de este campo). Junto con
     * `messageParams["preview"]`, permite a [org.taskhub.ui.i18n.NotificationText.message]
     * resolver el nombre del autor CONTRA EL ESTADO ACTUAL de la lista de
     * miembros en vez del `message` ya congelado con el nombre de cuando se
     * envió — así, si el autor abandona/es expulsado del hogar después
     * (`anonymizeMemberMessages` reescribe `messages.authorName`, pero antes
     * de este campo no había forma de re-resolver TAMBIÉN el nombre ya
     * mostrado en notificaciones ya generadas), el nombre mostrado se
     * actualiza solo, sin tener que reescribir notificaciones ya creadas —
     * ronda de deuda aplicable 2026-09-12, punto B10.
     */
    val authorMemberId: String? = null
)

// ── Reward DTOs ────────────────────────────────────────────

/** Documento `households/{householdId}/rewards/{id}`. Recompensa canjeable por puntos. */
@Immutable
@Serializable
data class RewardResponse(
    val id: String,
    val householdId: String,
    val title: String,
    val description: String = "",
    val cost: Int = 0,
    val icon: String = "🎁",
    val createdBy: String = "",
    val createdAt: Long = 0
)

/**
 * Documento `households/{householdId}/rewardRedemptions/{id}`.
 * Registro histórico de un canje de recompensa (puntos ya descontados al
 * miembro en el momento del canje).
 */
@Serializable
data class RewardRedemption(
    val id: String,
    val rewardId: String,
    val memberId: String,
    val redeemedAt: Long = 0,
    val pointsSpent: Int = 0
)


