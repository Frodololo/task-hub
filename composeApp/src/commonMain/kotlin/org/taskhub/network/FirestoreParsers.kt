/**
 * Traducción de documentos crudos de la Firestore REST API
 * ([FirestoreDocumentResponse], campos tipados como [FirestoreValue]) a los
 * modelos de dominio de `network.models`. Usado por todos los repositorios
 * de `network/` al leer colecciones/documentos.
 */
package org.taskhub.network

import org.taskhub.network.models.AssignmentSlot
import org.taskhub.network.models.CommentResponse
import org.taskhub.network.models.HouseholdResponse
import org.taskhub.network.models.MemberResponse
import org.taskhub.network.models.MessageResponse
import org.taskhub.network.models.NotificationResponse
import org.taskhub.network.models.RewardRedemption
import org.taskhub.network.models.RewardResponse
import org.taskhub.network.models.Subtask
import org.taskhub.network.models.spaceTypeFromFirestoreValue
import org.taskhub.network.models.TaskAssignmentResponse
import org.taskhub.network.models.TaskHistoryResponse
import org.taskhub.network.models.TaskResponse

/**
 * Parseo puro de respuestas de la Firestore REST API. Sin I/O — testable
 * directamente en `commonTest`.
 */
object FirestoreParsers {

    /**
     * Extrae el ID de documento del "resource name" completo que devuelve Firestore.
     *
     * Cuando esto se ejecuta, el status HTTP ya ha sido validado por el
     * [io.ktor.client.plugins.HttpResponseValidator] instalado en el cliente ktor — cualquier
     * 4xx/5xx sale como [FirestoreException] antes de que se llegue a parsear el body. Por
     * tanto, un `name` en blanco aquí significa que una respuesta 2xx vino genuinamente sin el
     * campo 'name' esperado, lo cual apunta a un bug en [operation], no a un fallo de transporte.
     */
    fun extractDocId(resourceName: String, operation: String): String {
        if (resourceName.isBlank()) {
            throw IllegalStateException(
                "Firestore: la operación '$operation' devolvió una respuesta 200 sin el campo " +
                "'name' esperado en el documento. No es un error de permisos ni de red — " +
                "revisa la respuesta de '$operation'."
            )
        }
        return resourceName.substringAfterLast("/")
    }

    /**
     * Parsea un documento `households/{id}`. Si [doc] no trae `name` (p. ej.
     * viene de un `runQuery` que no lo incluye) se usa [knownId] como
     * respaldo; si tampoco hay [knownId], es un fallo real del llamador.
     */
    fun toHouseholdResponse(
        doc: FirestoreDocumentResponse,
        knownId: String? = null,
        operation: String = "getHousehold"
    ): HouseholdResponse {
        val f = doc.fields
        val id = if (doc.name.isNotBlank()) extractDocId(doc.name, operation)
                 else knownId ?: throw IllegalStateException(
                     "Firestore: '$operation' devolvió un documento de hogar sin 'name' y sin ID conocido"
                 )
        return HouseholdResponse(
            id = id,
            name = f["name"]?.stringValue ?: "",
            inviteCode = f["inviteCode"]?.stringValue ?: "",
            createdAt = f["createdAt"]?.integerValue?.toLongOrNull() ?: 0L,
            updatedAt = f["updatedAt"]?.integerValue?.toLongOrNull() ?: 0L,
            isPersonal = f["isPersonal"]?.booleanValue ?: false,
            ownerId = f["ownerId"]?.stringValue ?: "",
            timezone = f["timezone"]?.stringValue,
            spaceType = spaceTypeFromFirestoreValue(f["spaceType"]?.stringValue)
        )
    }

    /** Parsea un documento `households/{id}/members/{memberId}`. */
    fun toMemberResponse(
        doc: FirestoreDocumentResponse,
        householdId: String,
        operation: String = "getMembers"
    ): MemberResponse {
        val f = doc.fields
        val id = if (doc.name.isNotBlank()) extractDocId(doc.name, operation)
                 else throw IllegalStateException(
                     "Firestore: '$operation' devolvió un documento de miembro sin 'name'"
                 )
        return MemberResponse(
            id = id,
            householdId = f["householdId"]?.stringValue ?: householdId,
            displayName = f["displayName"]?.stringValue ?: "",
            avatarUrl = f["avatarUrl"]?.stringValue,
            role = f["role"]?.stringValue ?: "child",
            totalPoints = f["totalPoints"]?.integerValue?.toIntOrNull() ?: 0,
            joinedAt = f["joinedAt"]?.integerValue?.toLongOrNull() ?: 0L,
            userId = f["userId"]?.stringValue,
            currentStreak = f["currentStreak"]?.integerValue?.toIntOrNull() ?: 0,
            bestStreak = f["bestStreak"]?.integerValue?.toIntOrNull() ?: 0,
            lastStreakDate = f["lastStreakDate"]?.integerValue?.toLongOrNull() ?: 0L,
            leftAt = f["leftAt"]?.integerValue?.toLongOrNull() ?: 0L,
            appreciationGiven = f["appreciationGiven"]?.integerValue?.toIntOrNull() ?: 0,
            appreciationWeekStart = f["appreciationWeekStart"]?.integerValue?.toLongOrNull() ?: 0L
        )
    }

