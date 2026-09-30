package com.exclusivo.moon

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.app.Activity
import android.app.role.RoleManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private val ocupado = AtomicBoolean(false)
    private var conectado = false
    private var escuchando = false
    private var seq = 0

    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var tvEstado: TextView
    private lateinit var fab: ImageButton
    private lateinit var pulso: View
    private lateinit var animPulso: ObjectAnimator
    private lateinit var vistas: List<View>
    private lateinit var tabs: List<TextView>
    private lateinit var contAct: LinearLayout
    private lateinit var tvTituloIn: TextView
    private lateinit var tvTextoIn: TextView
    private lateinit var waveIn: View
    private var ultimoTexto = ""
    private var ultAct = -1L
    private var tabActual = 0

    private fun c(id: Int) = getColor(id)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // Piezas compartidas (UiKit) y hoja de detalle de Actividad; se crean al primer uso, ya con prefs listo.
    private val kit by lazy { UiKit(this, prefs) }
    private val detalle by lazy { DetalleActividad(this, kit, ui) { cargarActividad() } }
    // Pestaña Sistema: categorías, subpantallas y Diagnóstico (Ajustes.kt).
    private val ajustes by lazy {
        Ajustes(this, kit, prefs, ui, findViewById<LinearLayout>(R.id.contSis), { asegurarServicio() }, { alternarGesto() })
    }

    private fun tv(t: String, sp: Float, col: Int, bold: Boolean = false) = kit.tv(t, sp, col, bold)

    private fun tarjetaAct(titulo: String, sub: String, color: Int, o: JSONObject) {
        val card = LinearLayout(this).apply {
            setBackgroundResource(R.drawable.card_bg); clipToOutline = true
            setOnClickListener { detalle.mostrar(o) }
        }
        card.addView(View(this).apply { setBackgroundColor(color) }, LinearLayout.LayoutParams(dp(3), -1))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12)) }
        col.addView(tv(titulo, 16f, c(R.color.moon_text)))
        col.addView(tv(sub, 12f, c(R.color.moon_muted)))
        card.addView(col, LinearLayout.LayoutParams(-1, -2))
        contAct.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("moon", 0)
        if (checkSelfPermission("com.termux.permission.RUN_COMMAND") != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf("com.termux.permission.RUN_COMMAND"), 7)
        tvEstado = findViewById(R.id.tvEstado)
        fab = findViewById(R.id.fab)
        pulso = findViewById(R.id.pulso)
        contAct = findViewById(R.id.contAct)
        tvTituloIn = findViewById(R.id.tvTituloIn)
        tvTextoIn = findViewById(R.id.tvTextoIn)
        waveIn = findViewById(R.id.waveIn)
        vistas = listOf(R.id.vInicio, R.id.scrollAct, R.id.vSistema).map { findViewById<View>(it) }
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
        findViewById<View>(R.id.btnLimpiar).setOnClickListener {
            Thread { Api.call(this, "/limpiar", "POST") }.start()
            contAct.removeAllViews()
        }
        ajustes.mostrar(Ajustes.PRINCIPAL)
    }

    private fun mostrar(i: Int) {
        tabActual = i
        if (i == 2) ajustes.alEntrar()
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
        asegurarServicio()
        refrescar()
    }

    /** El servicio vive mientras esté puesta la franja, «Luna» o el auricular. */
    private fun asegurarServicio() {
        val quiere = prefs.getBoolean("gesto", false) || prefs.getBoolean("luna", false) || prefs.getBoolean("auricular", false)
        if (quiere && !Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))); return
        }
        val i = Intent(this, EdgeService::class.java)
        if (quiere) startForegroundService(i) else stopService(i)
    }

    private fun refrescar() {
        val a = esAsistente(); val o = Settings.canDrawOverlays(this); val g = prefs.getBoolean("gesto", false) && o
        marca(findViewById(R.id.chipAsis), a); marca(findViewById(R.id.chipGesto), g)
        ajustes.pintar()
    }

    override fun onResume() {
        super.onResume()
        if (Settings.canDrawOverlays(this) &&
            (prefs.getBoolean("gesto", false) || prefs.getBoolean("luna", false) || prefs.getBoolean("auricular", false)))
            startForegroundService(Intent(this, EdgeService::class.java))
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
                    Acciones.procesar(applicationContext, r)
                    ocupado.set(false)
                    ui.post { pintar(r) }
                }.start()
            }
            if (tabActual == 2) ajustes.tick(conectado)
            ui.postDelayed(this, 400)
        }
    }

    private fun alternar() {
        if (!conectado) {
            Toast.makeText(this, "Inicia el servidor en Termux: python3.9 voz_servidor.py", Toast.LENGTH_LONG).show()
            return
        }
        if (!escuchando) ultimoTexto = ""
        Thread { Api.call(this, if (escuchando) "/parar" else "/escuchar", "POST") }.start()
    }

    private fun estado(txt: String, ok: Boolean) {
        tvEstado.text = txt
        tvEstado.setTextColor(c(if (ok) R.color.moon_ok else R.color.moon_bad))
        marca(findViewById(R.id.chipVoz), ok); ajustes.conexion(ok)
    }

    private fun pintar(resp: String?) {
        if (resp == null) {
            conectado = false; escuchando = false
            Termux.iniciarServidor(applicationContext)
            estado("Sin conexión con Termux", false)
            vistaInicio("Sin conexión con Termux", "Intentando iniciar el servidor… si no arranca, ejecuta ./moon.sh en Termux", false)
            actualizarBoton(); return
        }
        try {
            val j = JSONObject(resp)
            if (j.has("auth")) {
                conectado = false
                estado("Token incorrecto", false)
                vistaInicio("Token incorrecto", "Revísalo en Sistema > Asistente y servidor", false)
                actualizarBoton(); return
            }
            conectado = true
            escuchando = j.getBoolean("escuchando")
            if (j.getInt("total") < seq) seq = 0
            val fin = j.getJSONArray("finales")
            for (i in 0 until fin.length()) {
                val f = fin.getJSONObject(i)
                seq = maxOf(seq, f.getInt("n"))
                ultimoTexto = f.getString("t")
            }
            val act = j.optLong("act", 0)
            if (act != ultAct) { ultAct = act; cargarActividad() }
            val o = j.optJSONObject("orden")
            if (o != null) {
                val paso = o.getInt("paso"); val hecho = o.getBoolean("hecho")
                val nombres = listOf("Modo avión activado", "Modo avión desactivado", "Encendiendo el hotspot")
                vistaInicio(o.getString("nombre"), if (hecho) "Listo" else "Paso $paso de 3: ${nombres[paso - 1]}", false)
            } else {
                val parcial = j.getString("parcial")
                val txt = if (parcial.isNotEmpty()) parcial else ultimoTexto
                vistaInicio(
                    if (escuchando) "Escuchando" else "En pausa",
                    if (txt.isNotEmpty()) txt else if (escuchando) "Habla ahora…" else "Toca el micrófono para empezar",
                    escuchando
                )
            }
            estado(if (escuchando) "Escuchando…" else "Listo", true)
        } catch (e: Exception) { /* respuesta inesperada: se ignora hasta el próximo ciclo */ }
        actualizarBoton()
    }

    private fun vistaInicio(titulo: String, texto: String, onda: Boolean) {
        tvTituloIn.text = titulo
        tvTextoIn.text = texto
        waveIn.visibility = if (onda) View.VISIBLE else View.INVISIBLE
    }

    private fun cargarActividad() {
        Thread {
            val r = Api.call(this, "/actividad")
            ui.post { pintarActividad(r) }
        }.start()
    }

    private fun pintarActividad(r: String?) {
        if (r == null) return
        val items = try { JSONObject(r).getJSONArray("items") } catch (e: Exception) { return }
        contAct.removeAllViews()
        if (items.length() == 0) {
            contAct.addView(tv("Aún no hay actividad", 14f, c(R.color.moon_muted)))
            return
        }
        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        for (i in items.length() - 1 downTo 0) {
            val o = items.getJSONObject(i)
            val hora = fmt.format(Date((o.optDouble("t", 0.0) * 1000).toLong()))
            val dur = o.optInt("dur_ms", 0)
            val extra = (if (dur > 0) ", $dur ms" else "") + when (o.optString("fb")) {
                "ok" -> "  ✓"
                "corr" -> o.optString("dije").let { if (it.isNotEmpty()) "  · dije «$it»" else "  · corregido" }
                else -> ""
            }
            if (o.optString("tipo") == "orden")
                tarjetaAct(o.optString("nombre", "Orden"), "$hora, por ${o.optString("via", "voz")}$extra", c(R.color.moon_ok), o)
            else
                tarjetaAct("«" + o.optString("texto") + "»", "$hora, transcripción$extra", c(R.color.moon_accent), o)
        }
    }

    private fun actualizarBoton() {
        fab.setImageResource(if (escuchando) R.drawable.ic_stop else R.drawable.ic_mic)
        if (escuchando) { if (!animPulso.isStarted) animPulso.start() } else { animPulso.cancel(); pulso.alpha = 0f }
    }

    /** «Atrás» dentro de una subpantalla de Sistema vuelve a la lista de categorías. */
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (tabActual == 2 && ajustes.atras()) return
        super.onBackPressed()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 8) ajustes.alTenerPermisos()
    }
}
