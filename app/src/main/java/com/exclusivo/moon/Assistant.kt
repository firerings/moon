package com.exclusivo.moon

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.view.LayoutInflater
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import org.json.JSONObject

class MoonInteractionService : VoiceInteractionService()

class MoonSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = MoonSession(this)
}

/** Obligatorio para que Android liste a Moon como asistente; no reconoce nada. */
class MoonRecognitionService : RecognitionService() {
    override fun onStartListening(i: Intent?, c: Callback?) { c?.error(SpeechRecognizer.ERROR_CLIENT) }
    override fun onCancel(c: Callback?) {}
    override fun onStopListening(c: Callback?) {}
}

class MoonSession(ctx: Context) : VoiceInteractionSession(ctx) {
    private val panel = OverlayPanel(ctx)
    override fun onCreateContentView(): View { panel.onClose = { hide() }; return panel.view }
    override fun onShow(args: Bundle?, showFlags: Int) { super.onShow(args, showFlags); panel.start() }
    override fun onHide() { panel.stop(); super.onHide() }
}

/** Overlay de escucha: lo usan el asistente del sistema y el gesto de esquina. */
class OverlayPanel(private val ctx: Context) {
    val view: View = LayoutInflater.from(ctx).inflate(R.layout.overlay, null)
    var onClose: (() -> Unit)? = null
    private val ui = Handler(Looper.getMainLooper())
    private var seq = 0
    private var ultimo = ""
    private var ocupado = false
    private val titulo = view.findViewById<TextView>(R.id.ovTitulo)
    private val texto = view.findViewById<TextView>(R.id.ovTexto)
    private val pEscucha = view.findViewById<View>(R.id.ovEscucha)
    private val pOrden = view.findViewById<View>(R.id.ovOrden)
    private val pasos = listOf(R.id.paso1, R.id.paso2, R.id.paso3).map { view.findViewById<TextView>(it) }

    // Tarjeta Sí/No al centro (confirmar llamada, modo ahorro...). Sale cuando /estado trae "pregunta".
    private val tarjeta = view.findViewById<View>(R.id.ovPregunta)
    private val pgTitulo = view.findViewById<TextView>(R.id.pgTitulo)
    private val pgOpcion = view.findViewById<TextView>(R.id.pgOpcion)
    private val pgNombre = view.findViewById<TextView>(R.id.pgNombre)
    private val pgNumero = view.findViewById<TextView>(R.id.pgNumero)
    private val pgBarra = view.findViewById<ProgressBar>(R.id.pgBarra)
    private var pregId = ""          // pregunta que se está mostrando
    private var pregIgnorada = ""    // la que acabas de tocar: no se vuelve a dibujar mientras el servidor la cierra

    init {
        view.findViewById<View>(R.id.scrim).setOnClickListener { onClose?.invoke() }
        view.findViewById<View>(R.id.ovStop).setOnClickListener { onClose?.invoke() }
        view.findViewById<View>(R.id.pgSi).setOnClickListener { responder("si") }
        view.findViewById<View>(R.id.pgNo).setOnClickListener { responder("no") }
    }

    fun start() {
        seq = 0; ultimo = ""
        pregId = ""; pregIgnorada = ""; tarjeta.visibility = View.GONE
        titulo.text = "Escuchando"; texto.text = ""
        pEscucha.visibility = View.VISIBLE; pOrden.visibility = View.GONE
        Thread { Api.call(ctx, "/escuchar", "POST") }.start()
        ui.post(poll)
    }

    fun stop() {
        ui.removeCallbacks(poll)
        Thread { Api.call(ctx, "/parar", "POST") }.start()
    }

    private val poll = object : Runnable {
        override fun run() {
            if (!ocupado) {
                ocupado = true
                Thread {
                    val r = Api.call(ctx, "/estado?desde=$seq")
                    val abrio = Acciones.procesar(ctx.applicationContext, r)
                    if (abrio) Acciones.vigilar(ctx.applicationContext, 4)
                    ocupado = false
                    ui.post { pintar(r); if (abrio) onClose?.invoke() }
                }.start()
            }
            ui.postDelayed(this, 350)
        }
    }

