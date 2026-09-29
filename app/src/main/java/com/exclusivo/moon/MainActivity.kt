package com.exclusivo.moon

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.app.role.RoleManager
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.floatingactionbutton.FloatingActionButton
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {

    private val ui = Handler(Looper.getMainLooper())
    private val ocupado = AtomicBoolean(false)
    private var conectado = false
    private var escuchando = false
    private var ordenVista = false
    private var seq = 0

    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var tvEstado: TextView
    private lateinit var fab: FloatingActionButton
    private lateinit var pulso: View
    private lateinit var animPulso: ObjectAnimator
    private lateinit var vistas: List<View>
    private lateinit var tabs: List<TextView>
    private lateinit var contAct: LinearLayout
    private lateinit var sAsis: TextView
    private lateinit var sVoz: TextView
    private lateinit var sGesto: TextView
    private lateinit var sOver: TextView

    private fun c(id: Int) = ContextCompat.getColor(this, id)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun tv(t: String, sp: Float, col: Int, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = sp; setTextColor(col); if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun tarjeta() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setBackgroundResource(R.drawable.card_bg); setPadding(dp(16), dp(6), dp(16), dp(6))
    }

    private fun fila(t: String, accion: () -> Unit): Pair<LinearLayout, TextView> {
        val r = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(12), 0, dp(12)); setOnClickListener { accion() } }
        r.addView(tv(t, 15f, c(R.color.moon_text)), LinearLayout.LayoutParams(0, -2, 1f))
        val ch = tv("", 12f, c(R.color.moon_muted)).apply { setBackgroundResource(R.drawable.chip_bg); setPadding(dp(12), dp(5), dp(12), dp(5)) }
        r.addView(ch)
        return r to ch
    }

    private fun tarjetaAct(titulo: String, sub: String, color: Int) {
        val card = LinearLayout(this).apply { setBackgroundResource(R.drawable.card_bg); clipToOutline = true }
        card.addView(View(this).apply { setBackgroundColor(color) }, LinearLayout.LayoutParams(dp(3), -1))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12)) }
        col.addView(tv(titulo, 16f, c(R.color.moon_text)))
        col.addView(tv(sub, 12f, c(R.color.moon_muted)))
        card.addView(col, LinearLayout.LayoutParams(-1, -2))
        contAct.addView(card, 0, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("moon", 0)
        tvEstado = findViewById(R.id.tvEstado)
        fab = findViewById(R.id.fab)
        pulso = findViewById(R.id.pulso)
        contAct = findViewById(R.id.contAct)
        vistas = listOf(R.id.vInicio, R.id.vActividad, R.id.vSistema).map { findViewById<View>(it) }
        tabs = listOf(R.id.tab0, R.id.tab1, R.id.tab2).map { findViewById<TextView>(it) }
        tabs.forEachIndexed { i, t -> t.setOnClickListener { mostrar(i) } }
        mostrar(0)

        animPulso = ObjectAnimator.ofPropertyValuesHolder(
            pulso,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.6f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.6f),
            PropertyValuesHolder.ofFloat(View.ALPHA, 0.5f, 0f)
        ).apply { duration = 1200; repeatCount = ValueAnimator.INFINITE; interpolator = DecelerateInterpolator() }
        fab.setOnClickListener { alternar() }
        findViewById<View>(R.id.btnLimpiar).setOnClickListener { contAct.removeAllViews() }

        // Pestaña Sistema
        val cont = findViewById<LinearLayout>(R.id.contSis)
        val t1 = tarjeta()
        val (f1, c1) = fila("Asistente predeterminado") { startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) }
        val (f2, c2) = fila("Servidor de voz") {}
        val (f3, c3) = fila("Gesto de esquina") { alternarGesto() }
        val (f4, c4) = fila("Mostrar sobre otras apps") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        sAsis = c1; sVoz = c2; sGesto = c3; sOver = c4
        listOf(f1, f2, f3, f4).forEach { t1.addView(it) }
        val et = EditText(this).apply {
            hint = "Token que imprime voz_servidor.py"; setText(prefs.getString("token", ""))
            setTextColor(c(R.color.moon_text)); setHintTextColor(c(R.color.moon_muted)); textSize = 14f; isSingleLine = true
        }
        t1.addView(et)
        t1.addView(tv("Guardar token", 14f, c(R.color.moon_accent)).apply {
            setPadding(0, dp(10), 0, dp(10))
            setOnClickListener {
                prefs.edit().putString("token", et.text.toString().trim()).apply()
                Toast.makeText(this@MainActivity, "Token guardado", Toast.LENGTH_SHORT).show()
            }
        })
        cont.addView(t1)
        val v = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "?" }
        val t2 = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundResource(R.drawable.card_bg); setPadding(dp(16), dp(14), dp(16), dp(16)) }
        t2.addView(tv("Versión $v", 15f, c(R.color.moon_text), true))
        t2.addView(tv("Las nuevas versiones se publican en GitHub", 13f, c(R.color.moon_muted)))
        t2.addView(tv("Actualizar", 13f, c(R.color.moon_bg), true).apply {
            setBackgroundResource(R.drawable.pill_btn); setPadding(dp(18), dp(8), dp(18), dp(8))
            setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Firerings/moon/releases/latest"))) }
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })
        cont.addView(t2, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
    }

    private fun mostrar(i: Int) {
        vistas.forEachIndexed { k, v -> v.visibility = if (k == i) View.VISIBLE else View.GONE }
        tabs.forEachIndexed { k, t -> t.setTextColor(c(if (k == i) R.color.moon_accent else R.color.moon_muted)) }
    }

    private fun esAsistente() =
        Build.VERSION.SDK_INT >= 29 && getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_ASSISTANT)

    private fun marca(t: TextView, ok: Boolean, txt: String? = null) {
        if (txt != null) t.text = txt
        t.setTextColor(c(if (ok) R.color.moon_ok else R.color.moon_muted))
    }

    private fun alternarGesto() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))); return
        }
        val on = !prefs.getBoolean("gesto", false)
        prefs.edit().putBoolean("gesto", on).apply()
        val i = Intent(this, EdgeService::class.java)
        if (on) ContextCompat.startForegroundService(this, i) else stopService(i)
        refrescar()
    }

    private fun refrescar() {
        val a = esAsistente(); val o = Settings.canDrawOverlays(this); val g = prefs.getBoolean("gesto", false) && o
        marca(findViewById(R.id.chipAsis), a); marca(findViewById(R.id.chipGesto), g)
        marca(sAsis, a, if (a) "Activo" else "Sin activar")
        marca(sGesto, g, if (g) "Activo" else "Apagado")
        marca(sOver, o, if (o) "Permitido" else "Falta permiso")
    }

    override fun onResume() {
        super.onResume()
        if (prefs.getBoolean("gesto", false) && Settings.canDrawOverlays(this))
            ContextCompat.startForegroundService(this, Intent(this, EdgeService::class.java))
        refrescar()
        ui.post(poll)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(poll)
        animPulso.cancel()
    }

    private val poll = object : Runnable {
        override fun run() {
            if (ocupado.compareAndSet(false, true)) {
                Thread {
                    val r = Api.call(this@MainActivity, "/estado?desde=$seq")
                    ocupado.set(false)
                    ui.post { pintar(r) }
                }.start()
            }
            ui.postDelayed(this, 400)
        }
    }

    private fun alternar() {
        if (!conectado) {
            Toast.makeText(this, "Inicia el servidor en Termux: python3.9 voz_servidor.py", Toast.LENGTH_LONG).show()
            return
        }
        Thread { Api.call(this, if (escuchando) "/parar" else "/escuchar", "POST") }.start()
    }

    private fun estado(txt: String, ok: Boolean) {
        tvEstado.text = txt
        tvEstado.setTextColor(c(if (ok) R.color.moon_ok else R.color.moon_bad))
        marca(findViewById(R.id.chipVoz), ok); marca(sVoz, ok, if (ok) "Conectado" else "Sin conexión")
    }

    private fun pintar(resp: String?) {
        if (resp == null) { conectado = false; escuchando = false; estado("Sin conexión con Termux", false); actualizarBoton(); return }
        try {
            val j = JSONObject(resp)
            if (j.has("auth")) { conectado = false; estado("Token incorrecto", false); actualizarBoton(); return }
            conectado = true
            escuchando = j.getBoolean("escuchando")
            if (j.getInt("total") < seq) seq = 0
            val hora = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            val fin = j.getJSONArray("finales")
            for (i in 0 until fin.length()) {
                val f = fin.getJSONObject(i)
                seq = maxOf(seq, f.getInt("n"))
                tarjetaAct("«" + f.getString("t") + "»", "$hora, transcripción", c(R.color.moon_accent))
            }
            val hayOrden = j.optJSONObject("orden") != null
            if (hayOrden && !ordenVista) tarjetaAct("Reiniciar conexión", "$hora, por voz", c(R.color.moon_ok))
            ordenVista = hayOrden
            estado(if (escuchando) "Escuchando…" else "Listo", true)
        } catch (e: Exception) { /* respuesta inesperada: se ignora hasta el próximo ciclo */ }
        actualizarBoton()
    }

    private fun actualizarBoton() {
        fab.setImageResource(if (escuchando) R.drawable.ic_stop else R.drawable.ic_mic)
        if (escuchando) { if (!animPulso.isStarted) animPulso.start() } else { animPulso.cancel(); pulso.alpha = 0f }
    }
}
