// Miembros/perfiles/puntos — ver KDoc de [MemberRepository] más abajo para el
// detalle del alcance (qué se quedó en FirestoreRepository y por qué).

package org.taskhub.network

import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import org.taskhub.network.models.MemberResponse
import org.taskhub.network.models.UserProfile
import org.taskhub.storage.TaskCache

/**
 * Lanzada por [MemberRepository.addMemberPoints] cuando se pide un [floor] y
 * el resultado del descuento lo incumpliría — ver su KDoc. Vive a nivel de
 * paquete (no anidada en [FirestoreRepository]) para que [MemberRepository]
 * pueda lanzarla sin depender de la fachada (evita el ciclo
 * `MemberRepository` → `FirestoreRepository` → `MemberRepository`, mismo
 * motivo que el resto de este archivo, ver su KDoc de cabecera).
 */
class InsufficientBalanceException(message: String) : Exception(message)

/**
 * Miembros (colección `members`, subcolección de `households/{id}`), perfiles
 * globales (`users/{userId}`) y puntos (agradecer/donar/logros). Extraído de
 * [FirestoreRepository] (ver docs/review-panel-expertos-v3-2026-09-01.md,
 * Experto 7 #1, y `HouseholdRepository` como precedente del mismo patrón).
 * Lógica movida tal cual, sin cambios de comportamiento.
 *
 * `getLocalId`/`currentUserIdentities` viven en [FirestoreClient] (no en
 * [FirestoreRepository]) precisamente para que este repo pueda depender de
 * [FirestoreClient] directamente sin crear un ciclo
 * `MemberRepository` → `FirestoreRepository` → `MemberRepository` — eso es lo
 * que permite registrar este repo como `single` de Koin en vez de construirlo
 * a mano con lambdas dentro de la fachada (panel v7, #16).
 *
 * NO incluye `completeTask`/`completeAssignment`/`reassignTaskCompletion`/
 * `redeemReward`/`donatePoints`: son flujos que delegan la transacción
 * completa en una Cloud Function (o, en el caso de `completeTask`, orquestan
 * Task+Member) y se quedan en la fachada, que es la capa que conoce
 * `cloudFunctionsClient` — igual que `deleteHousehold`/`leaveHousehold` se
 * quedaron fuera de [HouseholdRepository] por depender de
 * `getMembers`/`currentMemberCache`, que ahora viven aquí. `appreciateMember`
 * SÍ sigue aquí: no existe todavía una Cloud Function equivalente (kanban
 * "CF donatePoints/appreciateMember").
 */
