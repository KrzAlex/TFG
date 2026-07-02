package com.tfg.temieeg.eeg

import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Decodificador de código Morse por parpadeo.
 *
 * Protocolo:
 *   · 1 parpadeo           = PUNTO  (·)
 *   · 2 parpadeos rápidos  = RAYA   (—)   [intervalo < dashWindowMs]
 *
 * Separación de letras  : silencio > [letterGapMs]  (≈ 1 500 ms)
 * Separación de palabras: silencio > [wordGapMs]    (≈ 3 000 ms)
 *
 * Ajusta [dashWindowMs] para la velocidad del usuario.
 * La activación del modo Morse debe bajar el debounce del OSCReceiver a 150 ms.
 *
 * Callbacks:
 *   [onSymbolsUpdated]  — símbolo en curso (p. ej. "·—·")
 *   [onLetterDecoded]   — letra completada (p. ej. "R")
 *   [onWordCompleted]   — espacio entre palabras
 *   [onDecodedTextChanged] — cadena completa acumulada
 */
class MorseDecoder(
    /** Ventana para el segundo parpadeo (doble = raya). Subir = más fácil. */
    var dashWindowMs: Long = DASH_WINDOW_DEFAULT_MS,
    /** Silencio tras el que se cierra la letra. */
    var letterGapMs: Long = LETTER_GAP_DEFAULT_MS,
    /** Silencio adicional tras el que se inserta un espacio. */
    var wordGapMs: Long = WORD_GAP_DEFAULT_MS
) {

    var onSymbolsUpdated: ((String) -> Unit)? = null
    var onLetterDecoded: ((Char) -> Unit)? = null
    var onWordCompleted: (() -> Unit)? = null
    var onDecodedTextChanged: ((String) -> Unit)? = null

    /** Texto decodificado acumulado. */
    var decodedText: String = ""; private set

    private val currentSymbols = StringBuilder()   // símbolos de la letra en curso

    private val handler = Handler(Looper.getMainLooper())

    @Volatile private var lastBlinkTime  = 0L
    @Volatile private var pendingBlinks  = 0        // parpadeos pendientes de clasificar

    // Runnable que se ejecuta tras dashWindowMs para clasificar el primer parpadeo
    private val classifyRunnable = Runnable { classifyPending() }

    // Runnable que cierra la letra en curso tras letterGapMs
    private val letterRunnable = Runnable { closeLetter() }

    // Runnable que cierra la palabra tras wordGapMs
    private val wordRunnable = Runnable { closeWord() }

    // ── API pública ───────────────────────────────────────────────────────────

    /**
     * Llamar cada vez que el OSCReceiver detecta un parpadeo válido.
     * Debe usarse con blinkDebounceMs ≤ 150 ms en modo Morse.
     */
    fun recordBlink() {
        val now = System.currentTimeMillis()

        // Cancelar cierres pendientes mientras el usuario sigue parpadeando
        handler.removeCallbacks(letterRunnable)
        handler.removeCallbacks(wordRunnable)

        val gap = now - lastBlinkTime
        lastBlinkTime = now

        if (pendingBlinks > 0 && gap < dashWindowMs) {
            // Segundo parpadeo rápido → ya tenemos una raya
            handler.removeCallbacks(classifyRunnable)
            pendingBlinks = 0
            addSymbol(DASH)
        } else {
            // Primer parpadeo (o llegó tarde → el anterior ya se clasificó como punto)
            pendingBlinks = 1
            handler.removeCallbacks(classifyRunnable)
            handler.postDelayed(classifyRunnable, dashWindowMs)
        }

        // Programar cierre de letra si hay silencio
        handler.postDelayed(letterRunnable, letterGapMs)
    }

    /** Borra el texto decodificado y los símbolos en curso. */
    fun clear() {
        handler.removeCallbacks(classifyRunnable)
        handler.removeCallbacks(letterRunnable)
        handler.removeCallbacks(wordRunnable)
        pendingBlinks = 0
        currentSymbols.clear()
        decodedText = ""
        onSymbolsUpdated?.invoke("")
        onDecodedTextChanged?.invoke("")
    }

    // ── Lógica interna ────────────────────────────────────────────────────────

    /** El tiempo de dashWindowMs expiró sin un segundo parpadeo → era un punto. */
    private fun classifyPending() {
        if (pendingBlinks > 0) {
            pendingBlinks = 0
            addSymbol(DOT)
        }
    }

    private fun addSymbol(symbol: Char) {
        currentSymbols.append(symbol)
        Log.d(TAG, "Símbolo: $symbol  →  en curso: \"$currentSymbols\"")
        onSymbolsUpdated?.invoke(currentSymbols.toString())
    }

    private fun closeLetter() {
        if (currentSymbols.isEmpty()) {
            // Retraso antes de declarar palabra
            handler.postDelayed(wordRunnable, wordGapMs - letterGapMs)
            return
        }

        val code   = currentSymbols.toString()
        val letter = MORSE_TABLE[code]
        currentSymbols.clear()

        if (letter != null) {
            decodedText += letter
            Log.i(TAG, "Letra: $letter  (código: $code)  →  texto: \"$decodedText\"")
            onLetterDecoded?.invoke(letter)
            onDecodedTextChanged?.invoke(decodedText)
        } else {
            Log.w(TAG, "Código desconocido: \"$code\" — ignorado")
        }

        onSymbolsUpdated?.invoke("")

        // Iniciar cuenta para espacio de palabra
        handler.postDelayed(wordRunnable, wordGapMs - letterGapMs)
    }

    private fun closeWord() {
        if (decodedText.isNotEmpty() && !decodedText.endsWith(' ')) {
            decodedText += ' '
            Log.i(TAG, "Espacio de palabra → texto: \"$decodedText\"")
            onWordCompleted?.invoke()
            onDecodedTextChanged?.invoke(decodedText)
        }
    }

    // ── Tabla Morse ───────────────────────────────────────────────────────────

    companion object {
        private const val DOT  = '·'
        private const val DASH = '—'

        const val DASH_WINDOW_DEFAULT_MS  = 350L   // ventana para el segundo parpadeo
        const val LETTER_GAP_DEFAULT_MS   = 1500L  // silencio → cierre de letra
        const val WORD_GAP_DEFAULT_MS     = 3000L  // silencio → espacio de palabra

        private const val TAG = "MorseDecoder"

        /** Tabla de código Morse internacional (ITU-R M.1677-1). */
        val MORSE_TABLE: Map<String, Char> = mapOf(
            // Letras
            "·—"    to 'A',
            "—···"  to 'B',
            "—·—·"  to 'C',
            "—··"   to 'D',
            "·"     to 'E',
            "··—·"  to 'F',
            "——·"   to 'G',
            "····"  to 'H',
            "··"    to 'I',
            "·———"  to 'J',
            "—·—"   to 'K',
            "·—··"  to 'L',
            "——"    to 'M',
            "—·"    to 'N',
            "———"   to 'O',
            "·——·"  to 'P',
            "——·—"  to 'Q',
            "·—·"   to 'R',
            "···"   to 'S',
            "—"     to 'T',
            "··—"   to 'U',
            "···—"  to 'V',
            "·——"   to 'W',
            "—··—"  to 'X',
            "—·——"  to 'Y',
            "——··"  to 'Z',
            // Dígitos
            "·————" to '1',
            "··———" to '2',
            "···——" to '3',
            "····—" to '4',
            "·····" to '5',
            "—····" to '6',
            "——···" to '7',
            "———··" to '8',
            "————·" to '9',
            "—————" to '0'
        )
    }
}
