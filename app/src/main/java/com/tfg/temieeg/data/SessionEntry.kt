package com.tfg.temieeg.data

/**
 * Entrada del log de sesión — snapshot completo de métricas EEG, movimiento,
 * gestos BCI y contexto de juego en un instante dado.
 *
 * Los campos *Event (blinkEvent, nodEvent, shakeEvent, jawClenchEvent) son true
 * únicamente en el tick en que el gesto fue detectado; el resto del tiempo son false.
 *
 * [event] y [eventDetail] llevan además los sucesos discretos de la partida
 * (entrar en una sala, superarla, fallar, bifurcar…) que antes no quedaban
 * registrados y sin los cuales no se pueden calcular tiempos por sala ni
 * número de intentos. Van en la misma fila que el tick para no partir el CSV
 * en dos ficheros: la mayoría de filas los llevan vacíos.
 *
 * Se exporta a CSV al finalizar la sesión (ver SessionLogger).
 */
data class SessionEntry(
    val timestamp: Long,
    /** Milisegundos desde el inicio de la sesión — evita tener que restar en el análisis. */
    val elapsedMs: Long = 0L,
    val state: MentalState,

    // ── Índices procesados ────────────────────────────────────────────────────
    val concentration: Float,
    val mellow: Float,
    val gammaActivity: Float,   // γ/(α+β+θ+γ) — discrimina ATTENTION de STRESS

    // ── Bandas EEG brutas (log10-potencia) ───────────────────────────────────
    val alpha: Float,
    val beta: Float,
    val theta: Float,
    val delta: Float,
    val gamma: Float,

    // ── Calidad de señal (HSI media, 1=buena · 4=sin contacto) ───────────────
    val signalQuality: Float,

    // ── Movimiento ───────────────────────────────────────────────────────────
    val gyroX: Float = 0f,   // roll  — inclinar la cabeza (°/s)
    val gyroY: Float,        // pitch — asentir (°/s)
    val gyroZ: Float,        // yaw   — negar   (°/s)
    val accX: Float,
    val accY: Float,
    val accZ: Float,

    // ── Eventos de gesto (true solo el tick en que se disparó) ───────────────
    val blinkEvent: Boolean,
    val jawClenchEvent: Boolean,
    val nodEvent: Boolean,
    val shakeEvent: Boolean,

    // ── Dispositivo y entorno ────────────────────────────────────────────────
    val battery: Int = -1,        // % de la diadema, -1 si se desconoce
    val noiseLevel: Int = -1,     // amplitud del micrófono (0–32767), -1 si está apagado

    // ── Robot ─────────────────────────────────────────────────────────────────
    val robotAction: String,

    // ── Contexto de juego ────────────────────────────────────────────────────
    val roomIndex: Int,            // -1 si no hay partida activa
    val roomTitle: String,         // "" si no hay partida activa
    val escapeRoomName: String = "",  // nombre del nivel (p.ej. "El Escape Clásico")
    val moduleType: String     = "",  // clase del módulo activo ("CalmModule", "MorseModule"…)
    val temiSpeaking: Boolean  = false, // true si el robot estaba hablando en este instante

    // ── Suceso discreto de la partida (vacío en la mayoría de filas) ─────────
    val event: String       = "",  // ver SessionEvent
    val eventDetail: String = ""   // texto libre asociado al suceso
)

/**
 * Sucesos discretos que se registran en el CSV además del muestreo continuo.
 * Son los que permiten reconstruir la partida: cuánto duró cada sala, cuántos
 * intentos hicieron falta y qué rama se tomó en las bifurcaciones.
 */
object SessionEvent {
    const val GAME_START    = "GAME_START"     // detalle: nombre del nivel
    const val GAME_END      = "GAME_END"       // detalle: "completed" | "aborted"
    const val ROOM_START    = "ROOM_START"     // detalle: "<índice>/<total> <título>"
    const val ROOM_SUCCESS  = "ROOM_SUCCESS"   // detalle: mensaje de feedback
    const val ROOM_FAIL     = "ROOM_FAIL"      // detalle: mensaje de feedback
    const val ROOM_SKIP     = "ROOM_SKIP"      // salto por el botón de rescate
    const val BRANCH        = "BRANCH"         // detalle: "sala N -> sala M"
    const val MORSE_TARGET  = "MORSE_TARGET"   // detalle: "letra código"
    const val MORSE_LETTER  = "MORSE_LETTER"   // detalle: letra decodificada
    const val TTS_START     = "TTS_START"      // detalle: texto que dice el robot
    const val TTS_END       = "TTS_END"
    const val VIDEO_START   = "VIDEO_START"
    const val VIDEO_END     = "VIDEO_END"
    const val CALIBRATION   = "CALIBRATION"    // detalle: umbrales resultantes
}

/**
 * Cabecera del CSV: condiciones en que se grabó la sesión. Sin esto no se puede
 * reproducir ni comparar un registro con otro, porque los umbrales de
 * clasificación son ajustables y personalizables por usuario.
 */
data class SessionMeta(
    val appVersion: String,
    val device: String,
    val levelName: String,
    val connectionMode: String,
    val stressThreshold: Float,
    val attentionThreshold: Float,
    val calmThreshold: Float,
    val gammaThreshold: Float,
    val blinkDebounceMs: Long,
    val nodThreshold: Float,
    val shakeThreshold: Float,
    val startedAt: String
)
