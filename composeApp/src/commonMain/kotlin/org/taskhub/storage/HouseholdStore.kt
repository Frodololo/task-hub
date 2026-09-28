// Capa de persistencia (storage/): referencia local a los hogares del
// usuario (IDs, no los datos completos — ver [TaskCache] para eso).
// Es la fuente de verdad de "a qué hogares pertenezco" entre reinicios,
// para resolverlos sin depender de una consulta de red al arrancar.

package org.taskhub.storage

import com.russhwolf.settings.Settings
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.taskhub.network.models.SpaceType

/**
 * Locally-saved household reference (ID + display info).
 * Used to find households across app restarts without relying on a
 * Firestore collection-group query at every cold start.
 */
@Serializable
data class SavedHousehold(
    val id: String,
    val name: String,
    val inviteCode: String,
    /** True si es el espacio "Personal" auto-creado (sin invitaciones). */
    val isPersonal: Boolean = false,
    val spaceType: SpaceType = SpaceType.HOME,
    /** Emoji elegido manualmente por el creador del hogar. `null` = usar el de [spaceType]. */
    val emoji: String? = null
)

/**
 * Persists household IDs locally via [Settings] (SharedPreferences on Android,
 * NSUserDefaults on iOS) — the stable, offline-first reference for "which
 * households have I joined?".
 */
class HouseholdStore(private val settings: Settings) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Save a household to local storage (no duplicates; updates if already present).
     */
    fun saveHousehold(
        householdId: String,
        householdName: String,
        inviteCode: String,
        isPersonal: Boolean = false,
        spaceType: SpaceType = SpaceType.HOME,
        emoji: String? = null
    ) {
        val current = getSavedHouseholds().toMutableList()
        val existing = current.indexOfFirst { it.id == householdId }
        val entry = SavedHousehold(
            id = householdId,
            name = householdName,
            inviteCode = inviteCode,
            isPersonal = isPersonal,
            spaceType = spaceType,
            emoji = emoji
        )
        if (existing >= 0) {
            current[existing] = entry
        } else {
            current.add(entry)
        }
        settings.putString(KEY_SAVED_HOUSEHOLDS, json.encodeToString(current))
    }

    /**
     * Returns all locally-saved households.
     */
    fun getSavedHouseholds(): List<SavedHousehold> {
        val raw = settings.getString(KEY_SAVED_HOUSEHOLDS, "")
        if (raw.isEmpty()) return emptyList()
        return try {
            json.decodeFromString<List<SavedHousehold>>(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Remove a household from local storage by its ID.
     */
    fun removeHousehold(householdId: String) {
        val current = getSavedHouseholds().toMutableList()
        current.removeAll { it.id == householdId }
        settings.putString(KEY_SAVED_HOUSEHOLDS, json.encodeToString(current))
    }

    /**
     * Sustituye la lista completa de hogares guardados por [households] en UNA
     * sola escritura — usado por [org.taskhub.network.HouseholdRepository.reconcileHouseholds]
     * para podar varios hogares de golpe sin encadenar N llamadas a
     * [removeHousehold] desde coroutines paralelas: cada una hace su propio
     * read-modify-write sobre la MISMA lista sin serializar, así que la
     * última en escribir "gana" y puede resucitar un hogar que otra
     * coroutine del mismo lote ya había podado (panel v7, Exp. 6, MENOR).
     * Idempotente: llamarla dos veces con la misma lista dejan el mismo
     * resultado.
     */
    fun replaceSavedHouseholds(households: List<SavedHousehold>) {
        settings.putString(KEY_SAVED_HOUSEHOLDS, json.encodeToString(households))
    }

    // ── Personal space ─────────────────────────────────────

    /**
     * Returns the ID of the auto-created "Personal" household, or null if
     * it hasn't been created yet (first app launch).
     */
    fun getPersonalHouseholdId(): String? {
        return settings.getString(KEY_PERSONAL_HOUSEHOLD_ID, "").ifEmpty { null }
    }

    /**
     * Persist the Personal household ID separately so it can be queried
     * without scanning all households.
     */
    fun savePersonalHousehold(id: String) {
        settings.putString(KEY_PERSONAL_HOUSEHOLD_ID, id)
    }

    /**
     * Reemplaza el espacio Personal guardado por el ID indicado, eliminando
     * cualquier entrada `isPersonal` previa (p. ej. el espacio por-dispositivo
     * creado antes de vincular Google). Mantiene el listado sin duplicados.
     */
    fun replacePersonalHousehold(id: String) {
        val current = getSavedHouseholds().filterNot { it.isPersonal }.toMutableList()
        current.add(SavedHousehold(id = id, name = "Personal", inviteCode = "", isPersonal = true))
        settings.putString(KEY_SAVED_HOUSEHOLDS, json.encodeToString(current))
        savePersonalHousehold(id)
    }

    /**
     * Borra todo el estado local de hogares (lista guardada + espacio
     * Personal). Usado por el flujo "eliminar cuenta"
     * ([org.taskhub.ui.models.GoogleAuthManager.deleteAccount]): tras borrar
     * los hogares en Firestore, no debe quedar ningún rastro local que
     * intente reconciliarlos en el próximo arranque.
     */
    fun clearAll() {
        settings.remove(KEY_SAVED_HOUSEHOLDS)
        settings.remove(KEY_PERSONAL_HOUSEHOLD_ID)
    }

    companion object {
        private const val KEY_SAVED_HOUSEHOLDS = "taskhub_saved_households"
        private const val KEY_PERSONAL_HOUSEHOLD_ID = "taskhub_personal_household_id"
    }
}