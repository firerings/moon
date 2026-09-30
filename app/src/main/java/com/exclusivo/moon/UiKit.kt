package com.exclusivo.moon

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/** Piezas de interfaz que comparten las pantallas de la app (todo se construye por código, sin XML). */
class UiKit(private val ctx: Context, private val prefs: SharedPreferences) {
    fun c(id: Int) = ctx.getColor(id)
    fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    fun tv(t: String, sp: Float, col: Int, bold: Boolean = false) = TextView(ctx).apply {
        text = t; textSize = sp; setTextColor(col); if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    fun tarjeta() = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL; setBackgroundResource(R.drawable.card_bg); setPadding(dp(16), dp(6), dp(16), dp(6))
    }

    fun fila(t: String, accion: () -> Unit): Pair<LinearLayout, TextView> {
        val r = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(12), 0, dp(12)); setOnClickListener { accion() } }
        r.addView(tv(t, 15f, c(R.color.moon_text)), LinearLayout.LayoutParams(0, -2, 1f))
        val ch = tv("", 12f, c(R.color.moon_muted)).apply { setBackgroundResource(R.drawable.chip_bg); setPadding(dp(12), dp(5), dp(12), dp(5)) }
        r.addView(ch)
        return r to ch
    }

    fun filaSwitch(t: String, clave: String, def: Boolean = false, luego: () -> Unit = {}): LinearLayout {
        var chip: TextView? = null
        fun pinta() {
            val on = prefs.getBoolean(clave, def)
            chip?.text = if (on) "Sí" else "No"
            chip?.setTextColor(c(if (on) R.color.moon_ok else R.color.moon_muted))
        }
        val (f, ch) = fila(t) { prefs.edit().putBoolean(clave, !prefs.getBoolean(clave, def)).apply(); pinta(); luego() }
        chip = ch
        pinta()
        return f
    }

    /** Deslizador de tamaño: guarda en dp y la franja se redimensiona al instante. */
    fun barra(t: String, clave: String, def: Int, min: Int, max: Int): LinearLayout {
        val cont = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(10), 0, dp(4)) }
        val cab = tv("$t: ${prefs.getInt(clave, def).coerceIn(min, max)} dp", 14f, c(R.color.moon_text))
        cont.addView(cab)
        val sb = SeekBar(ctx)
        sb.max = max - min
        sb.progress = prefs.getInt(clave, def).coerceIn(min, max) - min
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, deUsuario: Boolean) {
                val v = p + min
                cab.text = "$t: $v dp"
                if (deUsuario) prefs.edit().putInt(clave, v).apply()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        cont.addView(sb)
        return cont
    }

    /** Color de un semáforo: 0 gris (sin dato), 1 verde (bien), 2 ámbar (aviso), 3 rojo (mal). */
    fun colorSemaforo(n: Int): Int = when (n) {
        1 -> c(R.color.moon_ok)
        2 -> Color.parseColor("#FFC857")
        3 -> c(R.color.moon_bad)
        else -> c(R.color.moon_muted)
    }

    /** Fila con un punto de color, el título y un texto de estado a la derecha. */
    class Semaforo(val fila: LinearLayout, private val punto: View, private val estado: TextView, private val kit: UiKit) {
        fun poner(n: Int, txt: String) {
            punto.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(kit.colorSemaforo(n)) }
            estado.text = txt
            estado.setTextColor(kit.colorSemaforo(n))
        }
    }

    fun semaforo(t: String, accion: () -> Unit = {}): Semaforo {
        val r = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(12), 0, dp(12)); setOnClickListener { accion() } }
        val punto = View(ctx)
        r.addView(punto, LinearLayout.LayoutParams(dp(12), dp(12)).apply { marginEnd = dp(12) })
        r.addView(tv(t, 15f, c(R.color.moon_text)), LinearLayout.LayoutParams(0, -2, 1f))
        val ch = tv("—", 12f, c(R.color.moon_muted)).apply { setBackgroundResource(R.drawable.chip_bg); setPadding(dp(12), dp(5), dp(12), dp(5)) }
        r.addView(ch)
        val s = Semaforo(r, punto, ch, this)
        s.poner(0, "—")
        return s
    }

    fun boton(t: String, primario: Boolean, accion: () -> Unit) =
        tv(t, 14f, c(if (primario) R.color.moon_bg else R.color.moon_text), true).apply {
            setBackgroundResource(if (primario) R.drawable.pill_btn else R.drawable.chip_bg)
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setOnClickListener { accion() }
        }

    fun filaBotones(vararg b: TextView): LinearLayout {
        val r = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(12), 0, 0) }
        b.forEachIndexed { i, x ->
            r.addView(x, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) marginStart = dp(8) })
        }
        return r
    }
}
