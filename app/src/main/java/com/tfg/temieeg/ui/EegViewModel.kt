package com.tfg.temieeg.ui

import androidx.lifecycle.ViewModel
import com.tfg.temieeg.data.MentalState
import com.tfg.temieeg.data.MuseState
import com.tfg.temieeg.eeg.HeadGestureDetector
import com.tfg.temieeg.eeg.MentalStateProcessor
import com.tfg.temieeg.eeg.MorseDecoder
import com.tfg.temieeg.game.EscapeRoomEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Dueño del pipeline BCI y de su estado observable.
 *
 * Al vivir en un [ViewModel], los componentes de procesado (clasificador de estado
 * mental, detector de gestos, decodificador Morse y motor de escape room) y sus
 * ajustes sobreviven a recreaciones de la Activity (rotación / cambios de config)
 * y quedan aislados de la UI: [MainActivity] los observa y delega en ellos en vez
 * de poseerlos directamente. Es el primer paso hacia una arquitectura MVVM.
 */
class EegViewModel : ViewModel() {

    val processor           = MentalStateProcessor()
    val headGestureDetector = HeadGestureDetector()
    val morseDecoder        = MorseDecoder()
    val escapeRoomEngine    = EscapeRoomEngine()

    private val _mentalState = MutableStateFlow(MentalState.NEUTRAL)
    /** Último estado mental clasificado (fuente única de verdad para la UI). */
    val mentalState: StateFlow<MentalState> = _mentalState.asStateFlow()

    private val _metrics = MutableStateFlow<Map<String, Float>>(emptyMap())
    /** Últimas métricas EEG derivadas (concentration, mellow, bandas brutas…). */
    val metrics: StateFlow<Map<String, Float>> = _metrics.asStateFlow()

    /**
     * Procesa una muestra del MUSE: alimenta el clasificador y publica el estado
     * y las métricas resultantes en los flujos observables. Devuelve el estado.
     */
    fun process(sample: MuseState): MentalState {
        processor.addSample(sample)
        val state = processor.getCurrentState()
        _mentalState.value = state
        _metrics.value = processor.getMetrics()
        return state
    }

    override fun onCleared() {
        escapeRoomEngine.abort()
        morseDecoder.clear()
    }
}