    /**
     * Parsea un documento `households/{id}/tasks/{taskId}`, incluyendo listas
     * anidadas (`subtasks`, `assignmentRotation`) que Firestore representa
     * como `arrayValue` de `mapValue`.
     */
    fun toTaskResponse(doc: FirestoreDocumentResponse, householdId: String): TaskResponse {
        val f = doc.fields
        return TaskResponse(
            id = extractDocId(doc.name, "getTasks"),
            householdId = f["householdId"]?.stringValue ?: householdId,
            createdBy = f["createdBy"]?.stringValue ?: "",
            title = f["title"]?.stringValue ?: "",
            description = f["description"]?.stringValue ?: "",
            points = f["points"]?.integerValue?.toIntOrNull() ?: 10,
            frequency = f["frequency"]?.stringValue ?: "once",
            recurrenceDays = f["recurrenceDays"]?.arrayValue?.values
                ?.mapNotNull { it.integerValue?.toIntOrNull() } ?: emptyList(),
            recurrenceDay = f["recurrenceDay"]?.integerValue?.toIntOrNull(),
            tags = f["tags"]?.arrayValue?.values
                ?.mapNotNull { it.stringValue } ?: emptyList(),
            subtasks = f["subtasks"]?.arrayValue?.values
                ?.mapNotNull { stValue ->
                    val sf = stValue.mapValue?.fields ?: return@mapNotNull null
                    val sid = sf["id"]?.stringValue ?: return@mapNotNull null
                    val stext = sf["text"]?.stringValue ?: return@mapNotNull null
                    val scompleted = sf["completed"]?.booleanValue ?: false
                    Subtask(id = sid, text = stext, completed = scompleted)
                } ?: emptyList(),
            penaltyMode = f["penaltyMode"]?.stringValue,
            penaltyValue = f["penaltyValue"]?.integerValue?.toIntOrNull() ?: 0,
            penaltyInterval = f["penaltyInterval"]?.stringValue ?: "day",
            penaltyMax = f["penaltyMax"]?.integerValue?.toIntOrNull() ?: 0,
            dueDate = f["dueDate"]?.integerValue?.toLongOrNull() ?: 0L,
            lastCompletedDate = f["lastCompletedDate"]?.integerValue?.toLongOrNull(),
            completedBy = f["completedBy"]?.stringValue,
            assignmentRotation = f["assignmentRotation"]?.arrayValue?.values
                ?.mapNotNull { slotValue ->
                    val sf = slotValue.mapValue?.fields ?: return@mapNotNull null
                    val dow = sf["dayOfWeek"]?.integerValue?.toIntOrNull() ?: return@mapNotNull null
                    val mid = sf["memberId"]?.stringValue ?: return@mapNotNull null
                    AssignmentSlot(dayOfWeek = dow, memberId = mid)
                } ?: emptyList(),
            nextDueAt = f["nextDueAt"]?.integerValue?.toLongOrNull(),
            createdAt = f["createdAt"]?.integerValue?.toLongOrNull() ?: 0L,
            updatedAt = f["updatedAt"]?.integerValue?.toLongOrNull() ?: 0L
        )
    }

    /**
     * Parsea un documento `.../tasks/{taskId}/assignments/{assignmentId}`.
     * [TaskAssignmentResponse.updateTime] se conserva tal cual (el timestamp
     * de escritura que da Firestore) para poder usarlo como control de
     * concurrencia optimista al completar una asignación.
     */
    fun toTaskAssignmentResponse(
        doc: FirestoreDocumentResponse,
        taskId: String
    ): TaskAssignmentResponse {
        val f = doc.fields
        return TaskAssignmentResponse(
            id = extractDocId(doc.name, "getAssignments"),
            taskId = f["taskId"]?.stringValue ?: taskId,
            memberId = f["memberId"]?.stringValue ?: "",
            mandatory = f["mandatory"]?.booleanValue ?: false,
            dueDate = f["dueDate"]?.integerValue?.toLongOrNull() ?: 0L,
            status = f["status"]?.stringValue ?: "assigned",
            completedAt = f["completedAt"]?.integerValue?.toLongOrNull(),
            pointsAwarded = f["pointsAwarded"]?.integerValue?.toIntOrNull(),
            onTime = f["onTime"]?.booleanValue,
            assignedAt = f["assignedAt"]?.integerValue?.toLongOrNull() ?: 0L,
            googleEventId = f["googleEventId"]?.stringValue,
            updateTime = doc.updateTime
        )
    }

