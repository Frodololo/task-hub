/**
 * Capa REST de Firestore para recompensas y sus canjes (alta/baja de
 * recompensas, lectura de canjes). Consumida por `ScreenModel`s de la UI de
 * recompensas.
 */
package org.taskhub.network

import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.Clock
import org.taskhub.network.models.RewardRedemption
import org.taskhub.network.models.RewardResponse
import org.taskhub.storage.TaskCache

/**
 * Recompensas de un hogar (subcolección `households/{id}/rewards` y
 * `rewardRedemptions`). Extraído de [FirestoreRepository] (ver
 * docs/refactor-arquitectura-2026-08-31.md, punto 6, fase 2.2). Lógica movida
 * tal cual, sin cambios de comportamiento.
 *
 * `redeemReward` NO vive aquí: delega en la Cloud Function transaccional
 * `redeemReward` (kanban "CF redeemReward") — ver
 * [FirestoreRepository.redeemReward]. Este repo ya no escribe
 * `rewardRedemptions`, solo lo lee (para historial/anonimización).
 */
/**
 * Repositorio REST de recompensas de un hogar: alta/baja de recompensas y
 * lectura de canjes. Delega auth y manejo de errores en [FirestoreClient].
 */
class RewardsRepository(
    private val baseUrl: String,
    private val firestoreClient: FirestoreClient,
    private val taskCache: TaskCache
) {
    private val client = firestoreClient.client

    private suspend fun HttpRequestBuilder.withAuth() = with(firestoreClient) { withAuth() }
    private fun extractDocId(resourceName: String, operation: String): String =
        firestoreClient.extractDocId(resourceName, operation)

    /**
     * Lista todas las recompensas de un hogar. Cache-first ante fallo (ronda
     * de deuda aplicable 2026-09-12, punto B11): antes usaba
     * `orDefault(emptyList())`, que no distingue "el hogar de verdad no
     * tiene recompensas" de "no se pudo leer" — un fallo de red puntual
     * vaciaba la lista de recompensas canjeables en vez de mostrar la última
     * foto conocida.
     */
    suspend fun getRewards(householdId: String): List<RewardResponse> {
        return try {
            val documents = client.listAllDocuments(
                "$baseUrl/households/$householdId/rewards"
            ) {
                withAuth()
            }
            val rewards = documents.map { doc -> FirestoreParsers.toRewardResponse(doc, householdId) }
            taskCache.cacheRewards(householdId, rewards)
            rewards
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            taskCache.getCachedRewards(householdId) ?: emptyList()
        }
    }

    /** Crea una recompensa nueva en el hogar. Requiere auth (escritura). */
    suspend fun createReward(
        householdId: String,
        title: String,
        description: String,
        cost: Int,
        icon: String,
        createdBy: String
    ): RewardResponse {
        val now = Clock.System.now().toEpochMilliseconds()

        val fields = mapOf(
            "householdId" to FirestoreValue(stringValue = householdId),
            "title" to FirestoreValue(stringValue = title),
            "description" to FirestoreValue(stringValue = description),
            "cost" to FirestoreValue(integerValue = cost.toString()),
            "icon" to FirestoreValue(stringValue = icon),
            "createdBy" to FirestoreValue(stringValue = createdBy),
            "createdAt" to FirestoreValue(integerValue = now.toString())
        )

        val response: FirestoreDocumentResponse = client.post(
            "$baseUrl/households/$householdId/rewards"
        ) {
            withAuth()
            contentType(ContentType.Application.Json)
            setBody(FirestoreDocument(fields))
        }.body()

        val id = extractDocId(response.name, "createReward")
        taskCache.clearRewards(householdId)
        return RewardResponse(id, householdId, title, description, cost, icon, createdBy, now)
    }

    /** Borra una recompensa. Requiere auth (escritura). */
    suspend fun deleteReward(householdId: String, rewardId: String) {
        client.delete("$baseUrl/households/$householdId/rewards/$rewardId") {
            withAuth()
        }
        taskCache.clearRewards(householdId)
    }

    /**
     * Lista todos los canjes de recompensas de un hogar. Cache-first ante
     * fallo — mismo motivo que [getRewards] (punto B11).
     */
    suspend fun getRewardRedemptions(householdId: String): List<RewardRedemption> {
        return try {
            val documents = client.listAllDocuments(
                "$baseUrl/households/$householdId/rewardRedemptions"
            ) {
                withAuth()
            }
            val redemptions = documents.map { doc -> FirestoreParsers.toRewardRedemption(doc) }
            taskCache.cacheRewardRedemptions(householdId, redemptions)
            redemptions
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            taskCache.getCachedRewardRedemptions(householdId) ?: emptyList()
        }
    }

    /**
     * Reescribe `memberId` a [anonymizedMemberId] en los canjes de
     * recompensa de los miembros en [memberIds] — mismo motivo y patrón que
     * [org.taskhub.network.TaskRepository.anonymizeMemberTaskHistory] (panel
     * v14 2026-09-20, Experto 10, IMPORTANTE): `rewardRedemptions` solo
     * guarda el `memberId` crudo, sin nombre desnormalizado, así que quedaba
     * expuesto para siempre en hogares compartidos tras
     * `leaveHousehold`/`deleteMember`. Best-effort por registro.
     */
    /** @return `true` si TODOS los canjes se anonimizaron correctamente (ver KDoc de [HouseholdRepository.anonymizeMemberMessages], panel v16 hallazgo I7). */
    suspend fun anonymizeMemberRedemptions(householdId: String, memberIds: Set<String>, anonymizedMemberId: String): Boolean {
        if (memberIds.isEmpty()) return true
        val redemptions = try {
            getRewardRedemptions(householdId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return false
        }
        var allOk = true
        redemptions.filter { it.memberId in memberIds }.forEach { redemption ->
            try {
                client.patch("$baseUrl/households/$householdId/rewardRedemptions/${redemption.id}") {
                    withAuth()
                    with(firestoreClient) { updateMaskFieldPaths("memberId") }
                    contentType(ContentType.Application.Json)
                    setBody(FirestoreDocument(mapOf("memberId" to FirestoreValue(stringValue = anonymizedMemberId))))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Se prioriza anonimizar el resto de canjes (best-effort),
                // pero el fallo se acumula en el resultado — ver KDoc arriba.
                allOk = false
            }
        }
        taskCache.clearRewardRedemptions(householdId)
        return allOk
    }
}