class MemberRepository(
    private val baseUrl: String,
    private val firestoreClient: FirestoreClient,
    private val taskCache: TaskCache
) {
    private val client = firestoreClient.client

    // Reintentos ante conflicto de concurrencia optimista (ver `addMemberPoints`/
    // `addMemberAchievement`) — alias local, mismo motivo que en FirestoreRepository.
    private val OPTIMISTIC_WRITE_MAX_RETRIES = FirestoreClient.OPTIMISTIC_WRITE_MAX_RETRIES

    private suspend fun HttpRequestBuilder.withAuth() = with(firestoreClient) { withAuth() }
    private fun HttpRequestBuilder.updateMaskFieldPaths(vararg fields: String) =
        with(firestoreClient) { updateMaskFieldPaths(*fields) }
    private fun HttpRequestBuilder.updateMaskFieldPaths(fields: Collection<String>) =
        with(firestoreClient) { updateMaskFieldPaths(fields) }
    private fun extractDocId(resourceName: String, operation: String): String =
        firestoreClient.extractDocId(resourceName, operation)
    private suspend fun ensureAuth() = firestoreClient.ensureAuth()

    // ── Miembro actual (single source of truth, ver resolveCurrentMember) ──
    // Memoiza el resultado por hogar: sin esto, cada ScreenModel que necesita
    // saber "quién soy en este hogar" (TaskScreenModel, CalendarSyncManager,
    // HouseholdScreen) repite la misma resolución de identidad (+ getMembers)
    // de forma independiente y podría, en teoría, no coincidir si la lista de
    // miembros cambia entre una llamada y otra dentro de la misma sesión.
    private val currentMemberMutex = Mutex()
    private val currentMemberCache = mutableMapOf<String, String>()

    /**
     * Invalida la entrada de [currentMemberCache] de un hogar. La llaman
     * `deleteHousehold`/`leaveHousehold` (se quedan en [FirestoreRepository],
     * ver KDoc de la clase) tras borrar/abandonar el hogar.
     *
     * Protegida por [currentMemberMutex] (mismo mutex que [resolveCurrentMember]):
     * antes era una escritura (`MutableMap.remove`) totalmente desprotegida
     * sobre una `mutableMapOf` no thread-safe, mientras que [resolveCurrentMember]
     * sí serializaba sus propias lecturas/escrituras — una invalidación
     * concurrente con una resolución en curso podía dejar el mapa en un
     * estado inconsistente (panel de revisión 2026-09-03/04, Experto 6:
     * matiz sobre v5, que solo documentaba lecturas fuera de mutex, no esta
     * escritura).
     */
    suspend fun invalidateCurrentMember(householdId: String) {
        currentMemberMutex.withLock {
            currentMemberCache.remove(householdId)
        }
    }

    /**
     * Invalida TODA la caché de miembro actual (todos los hogares). La llama
     * `GoogleAuthManager.signOut()` — sin esto, en un dispositivo familiar
     * compartido, tras cerrar sesión de un perfil e iniciar con otro, el
     * `memberId` cacheado del perfil anterior podía seguir resolviéndose para
     * el mismo hogar, atribuyendo compleciones/puntos al miembro equivocado
     * (panel de revisión 2026-09-04, Experto 9/10, NUEVO).
     */
    suspend fun invalidateAllCurrentMembers() {
        currentMemberMutex.withLock {
            currentMemberCache.clear()
        }
    }

    // ────────────────────────────────────────────────────────
    //  Household membership
    // ────────────────────────────────────────────────────────

    /**
     * Check if any of the given [userIds] (identidades del usuario) ya es
     * miembro del hogar. Acepta una lista porque el UID activo de esta sesión
     * y el UID de Google persistido pueden no coincidir en el primer frame
     * tras el login, y el miembro pudo crearse con cualquiera de los dos.
     *
     * SIN caché de respaldo propia a propósito (ronda de deuda aplicable
     * 2026-09-12, punto B11 — evaluado y descartado): esta función gatea
     * acceso (p.ej. si se puede entrar a un hogar), así que "no se pudo
     * comprobar" debe FALLAR CERRADO (`false`, tratar como no-miembro) en vez
     * de servir un `true` cacheado potencialmente obsoleto — un miembro
     * expulsado hace un momento no debe seguir pasando esta comprobación
     * solo porque el dispositivo aún tiene la respuesta anterior en caché.
     * `getMembers` (que sí cachea) puede seguir sirviendo offline para
     * lectura/listado; esta capa de decisión de acceso no debe heredar esa
     * caché.
     */
    suspend fun isMember(householdId: String, userIds: List<String>): Boolean = orDefault(false) {
        getMembers(householdId).any { it.userId != null && it.userId in userIds }
    }

    /** ¿El usuario actual ya es miembro de este hogar? */
    suspend fun isCurrentUserMember(householdId: String): Boolean =
        isMember(householdId, firestoreClient.currentUserIdentities())

    // ────────────────────────────────────────────────────────
    //  Members (subcollection under households/{id})
    // ────────────────────────────────────────────────────────

    /**
     * List members of a household. Falls back to local cache if offline.
     *
     * Un 404/403 es una señal DEFINITIVA (hogar borrado / acceso perdido, igual
     * que en `getHousehold`) y se relanza en vez de devolver la caché stale;
     * solo fallos de transporte/servidor caen a caché.
     */
    suspend fun getMembers(householdId: String): List<MemberResponse> {
        return try {
            val documents = client.listAllDocuments("$baseUrl/households/$householdId/members") {
                withAuth()
            }

            val members = documents
                .map { toMemberResponse(it, householdId) }
                .filter { it.leftAt == 0L }  // ocultar miembros que abandonaron (soft-delete)
            taskCache.cacheMembers(householdId, members)
            members
        } catch (e: CancellationException) {
            throw e
        } catch (e: FirestoreException) {
            if (e.statusCode == 404 || e.statusCode == 403) throw e
            taskCache.getCachedMembers(householdId) ?: throw e
        } catch (e: Exception) {
            taskCache.getCachedMembers(householdId) ?: throw e
        }
    }

    /** Add a member to a household. Requires auth (write). */
    suspend fun createMember(
        householdId: String,
        displayName: String,
        role: String = "child",
        avatarUrl: String? = null,
        userId: String? = null,
        inviteCode: String? = null
    ): MemberResponse {
        // Deduplicación: si este usuario ya es miembro del hogar, devolvemos el
        // miembro existente en lugar de crear un duplicado (p. ej. al re-unirse).
        if (userId != null) {
            val existing = try {
                getMembers(householdId).firstOrNull { it.userId == userId }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (existing != null) return existing
        }

        val now = Clock.System.now().toEpochMilliseconds()

        val fields = mutableMapOf<String, FirestoreValue>(
            "householdId" to FirestoreValue(stringValue = householdId),
            "displayName" to FirestoreValue(stringValue = displayName),
            "role" to FirestoreValue(stringValue = role),
            "totalPoints" to FirestoreValue(integerValue = "0"),
            "joinedAt" to FirestoreValue(integerValue = now.toString()),
            "currentStreak" to FirestoreValue(integerValue = "0"),
            "bestStreak" to FirestoreValue(integerValue = "0"),
            "lastStreakDate" to FirestoreValue(integerValue = "0"),
            "appreciationGiven" to FirestoreValue(integerValue = "0"),
            "appreciationWeekStart" to FirestoreValue(integerValue = "0")
        )
        if (avatarUrl != null) {
            fields["avatarUrl"] = FirestoreValue(stringValue = avatarUrl)
        } else {
            fields["avatarUrl"] = FirestoreValue(nullValue = "NULL_VALUE")
        }
        if (userId != null) {
            fields["userId"] = FirestoreValue(stringValue = userId)
        } else {
            fields["userId"] = FirestoreValue(nullValue = "NULL_VALUE")
        }
        // Solo se incluye al auto-unirse: las reglas validan este código
        // contra el inviteCode del hogar para autorizar la creación.
        if (inviteCode != null) {
            fields["inviteCode"] = FirestoreValue(stringValue = inviteCode)
        }

        val response: FirestoreDocumentResponse =
            if (userId != null) {
                // Miembro vinculado a una cuenta → documento keyed por su UID,
                // para que las reglas puedan verificar membresía con exists().
                client.post("$baseUrl/households/$householdId/members") {
                    withAuth()
                    parameter("documentId", userId)
                    contentType(ContentType.Application.Json)
                    setBody(FirestoreDocument(fields))
                }.body()
            } else {
                // Perfil "hijo/a" sin cuenta → ID automático.
                client.post("$baseUrl/households/$householdId/members") {
                    withAuth()
                    contentType(ContentType.Application.Json)
                    setBody(FirestoreDocument(fields))
                }.body()
            }

        val id = extractDocId(response.name, "createMember")
        taskCache.clearMembers(householdId)

        // Reclamar el perfil global del usuario (base del "perfilado creciente").
        if (userId != null) {
            try {
                upsertUserProfile(userId = userId, displayName = displayName, avatarUrl = avatarUrl)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // No crítico: el perfil se puede reclamar más tarde.
            }
        }

        return MemberResponse(id, householdId, displayName, avatarUrl, role, 0, now,
            userId = userId,
            currentStreak = 0, bestStreak = 0, lastStreakDate = 0)
    }

    /**
     * Resuelve el ID del miembro que representa al usuario actual en un hogar.
     *
     * Orden de preferencia:
     *   1. Miembro cuyo [MemberResponse.userId] coincide con el usuario autenticado (localId).
     *   2. El primer miembro existente (espacios Personales legados sin userId,
     *      u hogares donde el usuario actual aún no tiene userId asignado).
     *   3. Si no hay ningún miembro, crea uno "Yo" (admin) vinculado al usuario.
     *
     * Garantiza que completar tareas nunca falle con "No se ha identificado al
     * miembro actual", sin importar cómo se haya navegado hasta la tarea.
     *
     * Memoizada por hogar (single source of truth, ver [currentMemberCache]):
     * llamadas repetidas dentro de la misma sesión devuelven el mismo valor
     * sin repetir la resolución de identidad ni la llamada a [getMembers].
     * La entrada se invalida al abandonar el hogar o borrarlo (ver
     * [invalidateCurrentMember]).
     */
    suspend fun resolveCurrentMember(householdId: String): String {
        currentMemberCache[householdId]?.let { return it }
        return currentMemberMutex.withLock {
            currentMemberCache[householdId]?.let { return@withLock it }
            val resolved = resolveCurrentMemberUncached(householdId)
            // No se memoiza un resultado vacío: sería un fallo transitorio
            // (getMembers y createMember fallaron), no una identidad real.
            if (resolved.isNotBlank()) {
                currentMemberCache[householdId] = resolved
            }
            resolved
        }
    }

    private suspend fun resolveCurrentMemberUncached(householdId: String): String {
        // Asegura autenticación para que getLocalId() devuelva el UID real
        // (persistido) y no null en el primer arranque.
        ensureAuth()
        val localId = firestoreClient.getLocalId()
        val members = try {
            getMembers(householdId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }

        // 1-2. Resolución pura y testeada (ver [MemberResolutionRules] y su
        // test): miembro vinculado a cualquiera de las identidades del
        // usuario, o el primer miembro SIN cuenta vinculada como fallback.
        // Si no hay ningún perfil sin reclamar, se cae al paso 3 de abajo
        // (crear un miembro "Yo" nuevo) en vez de adivinar.
        val identities = firestoreClient.currentUserIdentities()
        MemberResolutionRules.resolveExistingMemberId(members, identities)?.let { return it }

        // 3. Sin miembros: crear uno "Yo" vinculado al usuario actual.
        // Si createMember falla (p. ej. Firestore devuelve una respuesta sin
        // 'name', o la creación es rechazada por las reglas), no se propaga la
        // excepción: se usa el localId como fallback para que abrir un hogar
        // sin miembros nunca crashee la UI.
        return try {
            createMember(
                householdId = householdId,
                displayName = "Yo",
                role = "admin",
                userId = localId
            ).id
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            localId ?: ""
        }
    }

    /**
     * Asegura que el espacio Personal tenga un miembro "Yo".
     * Sin él, completar tareas falla con "No se ha identificado al miembro actual".
     * Idempotente: si ya hay miembros, devuelve el primero sin crear nada.
     * Cubre también la migración de espacios Personales creados antes de este fix.
     * Delega en [resolveCurrentMember], que además vincula al usuario autenticado.
     */
    suspend fun ensurePersonalMember(householdId: String): String =
        resolveCurrentMember(householdId)

    /**
     * Elimina (da de baja) a un miembro: soft-delete vía `leftAt` + anonimiza
     * sus datos personales (nombre, avatar) — ver hallazgo de privacidad "el
     * borrado no borra datos reales" (docs/review-panel-expertos-v3-2026-09-01.md,
     * Experto 10 #4). No es un borrado real del documento (a diferencia de
     * `deleteHousehold`): `taskHistory`/`members/{mid}/achievements`
     * referencian este `memberId`, y borrar el documento dejaría esas
     * referencias huérfanas y rompería StatsScreen. `totalPoints`/`role`/
     * rachas se conservan (siguen siendo parte del histórico de puntos del
     * hogar); solo se anonimizan los campos que identifican a la persona.
     * Requires auth (write).
     *
     * NO purga las referencias a este miembro en `assignmentRotation`/
     * asignaciones "assigned" de las tareas del hogar — eso lo hace
     * `FirestoreRepository.deleteMember` (el facade que envuelve esta
     * función) después del soft-delete, ver su KDoc (panel v4, Experto 2
     * hallazgo #2 ALTO).
     */
    suspend fun deleteMember(householdId: String, memberId: String): Boolean {
        val now = Clock.System.now().toEpochMilliseconds()

        val fields = mapOf(
            "leftAt" to FirestoreValue(integerValue = now.toString()),
            // Placeholder fijo (no localizado): igual que el "Yo" por defecto
            // de resolveCurrentMemberUncached, es un valor de datos, no de UI.
            "displayName" to FirestoreValue(stringValue = "Miembro eliminado"),
            "avatarUrl" to FirestoreValue(nullValue = "NULL_VALUE")
        )

        client.patch("$baseUrl/households/$householdId/members/$memberId") {
            withAuth()
            updateMaskFieldPaths("leftAt", "displayName", "avatarUrl")
            contentType(ContentType.Application.Json)
            setBody(FirestoreDocument(fields))
        }
        taskCache.clearMembers(householdId)

        return true
    }

    /**
     * Edita el rol de un miembro ("admin" | "child"). Permite reasignar el rol
     * sin recrear el miembro (parte del "perfilado" estable por usuario).
     */
    suspend fun updateMemberRole(householdId: String, memberId: String, role: String) {
        val fields = mapOf(
            "role" to FirestoreValue(stringValue = role)
        )
        client.patch("$baseUrl/households/$householdId/members/$memberId") {
            withAuth()
            updateMaskFieldPaths("role")
            contentType(ContentType.Application.Json)
            setBody(FirestoreDocument(fields))
        }
        taskCache.clearMembers(householdId)
    }

    // ────────────────────────────────────────────────────────
    //  User profile (perfilado global, colección users/{userId})
    // ────────────────────────────────────────────────────────

    /**
     * Lee el perfil global de un usuario, o null si aún no existe.
     *
     * SIN caché de respaldo propia a propósito (punto B11, evaluado y
     * descartado): `null` ya es un resultado válido y frecuente ("perfil aún
     * no creado", p.ej. justo tras el alta) — cachear haría indistinguibles
     * "no se pudo leer" de "de verdad no existe todavía", que es justo la
     * ambigüedad que esta ronda busca resolver en OTRAS lecturas (rewards/
     * historial), no introducirla aquí. El perfil además se usa para
     * mostrar/editar datos del USUARIO ACTUAL en la sesión en curso — no hay
     * un escenario offline realista de "abrir mi perfil sin red" que
     * justifique el coste de mantener esta caché sincronizada.
     */
    suspend fun getUserProfile(userId: String): UserProfile? = orDefault(null) {
        val response: FirestoreDocumentResponse = client.getWithRetry("$baseUrl/users/$userId") {
            withAuth()
        }.body()
        val f = response.fields
        UserProfile(
            id = userId,
            displayName = f["displayName"]?.stringValue ?: "",
            avatarUrl = f["avatarUrl"]?.stringValue,
            avatarEmoji = f["avatarEmoji"]?.stringValue ?: "",
            bio = f["bio"]?.stringValue ?: "",
            status = f["status"]?.stringValue ?: "",
            createdAt = f["createdAt"]?.integerValue?.toLongOrNull() ?: 0L,
            updatedAt = f["updatedAt"]?.integerValue?.toLongOrNull() ?: 0L
        )
    }

    /**
     * Crea o actualiza el perfil global de un usuario (upsert vía PATCH).
     * Base del "perfilado creciente": añadir foto/bio/etc. luego = añadir campos
     * aquí y en [UserProfile], sin tocar la membresía por hogar.
     */
    suspend fun upsertUserProfile(
        userId: String,
        displayName: String,
        avatarUrl: String? = null,
        avatarEmoji: String = "",
        bio: String = "",
        status: String = ""
    ) {
        val now = Clock.System.now().toEpochMilliseconds()
        val fields = mutableMapOf<String, FirestoreValue>(
            "displayName" to FirestoreValue(stringValue = displayName),
            "updatedAt" to FirestoreValue(integerValue = now.toString())
        )
        if (avatarUrl != null) {
            fields["avatarUrl"] = FirestoreValue(stringValue = avatarUrl)
        } else {
            fields["avatarUrl"] = FirestoreValue(nullValue = "NULL_VALUE")
        }
        fields["avatarEmoji"] = FirestoreValue(stringValue = avatarEmoji)
        fields["bio"] = FirestoreValue(stringValue = bio)
        fields["status"] = FirestoreValue(stringValue = status)

        client.patch("$baseUrl/users/$userId") {
            withAuth()
            updateMaskFieldPaths(fields.keys)
            contentType(ContentType.Application.Json)
            setBody(FirestoreDocument(fields))
        }
    }

    /**
     * Borra el perfil global de un usuario (`users/{userId}`) — parte del
     * flujo "eliminar cuenta" (ver `GoogleAuthManager.deleteAccount`). No
     * borra su membresía en ningún hogar: eso lo hace por separado
     * `deleteHousehold` (espacio Personal) / `leaveHousehold` (hogares
     * compartidos) antes de llamar a esta función.
     */
    suspend fun deleteUserProfile(userId: String) {
        client.delete("$baseUrl/users/$userId") { withAuth() }
    }

    /** Update member streak fields. Requires auth (write). */
    suspend fun updateMemberStreak(
        householdId: String,
        memberId: String,
        currentStreak: Int,
        bestStreak: Int,
        lastStreakDate: Long
    ) {
        val fields = mapOf(
            "currentStreak" to FirestoreValue(integerValue = currentStreak.toString()),
            "bestStreak" to FirestoreValue(integerValue = bestStreak.toString()),
            "lastStreakDate" to FirestoreValue(integerValue = lastStreakDate.toString())
        )
        client.patch("$baseUrl/households/$householdId/members/$memberId") {
            withAuth()
            updateMaskFieldPaths("currentStreak", "bestStreak", "lastStreakDate")
            contentType(ContentType.Application.Json)
            setBody(FirestoreDocument(fields))
        }
        // Las demás mutaciones de este archivo invalidan la caché tras
        // escribir (createMember/deleteMember/updateMemberRole/addMemberPoints/
        // appreciateMember...); esta se había quedado fuera — sin esto, un
        // fallback a caché tras un fallo de red justo después de actualizar la
        // racha devolvía currentStreak/bestStreak/lastStreakDate obsoletos.
        taskCache.clearMembers(householdId)
    }

    /**
     * Update member total points (add delta). Requires auth (write).
     *
     * Usa concurrencia optimista (`currentDocument.updateTime`) en vez de un
     * simple leer-y-escribir: si otro dispositivo modifica el mismo documento
     * entre la lectura y la escritura, Firestore rechaza el PATCH
     * (FAILED_PRECONDITION/ABORTED) y reintentamos con el valor fresco, en vez
     * de sobrescribir a ciegas y perder el incremento concurrente.
     *
     * [floor], si se indica, se revalida en CADA intento contra el valor
     * `current` recién leído (no contra el saldo que el caller pudo haber
     * comprobado antes con una lectura ya obsoleta) — sin esto, dos descuentos
     * concurrentes sobre el mismo miembro (p.ej. `redeemReward`/`donatePoints`
     * desde dos dispositivos) podían dejar `totalPoints` negativo: cada
     * escritura es individualmente válida vista contra SU propia lectura
     * fresca, ninguna sabe del descuento concurrente del otro (panel v12,
     * Red/offline). Lanza [InsufficientBalanceException] en vez de escribir
     * si se incumpliría.
     */
    suspend fun addMemberPoints(
        householdId: String,
        memberId: String,
        delta: Int,
        floor: Int? = null
    ) {
        if (delta == 0) return
        val docUrl = "$baseUrl/households/$householdId/members/$memberId"
        repeat(OPTIMISTIC_WRITE_MAX_RETRIES) { attempt ->
            val current: FirestoreDocumentResponse = try {
                client.getWithRetry(docUrl) { withAuth() }.body()
            } catch (e: FirestoreException) {
                if (e.statusCode == 404) return // miembro inexistente: no-op, como antes
                throw e
            }
            val newTotal = (current.fields["totalPoints"]?.integerValue?.toIntOrNull() ?: 0) + delta
            if (floor != null && newTotal < floor) {
                throw InsufficientBalanceException(
                    "Saldo insuficiente: el descuento dejaría $newTotal puntos (mínimo $floor)"
                )
            }
            try {
                client.patch(docUrl) {
                    withAuth()
                    updateMaskFieldPaths("totalPoints")
                    current.updateTime?.let { parameter("currentDocument.updateTime", it) }
                    contentType(ContentType.Application.Json)
                    setBody(FirestoreDocument(mapOf("totalPoints" to FirestoreValue(integerValue = newTotal.toString()))))
                }
                taskCache.clearMembers(householdId)
                return
            } catch (e: FirestoreException) {
                val isConflict = e.code == "FAILED_PRECONDITION" || e.code == "ABORTED"
                if (!isConflict || attempt == OPTIMISTIC_WRITE_MAX_RETRIES - 1) throw e
                // Otro escritor ganó la carrera: reintentar con el valor fresco.
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Timeout/IOException (AMBIGUOUS): el servidor pudo haber completado
                // el PATCH pese al fallo visto por el cliente — invalidar la caché
                // para que la siguiente lectura muestre el saldo real en vez de un
                // valor "congelado" que refuerza la falsa sensación de que la
                // operación no tuvo efecto (panel v15, oleada 2/3).
                if (e.errorCategory() == ErrorCategory.AMBIGUOUS) {
                    taskCache.clearMembers(householdId)
                }
                throw e
            }
        }
    }

    // ────────────────────────────────────────────────────────
    //  Appreciation ("agradecer" — acuñación con tope semanal) & donations
    // ────────────────────────────────────────────────────────

/** Resultado de [appreciateMember]: éxito (con presupuesto restante y nuevo total del receptor) o error tipado. */
    sealed class AppreciateResult {
        data class Ok(val remaining: Int, val receptorNewTotal: Int) : AppreciateResult()
        data class Error(val reason: AppreciateErrorReason) : AppreciateResult()
    }

    /**
     * Motivos por los que [appreciateMember] puede rechazar la operación sin lanzar.
     * [UNCERTAIN]: el acreditado al receptor falló con un error ambiguo (timeout de
     * red, IOException) — el servidor pudo haber completado la escritura antes del
     * timeout; a diferencia de [TRANSFER_FAILED], reintentar la acción puede duplicar
     * los puntos del receptor (mismo patrón que [DonateErrorReason.UNCERTAIN], panel v15).
     */
    enum class AppreciateErrorReason { SELF, INVALID_AMOUNT, LIMIT_EXCEEDED, MEMBER_NOT_FOUND, TRANSFER_FAILED, UNCERTAIN }

    /** Traduce el error de dominio (sin dependencias de red) de [PointsRules] al tipo público de este repo. */
    private fun PointsRules.AppreciateError.toRepoReason(): AppreciateErrorReason = when (this) {
        PointsRules.AppreciateError.SELF -> AppreciateErrorReason.SELF
        PointsRules.AppreciateError.INVALID_AMOUNT -> AppreciateErrorReason.INVALID_AMOUNT
        PointsRules.AppreciateError.LIMIT_EXCEEDED -> AppreciateErrorReason.LIMIT_EXCEEDED
    }

    /**
     * Resultado de `FirestoreRepository.donatePoints` (éxito, con los nuevos
     * totales de ambos, o error tipado). La implementación vive en
     * `FirestoreRepository` (delega en la Cloud Function transaccional
     * `donatePoints`, kanban "CF donatePoints/appreciateMember") — este tipo
     * se queda aquí, junto a [AppreciateResult], porque es el contrato
     * público que ya consume [org.taskhub.ui.models.MemberScreenModel].
     */
    sealed class DonateResult {
        data class Ok(val donorNewTotal: Int, val receptorNewTotal: Int) : DonateResult()
        data class Error(val reason: DonateErrorReason) : DonateResult()
    }

    /**
     * Motivos por los que [donatePoints] puede rechazar la operación sin lanzar.
     * [ROLLBACK_FAILED] es distinto de [TRANSFER_FAILED]: en ese caso concreto
     * la reversión del débito al donante TAMBIÉN falló, así que sus puntos sí
     * pueden haberse visto afectados — a diferencia de [TRANSFER_FAILED], cuyo
     * mensaje afirma lo contrario (panel 2026-09-11, IMPORTANTE).
     * [AMOUNT_EXCEEDS_LIMIT]: caso concreto de fallo en el acreditado al
     * receptor cuando `amount` supera [PointsRules.MAX_PEER_TRANSFER_AMOUNT]
     * — `firestore.rules` `isPeerPointsTransfer` topa cada escritura de
     * "donar/agradecer entre iguales" a +1000 puntos (ver cabecera del
     * archivo), pero ni [PointsRules.validateDonateBalance] ni ningún otro
     * punto del cliente lo comprobaban antes de intentar la donación — un
     * donante con más de 1000 puntos acumulados que donara todo su saldo de
     * una vez recibía el mismo mensaje genérico "transferencia fallida" que
     * un fallo de red real (panel de expertos 2026-09-13). Distinguirlo dejó
     * a un donante SIN CAPACIDAD DE ADMIN atrapado en el 403 de la regla;
     * quien SÍ es owner/admin del hogar no llega a este `catch` (su propia
     * escritura pasa por `isTrusted(hid)`, sin tope).
     * [UNCERTAIN]: la operación de acreditado al receptor falló con un error
     * ambigüo (timeout de red, IOException) — el servidor pudo haber completado
     * la escritura antes del timeout, así que NO se revierte el débito al
     * donante para evitar duplicar puntos (panel v14, hallazgo 2).
     */
    enum class DonateErrorReason { SELF, INVALID_AMOUNT, INSUFFICIENT_BALANCE, MEMBER_NOT_FOUND, TRANSFER_FAILED, ROLLBACK_FAILED, AMOUNT_EXCEEDS_LIMIT, UNCERTAIN }

    /** Presupuesto de "agradecer" vigente de [member] en el instante [now] — ver [PointsRules.currentAppreciationBudget]. */
    private fun currentAppreciationBudget(member: MemberResponse, now: Long): PointsRules.AppreciationBudget =
        PointsRules.currentAppreciationBudget(member.appreciationGiven, member.appreciationWeekStart, now)

    /**
     * Puntos que [member] aún puede DAR agradeciendo esta semana (0..50), sin mutar nada.
     * Útil para que la UI muestre el presupuesto restante antes de abrir el diálogo.
     */
    fun appreciationRemaining(member: MemberResponse, now: Long = Clock.System.now().toEpochMilliseconds()): Int =
        currentAppreciationBudget(member, now).remaining

    /**
     * "Agradecer": acuña [amount] puntos para [toMemberId] sin restárselos a [fromMemberId].
     * Cada miembro puede DAR como máximo 50 puntos por semana (lunes-domingo); al superar el
     * tope semanal, o al agradecerse a sí mismo, devuelve un [AppreciateResult.Error] tipado
     * en vez de lanzar una excepción.
     */
    suspend fun appreciateMember(
        householdId: String,
        fromMemberId: String,
        toMemberId: String,
        amount: Int
    ): AppreciateResult {
        PointsRules.validateAppreciateBasic(fromMemberId, toMemberId, amount)?.let {
            return AppreciateResult.Error(it.toRepoReason())
        }

        val toMember = getMembers(householdId).find { it.id == toMemberId }
            ?: return AppreciateResult.Error(AppreciateErrorReason.MEMBER_NOT_FOUND)

        // Consumir presupuesto ANTES de acuñar puntos (si la red falla entre las
        // dos escrituras, es mejor que el receptor se quede sin acreditar,
        // recuperable reintentando, que dejar que el emisor supere el tope
        // semanal reintentando una acuñación que ya se completó) — y con
        // concurrencia optimista (`currentDocument.updateTime` + reintento, igual
        // que [addMemberPoints]): sin esto, dos "agradecer" casi simultáneos del
        // mismo emisor podrían validar ambos contra el mismo presupuesto stale y
        // saltarse el tope de 50 pts/semana. El perdedor de la carrera reintenta
        // automáticamente contra el valor fresco (hasta [OPTIMISTIC_WRITE_MAX_RETRIES]
        // veces); si el tope ya estaba agotado por el ganador, ve LIMIT_EXCEEDED.
        val docUrl = "$baseUrl/households/$householdId/members/$fromMemberId"
        repeat(OPTIMISTIC_WRITE_MAX_RETRIES) { attempt ->
            val current: FirestoreDocumentResponse = try {
                client.getWithRetry(docUrl) { withAuth() }.body()
            } catch (e: FirestoreException) {
                if (e.statusCode == 404) return AppreciateResult.Error(AppreciateErrorReason.MEMBER_NOT_FOUND)
                throw e
            }
            val fromMember = FirestoreParsers.toMemberResponse(current, householdId, "appreciateMember")
            val now = Clock.System.now().toEpochMilliseconds()
            val budget = currentAppreciationBudget(fromMember, now)
            PointsRules.validateAppreciateLimit(amount, budget)?.let {
                return AppreciateResult.Error(it.toRepoReason())
            }
            val newGiven = budget.given + amount
            val fields = mapOf(
                "appreciationGiven" to FirestoreValue(integerValue = newGiven.toString()),
                "appreciationWeekStart" to FirestoreValue(integerValue = budget.weekStart.toString())
            )
            try {
                client.patch(docUrl) {
                    withAuth()
                    updateMaskFieldPaths("appreciationGiven", "appreciationWeekStart")
                    current.updateTime?.let { parameter("currentDocument.updateTime", it) }
                    contentType(ContentType.Application.Json)
                    setBody(FirestoreDocument(fields))
                }
                taskCache.clearMembers(householdId)
                // Acuñar: el receptor gana puntos sin que se le resten al que agradece.
                // Envuelto en try/catch: si `toMemberId` no es el propio emisor y
                // este no es isTrusted(hid), firestore.rules rechaza el PATCH al
                // documento ajeno (403) — sin capturar, esa excepción se propagaba
                // sin control fuera de este bloque (panel de revisión 2026-09-10,
                // Experto 9, CRÍTICO). El presupuesto ya consumido no se revierte
                // a propósito (ver comentario de la función): es preferible que el
                // emisor pierda parte de su cupo semanal, recuperable la semana
                // siguiente, a reabrir la carrera que el orden actual evita.
                try {
                    addMemberPoints(householdId, toMemberId, amount)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Ver KDoc de [AppreciateErrorReason.UNCERTAIN]: un timeout aquí no
                    // permite saber si el receptor ya fue acreditado (panel v15, mismo
                    // patrón que el fix de [donatePoints] para AMBIGUOUS).
                    if (e.errorCategory() == ErrorCategory.AMBIGUOUS) {
                        return AppreciateResult.Error(AppreciateErrorReason.UNCERTAIN)
                    }
                    return AppreciateResult.Error(AppreciateErrorReason.TRANSFER_FAILED)
                }
                return AppreciateResult.Ok(
                    remaining = PointsRules.WEEKLY_APPRECIATION_BUDGET - newGiven,
                    receptorNewTotal = toMember.totalPoints + amount
                )
            } catch (e: FirestoreException) {
                val isConflict = e.code == "FAILED_PRECONDITION" || e.code == "ABORTED"
                if (!isConflict || attempt == OPTIMISTIC_WRITE_MAX_RETRIES - 1) throw e
                // Otro escritor ganó la carrera: reintentar con el presupuesto fresco.
            }
        }
        throw IllegalStateException("appreciateMember: reintentos de concurrencia agotados")
    }

    /**
     * Get member's unlocked achievement IDs.
     *
     * SIN caché de respaldo propia a propósito (punto B11, evaluado y
     * descartado): a diferencia de rewards/taskHistory (colecciones que se
     * acumulan y se LEEN para mostrar datos), esta lectura alimenta
     * [org.taskhub.ui.models.checkAndAwardAchievements] — un `emptySet()`
     * ante fallo de red ya es el comportamiento seguro por diseño (evita
     * volver a "otorgar" un logro que en realidad ya estaba desbloqueado
     * porque la lectura de comprobación falló); servir un `Set` cacheado
     * obsoleto en ese punto de ESCRITURA condicional sería más arriesgado
     * (podría ocultar un logro nuevo real) que útil, y StatsScreen (la
     * pantalla de LECTURA de logros) ya tolera mostrar el estado vacío hasta
     * el siguiente refresco.
     */
    suspend fun getMemberAchievements(householdId: String, memberId: String): Set<String> = orDefault(emptySet()) {
        val response: FirestoreDocumentResponse = client.getWithRetry(
            "$baseUrl/households/$householdId/members/$memberId/achievements/_meta"
        ) {
            withAuth()
        }.body()
        response.fields["unlocked"]?.arrayValue?.values
            ?.mapNotNull { it.stringValue }
            ?.toSet()
            ?: emptySet()
    }

    /**
     * Add an achievement ID to member's unlocked achievements.
     *
     * Lee-modifica-escribe con concurrencia optimista (igual que [addMemberPoints]):
     * si dos logros se desbloquean casi a la vez (misma tarea que dispara dos, o
     * dos dispositivos), el PATCH que llega segundo ve el precondition fallar y
     * reintenta con el array ya actualizado, en vez de sobrescribirlo y perder
     * el logro que ganó la carrera.
     *
     * Panel v17 (hallazgo IMPORTANTE de QA): cuando el documento `_meta`
     * TODAVÍA NO EXISTE (`current == null`, primer logro de este miembro),
     * el PATCH se mandaba SIN precondición alguna (`current?.updateTime` es
     * null, el `let` no aporta nada) — dos dispositivos desbloqueando
     * logros DISTINTOS por primera vez casi a la vez podían pisarse: ambos
     * leían `current = null`, ambos creaban el documento con SU único logro,
     * y el segundo PATCH (último-en-escribir-gana sobre el array completo)
     * borraba en silencio el logro del primero. Ahora se manda
     * `currentDocument.exists = false` como precondición en ese caso: si
     * alguien más ya creó el documento entretanto, el PATCH falla con el
     * mismo `FAILED_PRECONDITION` que ya maneja el resto de esta función, y
     * el reintento relee el documento (ahora sí existente) para fusionar en
     * vez de pisar.
     */
    suspend fun addMemberAchievement(householdId: String, memberId: String, achievementId: String) {
        val docUrl = "$baseUrl/households/$householdId/members/$memberId/achievements/_meta"
        val now = Clock.System.now().toEpochMilliseconds()
        repeat(OPTIMISTIC_WRITE_MAX_RETRIES) { attempt ->
            val current: FirestoreDocumentResponse? = try {
                client.getWithRetry(docUrl) { withAuth() }.body()
            } catch (e: FirestoreException) {
                if (e.statusCode == 404) null else throw e
            }
            val existing = current?.fields?.get("unlocked")?.arrayValue?.values
                ?.mapNotNull { it.stringValue }?.toSet() ?: emptySet()
            if (achievementId in existing) return // ya desbloqueado
            val allUnlocked = existing + achievementId
            val fields = mapOf(
                "unlocked" to FirestoreValue(
                    arrayValue = FirestoreArrayValue(values = allUnlocked.map { FirestoreValue(stringValue = it) })
                ),
                "updatedAt" to FirestoreValue(integerValue = now.toString())
            )
            try {
                client.patch(docUrl) {
                    withAuth()
                    updateMaskFieldPaths("unlocked", "updatedAt")
                    if (current?.updateTime != null) {
                        parameter("currentDocument.updateTime", current.updateTime)
                    } else {
                        parameter("currentDocument.exists", "false")
                    }
                    contentType(ContentType.Application.Json)
                    setBody(FirestoreDocument(fields))
                }
                return
            } catch (e: FirestoreException) {
                val isConflict = e.code == "FAILED_PRECONDITION" || e.code == "ABORTED"
                if (!isConflict || attempt == OPTIMISTIC_WRITE_MAX_RETRIES - 1) throw e
            }
        }
    }

    private fun toMemberResponse(
        doc: FirestoreDocumentResponse,
        householdId: String,
        operation: String = "getMembers"
    ): MemberResponse = FirestoreParsers.toMemberResponse(doc, householdId, operation)
}
