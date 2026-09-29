package com.exclusivo.moon

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Voz del asistente con el motor de texto a voz de Android. Se arranca una sola vez, así que
 * habla casi al instante (termux-tts-speak tarda varios segundos en arrancar cada vez).
 * Al terminar de hablar avisa al servidor (/ack) para que reactive el micrófono.
 */
object Voz : TextToSpeech.OnInitListener {
    private var app: Context? = null
    private var tts: TextToSpeech? = null
    private var listo = false
    private var espera: Pair<String, Int>? = null

    @Synchronized
    fun calentar(ctx: Context) {
        if (tts != null) return
        val a = ctx.applicationContext
        app = a
        tts = TextToSpeech(a, this)
    }

    @Synchronized
    fun decir(ctx: Context, texto: String, id: Int) {
        calentar(ctx)
        if (listo) hablar(texto, id) else espera = Pair(texto, id)
    }

    @Synchronized
    override fun onInit(status: Int) {
        val t = tts ?: return
        if (status == TextToSpeech.SUCCESS) {
            val r = t.setLanguage(Locale("es"))
            listo = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(u: String?) {}
                override fun onDone(u: String?) { confirmar(u, true) }
                override fun onError(u: String?) { confirmar(u, false) }
            })
        } else {
            listo = false
            t.shutdown()
            tts = null
        }
        val e = espera
        espera = null
        if (e != null) {
            if (listo) hablar(e.first, e.second) else confirmar(e.second.toString(), false)
        }
    }

    private fun hablar(texto: String, id: Int) {
        val r = tts?.speak(texto, TextToSpeech.QUEUE_ADD, null, id.toString())
        if (r != TextToSpeech.SUCCESS) confirmar(id.toString(), false)
    }

    private fun confirmar(u: String?, ok: Boolean) {
        val id = u?.toIntOrNull() ?: return
        val c = app ?: return
        Thread { Api.call(c, "/ack?id=" + id + "&ok=" + (if (ok) 1 else 0), "POST") }.start()
    }
}
