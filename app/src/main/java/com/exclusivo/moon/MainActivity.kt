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
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {

    private val ui = Handler(Looper.getMainLooper())
    private val ocupado = AtomicBoolean(false)
    private var conectado = false
    private var escuchando = false
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
    private lateinit var sShz: TextView
    private lateinit var sMod: TextView
    private lateinit var tvUpd: TextView
    private lateinit var tvTituloIn: TextView
    private lateinit var tvTextoIn: TextView
    private lateinit var waveIn: View
    private var ultimoTexto = ""
    private var ultAct = -1L
    private var ultSis = 0L
    private var tabActual = 0
    private var verApp = "?"

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
        contAct.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("moon", 0)
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

        // Pestaña Sistema
        val cont = findViewById<LinearLayout>(R.id.contSis)
        val t1 = tarjeta()
        val (f1, c1) = fila("Asistente predeterminado") { startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) }
        val (f2, c2) = fila("Servidor de voz") {}
        val (f3, c3) = fila("Gesto de esquina") { alternarGesto() }
        val (f4, c4) = fila("Superposición") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        val (f5, c5) = fila("Shizuku") {}
        val (f6, c6) = fila("Modelo de voz") {}
        sAsis = c1; sVoz = c2; sGesto = c3; sOver = c4; sShz = c5; sMod = c6
        marca(sShz, false, "—"); marca(sMod, false, "—")
        listOf(f1, f2, f5, f4, f6, f3).forEach { t1.addView(it) }
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
        val t3 = tarjeta()
        t3.addView(tv("Zona del gesto (toca para cambiar)", 13f, c(R.color.moon_muted)).apply { setPadding(0, dp(10), 0, dp(2)) })
        t3.addView(filaAjuste("Alto de la franja", "fr_alto", 12, listOf(8, 12, 16, 24)))
        t3.addView(filaAjuste("Ancho de la franja", "fr_ancho", 56, listOf(56, 90, 140)))
        t3.addView(filaAjuste("Altura sobre el borde", "fr_sube", 0, listOf(0, 12, 24, 36)))
        cont.addView(t3, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        val v = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "?" } catch (e: Exception) { "?" }
        verApp = v
        val t2 = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundResource(R.drawable.card_bg); setPadding(dp(16), dp(14), dp(16), dp(16)) }
        t2.addView(tv("Versión $v", 15f, c(R.color.moon_text), true))
        tvUpd = tv("Las nuevas versiones se publican en GitHub", 13f, c(R.color.moon_muted))
        t2.addView(tvUpd)
        t2.addView(tv("Actualizar", 13f, c(R.color.moon_bg), true).apply {
            setBackgroundResource(R.drawable.pill_btn); setPadding(dp(18), dp(8), dp(18), dp(8))
            setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Firerings/moon/releases/latest"))) }
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })
        cont.addView(t2, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
    }

    private fun mostrar(i: Int) {
        tabActual = i
        if (i == 2) { ultSis = 0; comprobarActualizacion() }
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
        marca(sOver, o, if (o) "Permitida" else "Falta permiso")
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
            if (tabActual == 2 && conectado && System.currentTimeMillis() - ultSis > 5000) {
                ultSis = System.currentTimeMillis()
                Thread {
                    val r = Api.call(this@MainActivity, "/sistema", "GET", 7000)
                    ui.post { pintarSistema(r) }
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
        if (!escuchando) ultimoTexto = ""
        Thread { Api.call(this, if (escuchando) "/parar" else "/escuchar", "POST") }.start()
    }

    private fun estado(txt: String, ok: Boolean) {
        tvEstado.text = txt
        tvEstado.setTextColor(c(if (ok) R.color.moon_ok else R.color.moon_bad))
        marca(findViewById(R.id.chipVoz), ok); marca(sVoz, ok, if (ok) "Conectado" else "Sin conexión")
    }

    private fun pintar(resp: String?) {
        if (resp == null) {
            conectado = false; escuchando = false
            estado("Sin conexión con Termux", false)
            vistaInicio("Sin conexión con Termux", "Inicia voz_servidor.py en Termux", false)
            actualizarBoton(); return
        }
        try {
            val j = JSONObject(resp)
            if (j.has("auth")) {
                conectado = false
                estado("Token incorrecto", false)
                vistaInicio("Token incorrecto", "Revísalo en la pestaña Sistema", false)
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
            if (o.optString("tipo") == "orden")
                tarjetaAct(o.optString("nombre", "Orden"), "$hora, por ${o.optString("via", "voz")}", c(R.color.moon_ok))
            else
                tarjetaAct("«" + o.optString("texto") + "»", "$hora, transcripción", c(R.color.moon_accent))
        }
    }

    private fun pintarSistema(r: String?) {
        if (r == null) { marca(sShz, false, "—"); marca(sMod, false, "—"); return }
        try {
            val j = JSONObject(r)
            val s = j.getBoolean("shizuku")
            marca(sShz, s, if (s) "Activo" else "Inactivo")
            marca(sMod, true, j.getString("modelo"))
        } catch (e: Exception) { /* sin datos: se conserva lo anterior */ }
    }

    private fun esMayor(a: List<Int>, b: List<Int>): Boolean {
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun mostrarUpd(tag: String) {
        if (tag.isEmpty()) return
        val nueva = tag.removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
        val actual = verApp.split(".").map { it.toIntOrNull() ?: 0 }
        val hay = esMayor(nueva, actual)
        tvUpd.text = if (hay) "Hay una actualización disponible (${tag.removePrefix("v")})" else "Estás al día"
        tvUpd.setTextColor(c(if (hay) R.color.moon_accent else R.color.moon_muted))
    }

    /** Consulta la última release en GitHub como mucho una vez por hora. */
    private fun comprobarActualizacion() {
        val ahora = System.currentTimeMillis()
        if (ahora - prefs.getLong("upd_ts", 0) < 3600_000) { mostrarUpd(prefs.getString("upd_tag", "") ?: ""); return }
        Thread {
            val tag = try {
                val con = URL("https://api.github.com/repos/Firerings/moon/releases/latest").openConnection() as HttpURLConnection
                con.connectTimeout = 4000; con.readTimeout = 4000
                con.setRequestProperty("Accept", "application/vnd.github+json")
                JSONObject(con.inputStream.bufferedReader().use { it.readText() }).getString("tag_name")
            } catch (e: Exception) { null }
            ui.post {
                if (tag != null) {
                    prefs.edit().putLong("upd_ts", ahora).putString("upd_tag", tag).apply()
                    mostrarUpd(tag)
                }
            }
        }.start()
    }

    private fun filaAjuste(t: String, clave: String, def: Int, vals: List<Int>): LinearLayout {
        var chip: TextView? = null
        val (f, ch) = fila(t) {
            val nuevo = vals[(vals.indexOf(prefs.getInt(clave, def)) + 1) % vals.size]
            prefs.edit().putInt(clave, nuevo).apply()
            chip?.text = "$nuevo dp"
            reiniciarGesto()
        }
        chip = ch
        ch.text = "${prefs.getInt(clave, def)} dp"
        return f
    }

    /** Recrea la franja para que tome los nuevos valores. */
    private fun reiniciarGesto() {
        if (!prefs.getBoolean("gesto", false) || !Settings.canDrawOverlays(this)) return
        val i = Intent(this, EdgeService::class.java)
        stopService(i)
        ContextCompat.startForegroundService(this, i)
    }

    private fun actualizarBoton() {
        fab.setImageResource(if (escuchando) R.drawable.ic_stop else R.drawable.ic_mic)
        if (escuchando) { if (!animPulso.isStarted) animPulso.start() } else { animPulso.cancel(); pulso.alpha = 0f }
    }
}
