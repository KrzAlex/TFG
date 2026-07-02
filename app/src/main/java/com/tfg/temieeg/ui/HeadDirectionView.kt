package com.tfg.temieeg.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Vista personalizada que muestra la orientación de la cabeza del usuario
 * a partir de los datos del acelerómetro del MUSE (canal OSC /muse/acc).
 *
 * Representación:
 *  - Círculo exterior = límite de movimiento (±2 g)
 *  - Anillo interior  = referencia ±1 g
 *  - Flecha azul      = dirección actual de la cabeza (pitch + roll)
 *  - X (horizontal)   = inclinación lateral (roll)
 *  - Z (vertical)     = inclinación frente/atrás (pitch)
 *
 * NOTA: el acelerómetro no detecta giro horizontal (yaw).
 */
class HeadDirectionView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var accX = 0f
    private var accY = 0f
    private var accZ = 0f

    // ── Pinturas ─────────────────────────────────────────────────────────────

    private val paintRingOuter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#BDBDBD")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val paintRingInner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E0E0E0")
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    private val paintCross = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#EEEEEE")
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    private val paintArrow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1565C0")
        style = Paint.Style.STROKE
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
    }
    private val paintTip = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1565C0")
        style = Paint.Style.FILL
    }
    private val paintCenter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#616161")
        style = Paint.Style.FILL
    }
    private val paintLabelCenter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#9E9E9E")
        textSize = 28f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val paintLabelRight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#9E9E9E")
        textSize = 28f
        textAlign = Paint.Align.RIGHT
        typeface = Typeface.DEFAULT_BOLD
    }
    private val paintLabelLeft = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#9E9E9E")
        textSize = 28f
        textAlign = Paint.Align.LEFT
        typeface = Typeface.DEFAULT_BOLD
    }
    private val paintValues = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#424242")
        textSize = 23f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.MONOSPACE
    }

    // ── API pública ───────────────────────────────────────────────────────────

    /** Actualiza los valores del acelerómetro y redibuja la vista. */
    fun update(x: Float, y: Float, z: Float) {
        accX = x
        accY = y
        accZ = z
        invalidate()
    }

    // ── Dibujado ──────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        // Reservar espacio inferior para los valores numéricos
        val valRowH = 36f
        val padV    = 8f
        val padH    = 38f   // espacio para etiquetas laterales

        val cx      = w / 2f
        val circleH = h - valRowH - padV * 2
        val cy      = padV + circleH / 2f
        val radius  = min(w / 2f - padH, circleH / 2f - 4f).coerceAtLeast(10f)

        // Anillos de referencia
        canvas.drawCircle(cx, cy, radius, paintRingOuter)
        canvas.drawCircle(cx, cy, radius * 0.5f, paintRingInner)

        // Cruz central
        canvas.drawLine(cx - radius, cy, cx + radius, cy, paintCross)
        canvas.drawLine(cx, cy - radius, cx, cy + radius, paintCross)

        // Etiquetas cardinales
        canvas.drawText("↑", cx, cy - radius - 2f, paintLabelCenter)
        canvas.drawText("↓", cx, cy + radius + 28f, paintLabelCenter)
        canvas.drawText("←", cx - radius - 2f, cy + 9f, paintLabelRight)
        canvas.drawText("→", cx + radius + 2f, cy + 9f, paintLabelLeft)

        // ── Flecha de orientación ──────────────────────────────────────────
        // Mapeo: accX → componente horizontal  (tilt lateral / roll)
        //        accZ → componente vertical    (nod adelante-atrás / pitch)
        // Escala: 1 g ≡ 80 % del radio (el círculo exterior = ±1.25 g aprox.)
        val scale = radius * 0.8f
        var dx = accX * scale
        var dy = accZ * scale   // Z positivo = inclinar cabeza hacia adelante → flecha abajo

        // Limitar al interior del círculo
        val len = sqrt(dx * dx + dy * dy)
        val maxLen = radius * 0.92f
        if (len > maxLen && len > 0f) {
            dx = dx / len * maxLen
            dy = dy / len * maxLen
        }

        val tipX = cx + dx
        val tipY = cy + dy

        canvas.drawLine(cx, cy, tipX, tipY, paintArrow)
        canvas.drawCircle(tipX, tipY, 12f, paintTip)
        canvas.drawCircle(cx, cy, 6f, paintCenter)

        // Valores numéricos
        canvas.drawText(
            "X:${"%.2f".format(accX)}  Y:${"%.2f".format(accY)}  Z:${"%.2f".format(accZ)}",
            cx, h - padV, paintValues
        )
    }
}
