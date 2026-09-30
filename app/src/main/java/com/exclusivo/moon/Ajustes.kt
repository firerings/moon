package com.exclusivo.moon

import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.provider.Settings
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

/**
 * Pestaña Sistema: una lista de categorías y una subpantalla por categoría (asistente y servidor, gesto,
 * otras formas de activar, contactos y llamadas, diagnóstico, versión). Diagnóstico trae un semáforo por
 * componente y la caja «Probar frase», que muestra qué decidiría Moon sin ejecutar nada.
 * [asegurarServicio] y [alternarGesto] son los de MainActivity (el servicio de la franja vive allí).
 */
class Ajustes(
    private val act: Activity,
    private val kit: UiKit,
    private val prefs: SharedPreferences,
    private val ui: Handler,
    private val cont: LinearLayout,
    private val asegurarServicio: () -> Unit,
    private val alternarGesto: () -> Unit,
) {
    companion object {
        const val PRINCIPAL = 0
        const val ASISTENTE = 1
        const val GESTO = 2
        const val ACTIVAR = 3
        const val CONTACTOS = 4
        const val DIAG = 5
        const val ACERCA = 6
        private val TITULOS = arrayOf(
            "", "Asistente y servidor", "Gesto de la franja", "Otras formas de activar",
            "Contactos y llamadas", "Diagnóstico", "Versión y actualizaciones"
        )
        private const val PERMISOS = 8
    }

    private var pantalla = PRINCIPAL
    private var conectado = false
    private var diag: JSONObject? = null
    private var ultDiag = 0L
    private var pidiendo = false
    private var confirmar = true
    private var chipConf: TextView? = null
    private var tvUpd: TextView? = null
    private val filas = HashMap<String, UiKit.Semaforo>()
    private val verApp: String by lazy {
        try { act.packageManager.getPackageInfo(act.packageName, 0).versionName ?: "?" } catch (e: Exception) { "?" }
    }

    // ---------- navegación ----------
    /** Vuelve a la lista de categorías; false si ya estaba en ella (entonces «atrás» sale de la app). */
    fun atras(): Boolean {
        if (pantalla == PRINCIPAL) return false
        mostrar(PRINCIPAL)
        return true
    }

    /** Al abrir la pestaña Sistema: refresca lo que se ve. */
    fun alEntrar() {
        ultDiag = 0
        pintar()
    }

    fun mostrar(p: Int) {
        pantalla = p
        filas.clear()
        chipConf = null
        tvUpd = null
        ultDiag = 0
        cont.removeAllViews()
        (cont.parent as? View)?.scrollTo(0, 0)
        if (p == PRINCIPAL) {
            principal()
        } else {
            cont.addView(kit.tv("‹ Sistema", 14f, kit.c(R.color.moon_accent)).apply {
                setPadding(0, kit.dp(6), kit.dp(28), kit.dp(10)); setOnClickListener { mostrar(PRINCIPAL) }
            })
            cont.addView(kit.tv(TITULOS[p], 20f, kit.c(R.color.moon_text), true).apply { setPadding(0, 0, 0, kit.dp(12)) })
            when (p) {
                ASISTENTE -> asistente()
                GESTO -> gesto()
                ACTIVAR -> activar()
                CONTACTOS -> contactos()
                DIAG -> diagnostico()
                ACERCA -> acerca()
            }
        }
        pintar()
    }

    private fun agregar(v: View, arriba: Int = 14) {
        cont.addView(v, LinearLayout.LayoutParams(-1, -2).apply { topMargin = kit.dp(arriba) })
    }

    private fun titulo(t: String) = kit.tv(t, 13f, kit.c(R.color.moon_muted)).apply { setPadding(0, kit.dp(10), 0, kit.dp(2)) }
    private fun nota(t: String, arriba: Int = 6) =
        kit.tv(t, 12f, kit.c(R.color.moon_muted)).apply { setPadding(0, kit.dp(arriba), 0, kit.dp(10)) }

    private fun principal() {
        val t = kit.tarjeta()
        for (i in 1 until TITULOS.size) {
            val par = kit.fila(TITULOS[i]) { mostrar(i) }
            par.second.text = "›"
            t.addView(par.first)
        }
        cont.addView(t)
    }

    // ---------- subpantallas ----------
    private fun asistente() {
        val t = kit.tarjeta()
        val asis = kit.semaforo("Asistente predeterminado") { act.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) }
        val serv = kit.semaforo("Servidor de voz")
        val shz = kit.semaforo("Shizuku")
        val over = kit.semaforo("Superposición") {
            act.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + act.packageName)))
        }
        val mod = kit.semaforo("Modelo de voz")
        filas["asis"] = asis; filas["serv"] = serv; filas["shz"] = shz; filas["over"] = over; filas["mod"] = mod
        for (s in listOf(asis, serv, shz, over, mod)) t.addView(s.fila)
        val et = EditText(act).apply {
            hint = "Token que imprime voz_servidor.py"; setText(prefs.getString("token", ""))
            setTextColor(kit.c(R.color.moon_text)); setHintTextColor(kit.c(R.color.moon_muted)); textSize = 14f; isSingleLine = true
        }
        t.addView(et)
        t.addView(kit.tv("Guardar token", 14f, kit.c(R.color.moon_accent)).apply {
            setPadding(0, kit.dp(10), 0, kit.dp(10))
            setOnClickListener {
                prefs.edit().putString("token", et.text.toString().trim()).apply()
                Toast.makeText(act, "Token guardado", Toast.LENGTH_SHORT).show()
            }
        })
        agregar(t, 0)
    }

    private fun gesto() {
        val t0 = kit.tarjeta()
        val g = kit.semaforo("Gesto de esquina") { alternarGesto() }
        filas["gesto"] = g
        t0.addView(g.fila)
        agregar(t0, 0)
        val t3 = kit.tarjeta()
        t3.addView(titulo("Franja del gesto"))
        t3.addView(kit.filaSwitch("Desbloquear para mover", "fr_mover"))
        t3.addView(kit.filaSwitch("Ocultar franja", "fr_oculto", true))
        t3.addView(kit.barra("Alto", "fr_alto", 12, 6, 120))
        t3.addView(kit.barra("Ancho", "fr_ancho", 56, 16, 320))
        t3.addView(kit.tv("Restablecer posición y tamaño", 14f, kit.c(R.color.moon_accent)).apply {
            setPadding(0, kit.dp(12), 0, kit.dp(6))
            setOnClickListener {
                prefs.edit().remove("fr_x").remove("fr_sube").remove("fr_alto").remove("fr_ancho").apply()
                act.recreate()
            }
        })
        t3.addView(nota("Desbloqueada se ve y se arrastra; al terminar, bloquéala. Oculta es invisible pero sigue funcionando. " +
            "Necesita el gesto de esquina activo.", 0))
        agregar(t3)
        val t4 = kit.tarjeta()
        t4.addView(titulo("Activar con"))
        t4.addView(kit.filaSwitch("Toque", "fr_toque", true))
        t4.addView(kit.filaSwitch("Doble toque", "fr_doble", false))
        t4.addView(kit.filaSwitch("Mantener pulsado", "fr_largo", false))
        t4.addView(kit.filaSwitch("Deslizar", "fr_desliza", true))
        t4.addView(titulo("Dirección del deslizamiento"))
        t4.addView(kit.filaSwitch("Hacia dentro de la pantalla", "fr_dir_dentro", true))
        t4.addView(kit.filaSwitch("Izquierda", "fr_dir_izq", true))
        t4.addView(kit.filaSwitch("Derecha", "fr_dir_der", true))
        t4.addView(kit.filaSwitch("Arriba", "fr_dir_arr", true))
        t4.addView(kit.filaSwitch("Abajo", "fr_dir_aba", true))
        t4.addView(kit.barra("Distancia mínima", "fr_dist", 40, 15, 200))
        t4.addView(kit.filaSwitch("Vibrar al activar", "fr_vibra", true))
        t4.addView(nota("«Hacia dentro» se adapta al borde donde pongas la franja y, si está activo, ignora las direcciones sueltas. " +
            "Con toque activo, el doble toque no hace falta."))
        agregar(t4)
    }

    private fun activar() {
        val t = kit.tarjeta()
        t.addView(kit.filaSwitch("Decir «Luna»", "luna", false) { asegurarServicio(); pintar() })
        t.addView(kit.filaSwitch("Botón del auricular (mantener)", "auricular", false) { asegurarServicio(); pintar() })
        t.addView(nota("«Luna» mantiene el micrófono escuchando, así que gasta batería: actívalo solo cuando lo uses. " +
            "El auricular deja de controlar tu música con la pulsación corta mientras esté activo."))
        agregar(t, 0)
    }

    private fun contactos() {
        val t = kit.tarjeta()
        val pc = kit.semaforo("Permiso de contactos") { pedirPermisos(arrayOf(Manifest.permission.READ_CONTACTS)) }
        val pl = kit.semaforo("Permiso de llamadas") { pedirPermisos(arrayOf(Manifest.permission.CALL_PHONE)) }
        val sc = kit.semaforo("Contactos sincronizados")
        filas["pcont"] = pc; filas["pllam"] = pl; filas["cont"] = sc
        for (s in listOf(pc, pl, sc)) t.addView(s.fila)
        val par = kit.fila("Preguntar antes de llamar") { alternarConfirmar() }
        chipConf = par.second
        t.addView(par.first)
        t.addView(kit.filaBotones(
            kit.boton("Dar permisos", true) { pedirPermisos(arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE)) },
            kit.boton("Sincronizar ahora", false) { sincronizar() }
        ))
        t.addView(nota("Al servidor solo viajan nombres e ids; el número se queda en el móvil y lo marca esta app. " +
            "Di «llama a Daniel»: Moon busca por parecido de sonido y te pregunta antes de marcar. " +
            "Si apagas la pregunta, solo marca sin preguntar cuando el nombre es exacto y único.", 12))
        agregar(t, 0)
    }

    private fun diagnostico() {
        val t = kit.tarjeta()
        val lista = listOf(
            "serv" to "Servidor de voz", "mod" to "Modelo de voz", "nlu" to "Comandos y NLU", "audio" to "Micrófono (audio)",
            "shz" to "Shizuku", "termux" to "Permiso de Termux", "over" to "Superposición", "asis" to "Asistente predeterminado",
            "gesto" to "Gesto de esquina", "luna" to "Decir «Luna»", "auric" to "Botón del auricular",
            "apps" to "Apps sincronizadas", "cont" to "Contactos sincronizados", "pllam" to "Permiso de llamadas",
            "linterna" to "Linterna (app)", "bat" to "Batería (app)"
        )
        for (par in lista) {
            val s = kit.semaforo(par.second)
            filas[par.first] = s
            t.addView(s.fila)
        }
        agregar(t, 0)
        val p = kit.tarjeta()
        p.addView(titulo("Probar frase"))
        val et = EditText(act).apply {
            hint = "Ej.: llama a Daniel"; setTextColor(kit.c(R.color.moon_text)); setHintTextColor(kit.c(R.color.moon_muted))
            textSize = 14f; isSingleLine = true
        }
        p.addView(et)
        val res = kit.tv("", 14f, kit.c(R.color.moon_text)).apply { setPadding(0, kit.dp(10), 0, kit.dp(4)) }
        p.addView(kit.filaBotones(kit.boton("Probar", true) { probar(et.text.toString().trim(), res) }))
        p.addView(res)
        p.addView(nota("No ejecuta nada ni habla: solo muestra qué decidiría Moon con esa frase.", 0))
        agregar(p)
    }

    private fun acerca() {
        val t = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundResource(R.drawable.card_bg)
            setPadding(kit.dp(16), kit.dp(14), kit.dp(16), kit.dp(16))
        }
        t.addView(kit.tv("Versión $verApp", 15f, kit.c(R.color.moon_text), true))
        val u = kit.tv("Las nuevas versiones se publican en GitHub", 13f, kit.c(R.color.moon_muted))
        tvUpd = u
        t.addView(u)
        t.addView(kit.tv("Actualizar", 13f, kit.c(R.color.moon_bg), true).apply {
            setBackgroundResource(R.drawable.pill_btn); setPadding(kit.dp(18), kit.dp(8), kit.dp(18), kit.dp(8))
            setOnClickListener { act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Firerings/moon/releases/latest"))) }
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = kit.dp(12) })
        agregar(t, 0)
        Actualizaciones.consultar(prefs, ui) { mostrarUpd(it) }
    }

    private fun mostrarUpd(tag: String) {
        val v = tvUpd ?: return
        if (tag.isEmpty()) return
        val hay = Actualizaciones.hayNueva(tag, verApp)
        v.text = if (hay) "Hay una actualización disponible (${tag.removePrefix("v")})" else "Estás al día"
        v.setTextColor(kit.c(if (hay) R.color.moon_accent else R.color.moon_muted))
    }

    // ---------- acciones ----------
    private fun pedirPermisos(p: Array<String>) {
        act.requestPermissions(p, PERMISOS)
    }

    /** MainActivity lo llama al volver de pedir permisos (código 8). */
    fun alTenerPermisos() {
        Thread {
            Contactos.sincronizar(act.applicationContext, true)
            ui.post { ultDiag = 0; pintar() }
        }.start()
    }

    private fun sincronizar() {
        if (!Contactos.puedeLeer(act)) {
            Toast.makeText(act, "Falta el permiso de contactos", Toast.LENGTH_SHORT).show()
            return
        }
        if (!conectado) {
            Toast.makeText(act, "Sin conexión con el servidor de voz", Toast.LENGTH_SHORT).show()
            return
        }
        Thread {
            val ok = Contactos.sincronizar(act.applicationContext, true)
            ui.post {
                Toast.makeText(act, if (ok) "Contactos sincronizados" else "No se pudo sincronizar", Toast.LENGTH_SHORT).show()
                ultDiag = 0
            }
        }.start()
    }

    private fun alternarConfirmar() {
        if (!conectado) {
            Toast.makeText(act, "Sin conexión con el servidor de voz", Toast.LENGTH_SHORT).show()
            return
        }
        val nuevo = !confirmar
        Thread {
            val r = Api.call(act, "/ajustes", "POST", 2000, JSONObject().put("clave", "confirmar_llamadas").put("valor", nuevo).toString())
            val ok = r != null && r.contains("true")
            ui.post {
                if (ok) { confirmar = nuevo; pintar() }
                else Toast.makeText(act, "No se pudo guardar el ajuste", Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun probar(texto: String, res: TextView) {
        if (texto.isEmpty()) return
        if (!conectado) {
            res.text = "Sin conexión con el servidor de voz"
            return
        }
        res.text = "Probando…"
        Thread {
            val r = Api.call(act, "/probar", "POST", 8000, JSONObject().put("texto", texto).toString())
            val salida = try {
                val j = JSONObject(r ?: "")
                if (!j.optBoolean("ok", false)) "No se pudo probar la frase"
                else {
                    val sb = StringBuilder()
                    val nombre = j.optString("nombre")
                    sb.append(if (nombre.isNotEmpty()) nombre else "No haría nada")
                    sb.append("\nVía: ").append(j.optString("via"))
                    if (j.has("confianza")) sb.append(" · ").append(j.optString("intent")).append(" ").append((j.optDouble("confianza") * 100).toInt()).append(" %")
                    val ent = j.optJSONObject("entidades")
                    if (ent != null && ent.length() > 0) sb.append("\nEntidades: ").append(ent.toString())
                    val det = j.optString("detalle")
                    if (det.isNotEmpty()) sb.append("\n").append(det)
                    sb.toString()
                }
            } catch (e: Exception) { "No se pudo probar la frase" }
            ui.post { res.text = salida }
        }.start()
    }

    // ---------- estado ----------
    private fun esAsistente() =
        Build.VERSION.SDK_INT >= 29 && act.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_ASSISTANT)

    /** MainActivity avisa de si hay conexión con el servidor de voz. */
    fun conexion(ok: Boolean) {
        if (ok == conectado) return
        conectado = ok
        if (!ok) diag = null
        pintar()
    }

    /** MainActivity lo llama cada 400 ms con la pestaña Sistema abierta; el diagnóstico se pide como mucho cada 5 s. */
    fun tick(conectado: Boolean) {
        if (pantalla != ASISTENTE && pantalla != DIAG && pantalla != CONTACTOS) return
        if (!conectado || pidiendo || System.currentTimeMillis() - ultDiag < 5000) return
        pidiendo = true
        ultDiag = System.currentTimeMillis()
        Thread {
            val r = Api.call(act, "/diagnostico", "GET", 7000)
            val j = try { if (r != null && !r.contains("auth")) JSONObject(r) else null } catch (e: Exception) { null }
            ui.post {
                pidiendo = false
                if (j != null) {
                    diag = j
                    confirmar = j.optJSONObject("ajustes")?.optBoolean("confirmar_llamadas", true) ?: true
                }
                pintar()
            }
        }.start()
    }

    private fun hace(d: JSONObject, k: String): Int = if (d.isNull(k)) -1 else d.optInt(k, -1)
    private fun dur(s: Int): String = if (s < 90) "$s s" else if (s < 5400) "${s / 60} min" else "${s / 3600} h"

    /** Pinta los semáforos de la pantalla visible con lo último que se sabe. */
    fun pintar() {
        val overlay = Settings.canDrawOverlays(act)
        val asis = esAsistente()
        val gestoOn = prefs.getBoolean("gesto", false)
        val lunaOn = prefs.getBoolean("luna", false)
        val auricOn = prefs.getBoolean("auricular", false)
        filas["asis"]?.poner(if (asis) 1 else 2, if (asis) "Activo" else "Sin activar")
        filas["serv"]?.poner(if (conectado) 1 else 3, if (conectado) "Conectado" else "Sin conexión")
        filas["over"]?.poner(if (overlay) 1 else 3, if (overlay) "Permitida" else "Falta permiso")
        filas["gesto"]?.poner(if (!gestoOn) 0 else if (overlay) 1 else 3, if (!gestoOn) "Apagado" else if (overlay) "Activo" else "Falta superposición")
        filas["auric"]?.poner(if (auricOn) 1 else 0, if (auricOn) "Activo" else "Apagado")
        val termux = act.checkSelfPermission("com.termux.permission.RUN_COMMAND") == PackageManager.PERMISSION_GRANTED
        filas["termux"]?.poner(if (termux) 1 else 3, if (termux) "Permitido" else "Falta permiso")
        val leer = Contactos.puedeLeer(act)
        val llamar = Contactos.puedeLlamar(act)
        filas["pcont"]?.poner(if (leer) 1 else 2, if (leer) "Permitido" else "Falta permiso")
        filas["pllam"]?.poner(if (llamar) 1 else 2, if (llamar) "Permitido" else "Falta permiso")
        val flash = Telefono.hayLinterna(act)
        filas["linterna"]?.poner(if (flash) 1 else 3, if (flash) "Disponible" else "Sin flash")
        val bat = Telefono.bateria(act)
        filas["bat"]?.poner(if (bat != null) 1 else 3, if (bat != null) "${bat.first} %" + (if (bat.second) " · cargando" else "") else "Sin lectura")

        val d = diag
        if (d == null) {
            for (k in listOf("mod", "nlu", "audio", "shz", "apps", "cont")) filas[k]?.poner(0, "—")
            filas["luna"]?.poner(if (lunaOn) 2 else 0, if (lunaOn) "Activada" else "Apagado")
        } else {
            val shz = d.optBoolean("shizuku")
            filas["shz"]?.poner(if (shz) 1 else 2, if (shz) "Activo" else "Inactivo")
            filas["mod"]?.poner(1, d.optString("modelo", "?"))
            val n = d.optInt("comandos")
            if (d.optBoolean("nlu")) filas["nlu"]?.poner(1, "$n comandos + modelo") else filas["nlu"]?.poner(2, "Solo $n comandos")
            val fallos = d.optInt("audio_fallos", 0)
            val hFallo = hace(d, "audio_fallo_hace")
            val hOk = hace(d, "audio_ok_hace")
            if (fallos == 0) filas["audio"]?.poner(if (hOk >= 0) 1 else 0, if (hOk >= 0) "Funciona" else "Sin probar")
            else if (hFallo in 0..600) filas["audio"]?.poner(3, "Falló hace ${dur(hFallo)}")
            else filas["audio"]?.poner(2, "$fallos fallos (el último hace ${dur(hFallo)})")
            val na = d.optInt("apps")
            filas["apps"]?.poner(if (na > 0) 1 else 2, if (na > 0) "$na apps" else "Sin sincronizar")
            val nc = d.optInt("contactos")
            filas["cont"]?.poner(if (!leer) 2 else if (nc > 0) 1 else 2, if (!leer) "Falta permiso" else if (nc > 0) "$nc contactos" else "Sin sincronizar")
            val lunaViva = d.optBoolean("luna")
            filas["luna"]?.poner(if (!lunaOn) 0 else if (lunaViva) 1 else 2, if (!lunaOn) "Apagado" else if (lunaViva) "Escuchando" else "Activada, sin latido")
        }
        val chip = chipConf
        if (chip != null) {
            chip.text = if (confirmar) "Sí" else "No"
            chip.setTextColor(kit.c(if (confirmar) R.color.moon_ok else R.color.moon_muted))
        }
    }
}