    private fun pintar(r: String?) {
        if (r == null) {
            Termux.iniciarServidor(ctx.applicationContext)
            error("Sin conexión con Termux", "Intentando iniciar el servidor… si no arranca, ejecuta ./moon.sh"); return
        }
        val j = try { JSONObject(r) } catch (e: Exception) { return }
        if (j.has("auth")) { error("Token incorrecto", "Revísalo en Moon, pestaña Sistema"); return }
        pintarPregunta(j.optJSONObject("pregunta"))
        if (j.getInt("total") < seq) seq = 0
        val fin = j.getJSONArray("finales")
        for (i in 0 until fin.length()) { val f = fin.getJSONObject(i); seq = maxOf(seq, f.getInt("n")); ultimo = f.getString("t") }
        val o = j.optJSONObject("orden")
        if (o != null) {
            titulo.text = o.getString("nombre")
            pEscucha.visibility = View.GONE; pOrden.visibility = View.VISIBLE
            val paso = o.getInt("paso"); val hecho = o.getBoolean("hecho")
            val nombres = listOf("Modo avión activado", "Modo avión desactivado", "Encendiendo el hotspot")
            pasos.forEachIndexed { i, t ->
                val n = i + 1
                t.text = (if (n < paso || hecho) "✓  " else if (n == paso) "●  " else "○  ") + nombres[i]
            }
            texto.text = "Orden: «reiniciar»"
        } else {
            titulo.text = if (j.getBoolean("escuchando")) "Escuchando" else "En pausa"
            pEscucha.visibility = View.VISIBLE; pOrden.visibility = View.GONE
            val parcial = j.getString("parcial")
            texto.text = if (parcial.isNotEmpty()) parcial else ultimo
        }
    }

    private fun pintarPregunta(pr: JSONObject?) {
        val id = pr?.optString("id") ?: ""
        if (pr == null || id.isEmpty()) { pregIgnorada = ""; ocultarPregunta(); return }
        if (id == pregIgnorada) return
        if (id != pregId) {                                   // pregunta nueva: se arma la tarjeta una sola vez
            pregId = id
            val ex = pr.optJSONObject("extra")
            val llamar = ex != null && ex.optString("tipo") == "llamar"
            pgTitulo.text = if (llamar) "CONFIRMAR LLAMADA" else "MOON PREGUNTA"
            pgNombre.text = if (llamar) ex!!.optString("nombre") else pr.optString("texto")
            pgNombre.textSize = if (llamar) 26f else 20f
            val n = ex?.optInt("n", 1) ?: 1
            pgOpcion.visibility = if (llamar && n > 1) View.VISIBLE else View.GONE
            pgOpcion.text = "Opción ${ex?.optInt("k", 1) ?: 1} de $n"
            pgNumero.visibility = if (llamar) View.VISIBLE else View.GONE
            pgNumero.text = ""
            if (llamar) buscarNumero(id, ex!!.optString("cid"))
            tarjeta.visibility = View.VISIBLE
        }
        val espera = maxOf(pr.optDouble("espera", 15.0), 1.0)
        val resto = pr.optDouble("restante", espera)
        pgBarra.setProgress((resto / espera * 1000).toInt().coerceIn(0, 1000), true)
    }

    private fun ocultarPregunta() { pregId = ""; tarjeta.visibility = View.GONE }

    /** El número lo lee esta app de tus contactos; el servidor de Termux solo conoce id y nombre. */
    private fun buscarNumero(id: String, cid: String) {
        val app = ctx.applicationContext
        Thread {
            val n = Contactos.numeroLegible(app, cid)
            ui.post { if (pregId == id) pgNumero.text = n ?: "Número no disponible" }
        }.start()
    }

    private fun responder(r: String) {
        val id = pregId
        if (id.isEmpty()) return
        pregIgnorada = id
        ocultarPregunta()
        val app = ctx.applicationContext
        Thread { Api.call(app, "/responder", "POST", 3000, JSONObject().put("r", r).put("id", id).toString()) }.start()
    }

    private fun error(t: String, d: String) {
        ocultarPregunta()
        titulo.text = t; texto.text = d
        pEscucha.visibility = View.GONE; pOrden.visibility = View.GONE
    }
}
