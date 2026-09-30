package com.exclusivo.moon

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Franja del borde de la pantalla (esquina inferior izquierda por defecto): dibuja la vista, la coloca según las
 * preferencias fr_* y detecta toque, doble toque, mantener y deslizar. Cuando el gesto se cumple llama a [alAbrir].
 * Preferencias (dp): fr_alto, fr_ancho, fr_x (desde la izquierda), fr_sube (desde abajo).
 * Interruptores: fr_oculto (por defecto si: invisible pero activa) y fr_mover (desbloqueada: se ve y se arrastra).
 * «gesto» = la franja está puesta.
 */
class Franja(
    private val ctx: Context,
    private val pr: SharedPreferences,
    private val h: Handler,
    private val hayPanel: () -> Boolean,
    private val alAbrir: () -> Unit,
) {
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val d = ctx.resources.displayMetrics.density
    private var esquina: View? = null
    private var lp: WindowManager.LayoutParams? = null
    private var agregada = false

    private var x0 = 0f; private var y0 = 0f
    private var px0 = 0; private var py0 = 0
    private var t0 = 0L
    private var lanzado = false
    private var arrastro = false
    private var movido = false
    private var ultimoToque = 0L

    private val alPulsarLargo = Runnable {
        if (!lanzado && !movido && !hayPanel() && pr.getBoolean("fr_largo", false)) { lanzado = true; alAbrir() }
    }

    /** Crea la vista y sus parámetros (todavía no la pone en pantalla: eso lo hace [aplicar]). */
    fun crear() {
        val v = View(ctx)
        v.setOnTouchListener { _, e -> tocar(e) }
        esquina = v
        lp = WindowManager.LayoutParams(1, 1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.BOTTOM or Gravity.START }
    }

    /** Lee las preferencias y deja la franja como corresponde: tamaño, posición, aspecto y si está puesta. */
    fun aplicar() {
        val v = esquina ?: return
        val p = lp ?: return
        if (!pr.getBoolean("gesto", false)) {
            if (agregada) { try { wm.removeView(v) } catch (e: Exception) {}; agregada = false }
            return
        }
        val dm = ctx.resources.displayMetrics
        val moviendo = pr.getBoolean("fr_mover", false)
        val visible = moviendo || !pr.getBoolean("fr_oculto", true)
        p.width = (pr.getInt("fr_ancho", 56).coerceIn(16, 400) * d).toInt()
        p.height = (pr.getInt("fr_alto", 12).coerceIn(6, 400) * d).toInt()
        p.x = (pr.getInt("fr_x", 0) * d).toInt().coerceIn(0, maxOf(0, dm.widthPixels - p.width))
        p.y = (pr.getInt("fr_sube", 0) * d).toInt().coerceIn(0, maxOf(0, dm.heightPixels - p.height))
        v.background = if (visible) GradientDrawable().apply {
            cornerRadius = 99 * d
            setColor(if (moviendo) 0xCC8AB4FF.toInt() else 0x668AB4FF)
            if (moviendo) setStroke((2 * d).toInt(), Color.WHITE)
        } else null
        try {
            if (agregada) wm.updateViewLayout(v, p)
            else { wm.addView(v, p); agregada = true }
        } catch (e: Exception) { /* sin permiso de superposición o vista ya quitada: se ignora */ }
    }

    /** Quita la franja de la pantalla y cancela el temporizador de «mantener». */
    fun quitar() {
        h.removeCallbacks(alPulsarLargo)
        esquina?.let { if (agregada) { try { wm.removeView(it) } catch (e: Exception) {} } }
        agregada = false
    }

    private fun tocar(e: MotionEvent): Boolean {
        val p = lp ?: return true
        val dm = ctx.resources.displayMetrics
        val mover = pr.getBoolean("fr_mover", false)
        val holgura = 12 * d
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                x0 = e.rawX; y0 = e.rawY; lanzado = false; arrastro = false; movido = false
                px0 = p.x; py0 = p.y; t0 = e.eventTime
                h.removeCallbacks(alPulsarLargo)
                if (!mover && pr.getBoolean("fr_largo", false))
                    h.postDelayed(alPulsarLargo, 500)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - x0; val dy = e.rawY - y0
                if (abs(dx) > holgura || abs(dy) > holgura) { movido = true; h.removeCallbacks(alPulsarLargo) }
                if (mover) {
                    if (abs(dx) > 6 * d || abs(dy) > 6 * d) arrastro = true
                    if (arrastro) {
                        p.x = (px0 + dx.toInt()).coerceIn(0, maxOf(0, dm.widthPixels - p.width))
                        p.y = (py0 - dy.toInt()).coerceIn(0, maxOf(0, dm.heightPixels - p.height))
                        try { if (agregada) wm.updateViewLayout(esquina, p) } catch (ex: Exception) {}
                    }
                } else if (!lanzado && !hayPanel() && pr.getBoolean("fr_desliza", true) &&
                    hypot(dx, dy) >= pr.getInt("fr_dist", 40).coerceIn(15, 200) * d && direccionValida(dx, dy, p)) {
                    lanzado = true; alAbrir()
                }
            }
            MotionEvent.ACTION_UP -> {
                h.removeCallbacks(alPulsarLargo)
                if (mover) {
                    if (arrastro) pr.edit().putInt("fr_x", (p.x / d).toInt()).putInt("fr_sube", (p.y / d).toInt()).apply()
                } else if (!lanzado && !movido && !hayPanel() && e.eventTime - t0 < 400) {
                    if (pr.getBoolean("fr_toque", true)) {
                        alAbrir()
                    } else if (pr.getBoolean("fr_doble", false)) {
                        if (e.eventTime - ultimoToque < 300) { ultimoToque = 0L; alAbrir() } else ultimoToque = e.eventTime
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> h.removeCallbacks(alPulsarLargo)
        }
        return true
    }

    /** ¿El deslizamiento va en una dirección permitida? "Hacia dentro" depende del borde donde esté la franja. */
    private fun direccionValida(dx: Float, dy: Float, p: WindowManager.LayoutParams): Boolean {
        val dm = ctx.resources.displayMetrics
        val horizontal = abs(dx) >= abs(dy)
        if (pr.getBoolean("fr_dir_dentro", true)) {
            val cx = p.x + p.width / 2f
            val cyDesdeArriba = dm.heightPixels - (p.y + p.height / 2f)
            return if (horizontal) { if (cx < dm.widthPixels / 2f) dx > 0 else dx < 0 }
            else { if (cyDesdeArriba < dm.heightPixels / 2f) dy > 0 else dy < 0 }
        }
        return if (horizontal) pr.getBoolean(if (dx > 0) "fr_dir_der" else "fr_dir_izq", true)
        else pr.getBoolean(if (dy < 0) "fr_dir_arr" else "fr_dir_aba", true)
    }
}
