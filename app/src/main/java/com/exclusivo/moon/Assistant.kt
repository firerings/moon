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

    // La tarjeta Sí/No ya no vive aquí: es una ventana propia (Tarjeta.kt). Este panel solo se oculta mientras está a la vista.
    private val alTarjeta: (Int) -> Unit = { e ->
        when (e) {
            Tarjeta.MOSTRAR -> view.visibility = View.INVISIBLE
            Tarjeta.RESTAURAR -> view.visibility = View.VISIBLE
            Tarjeta.CERRAR -> { view.visibility = View.VISIBLE; onClose?.invoke() }   // «No» a todo: se oculta todo
        }
    }

    init {
        view.findViewById<View>(R.id.scrim).setOnClickListener { onClose?.invoke() }
        view.findViewById<View>(R.id.ovStop).setOnClickListener { onClose?.invoke() }
    }

    fun start() {
        seq = 0; ultimo = ""
        Tarjeta.oyente(alTarjeta)
        Tarjeta.panel(ctx.applicationContext, true)
        view.visibility = if (Tarjeta.activa) View.INVISIBLE else View.VISIBLE
        titulo.text = "Escuchando"; texto.text = ""
        pEscucha.visibility = View.VISIBLE; pOrden.visibility = View.GONE
        Thread { Api.call(ctx, "/escuchar", "POST") }.start()
        ui.post(poll)
    }

    fun stop() {
        ui.removeCallbacks(poll)
        Tarjeta.quitarOyente(alTarjeta)
        Tarjeta.panel(ctx.applicationContext, false)
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

    private fun error(t: String, d: String) {
        titulo.text = t; texto.text = d
        pEscucha.visibility = View.GONE; pOrden.visibility = View.GONE
    }
}
