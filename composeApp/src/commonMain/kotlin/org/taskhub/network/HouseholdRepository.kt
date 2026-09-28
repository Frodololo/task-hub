/**
 * Capa REST de Firestore para hogares, invitaciones y el chat de grupo.
 * Usado por [FirestoreRepository] (fachada) y directamente por los
 * `ScreenModel`s de hogar/onboarding/chat registrados vía Koin.
 */
package org.taskhub.network

import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import org.taskhub.network.models.HouseholdResponse
import org.taskhub.network.models.MessageResponse
import org.taskhub.network.models.SpaceType
import org.taskhub.network.models.firestoreValue
import org.taskhub.platform.AppLog
import org.taskhub.platform.secureRandomInt
import org.taskhub.storage.HouseholdStore
import org.taskhub.storage.SavedHousehold
import org.taskhub.storage.SettingsStore
import org.taskhub.storage.TaskCache
import org.taskhub.ui.i18n.AppStrings

/**
 * Hogares (colección `households`, subcolección `messages`) e invites.
 * Extraído de [FirestoreRepository] (ver docs/refactor-arquitectura-2026-08-31.md,
 * punto 6, fase 2.4). Lógica movida tal cual, sin cambios de comportamiento.
 *
 * NO incluye `deleteHousehold`/`leaveHousehold`: invalidan el caché de
 * miembro actual (`MemberRepository.invalidateCurrentMember`) y leen
 * `getMembers` a la vez que borran el hogar — orquestación Household+Member
 * que se queda en [FirestoreRepository] (`isMember`/`isCurrentUserMember` sí
 * se movieron a [MemberRepository] en la fase 2.5, al no tocar nada de
 * Household).
 *
 * `getLocalId` vive en [FirestoreClient] (no en [FirestoreRepository]) para
 * que este repo pueda depender de [FirestoreClient] directamente y
 * registrarse como `single` de Koin sin crear un ciclo hacia la fachada
 * (panel v7, #16 — mismo motivo documentado en [MemberRepository]).
 *
 * Depende también de [MemberRepository] (para listar destinatarios) y
 * [NotificationRepository] (para crearles una notificación) desde
 * `sendMessage` — sin ciclo, ninguno de los dos depende de este repo (panel
 * de notificaciones 2026-09-05, gap A).
 */
