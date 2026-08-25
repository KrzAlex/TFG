package com.tfg.temieeg.logging

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.tfg.temieeg.data.SessionEntry
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Registra los eventos de la sesión y los exporta a CSV al finalizarla.
 *
 * El archivo se guarda en [Context.getExternalFilesDir] — accesible sin
 * permisos especiales desde Android 10+. Ruta típica en Temi:
 *   /sdcard/Android/data/com.tfg.temieeg/files/
 *
 * Nombres de archivo:
 *   · Sesión manual    → session_YYYY-MM-DD_HH-mm-ss.csv
 *   · Partida de juego → game_<nivel>_YYYY-MM-DD_HH-mm-ss.csv
 *
 * Columnas exportadas:
 *   timestamp, state, concentration, mellow,
 *   alpha, beta, theta, delta, gamma,
 *   signal_quality, gyro_y, gyro_z, acc_x, acc_y, acc_z,
 *   blink, jaw_clench, nod, shake,
 *   robot_action, room_index, room_title,
 *   escape_room_name, module_type, temi_speaking
 */
class SessionLogger(private val context: Context) {

    // Pre-dimensionada para ~10 min a 4 Hz: evita las recopias de crecimiento
    // del ArrayList durante la partida (el camino caliente del logger).
    private val entries = ArrayList<SessionEntry>(INITIAL_CAPACITY)

    /** true si se alcanzó [MAX_ENTRIES] y se están descartando muestras. */
    var truncated: Boolean = false
        private set

    fun log(entry: SessionEntry) {
        // Techo de memoria: una sesión olvidada abierta podía crecer sin límite
        // (~4 muestras/s). Al alcanzarlo dejamos de acumular y avisamos una vez.
        if (entries.size >= MAX_ENTRIES) {
            if (!truncated) {
                truncated = true
                Log.w(TAG, "Límite de $MAX_ENTRIES muestras alcanzado — se dejan de registrar")
            }
            return
        }
        entries.add(entry)
    }

    /**
     * Igual que [exportCSV] pero fuera del hilo principal: serializar miles de
     * filas y escribirlas en disco bloqueaba la UI (riesgo de ANR en sesiones
     * largas). [onDone] se invoca en el hilo principal con el fichero o null.
     */
    fun exportCSVAsync(levelName: String = "", onDone: (File?) -> Unit) {
        val snapshot = ArrayList(entries)   // copia estable para el hilo de fondo
        val main = Handler(Looper.getMainLooper())
        Thread({
            val file = writeCsv(snapshot, levelName)
            main.post { onDone(file) }
        }, "SessionLogger-export").start()
    }

    /**
     * Serializa todas las entradas a un archivo CSV.
     *
     * @param levelName  Nombre del nivel de juego. Si no está vacío, el archivo
     *                   se llama `game_<levelName>_<timestamp>.csv`; en caso contrario,
     *                   `session_<timestamp>.csv`.
     * @return  El [File] creado, o null si no hay entradas o falla la escritura.
     */
    fun exportCSV(levelName: String = ""): File? = writeCsv(entries, levelName)

    private fun writeCsv(rows: List<SessionEntry>, levelName: String): File? {
        if (rows.isEmpty()) return null

        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault())
            .format(Date())

        val safeName = levelName
            .trim()
            .replace(Regex("[^A-Za-z0-9_\\-]"), "_")
            .take(40)
        val prefix = if (safeName.isNotEmpty()) "game_$safeName" else "session"
        val file = File(context.getExternalFilesDir(null), "${prefix}_$timestamp.csv")

        return try {
            file.bufferedWriter().use { writer ->
                writer.write(
                    "timestamp,state," +
                    "concentration,mellow,gamma_activity," +
                    "alpha,beta,theta,delta,gamma," +
                    "signal_quality," +
                    "gyro_y,gyro_z,acc_x,acc_y,acc_z," +
                    "blink,jaw_clench,nod,shake," +
                    "robot_action,room_index,room_title," +
                    "escape_room_name,module_type,temi_speaking\n"
                )
                rows.forEach { e ->
                    writer.write(
                        "${e.timestamp},${e.state}," +
                        "${e.concentration},${e.mellow},${e.gammaActivity}," +
                        "${e.alpha},${e.beta},${e.theta},${e.delta},${e.gamma}," +
                        "${e.signalQuality}," +
                        "${e.gyroY},${e.gyroZ},${e.accX},${e.accY},${e.accZ}," +
                        "${e.blinkEvent.toInt()},${e.jawClenchEvent.toInt()}," +
                        "${e.nodEvent.toInt()},${e.shakeEvent.toInt()}," +
                        "${csvQuote(e.robotAction)},${e.roomIndex},${csvQuote(e.roomTitle)}," +
                        "${csvQuote(e.escapeRoomName)},${e.moduleType},${e.temiSpeaking.toInt()}\n"
                    )
                }
            }
            Log.i(TAG, "CSV exportado: ${file.absolutePath} (${rows.size} filas)")
            file
        } catch (e: Exception) {
            Log.e(TAG, "Error exportando CSV", e)
            null
        }
    }

    fun clearSession() { entries.clear(); truncated = false }
    fun size(): Int = entries.size

    companion object {
        private const val TAG = "SessionLogger"

        /** ~10 min a 4 Hz — capacidad inicial para evitar recopias del ArrayList. */
        private const val INITIAL_CAPACITY = 2_400

        /** Techo de muestras en memoria (~2,5 h a 4 Hz) para no agotar el heap. */
        private const val MAX_ENTRIES = 36_000
        private fun Boolean.toInt() = if (this) 1 else 0

        /** Campo CSV entrecomillado con las comillas internas dobladas (RFC 4180). */
        private fun csvQuote(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
    }
}
