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
import android.widget.EditText
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

    private fun c(id: Int) = getColor(id)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // Piezas compartidas (UiKit) y hoja de detalle de Actividad; se crean al primer uso, ya con prefs listo.
    private val kit by lazy { UiKit(this, prefs) }
    private val detalle by lazy { DetalleActividad(this, kit, ui) { cargarActividad() } }

    private fun tv(t: String, sp: Float, col: Int, bold: Boolean = false) = kit.tv(t, sp, col, bold)
    private fun tarjeta() = kit.tarjeta()
    private fun fila(t: String, accion: () -> Unit) = kit.fila(t, accion)
    private fun filaSwitch(t: String, clave: String, def: Boolean = false, luego: () -> Unit = {}) = kit.filaSwitch(t, clave, def, luego)
    private fun barra(t: String, clave: String, def: Int, min: Int, max: Int) = kit.barra(t, clave, def, min, max)

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
        construirSistema()
    }

    /** Pestaña Sistema: estado, token, franja del gesto, formas de activar y versión. */
    private fun construirSistema() {
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
        t3.addView(tv("Franja del gesto", 13f, c(R.color.moon_muted)).apply { setPadding(0, dp(10), 0, dp(2)) })
        t3.addView(filaSwitch("Desbloquear para mover", "fr_mover"))
        t3.addView(filaSwitch("Ocultar franja", "fr_oculto", true))
        t3.addView(barra("Alto", "fr_alto", 12, 6, 120))
        t3.addView(barra("Ancho", "fr_ancho", 56, 16, 320))
        t3.addView(tv("Restablecer posición y tamaño", 14f, c(R.color.moon_accent)).apply {
            setPadding(0, dp(12), 0, dp(6))
            setOnClickListener {
                prefs.edit().remove("fr_x").remove("fr_sube").remove("fr_alto").remove("fr_ancho").apply()
                recreate()
            }
        })
        t3.addView(tv("Desbloqueada se ve y se arrastra; al terminar, bloquéala. Oculta es invisible pero sigue funcionando. " +
            "Necesita el gesto de esquina activo.", 12f, c(R.color.moon_muted)).apply { setPadding(0, 0, 0, dp(10)) })
        cont.addView(t3, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        val t4 = tarjeta()
        t4.addView(tv("Activar con", 13f, c(R.color.moon_muted)).apply { setPadding(0, dp(10), 0, dp(2)) })
        t4.addView(filaSwitch("Toque", "fr_toque", true))
        t4.addView(filaSwitch("Doble toque", "fr_doble", false))
        t4.addView(filaSwitch("Mantener pulsado", "fr_largo", false))
        t4.addView(filaSwitch("Deslizar", "fr_desliza", true))
        t4.addView(tv("Dirección del deslizamiento", 13f, c(R.color.moon_muted)).apply { setPadding(0, dp(10), 0, dp(2)) })
        t4.addView(filaSwitch("Hacia dentro de la pantalla", "fr_dir_dentro", true))
        t4.addView(filaSwitch("Izquierda", "fr_dir_izq", true))
        t4.addView(filaSwitch("Derecha", "fr_dir_der", true))
        t4.addView(filaSwitch("Arriba", "fr_dir_arr", true))
        t4.addView(filaSwitch("Abajo", "fr_dir_aba", true))
        t4.addView(barra("Distancia mínima", "fr_dist", 40, 15, 200))
        t4.addView(filaSwitch("Vibrar al activar", "fr_vibra", true))
        t4.addView(tv("«Hacia dentro» se adapta al borde donde pongas la franja y, si está activo, ignora las direcciones sueltas. " +
            "Con toque activo, el doble toque no hace falta.", 12f, c(R.color.moon_muted)).apply { setPadding(0, dp(6), 0, dp(10)) })
        cont.addView(t4, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        val t5 = tarjeta()
        t5.addView(tv("Otras formas de activar", 13f, c(R.color.moon_muted)).apply { setPadding(0, dp(10), 0, dp(2)) })
        t5.addView(filaSwitch("Decir «Luna»", "luna", false) { asegurarServicio() })
        t5.addView(filaSwitch("Botón del auricular (mantener)", "auricular", false) { asegurarServicio() })
        t5.addView(tv("«Luna» mantiene el micrófono escuchando, así que gasta batería: actívalo solo cuando lo uses. " +
            "El auricular deja de controlar tu música con la pulsación corta mientras esté activo.", 12f, c(R.color.moon_muted)).apply { setPadding(0, dp(6), 0, dp(10)) })
        cont.addView(t5, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
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
        marca(sAsis, a, if (a) "Activo" else "Sin activar")
        marca(sGesto, g, if (g) "Activo" else "Apagado")
        marca(sOver, o, if (o) "Permitida" else "Falta permiso")
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

    private fun mostrarUpd(tag: String) {
        if (tag.isEmpty()) return
        val hay = Actualizaciones.hayNueva(tag, verApp)
        tvUpd.text = if (hay) "Hay una actualización disponible (${tag.removePrefix("v")})" else "Estás al día"
        tvUpd.setTextColor(c(if (hay) R.color.moon_accent else R.color.moon_muted))
    }

    /** Consulta la última release en GitHub como mucho una vez por hora. */
    private fun comprobarActualizacion() = Actualizaciones.consultar(prefs, ui) { mostrarUpd(it) }

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

    private fun pintarSistema(r: String?) {
        if (r == null) { marca(sShz, false, "—"); marca(sMod, false, "—"); return }
        try {
            val j = JSONObject(r)
            val s = j.getBoolean("shizuku")
            marca(sShz, s, if (s) "Activo" else "Inactivo")
            marca(sMod, true, j.getString("modelo"))
        } catch (e: Exception) { /* sin datos: se conserva lo anterior */ }
    }

    private fun actualizarBoton() {
        fab.setImageResource(if (escuchando) R.drawable.ic_stop else R.drawable.ic_mic)
        if (escuchando) { if (!animPulso.isStarted) animPulso.start() } else { animPulso.cancel(); pulso.alpha = 0f }
    }
}
