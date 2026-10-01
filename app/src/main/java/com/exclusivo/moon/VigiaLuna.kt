package com.exclusivo.moon

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioManager
import android.os.Handler
import org.json.JSONObject

/**
 * Palabra «Luna»: la oye el servidor (voz_servidor.py). Aquí solo se consulta cada segundo (GET /luna?on=1)
 * y, cuando el contador sube, se llama a [alDetectar]. Se apaga sola cuando el interruptor «luna» se desactiva.
 * En cada consulta avisa también si el teléfono está en llamada (llamada=1/0): el servidor no abre el micrófono
 * durante ella y, al colgar, lo limpia una vez.
 */
class VigiaLuna(
    private val ctx: Context,
    private val pr: SharedPreferences,
    private val h: Handler,
    private val hayPanel: () -> Boolean,
    private val alDetectar: () -> Unit,
) {
    private var vigilando = false
    private var lunaVista = -1L

    /** true si el teléfono está en una llamada (normal o por internet). Sin permisos extra. */
    private fun enLlamada(app: Context): Boolean = try {
        val modo = (app.getSystemService(Context.AUDIO_SERVICE) as AudioManager).mode
        modo == AudioManager.MODE_IN_CALL || modo == AudioManager.MODE_IN_COMMUNICATION
    } catch (e: Exception) { false }

    /** Arranca la vigilancia si el interruptor está activo y no hay una en marcha. */
    fun actualizar() {
        if (!pr.getBoolean("luna", false) || vigilando) return
        vigilando = true
        lunaVista = -1L
        val app = ctx.applicationContext
        Thread {
            while (pr.getBoolean("luna", false)) {
                val r = Api.call(app, "/luna?on=1&llamada=" + (if (enLlamada(app)) "1" else "0"))
                val n = try { JSONObject(r ?: "").optLong("n", -1L) } catch (e: Exception) { -1L }
                if (n >= 0) {
                    if (lunaVista < 0 || n < lunaVista) lunaVista = n   // primera lectura o servidor reiniciado
                    else if (n > lunaVista) { lunaVista = n; h.post { if (!hayPanel()) alDetectar() } }
                }
                try { Thread.sleep(1000) } catch (e: InterruptedException) { break }
            }
            Api.call(app, "/luna?on=0")
            vigilando = false
        }.start()
    }
}
