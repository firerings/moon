package com.exclusivo.moon

import android.content.Context
import android.content.Intent

/**
 * Pide a Termux que arranque el servidor de voz (moon.sh).
 * Necesita: allow-external-apps=true en ~/.termux/termux.properties y el permiso
 * "Ejecutar comandos en Termux" concedido a Moon.
 */
object Termux {
    private const val DIR = "/data/data/com.termux/files/home/ProyectosTermux/netguard"
    private var ultimo = 0L

    fun iniciarServidor(ctx: Context): Boolean {
        val ahora = System.currentTimeMillis()
        if (ahora - ultimo < 20_000) return false
        ultimo = ahora
        return try {
            val i = Intent("com.termux.RUN_COMMAND")
            i.setClassName("com.termux", "com.termux.app.RunCommandService")
            i.putExtra("com.termux.RUN_COMMAND_PATH", "$DIR/moon.sh")
            i.putExtra("com.termux.RUN_COMMAND_WORKDIR", DIR)
            i.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            ctx.startService(i) != null
        } catch (e: Exception) { false }
    }
}
