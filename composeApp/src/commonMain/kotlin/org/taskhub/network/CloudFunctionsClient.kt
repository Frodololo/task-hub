// Cliente de bajo nivel para las Cloud Functions "callable HTTPS" de
// `functions/` — ver `docs/recurrencia-backend-cloud-functions-diseno-2026-09-11.md`,
// sección 3. Paralelo a [FirestoreClient], pero para invocar las 4 funciones
// que orquestan completar/deshacer/reasignar tareas en una transacción real
// del servidor en vez de una secuencia de escrituras REST del cliente.
package org.taskhub.network

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import org.taskhub.network.models.CallableRequest
import org.taskhub.network.models.CallableResult

/** Región del diseño (sección 2): `europe-west1`, más cercana a España que el default `us-central1`. */
const val DEFAULT_CLOUD_FUNCTIONS_BASE_URL = "https://europe-west1-task-hub-62f98.cloudfunctions.net"

/**
 * Invoca una Cloud Function "callable HTTPS" reutilizando el mismo
 * [HttpClient] y el mismo [FirestoreClient.bearerToken] que ya gestiona la
 * sesión de Firestore (sin duplicar auth) — ver KDoc de [call].
 */
class CloudFunctionsClient(
    // `@PublishedApi internal` (no `private`): la función pública inline
    // `call` reificada necesita acceso a estas propiedades desde el
    // call-site donde se inline-a — Kotlin no permite que una función pública
    // inline exponga miembros `private` de la clase.
    @PublishedApi internal val client: HttpClient,
    @PublishedApi internal val firestoreClient: FirestoreClient,
    @PublishedApi internal val baseUrl: String = DEFAULT_CLOUD_FUNCTIONS_BASE_URL
) {
    /**
     * POST `$baseUrl/$name` con body `{ "data": data }`, devuelve el campo
     * `result` de `{ "result": R }`.
     *
     * El `HttpClient` que recibe este cliente es el MISMO que usa
     * [FirestoreClient] (Koin lo comparte, ver `AppModule.kt`), que ya trae
     * instalado un `HttpResponseValidator` que intercepta CUALQUIER respuesta
     * >= 400 (para poder dar mensajes de error de Firestore) y la convierte en
     * [FirestoreException] ANTES de que este método pueda inspeccionar
     * `response.status` — por diseño, el body de error de una Cloud Function
     * (`{ "error": {status, message} }`) tiene la MISMA forma que
     * `FirestoreErrorBody` (status/message; el `code` numérico de Firestore
     * queda null vía `ignoreUnknownKeys`), así que ese validador ya nos da
     * `FirestoreException.code` = el `status` de la función (p.ej.
     * `"ABORTED"`/`"FAILED_PRECONDITION"`) y `.message` = el mensaje. Este
     * método solo tiene que traducir esa excepción a [CloudFunctionException]
     * — no hace falta (ni es alcanzable) un parseo manual del body de error.
     */
    suspend inline fun <reified T, reified R> call(name: String, data: T): R {
        val response = try {
            client.post("$baseUrl/$name") {
                with(firestoreClient) { withAuth() }
                contentType(ContentType.Application.Json)
                setBody(CallableRequest(data))
            }
        } catch (e: FirestoreException) {
            // Firestore (y las transacciones que usan las Cloud Functions)
            // responden 409 en conflictos de concurrencia sin `code` simbólico
            // en el body — sin este mapeo caían en "unknown" y
            // [errorCategory] no podía ofrecer "reintentar" como con un
            // ABORTED explícito.
            val status = e.code ?: if (e.statusCode == 409) "ABORTED" else "unknown"
            throw CloudFunctionException(status, e.statusCode, e.message)
        }
        return response.body<CallableResult<R>>().result
    }
}

/**
 * Error de una Cloud Function callable: [status] es el código simbólico
 * (p.ej. `"ABORTED"`, `"NOT_FOUND"`). [httpStatusCode] conserva el status HTTP
 * real de la respuesta (el mismo [FirestoreException.statusCode] que la
 * disparó) para que [errorCategory] pueda clasificar este fallo exactamente
 * igual que uno de Firestore (sin conexión / sin acceso / servidor /
 * operación) — sin este campo se perdía en la conversión y todo fallo de una
 * Cloud Function (completar/deshacer/reasignar tarea) cae en la misma
 * categoría genérica sin importar si fue un 403 o un 500.
 */
class CloudFunctionException(val status: String, val httpStatusCode: Int, message: String) : Exception(message)
