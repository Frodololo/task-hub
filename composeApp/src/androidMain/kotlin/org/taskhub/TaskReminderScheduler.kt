// Programación de recordatorios de vencimiento de tarea vía WorkManager.
// Invocado desde commonMain (`AndroidNotificationScheduler`, la implementación
// `actual` del scheduler de plataforma) al crear/editar/completar una tarea
// con fecha límite. Independiente del flujo de "tarea asignada"/"mensaje
// nuevo" de [NotificationPollWorker]: este es local al dispositivo, no pasa
// por Firestore.
package org.taskhub

import android.content.Context
import org.taskhub.platform.AppLog
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Programa un recordatorio único (no periódico) para avisar del vencimiento
 * de una tarea. Usa WorkManager para que se dispare de forma fiable a la
 * hora exacta, incluso si la app no está en primer plano o el proceso se
 * reinicia entre medias.
 *
 * El recordatorio se fija 1h antes del vencimiento.
 * Si el vencimiento está a menos de 1h vista o ya pasó, no se programa nada.
 */
object TaskReminderScheduler {

    private const val WORK_NAME_PREFIX = "task_reminder_"
    private const val REMINDER_MINUTES_BEFORE = 60

    /**
     * Programa (o reemplaza, si ya había uno para [taskId]) el recordatorio
     * de esta tarea. `enqueueUniqueWork` + [ExistingWorkPolicy.REPLACE]:
     * si la fecha límite cambia (p. ej. el usuario la reprograma), la nueva
     * llamada sustituye el trabajo pendiente en vez de acumular duplicados.
     */
    fun scheduleReminder(
        context: Context,
        taskId: String,
        householdId: String,
        taskTitle: String,
        dueDateEpochMs: Long
    ) {
        val now = System.currentTimeMillis()
        val reminderTime = dueDateEpochMs - (REMINDER_MINUTES_BEFORE * 60 * 1000L)

        if (reminderTime <= now) {
            AppLog.d("TaskReminderScheduler", "Deadline too soon or past, skipping reminder for $taskId")
            return
        }

        val delayMs = reminderTime - now
        val workName = "$WORK_NAME_PREFIX$taskId"

        val inputData = androidx.work.Data.Builder()
            .putString("taskTitle", taskTitle)
            .putString("taskId", taskId)
            .putString("householdId", householdId)
            .putInt("minutesBefore", REMINDER_MINUTES_BEFORE)
            .build()

        val constraints = Constraints.Builder()
            .build()

        val workRequest = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(inputData)
            .setConstraints(constraints)
            .addTag("task_reminder")
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(workName, ExistingWorkPolicy.REPLACE, workRequest)

        AppLog.d("TaskReminderScheduler", "Scheduled reminder for $taskId at $reminderTime (in ${delayMs / 60000} min)")
    }

    /**
     * Cancela el recordatorio pendiente de [taskId], si existe (p. ej. al
     * completar la tarea o borrar su fecha límite). No-op si no había
     * ninguno programado.
     */
    fun cancelReminder(context: Context, taskId: String) {
        val workName = "$WORK_NAME_PREFIX$taskId"
        WorkManager.getInstance(context).cancelUniqueWork(workName)
        AppLog.d("TaskReminderScheduler", "Cancelled reminder for $taskId")
    }
}

/**
 * `Worker` que se dispara a la hora programada por [TaskReminderScheduler]
 * y delega en [NotificationHelper.showTaskReminder] para mostrar la
 * notificación. Los datos de la tarea viajan en [inputData] (serializados
 * como `WorkRequest.Data`) porque WorkManager puede ejecutar esto en un
 * proceso nuevo, sin nada en memoria de la sesión que programó el trabajo.
 */
class ReminderWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    override fun doWork(): Result {
        val taskTitle = inputData.getString("taskTitle") ?: "Tarea"
        val taskId = inputData.getString("taskId") ?: ""
        val householdId = inputData.getString("householdId") ?: ""
        val minutesBefore = inputData.getInt("minutesBefore", 60)

        NotificationHelper.showTaskReminder(
            applicationContext,
            taskTitle,
            taskId,
            householdId,
            minutesBefore
        )

        AppLog.d("ReminderWorker", "Fired reminder for $taskId")
        return Result.success()
    }
}