package com.exclusivo.moon

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.app.Activity
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
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.InputType
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.SeekBar
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
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

    private fun tarjetaAct(titulo: String, sub: String, color: Int, o: JSONObject) {
        val card = LinearLayout(this).apply {
            setBackgroundResource(R.drawable.card_bg); clipToOutline = true
            setOnClickListener { mostrarDetalle(o) }
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
        t3.addView(filaSwitch("Mostrar franja", "fr_visible"))
        t3.addView(filaSwitch("Desbloquear para mover", "fr_mover"))
        t3.addView(filaSwitch("Ocultar franja", "fr_oculto"))
        t3.addView(barra("Alto", "fr_alto", 12, 6, 120))
        t3.addView(barra("Ancho", "fr_ancho", 56, 16, 320))
        t3.addView(tv("Restablecer posición y tamaño", 14f, c(R.color.moon_accent)).apply {
            setPadding(0, dp(12), 0, dp(6))
            setOnClickListener {
                prefs.edit().remove("fr_x").remove("fr_sube").remove("fr_alto").remove("fr_ancho").apply()
                recreate()
            }
        })
        t3.addView(tv("Con la franja desbloqueada arrástrala; al terminar, bloquéala. Necesita el gesto de esquina activo. " +
            "Si la ocultas, sigue el botón «Hablar» de la notificación.", 12f, c(R.color.moon_muted)).apply { setPadding(0, 0, 0, dp(10)) })
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
        if (on) startForegroundService(i) else stopService(i)
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

    private fun filaSwitch(t: String, clave: String): LinearLayout {
        var chip: TextView? = null
        fun pinta() {
            val on = prefs.getBoolean(clave, false)
            chip?.text = if (on) "Sí" else "No"
            chip?.setTextColor(c(if (on) R.color.moon_ok else R.color.moon_muted))
        }
        val (f, ch) = fila(t) { prefs.edit().putBoolean(clave, !prefs.getBoolean(clave, false)).apply(); pinta() }
        chip = ch
        pinta()
        return f
    }

    /** Deslizador de tamaño: guarda en dp y la franja se redimensiona al instante. */
    private fun barra(t: String, clave: String, def: Int, min: Int, max: Int): LinearLayout {
        val cont = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(10), 0, dp(4)) }
        val cab = tv("$t: ${prefs.getInt(clave, def).coerceIn(min, max)} dp", 14f, c(R.color.moon_text))
        cont.addView(cab)
        val sb = SeekBar(this)
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

    // ---------- detalle y correccion de una entrada de Actividad ----------
    private fun boton(t: String, primario: Boolean, accion: () -> Unit) =
        tv(t, 14f, c(if (primario) R.color.moon_bg else R.color.moon_text), true).apply {
            setBackgroundResource(if (primario) R.drawable.pill_btn else R.drawable.chip_bg)
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setOnClickListener { accion() }
        }

    private fun filaBotones(vararg b: TextView): LinearLayout {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(12), 0, 0) }
        b.forEachIndexed { i, x ->
            r.addView(x, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) marginStart = dp(8) })
        }
        return r
    }

    private fun lineasDetalle(ev: JSONObject): String {
        val l = mutableListOf<String>()
        val via = when (val v = ev.optString("via")) {
            "comando" -> "un comando fijo"
            "regla" -> "la regla «abre / cierra …»"
            "nlu" -> "el modelo de intenciones"
            "respuesta" -> "tu respuesta a una pregunta"
            "confirmado" -> "una confirmación"
            "ninguna" -> "nada la entendió"
            else -> v
        }
        if (via.isNotEmpty()) l.add("La decidió: $via")
        if (ev.has("intent")) {
            val cf = ev.optDouble("confianza", -1.0)
            l.add("Intención: ${ev.optString("intent")}" + (if (cf >= 0) " (${Math.round(cf * 100)} % de confianza)" else ""))
        }
        val ent = ev.optJSONObject("entidades")
        if (ent != null && ent.length() > 0) l.add("Entidades: $ent")
        if (ev.has("accion")) l.add("Acción: ${ev.optString("accion")}")
        val motivo = ev.optString("motivo")
        if (ev.has("ok")) {
            l.add(if (ev.optBoolean("ok")) "Resultado: hecho"
                  else "Resultado: no se ejecutó" + (if (motivo.isNotEmpty()) " ($motivo)" else ""))
        } else if (motivo.isNotEmpty()) l.add("Motivo: $motivo")
        if (ev.has("ms")) l.add("Tardó ${ev.optInt("ms")} ms")
        return if (l.isEmpty()) "Sin más detalle para esta entrada." else l.joinToString("\n")
    }

    private fun postJson(ruta: String, cuerpo: JSONObject, fin: (JSONObject?) -> Unit) {
        Thread {
            val r = Api.call(this, ruta, "POST", 3000, cuerpo.toString())
            val j = try { JSONObject(r ?: "") } catch (e: Exception) { null }
            ui.post { fin(if (j != null && j.optBoolean("ok")) j else null) }
        }.start()
    }

    private fun mostrarDetalle(o: JSONObject) {
        val id = o.optString("id")
        if (id.isEmpty()) return
        val oido = o.optString("texto")
        val esOrden = o.optString("tipo") == "orden"
        val dlg = Dialog(this)
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundResource(R.drawable.sheet_bg)
            setPadding(dp(20), dp(18), dp(20), dp(18))
        }
        raiz.addView(tv(if (esOrden) o.optString("nombre", "Orden") else "«$oido»", 18f, c(R.color.moon_text), true))
        if (esOrden && oido.isNotEmpty())
            raiz.addView(tv("Oí: «$oido»", 14f, c(R.color.moon_muted)).apply { setPadding(0, dp(4), 0, 0) })
        val info = tv("Cargando detalle…", 13f, c(R.color.moon_muted)).apply { setPadding(0, dp(10), 0, dp(2)) }
        raiz.addView(info)
        val zona = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        raiz.addView(zona)

        Thread {
            val r = Api.call(this, "/detalle?id=" + Uri.encode(id))
            val ev = try { JSONObject(r ?: "").optJSONObject("evento") } catch (e: Exception) { null }
            ui.post { info.text = if (ev != null) lineasDetalle(ev) else "Sin más detalle para esta entrada." }
        }.start()

        fun cuerpo(fb: String, dije: String = "") =
            JSONObject().put("id", id).put("fb", fb).put("oido", oido).put("dije", dije)

        fun ofrecerAlias(sug: JSONObject) {
            zona.removeAllViews()
            zona.addView(tv("¿Recordar que «${sug.optString("alias")}» significa ${sug.optString("nombre")}?", 15f, c(R.color.moon_text))
                .apply { setPadding(0, dp(12), 0, 0) })
            zona.addView(filaBotones(
                boton("No", false) { dlg.dismiss() },
                boton("Sí, recordar", true) {
                    postJson("/alias", JSONObject().put("alias", sug.optString("alias")).put("pkg", sug.optString("pkg"))) { j ->
                        Toast.makeText(this, if (j != null) "Alias guardado" else "No se pudo guardar el alias", Toast.LENGTH_SHORT).show()
                        dlg.dismiss()
                    }
                }))
        }

        fun formularioCorreccion() {
            zona.removeAllViews()
            val et = EditText(this).apply {
                hint = if (esOrden) "Lo que dije fue… (ej. abre telegram)" else "Lo que dije fue…"
                setTextColor(c(R.color.moon_text)); setHintTextColor(c(R.color.moon_muted)); textSize = 16f
                isSingleLine = true; inputType = InputType.TYPE_CLASS_TEXT
            }
            zona.addView(et, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            zona.addView(filaBotones(
                boton("Cancelar", false) { dlg.dismiss() },
                boton("Guardar", true) {
                    val dije = et.text.toString().trim()
                    if (dije.isEmpty()) { Toast.makeText(this, "Escribe lo que dijiste", Toast.LENGTH_SHORT).show(); return@boton }
                    postJson("/corregir", cuerpo("corr", dije)) { j ->
                        if (j == null) Toast.makeText(this, "No se pudo guardar (¿servidor?)", Toast.LENGTH_LONG).show()
                        else {
                            cargarActividad()
                            val sug = j.optJSONObject("sugerencia")
                            if (sug != null) ofrecerAlias(sug) else { Toast.makeText(this, "Corrección guardada", Toast.LENGTH_SHORT).show(); dlg.dismiss() }
                        }
                    }
                }))
            et.requestFocus()
        }

        zona.addView(filaBotones(
            boton("✓ Estuvo bien", false) {
                postJson("/corregir", cuerpo("ok")) { j ->
                    Toast.makeText(this, if (j != null) "Anotado" else "No se pudo guardar (¿servidor?)", Toast.LENGTH_SHORT).show()
                    if (j != null) cargarActividad()
                    dlg.dismiss()
                }
            },
            boton("Corregir", true) { formularioCorreccion() }))

        val marco = FrameLayout(this).apply { setPadding(dp(10), 0, dp(10), dp(10)); addView(raiz) }
        dlg.setContentView(marco)
        dlg.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(-1, -2)
            setGravity(Gravity.BOTTOM)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        dlg.show()
    }

    private fun actualizarBoton() {
        fab.setImageResource(if (escuchando) R.drawable.ic_stop else R.drawable.ic_mic)
        if (escuchando) { if (!animPulso.isStarted) animPulso.start() } else { animPulso.cancel(); pulso.alpha = 0f }
    }
}
