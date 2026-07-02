package com.tfg.temieeg.data

/**
 * Entrada del log de sesión — snapshot completo de métricas EEG, movimiento,
 * gestos BCI y contexto de juego en un instante dado.
 *
 * Los campos *Event (blinkEvent, nodEvent, shakeEvent, jawClenchEvent) son true
 * únicamente en el tick en que el gesto fue detectado; el resto del tiempo son false.
 *
 * Se exporta a CSV al finalizar la sesión (ver SessionLogger).
 */
data class SessionEntry(
    val timestamp: Long,
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
    val gyroY: Float,   // pitch — asentir (°/s)
    val gyroZ: Float,   // yaw   — negar   (°/s)
    val accX: Float,
    val accY: Float,
    val accZ: Float,

    // ── Eventos de gesto (true solo el tick en que se disparó) ───────────────
    val blinkEvent: Boolean,
    val jawClenchEvent: Boolean,
    val nodEvent: Boolean,
    val shakeEvent: Boolean,

    // ── Robot ─────────────────────────────────────────────────────────────────
    val robotAction: String,

    // ── Contexto de juego ────────────────────────────────────────────────────
    val roomIndex: Int,            // -1 si no hay partida activa
    val roomTitle: String,         // "" si no hay partida activa
    val escapeRoomName: String = "",  // nombre del nivel (p.ej. "El Escape Clásico")
    val moduleType: String     = "",  // clase del módulo activo ("CalmModule", "MorseModule"…)
    val temiSpeaking: Boolean  = false // true si el robot estaba hablando en este instante
)