    /** Parsea un documento `.../tasks/{taskId}/comments/{commentId}`. */
    fun toCommentResponse(doc: FirestoreDocumentResponse): CommentResponse {
        val f = doc.fields
        return CommentResponse(
            id = extractDocId(doc.name, "getComments"),
            authorName = f["authorName"]?.stringValue ?: "",
            text = f["text"]?.stringValue ?: "",
            createdAt = f["createdAt"]?.integerValue?.toLongOrNull() ?: 0L,
            memberId = f["memberId"]?.stringValue
        )
    }

    /** Parsea un documento del historial de compleciones de una tarea (`taskHistory`). */
    fun toTaskHistoryResponse(doc: FirestoreDocumentResponse): TaskHistoryResponse {
        val f = doc.fields
        return TaskHistoryResponse(
            id = extractDocId(doc.name, "getTaskHistory"),
            taskId = f["taskId"]?.stringValue ?: "",
            memberId = f["memberId"]?.stringValue ?: "",
            points = f["points"]?.integerValue?.toIntOrNull() ?: 0,
            completedAt = f["completedAt"]?.integerValue?.toLongOrNull() ?: 0L,
            onTime = f["onTime"]?.booleanValue ?: true,
            pointsApplied = f["pointsApplied"]?.booleanValue ?: true
        )
    }

    /**
     * Parsea un documento de notificación. `titleKey`/`messageKey`/
     * `messageParams` habilitan el render por-lector (i18n dinámico); ver el
     * comentario en línea sobre notificaciones antiguas sin esos campos.
     */
    fun toNotificationResponse(doc: FirestoreDocumentResponse): NotificationResponse {
        val f = doc.fields
        return NotificationResponse(
            id = extractDocId(doc.name, "getNotifications"),
            memberId = f["memberId"]?.stringValue ?: "",
            taskId = f["taskId"]?.stringValue ?: "",
            title = f["title"]?.stringValue ?: "",
            message = f["message"]?.stringValue ?: "",
            createdAt = f["createdAt"]?.integerValue?.toLongOrNull() ?: 0L,
            read = f["read"]?.booleanValue ?: false,
            // Ausentes en notificaciones ANTIGUAS (creadas antes del rediseño
            // de idioma por-lector) — null hace que el render use title/message
            // tal cual, igual que antes (retrocompatible).
            titleKey = f["titleKey"]?.stringValue,
            messageKey = f["messageKey"]?.stringValue,
            messageParams = f["messageParams"]?.mapValue?.fields
                ?.mapValues { it.value.stringValue ?: "" },
            authorMemberId = f["authorMemberId"]?.stringValue
        )
    }

    /** Parsea un documento `households/{id}/rewards/{rewardId}`. */
    fun toRewardResponse(doc: FirestoreDocumentResponse, householdId: String): RewardResponse {
        val f = doc.fields
        return RewardResponse(
            id = extractDocId(doc.name, "getRewards"),
            householdId = f["householdId"]?.stringValue ?: householdId,
            title = f["title"]?.stringValue ?: "",
            description = f["description"]?.stringValue ?: "",
            cost = f["cost"]?.integerValue?.toIntOrNull() ?: 0,
            icon = f["icon"]?.stringValue ?: "🎁",
            createdBy = f["createdBy"]?.stringValue ?: "",
            createdAt = f["createdAt"]?.integerValue?.toLongOrNull() ?: 0L
        )
    }

    /** Parsea un documento `households/{id}/rewardRedemptions/{redemptionId}`. */
    fun toRewardRedemption(doc: FirestoreDocumentResponse): RewardRedemption {
        val f = doc.fields
        return RewardRedemption(
            id = extractDocId(doc.name, "getRewardRedemptions"),
            rewardId = f["rewardId"]?.stringValue ?: "",
            memberId = f["memberId"]?.stringValue ?: "",
            redeemedAt = f["redeemedAt"]?.integerValue?.toLongOrNull() ?: 0L,
            pointsSpent = f["pointsSpent"]?.integerValue?.toIntOrNull() ?: 0
        )
    }

    /** Parsea un documento de mensaje del chat de grupo del hogar. */
    fun toMessageResponse(doc: FirestoreDocumentResponse): MessageResponse {
        val f = doc.fields
        return MessageResponse(
            id = extractDocId(doc.name, "getMessages"),
            memberId = f["memberId"]?.stringValue ?: "",
            authorName = f["authorName"]?.stringValue ?: "",
            text = f["text"]?.stringValue ?: "",
            createdAt = f["createdAt"]?.integerValue?.toLongOrNull() ?: 0L
        )
    }
}
