package com.tfg.temieeg.logging

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.tfg.temieeg.data.SessionEntry
import com.tfg.temieeg.data.SessionMeta
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Acumula el muestreo de la sesión y lo exporta a CSV.
 *
 * El fichero se guarda en [Context.getExternalFilesDir] — accesible sin
 * permisos especiales y compartible mediante FileProvider.
 *
 * Formato: unas líneas de cabecera que empiezan por `#` con las condiciones de
 * la grabación (versión, dispositivo, umbrales en uso…), y después el CSV con
 * una fila por muestra. Las líneas `#` son el convenio habitual de comentario:
 * `files/dashboard.html` las interpreta, y pandas las salta con `comment='#'`.
 *
 * Columnas: timestamp, elapsed_ms, state, concentration, mellow, gamma_activity,
 * alpha, beta, theta, delta, gamma, signal_quality, gyro_x, gyro_y, gyro_z,
 * acc_x, acc_y, acc_z, blink, jaw_clench, nod, shake, battery, noise,
 * robot_action, room_index, room_title, escape_room_name, module_type,
 * temi_speaking, event, event_detail
 */
class SessionLogger(private val context: Context) {

    // Pre-dimensionada para ~10 min a 4 Hz: evita las recopias de crecimiento
    // del ArrayList durante la partida (el camino caliente del logger).
    private val entries = ArrayList<SessionEntry>(INITIAL_CAPACITY)

    /** Condiciones de la grabación. Se fija al arrancar la sesión. */
    var meta: SessionMeta? = null

    /** Instante de inicio, para calcular `elapsed_ms` de cada muestra. */
    private var startedAtMs: Long = 0L

    /** true si se alcanzó [MAX_ENTRIES] y se están descartando muestras. */
    var truncated: Boolean = false
        private set

    /**
     * Marca el arranque de la sesión. El reloj relativo NO se ancla aquí sino en
     * la primera muestra registrada: las muestras traen su propia marca de
     * tiempo (la del paquete del MUSE), y mezclarla con el reloj de pared del
     * momento de arranque daba desfases.
     */
    fun startSession(meta: SessionMeta) {
        this.meta = meta
        startedAtMs = 0L
    }

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
        if (startedAtMs == 0L) startedAtMs = entry.timestamp
        entries.add(entry.copy(elapsedMs = entry.timestamp - startedAtMs))
    }

    /**
     * Registra un suceso discreto de la partida (entrar en una sala, superarla,
     * bifurcar…). Se guarda como una fila propia con los campos de señal a cero:
     * lo que importa de ella es el instante y el par evento/detalle.
     */
    fun logEvent(event: String, detail: String = "", context: SessionEntry? = null) {
        val now = System.currentTimeMillis()
        val base = context ?: entries.lastOrNull()
        val row = base?.copy(
            timestamp   = now,
            blinkEvent  = false, jawClenchEvent = false,
            nodEvent    = false, shakeEvent     = false,
            event       = event,
            eventDetail = detail
        ) ?: SessionEntry(
            timestamp = now, state = com.tfg.temieeg.data.MentalState.NEUTRAL,
            concentration = 0f, mellow = 0f, gammaActivity = 0f,
            alpha = 0f, beta = 0f, theta = 0f, delta = 0f, gamma = 0f,
            signalQuality = -1f, gyroY = 0f, gyroZ = 0f,
            accX = 0f, accY = 0f, accZ = 0f,
            blinkEvent = false, jawClenchEvent = false,
            nodEvent = false, shakeEvent = false,
            robotAction = "", roomIndex = -1, roomTitle = "",
            event = event, eventDetail = detail
        )
        log(row)
        Log.d(TAG, "Evento: $event ${if (detail.isNotEmpty()) "— $detail" else ""}")
    }

    /**
     * Igual que [exportCSV] pero fuera del hilo principal: serializar miles de
     * filas y escribirlas en disco bloqueaba la UI (riesgo de ANR en sesiones
     * largas). [onDone] se invoca en el hilo principal con el fichero o null.
     */
    fun exportCSVAsync(levelName: String = "", onDone: (File?) -> Unit) {
        val snapshot = ArrayList(entries)   // copia estable para el hilo de fondo
        val snapMeta = meta
        val main = Handler(Looper.getMainLooper())
        Thread({
            val file = writeCsv(snapshot, levelName, snapMeta)
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
    fun exportCSV(levelName: String = ""): File? = writeCsv(entries, levelName, meta)

    private fun writeCsv(rows: List<SessionEntry>, levelName: String, meta: SessionMeta?): File? {
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
                writeHeader(writer, meta, rows.size)
                writer.write(
                    "timestamp,elapsed_ms,state," +
                    "concentration,mellow,gamma_activity," +
                    "alpha,beta,theta,delta,gamma," +
                    "signal_quality," +
                    "gyro_x,gyro_y,gyro_z,acc_x,acc_y,acc_z," +
                    "blink,jaw_clench,nod,shake," +
                    "battery,noise," +
                    "robot_action,room_index,room_title," +
                    "escape_room_name,module_type,temi_speaking," +
                    "event,event_detail\n"
                )
                rows.forEach { e ->
                    writer.write(
                        "${e.timestamp},${e.elapsedMs},${e.state}," +
                        "${e.concentration},${e.mellow},${e.gammaActivity}," +
                        "${e.alpha},${e.beta},${e.theta},${e.delta},${e.gamma}," +
                        "${e.signalQuality}," +
                        "${e.gyroX},${e.gyroY},${e.gyroZ},${e.accX},${e.accY},${e.accZ}," +
                        "${e.blinkEvent.toInt()},${e.jawClenchEvent.toInt()}," +
                        "${e.nodEvent.toInt()},${e.shakeEvent.toInt()}," +
                        "${e.battery},${e.noiseLevel}," +
                        "${csvQuote(e.robotAction)},${e.roomIndex},${csvQuote(e.roomTitle)}," +
                        "${csvQuote(e.escapeRoomName)},${e.moduleType},${e.temiSpeaking.toInt()}," +
                        "${e.event},${csvQuote(e.eventDetail)}\n"
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

    /**
     * Cabecera con las condiciones de la grabación. Los umbrales son ajustables
     * y personalizables por usuario, así que sin ellos dos sesiones no son
     * comparables entre sí.
     */
    private fun writeHeader(writer: java.io.Writer, meta: SessionMeta?, rows: Int) {
        writer.write("# temi_eeg_session v2\n")
        writer.write("# rows=$rows\n")
        if (meta == null) return
        writer.write("# app_version=${meta.appVersion}\n")
        writer.write("# device=${meta.device}\n")
        writer.write("# level=${meta.levelName}\n")
        writer.write("# connection=${meta.connectionMode}\n")
        writer.write("# started_at=${meta.startedAt}\n")
        writer.write("# threshold_stress=${meta.stressThreshold}\n")
        writer.write("# threshold_attention=${meta.attentionThreshold}\n")
        writer.write("# threshold_calm=${meta.calmThreshold}\n")
        writer.write("# threshold_gamma=${meta.gammaThreshold}\n")
        writer.write("# blink_debounce_ms=${meta.blinkDebounceMs}\n")
        writer.write("# threshold_nod=${meta.nodThreshold}\n")
        writer.write("# threshold_shake=${meta.shakeThreshold}\n")
    }

    fun clearSession() {
        entries.clear()
        truncated = false
        startedAtMs = 0L
    }

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
