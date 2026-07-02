package com.tfg.temieeg.data

/**
 * Estado cognitivo/emocional inferido a partir del EEG.
 * Determina el comportamiento adaptativo del robot Temi.
 */
enum class MentalState {
    /** Estrés elevado — beta alto, mellow bajo. */
    STRESS,

    /** Atención alta — concentración elevada. */
    ATTENTION,

    /** Calma — mellow alto, beta bajo. */
    CALM,

    /** Estado base / no clasificado. */
    NEUTRAL
}
