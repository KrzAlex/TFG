package com.tfg.temieeg.data

/**
 * Snapshot de todas las métricas recibidas del MUSE vía Mind Monitor (OSC).
 *
 * Bandas en log10-potencia (μV²): alphaAbsolute, betaAbsolute,
 * thetaAbsolute, deltaAbsolute, gammaAbsolute.
 * Los índices [concentration] y [mellow] se calculan en MentalStateProcessor
 * a partir de alpha, beta y theta.
 */
data class MuseState(

    // ── Bandas EEG brutas (log10-potencia, media de 4 electrodos) ────────────

    /** Alpha  8–13 Hz — ritmo de reposo/relajación. */
    val alphaAbsolute: Float = 0f,

    /** Beta  13–30 Hz — alerta, estrés, foco activo. */
    val betaAbsolute: Float = 0f,

    /** Theta  4–8 Hz — somnolencia, meditación, creatividad. */
    val thetaAbsolute: Float = 0f,

    /** Delta  0.5–4 Hz — sueño profundo. */
    val deltaAbsolute: Float = 0f,

    /** Gamma  30–100 Hz — procesamiento cognitivo de alto nivel (Muse 2+). */
    val gammaAbsolute: Float = 0f,

    // ── Calidad de señal (/muse/elements/horseshoe) ──────────────────────────

    /**
     * Media de los 4 electrodos (TP9, AF7, AF8, TP10).
     * 1.0 = buen contacto · 2.0 = contacto regular · 4.0 = sin contacto.
     * -1.0 = aún no se ha recibido ningún paquete HSI (estado inicial).
     * MentalStateProcessor descarta muestras si > [SIGNAL_BAD_THRESHOLD].
     */
    val signalQuality: Float = -1f,

    // ── Movimiento ───────────────────────────────────────────────────────────

    /** Acelerómetro bruto en g (±2 g). */
    val accX: Float = 0f,
    val accY: Float = 0f,
    val accZ: Float = 0f,

    /** Giroscopio en °/s. */
    val gyroX: Float = 0f,
    val gyroY: Float = 0f,
    val gyroZ: Float = 0f,

    // ── Otros ────────────────────────────────────────────────────────────────

    /** Batería del MUSE (0–100 %). -1 = desconocido. */
    val battery: Int = -1,

    /** true el tick en que se detectó un parpadeo. */
    val blink: Boolean = false,

    /** true el tick en que se detectó un apriete de mandíbula. */
    val jawClench: Boolean = false,

    /** Marca de tiempo de captura. */
    val timestamp: Long = System.currentTimeMillis()
)
