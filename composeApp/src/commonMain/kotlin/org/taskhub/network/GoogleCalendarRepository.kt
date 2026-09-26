/**
 * Integración con la API REST de Google Calendar (independiente de Firestore).
 * Usado por la UI/ScreenModels de tareas para sincronizar eventos de tareas
 * con el calendario de Google del usuario, reutilizando el access token OAuth
 * obtenido al vincular la cuenta con Google Sign-In (el refresco del token en
 * sí vive fuera de este archivo; aquí solo se consume).
 */
package org.taskhub.network

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable

/**
 * Cliente REST de Google Calendar API v3.
 *
 * Crea/actualiza/borra eventos de calendario para que las tareas aparezcan en
 * el Google Calendar del usuario. Usa el access token OAuth obtenido al
 * vincular la cuenta con Google Sign-In (con el scope de Calendar); este
 * repositorio no gestiona el ciclo de vida del token (obtención/refresco),
 * solo lo recibe ya válido en cada llamada.
 *
 * Documentación de la API:
 *   https://developers.google.com/calendar/api/v3/reference/events/insert
 */
class GoogleCalendarRepository(
    // Mismo HttpClient que [FirestoreClient] (Koin lo comparte, ver
    // `AppModule.kt`, mismo patrón que [CloudFunctionsClient]): idéntica
    // configuración de JSON/timeouts a la que este archivo instanciaba por su
    // cuenta, evita duplicar el engine/pool de conexiones de Ktor por
    // plataforma (relevante ahora que hay 4 targets). El `catch (_: Exception)`
    // genérico de este archivo sigue funcionando igual si el
    // `HttpResponseValidator` de FirestoreClient envuelve un error de la API
    // de Calendar en una excepción.
    private val client: HttpClient
) {
    private val calendarBaseUrl = "https://www.googleapis.com/calendar/v3"

    /**
     * Busca un calendario propio/suscrito del usuario cuyo `summary` (nombre
     * visible) coincida, o lo crea si no existe. Idempotente — seguro de
     * llamar cada vez que haga falta sincronizar una tarea.
     *
     * @param accessToken Token OAuth Bearer (scope de Calendar).
     * @param summary Nombre visible del calendario (p. ej. "Task Hub").
     * @return el `calendarId` a usar con [createEvent]/[updateEvent]/[deleteEvent].
     */
    suspend fun ensureCalendar(accessToken: String, summary: String): String {
        val existingId = findCalendarIdByName(accessToken, summary)
        if (existingId != null) return existingId

        val response: CalendarInsertResponse = client.post("$calendarBaseUrl/calendars") {
            header("Authorization", "Bearer $accessToken")
            contentType(ContentType.Application.Json)
            setBody(CalendarInsertRequest(summary = summary))
        }.body()

        return response.id
    }

    /** Busca por nombre en la lista de calendarios del usuario; null si ninguno coincide. */
    private suspend fun findCalendarIdByName(accessToken: String, summary: String): String? {
        // Panel v18 (red/offline): mismo wrapper de reintento (backoff+jitter)
        // ya usado por cualquier lectura de Firestore — antes un timeout/5xx
        // transitorio de la API de Calendar abortaba de inmediato toda la
        // sincronización de una tarea sin reintentar.
        val response: CalendarListResponse = retryTransientReadFailure {
            client.get("$calendarBaseUrl/users/me/calendarList") {
                header("Authorization", "Bearer $accessToken")
                parameter("fields", "items(id,summary)")
            }
        }.body()

        return response.items?.firstOrNull { it.summary == summary }?.id
    }

    /**
     * Crea un evento en el calendario indicado.
     *
     * @param accessToken Token OAuth Bearer (scope de Calendar).
     * @param calendarId Calendario destino, p. ej. de [ensureCalendar] (o "primary").
     * @param summary Título del evento.
     * @param description Descripción del evento (notas de la tarea).
     * @param dueDateEpochMs Fecha límite en epoch millis. Se usa como fecha del
     *                       evento para tareas "once"; para tareas recurrentes
     *                       se usa la fecha de hoy (ver [buildEventRequest]).
     * @return el evento de Google Calendar creado, o lanza excepción en caso de error.
     */
    suspend fun createEvent(
        accessToken: String,
        calendarId: String = "primary",
        summary: String,
        description: String,
        dueDateEpochMs: Long
    ): CalendarEventResponse {
        val response: CalendarEventResponse = client.post(
            "$calendarBaseUrl/calendars/$calendarId/events"
        ) {
            header("Authorization", "Bearer $accessToken")
            contentType(ContentType.Application.Json)
            setBody(buildEventRequest(summary, description, dueDateEpochMs))
        }.body()

        return response
    }

    /**
     * Actualiza un evento existente (p. ej. cambió su título o fecha límite).
     *
     * @param calendarId Calendario donde vive el evento.
     * @param eventId Evento a actualizar, tal como lo devolvió [createEvent].
     */
    suspend fun updateEvent(
        accessToken: String,
        calendarId: String,
        eventId: String,
        summary: String,
        description: String,
        dueDateEpochMs: Long
    ): CalendarEventResponse {
        val response: CalendarEventResponse = client.put(
            "$calendarBaseUrl/calendars/$calendarId/events/$eventId"
        ) {
            header("Authorization", "Bearer $accessToken")
            contentType(ContentType.Application.Json)
            setBody(buildEventRequest(summary, description, dueDateEpochMs))
        }.body()

        return response
    }

    /** Borra un evento, p. ej. cuando se borra la tarea o se desvincula de Calendar. */
    suspend fun deleteEvent(accessToken: String, calendarId: String, eventId: String) {
        client.delete("$calendarBaseUrl/calendars/$calendarId/events/$eventId") {
            header("Authorization", "Bearer $accessToken")
        }
    }

    /**
     * Comprueba si el access token dado sigue siendo válido, con una petición
     * ligera a la API de Calendar (pide solo el campo `id`). Cualquier error
     * HTTP (401 por token expirado/revocado, etc.) se traduce a `false` en
     * vez de propagar la excepción — el llamador solo necesita saber
     * válido/inválido, no la causa concreta.
     */
    suspend fun validateToken(accessToken: String): Boolean {
        return try {
            retryTransientReadFailure {
                client.get("$calendarBaseUrl/calendars/primary") {
                    header("Authorization", "Bearer $accessToken")
                    parameter("fields", "id")
                }
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Construye el body de creación/actualización de evento. Los eventos se
     * mandan como "todo el día" (`date`, sin hora) — un único día, sin fecha
     * de fin distinta ([CalendarEventDateTime.date] igual en `start`/`end`).
     */
    private fun buildEventRequest(
        summary: String,
        description: String,
        dueDateEpochMs: Long
    ): CalendarEventRequest {
        // Determina la fecha del evento (YYYY-MM-DD).
        val dateString = if (dueDateEpochMs > 0) {
            epochMillisToDateString(dueDateEpochMs)
        } else {
            // Sin fecha límite — se usa hoy.
            val now = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
            epochMillisToDateString(now)
        }

        return CalendarEventRequest(
            summary = summary,
            description = description,
            start = CalendarEventDateTime(date = dateString),
            end = CalendarEventDateTime(date = dateString)
        )
    }

    /** Epoch millis → fecha en formato `YYYY-MM-DD` en la zona horaria local, para eventos "todo el día". */
    private fun epochMillisToDateString(epochMillis: Long): String {
        val instant = Instant.fromEpochMilliseconds(epochMillis)
        val local = instant.toLocalDateTime(TimeZone.currentSystemDefault())
        val month = local.monthNumber.toString().padStart(2, '0')
        val day = local.dayOfMonth.toString().padStart(2, '0')
        return "${local.year}-$month-$day"
    }
}

// ── Google Calendar API DTOs ─────────────────────────────

/** Cuerpo de creación/actualización de un evento (`POST`/`PUT .../events`). */
@Serializable
data class CalendarEventRequest(
    val summary: String,
    val description: String,
    val start: CalendarEventDateTime,
    val end: CalendarEventDateTime
)

/** Fecha de inicio/fin de un evento "todo el día" (sin hora, formato `YYYY-MM-DD`). */
@Serializable
data class CalendarEventDateTime(
    val date: String
)

/** Respuesta al crear/actualizar un evento. */
@Serializable
data class CalendarEventResponse(
    val id: String,
    val htmlLink: String? = null,
    val status: String? = null
)

/** Respuesta de `GET users/me/calendarList` (lista de calendarios del usuario). */
@Serializable
data class CalendarListResponse(
    val items: List<CalendarListItem>? = null
)

/** Entrada de la lista de calendarios del usuario. */
@Serializable
data class CalendarListItem(
    val id: String,
    val summary: String? = null
)

/** Cuerpo de `POST calendars` para crear un calendario nuevo. */
@Serializable
data class CalendarInsertRequest(
    val summary: String
)

/** Respuesta al crear un calendario. */
@Serializable
data class CalendarInsertResponse(
    val id: String,
    val summary: String? = null
)