class HouseholdRepository(
    private val baseUrl: String,
    private val firestoreClient: FirestoreClient,
    private val taskCache: TaskCache,
    private val memberRepository: MemberRepository,
    private val notificationRepository: NotificationRepository,
    private val settingsStore: SettingsStore
) {

    /**
     * Lanzada por [joinHousehold] cuando el código de invitación no resuelve
     * a ningún hogar. Tipada (en vez de un `IllegalStateException` con texto
     * fijo en español) para que el catch del ScreenModel pueda mapearla a
     * `AppStrings` por tipo — antes `e.message` nunca era null, así que el
     * fallback de i18n del catch nunca se usaba y un usuario con la app en
     * otro idioma veía el texto en español en el flujo de onboarding más
     * común de la app (panel de revisión 2026-09-10, Experto 2, IMPORTANTE,
     * NUEVO).
     */
    class InvalidInviteCodeException(message: String) : Exception(message)
    private val client = firestoreClient.client

    private suspend fun HttpRequestBuilder.withAuth() = with(firestoreClient) { withAuth() }
    private fun HttpRequestBuilder.updateMaskFieldPaths(vararg fields: String) =
        with(firestoreClient) { updateMaskFieldPaths(*fields) }
    private fun extractDocId(resourceName: String, operation: String): String =
        firestoreClient.extractDocId(resourceName, operation)
    private suspend fun ensureAuth() = firestoreClient.ensureAuth()

    // ────────────────────────────────────────────────────────
    //  Households
    // ────────────────────────────────────────────────────────

    /** Crea un hogar (ID de documento autogenerado). Requiere auth (escritura). */
    suspend fun createHousehold(
        name: String,
        isPersonal: Boolean = false,
        deviceTimezone: String = TimeZone.currentSystemDefault().id,
        spaceType: SpaceType = SpaceType.HOME,
        emoji: String? = null
    ): HouseholdResponse {
        ensureAuth()
        val now = Clock.System.now().toEpochMilliseconds()
        val inviteCode = if (isPersonal) "PERSONAL" else generateInviteCode()
        val ownerId = firestoreClient.getLocalId() ?: throw IllegalStateException("No autenticado")

        val fields = mapOf(
            "name" to FirestoreValue(stringValue = name),
            "inviteCode" to FirestoreValue(stringValue = inviteCode),
            "isPersonal" to FirestoreValue(booleanValue = isPersonal),
            "ownerId" to FirestoreValue(stringValue = ownerId),
            "createdAt" to FirestoreValue(integerValue = now.toString()),
            "updatedAt" to FirestoreValue(integerValue = now.toString()),
            "timezone" to FirestoreValue(stringValue = deviceTimezone),
            "spaceType" to FirestoreValue(stringValue = spaceType.firestoreValue)
        ) + if (emoji != null) mapOf("emoji" to FirestoreValue(stringValue = emoji)) else emptyMap()

        val response: FirestoreDocumentResponse = client.post("$baseUrl/households") {
            withAuth()
            contentType(ContentType.Application.Json)
            setBody(FirestoreDocument(fields))
        }.body()

        val id = extractDocId(response.name, "createHousehold")

        // Publicar el mapa código → hogar para poder unirse sin listar hogares.
        if (!isPersonal) {
            try {
                createInvite(inviteCode, id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.w("HouseholdRepository", "createHousehold: createInvite failed for householdId=$id, inviteCode=$inviteCode", e)
                // No crítico: sin invite, otros no pueden unirse por código.
            }
        }

        val household = HouseholdResponse(id, name, inviteCode, now, now, isPersonal, ownerId, deviceTimezone, spaceType, emoji)
        // Se cachea de inmediato para que getHousehold ya lo tenga en la primera carga.
        taskCache.cacheHousehold(household)
        return household
    }

    /**
     * Obtiene (o crea) el espacio Personal del usuario actual con un ID DETERMINISTA
     * derivado de su identidad estable: `personal_{uid}`, donde `uid` es el UID de
     * Google de la sesión iniciada.
     *
     * Esto hace el espacio Personal interdispositivo: con la misma cuenta de Google,
     * todos los dispositivos resuelven el MISMO documento `households/personal_{uid}`,
     * así que tareas/miembros/puntos se comparten automáticamente.
     */
    suspend fun getOrCreatePersonalHousehold(): HouseholdResponse {
        ensureAuth()
        val uid = firestoreClient.getLocalId() ?: throw IllegalStateException("No autenticado")
        val personalId = personalHouseholdId(uid)

        // 1) Si ya existe (lo creó este u otro dispositivo con la misma cuenta),
        //    devolverlo tal cual y refrescar la caché local.
        val existing = try {
            getHousehold(personalId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w("HouseholdRepository", "getOrCreatePersonalHousehold: getHousehold failed for personalId=$personalId", e)
            null
        }
        if (existing != null) return existing

        // 2) No existe → crearlo en el ID determinista.
        val now = Clock.System.now().toEpochMilliseconds()
        val deviceTimezone = TimeZone.currentSystemDefault().id
        val fields = mapOf(
            "name" to FirestoreValue(stringValue = "Personal"),
            "inviteCode" to FirestoreValue(stringValue = "PERSONAL"),
            "isPersonal" to FirestoreValue(booleanValue = true),
            "ownerId" to FirestoreValue(stringValue = uid),
            "timezone" to FirestoreValue(stringValue = deviceTimezone),
            "createdAt" to FirestoreValue(integerValue = now.toString()),
            "updatedAt" to FirestoreValue(integerValue = now.toString())
        )
        val response: FirestoreDocumentResponse = try {
            client.post("$baseUrl/households") {
                withAuth()
                parameter("documentId", personalId)
                contentType(ContentType.Application.Json)
                setBody(FirestoreDocument(fields))
            }.body()
        } catch (e: FirestoreException) {
            AppLog.w("HouseholdRepository", "getOrCreatePersonalHousehold: create failed for personalId=$personalId, uid=$uid", e)
            // Carrera entre dispositivos: el mismo usuario abrió la app en dos
            // sitios a la vez y ambos intentaron crear el mismo ID determinista.
            // El que llega segundo recibe ALREADY_EXISTS: no es un fallo real,
            // basta con leer el hogar que el otro dispositivo acaba de crear.
            if (e.code == "ALREADY_EXISTS" || e.statusCode == 409) {
                return getHousehold(personalId)
            }
            throw e
        }

        val household = HouseholdResponse(
            id = extractDocId(response.name, "getOrCreatePersonalHousehold"),
            name = "Personal",
            inviteCode = "PERSONAL",
            createdAt = now,
            updatedAt = now,
            isPersonal = true,
            ownerId = uid,
            timezone = deviceTimezone
        )
        taskCache.cacheHousehold(household)
        return household
    }

    /** ID determinista del espacio Personal para una identidad (UID) dada. */
    fun personalHouseholdId(uid: String): String = "personal_$uid"

    /**
     * Escribe invites/{code} → { householdId }. Las reglas de Firestore permiten
     * resolverlo por código (get) pero no listar la colección, de modo que el
     * código actúa como secreto compartido fuera de banda.
     */
    private suspend fun createInvite(code: String, householdId: String) {
        val fields = mapOf("householdId" to FirestoreValue(stringValue = householdId))
        client.post("$baseUrl/invites") {
            withAuth()
            parameter("documentId", code)
            contentType(ContentType.Application.Json)
            setBody(FirestoreDocument(fields))
        }
    }

    /**
     * Obtiene un hogar por su ID. Ante fallos de red/5xx, cae a la caché local.
     *
     * Un 404/403 de Firestore es una señal DEFINITIVA — el hogar se borró o se
     * perdió el acceso a él — así que NO debe caer a la caché (eso mantendría
     * mostrando un hogar "fantasma" para siempre). Los llamadores que
     * necesiten podar estado local deben capturar [FirestoreException] y
     * comprobar [FirestoreException.statusCode].
     */
    suspend fun getHousehold(id: String): HouseholdResponse {
        return try {
            val response: FirestoreDocumentResponse = client.getWithRetry("$baseUrl/households/$id") {
                withAuth()
            }.body()

            val household = toHouseholdResponse(response, knownId = id)
            taskCache.cacheHousehold(household)
            household
        } catch (e: CancellationException) {
            throw e
        } catch (e: FirestoreException) {
            AppLog.w("HouseholdRepository", "getHousehold: fetch failed for id=$id", e)
            if (e.statusCode == 404 || e.statusCode == 403) throw e
            taskCache.getCachedHousehold(id) ?: throw e
        } catch (e: Exception) {
            AppLog.w("HouseholdRepository", "getHousehold: fetch failed for id=$id", e)
            taskCache.getCachedHousehold(id) ?: throw e
        }
    }

    /**
     * Reconcilia los hogares guardados localmente en [store] contra Firestore
     * (fuente de verdad). Poda (borra de [store]) los que ya no existen o a los
     * que se perdió acceso (404/403 — señal inequívoca). Ante fallos de red o de
     * servidor (5xx, timeouts, sin conexión) CONSERVA la entrada: no podar nunca
     * por un fallo transitorio, solo por una confirmación explícita de Firestore.
     *
     * Devuelve la lista de hogares que sobreviven la reconciliación.
     *
     * Poda con UNA sola escritura final ([HouseholdStore.replaceSavedHouseholds])
     * en vez de un `removeHousehold` por hogar podado dentro de cada `async` —
     * varias coroutines llamando a `removeHousehold` en paralelo hacían cada
     * una su propio read-modify-write sobre la MISMA lista sin serializar
     * (última escritura gana), pudiendo resucitar un hogar que otra coroutine
     * del mismo lote ya había podado (panel v7, Exp. 6, MENOR, autocurable en
     * la siguiente pasada pero real). Reescribir de golpe con la lista final
     * de supervivientes es idempotente y no tiene esa carrera.
     */
    suspend fun reconcileHouseholds(store: HouseholdStore): List<SavedHousehold> {
        val saved = store.getSavedHouseholds()
        val survivors = coroutineScope {
            saved.map { h ->
                async {
                    val stillExists = try {
                        getHousehold(h.id)
                        true
                    } catch (e: FirestoreException) {
                        AppLog.w("HouseholdRepository", "reconcileHouseholds: getHousehold failed for id=${h.id}", e)
                        if (e.statusCode == 404 || e.statusCode == 403) {
                            false
                        } else {
                            true // 5xx u otro error tipado de Firestore: no es inequívoco, conservar
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AppLog.e("HouseholdRepository", "reconcileHouseholds: unexpected failure checking id=${h.id}", e)
                        true // red/timeout/etc: conservar
                    }
                    if (stillExists) h else null
                }
            }.awaitAll()
        }
        val survivorList = survivors.filterNotNull()
        // prunedIds se deriva por comparación DESPUÉS de awaitAll (no
        // acumulado desde dentro de cada `async` — mutar una lista compartida
        // desde coroutines potencialmente en paralelo sería la misma clase de
        // carrera que este fix elimina) — sin condición de carrera posible.
        val survivorIds = survivorList.map { it.id }.toSet()
        val prunedIds = saved.filter { it.id !in survivorIds }.map { it.id }
        if (prunedIds.isNotEmpty()) {
            // Releer justo antes de escribir en vez de reusar `saved` (foto
            // de antes del `awaitAll` de red): si durante esa ventana el
            // usuario abandonó un hogar a mano (leaveHousehold, que escribe
            // directo al store), sobrescribir con la foto vieja lo
            // resucitaba (panel de revisión 2026-09-10, arrastrado desde la
            // auditoría 2026-09-06, hallazgo crítico #4).
            val current = store.getSavedHouseholds()
            store.replaceSavedHouseholds(current.filter { it.id !in prunedIds })
            prunedIds.forEach { taskCache.clearHousehold(it) }
        }
        return survivorList
    }

    /** Batch-fetch multiple households by their document IDs (en paralelo). */
    suspend fun getHouseholds(ids: List<String>): List<HouseholdResponse> {
        if (ids.isEmpty()) return emptyList()
        return coroutineScope {
            ids.map { id ->
                async {
                    try {
                        getHousehold(id)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AppLog.w("HouseholdRepository", "getHouseholds: skipping stale id=$id", e)
                        null // stale ID from local store — skip
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }

    /**
     * Transfiere la propiedad del hogar (`ownerId`) a otro usuario.
     * `firestore.rules` gatea `update` de `households/{hid}` con
     * `isOwner(hid)` (quien llama sigue siendo el owner vigente en ese
     * instante — caso de [FirestoreRepository.leaveHousehold], el propio
     * owner abandonando) o con `isValidOwnerSuccession(hid)` (v10: un admin
     * no-owner expulsando al owner — ver [FirestoreRepository.deleteMember]
     * / [org.taskhub.network.HouseholdRules.planOwnerSuccession] — acotada a
     * transferir solo hacia un miembro real, vinculado a cuenta y activo de
     * este hogar).
     *
     * Sin transferir, el hogar se queda sin nadie que pase `isOwner(hid)`
     * para siempre en cuanto el UID del owner deja de poder autenticarse
     * (panel v4, Experto 2 hallazgo #6 ALTO).
     */
    suspend fun updateHouseholdOwner(householdId: String, newOwnerId: String) {
        val fields = mapOf("ownerId" to FirestoreValue(stringValue = newOwnerId))
        // Panel v18 (red/offline): invalidar en `finally` — mismo motivo que
        // el resto de escrituras de tareas (panel v17 punto 2). Un fallo
        // ambiguo aquí puede dejar la caché del hogar (persistida en disco)
        // sirviendo el owner ANTERIOR mientras el servidor ya aplicó la
        // transferencia, y `ownerId` gatea `isOwner(hid)` en firestore.rules.
        try {
            client.patch("$baseUrl/households/$householdId") {
                withAuth()
                updateMaskFieldPaths("ownerId")
                contentType(ContentType.Application.Json)
                setBody(FirestoreDocument(fields))
            }
        } finally {
            taskCache.clearHouseholdDoc(householdId)
        }
    }

    /** Find a household by invite code. Uses the invites/{code} map (no list). */
    suspend fun joinHousehold(inviteCode: String): HouseholdResponse {
        // 1) Resolver código → householdId vía invites/{code}.
        val inviteResponse: FirestoreDocumentResponse = client.getWithRetry("$baseUrl/invites/$inviteCode") {
            withAuth()
        }.body()

        val householdId = inviteResponse.fields["householdId"]?.stringValue
            ?: throw InvalidInviteCodeException("Código de invitación inválido")

        // 2) Leer el hogar por su ID.
        return getHousehold(householdId)
    }

    // ────────────────────────────────────────────────────────
    //  Messages (subcollection under households/{id})
    // ────────────────────────────────────────────────────────

    /**
     * Send a chat message to a household.
     *
     * Tras guardar el mensaje, crea una notificación (`households/{id}/notifications`,
     * mismo mecanismo que `TaskRepository.assignTask`) para cada miembro del
     * hogar EXCEPTO el autor — antes nadie más se enteraba de un mensaje
     * nuevo salvo que tuviera la pantalla del chat abierta (panel de
     * notificaciones 2026-09-05, gap A). Best-effort: si falla, el mensaje ya
     * se envió correctamente; la notificación es un efecto secundario.
     */
    suspend fun sendMessage(
        householdId: String,
        memberId: String,
        authorName: String,
        text: String
    ): MessageResponse {
        val now = Clock.System.now().toEpochMilliseconds()
        val fields = mapOf(
            "memberId" to FirestoreValue(stringValue = memberId),
            "authorName" to FirestoreValue(stringValue = authorName),
            "text" to FirestoreValue(stringValue = text),
            "createdAt" to FirestoreValue(integerValue = now.toString())
        )

        val response: FirestoreDocumentResponse = client.post(
            "$baseUrl/households/$householdId/messages"
        ) {
            withAuth()
            contentType(ContentType.Application.Json)
            setBody(FirestoreDocument(fields))
        }.body()

        val id = extractDocId(response.name, "sendMessage")

        try {
            val title = AppStrings.get("notification_new_message_title", settingsStore.getLanguage())
            val preview = if (text.length > 80) text.take(80) + "…" else text
            val body = "$authorName: $preview"
            val recipients = memberRepository.getMembers(householdId).filter { it.id != memberId }
            // try/catch POR destinatario (no uno solo envolviendo el `forEach`):
            // un fallo puntual creando la notificación de UN miembro no debe
            // dejar sin notificar al resto de la lista (panel de notificaciones
            // 2026-09-05, Programador senior — mismo patrón que
            // TaskRepository.assignTask, que sí lo hacía bien por miembro).
            recipients.forEach { recipient ->
                try {
                    // taskId="" es el centinela usado por el resto del sistema
                    // (NotificationListScreen, NotificationPollWorker) para
                    // distinguir "notificación de chat" de "notificación de
                    // tarea" y decidir a dónde navegar al tocarla.
                    notificationRepository.createNotification(
                        householdId = householdId,
                        memberId = recipient.id,
                        taskId = "",
                        // title: fallback en el idioma del autor. titleKey permite
                        // que el LECTOR lo vea en SU idioma (panel de
                        // notificaciones 2026-09-05, IMPORTANTE); messageKey
                        // queda null a propósito — el cuerpo ("$authorName:
                        // $preview") es contenido de usuario, no traducible.
                        title = title,
                        message = body,
                        titleKey = "notification_new_message_title",
                        // authorMemberId + messageParams["preview"]: permiten a
                        // NotificationText.message resolver el nombre del autor
                        // contra el estado ACTUAL de la lista de miembros en vez
                        // de quedarse fijado al `authorName` de este instante —
                        // si el autor abandona/es expulsado después, el nombre
                        // mostrado se actualiza solo (ver KDoc de
                        // NotificationResponse.authorMemberId, ronda de deuda
                        // aplicable 2026-09-12, punto B10). `message` (arriba)
                        // sigue siendo el fallback si no se puede resolver.
                        messageParams = mapOf("preview" to preview),
                        authorMemberId = memberId
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLog.w("HouseholdRepository", "sendMessage: createNotification failed for householdId=$householdId, recipient=${recipient.id}", e)
                    // No crítico: el mensaje ya se envió, la notificación es un efecto secundario.
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w("HouseholdRepository", "sendMessage: notification fan-out failed (getMembers?) for householdId=$householdId", e)
            // No crítico: fallo listando miembros — el mensaje ya se envió.
        }

        return MessageResponse(id, memberId, authorName, text, now)
    }

    /**
     * List chat messages for a household, oldest first.
     *
     * [limit], si se indica, trae solo los [limit] mensajes MÁS RECIENTES
     * (vía `orderBy=createdAt desc` en el servidor) en vez de la colección
     * completa — pensado para el sondeo de chat de
     * [org.taskhub.ui.models.HouseholdScreenModel.loadMessages], que antes
     * releía TODO el historial en cada ciclo (tarjeta kanban "Paginación
     * getMessages/getNotifications", 2026-09-13). NO se pasa desde
     * [anonymizeMemberMessages]: anonimizar el nombre de un miembro que se
     * va debe alcanzar a TODO su historial, no solo a los mensajes más
     * recientes, o dejaría su nombre real expuesto en mensajes antiguos.
     */
    suspend fun getMessages(householdId: String, limit: Int? = null): List<MessageResponse> {
        val documents = client.listAllDocuments(
            "$baseUrl/households/$householdId/messages",
            limit = limit,
            orderBy = if (limit != null) "createdAt desc" else null
        ) {
            withAuth()
        }

        return documents.map { doc -> FirestoreParsers.toMessageResponse(doc) }
            .sortedBy { it.createdAt }
    }

    /**
     * Purga best-effort de mensajes de chat con más de [maxAgeMillis] de
     * antigüedad (por [MessageResponse.createdAt]) — mismo patrón que
     * [NotificationRepository.purgeOldRead] (ronda de deuda aplicable
     * 2026-09-12, punto B9): TTL de retención de 90 días, a partir de una
     * lista ya obtenida con [getMessages] (evita un segundo fetch; el
     * caller — `HouseholdScreenModel.loadMessages`, que ya trae la
     * colección completa para pintar el chat — la pasa directamente). A
     * diferencia de las notificaciones (que solo purgan las YA LEÍDAS),
     * aquí no hay equivalente a "leído": se purga por antigüedad sin más
     * condición, igual que [TaskRepository.purgeOldTaskHistory].
     */
    suspend fun purgeOldMessages(householdId: String, all: List<MessageResponse>, maxAgeMillis: Long) {
        val cutoff = Clock.System.now().toEpochMilliseconds() - maxAgeMillis
        val stale = all.filter { it.createdAt < cutoff }
        for (message in stale) {
            try {
                client.delete("$baseUrl/households/$householdId/messages/${message.id}") {
                    withAuth()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.w("HouseholdRepository", "purgeOldMessages: delete failed for householdId=$householdId, messageId=${message.id}", e)
                // Best-effort, ver KDoc de la función.
            }
        }
    }

    /**
     * Reescribe `authorName` a [anonymizedName] en los mensajes de chat
     * enviados por cualquiera de [memberIds] — usado por
     * [FirestoreRepository.leaveHousehold] cuando un miembro se va (o se
     * borra su cuenta) pero el hogar sigue existiendo para el resto: el
     * mensaje en sí se conserva (contexto de la conversación), pero el
     * nombre real de quien lo escribió deja de quedar expuesto para siempre
     * (panel v4, Experto 10 hallazgo #4). Best-effort: un fallo puntual (o
     * no poder ni listar los mensajes) no debe abortar el resto del
     * abandono del hogar.
     */
    /**
     * @return `true` si TODOS los mensajes se anonimizaron correctamente;
     *   `false` si al menos uno falló (panel v16, 2026-09-24, hallazgo I7:
     *   antes cada fallo individual se tragaba en silencio — el nombre real
     *   de quien se fue podía quedar expuesto para siempre sin que nadie lo
     *   detectara, pese a que `privacy.html` promete anonimizarlo. El
     *   llamador decide qué hacer con `false` — no se bloquea el borrado de
     *   cuenta/abandono por esto, solo se hace el fallo observable).
     */
    suspend fun anonymizeMemberMessages(householdId: String, memberIds: Set<String>, anonymizedName: String): Boolean {
        if (memberIds.isEmpty()) return true
        val messages = try {
            getMessages(householdId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.e("HouseholdRepository", "anonymizeMemberMessages: getMessages failed for householdId=$householdId", e)
            return false
        }
        var allOk = true
        messages.filter { it.memberId in memberIds }.forEach { message ->
            try {
                client.patch("$baseUrl/households/$householdId/messages/${message.id}") {
                    withAuth()
                    updateMaskFieldPaths("authorName")
                    contentType(ContentType.Application.Json)
                    setBody(FirestoreDocument(mapOf("authorName" to FirestoreValue(stringValue = anonymizedName))))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e("HouseholdRepository", "anonymizeMemberMessages: patch failed for householdId=$householdId, messageId=${message.id}", e)
                // Se prioriza anonimizar el resto de mensajes (best-effort),
                // pero el fallo se acumula en el resultado — ver KDoc arriba.
                allOk = false
            }
        }
        return allOk
    }

    private fun toHouseholdResponse(
        doc: FirestoreDocumentResponse,
        knownId: String? = null,
        operation: String = "getHousehold"
    ): HouseholdResponse = FirestoreParsers.toHouseholdResponse(doc, knownId, operation)

    private fun generateInviteCode(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        return (1..8).map { chars[secureRandomInt(chars.length)] }.joinToString("")
    }
}
