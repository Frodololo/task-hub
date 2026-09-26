// DTOs del protocolo "callable HTTPS" de Firebase Cloud Functions — ver
// `docs/recurrencia-backend-cloud-functions-diseno-2026-09-11.md`, sección 3.
// Wire format: `{ "data": T }` de ida, `{ "result": R }` (éxito) o
// `{ "error": {status, message} }` (fallo) de vuelta — ver [CloudFunctionsClient].
package org.taskhub.network.models

import kotlinx.serialization.Serializable

@Serializable
data class CompleteRecurringTaskRequest(
    val householdId: String,
    val taskId: String,
    val memberId: String,
    /**
     * Concurrencia optimista opcional: `lastCompletedDate` que el cliente
     * tenía cargado antes de completar. Nombrado `expectedLastCompletedDate`
     * (no `expectedUpdateTime` como en el borrador de diseño, sección 2.1) para
     * calzar EXACTO con lo que `functions/src/completeRecurringTask.ts` lee de
     * `request.data` — esa función documenta en su propio KDoc la desviación
     * deliberada respecto al nombre de campo del borrador de diseño (sección
     * 4, ya implementada así en la fase 2a).
     */
    val expectedLastCompletedDate: Long? = null
)

/** Respuesta compartida por `completeRecurringTask` y `completeAssignment` (esta última añade `assignmentId` en el wire, ignorado aquí vía `ignoreUnknownKeys`). */
@Serializable
data class TaskCompletionFunctionResult(
    val completedAt: Long,
    val pointsAwarded: Int,
    val onTime: Boolean,
    val nextDueAt: Long? = null
)

@Serializable
data class CompleteAssignmentRequest(
    val householdId: String,
    val taskId: String,
    val assignmentId: String
)

@Serializable
data class ReassignTaskCompletionRequest(
    val householdId: String,
    val taskId: String,
    val newMemberId: String
)

@Serializable
data class ReassignTaskCompletionResult(
    val previousMemberId: String? = null,
    val pointsTransferred: Int
)

@Serializable
data class UndoTaskCompletionRequest(
    val householdId: String,
    val taskId: String,
    val completedAt: Long
)

@Serializable
data class UndoTaskCompletionResult(val reverted: Boolean)

@Serializable
data class RedeemRewardRequest(
    val householdId: String,
    val rewardId: String,
    val memberId: String
)

/** El coste real (`pointsSpent`) lo decide SIEMPRE el servidor leyendo `rewards/{rewardId}` — ver `functions/src/redeemReward.ts`. */
@Serializable
data class RedeemRewardResponse(
    val redemptionId: String,
    val pointsSpent: Int,
    val redeemedAt: Long,
    val memberNewTotal: Int
)

@Serializable
data class DonatePointsRequest(
    val householdId: String,
    val fromMemberId: String,
    val toMemberId: String,
    val amount: Int
)

@Serializable
data class DonatePointsResponse(
    val donorNewTotal: Int,
    val receptorNewTotal: Int
)

/** Envoltorio genérico del protocolo callable: `{ "data": T } → { "result": R }`. */
@Serializable
data class CallableRequest<T>(val data: T)

@Serializable
data class CallableResult<R>(val result: R)

/**
 * Forma del wire de error (`{ "error": {status, message} }`) — documental:
 * [CloudFunctionsClient] no la parsea a mano porque el `HttpClient` compartido
 * ya intercepta cualquier respuesta >=400 con el validador de
 * [FirestoreClient] (misma forma estructural que [FirestoreErrorBody], ver
 * KDoc de [CloudFunctionsClient.call]).
 */
@Serializable
data class CallableError(val error: CallableErrorBody)

@Serializable
data class CallableErrorBody(val status: String, val message: String)
