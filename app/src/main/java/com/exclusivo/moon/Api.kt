package com.exclusivo.moon

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL

object Api {
    private const val BASE = "http://127.0.0.1:8765"

    /** Devuelve el JSON, "{\"auth\":false}" si el token no vale, o null si no hay servidor. */
    fun call(ctx: Context, ruta: String, metodo: String = "GET", lectura: Int = 1500, cuerpo: String? = null): String? = try {
        val c = URL(BASE + ruta).openConnection() as HttpURLConnection
        c.requestMethod = metodo
        c.connectTimeout = 800
        c.readTimeout = lectura
        c.setRequestProperty("X-Moon-Token", ctx.getSharedPreferences("moon", 0).getString("token", "") ?: "")
        if (metodo == "POST") {
            c.doOutput = true
            if (cuerpo != null) {
                val b = cuerpo.toByteArray()
                c.setRequestProperty("Content-Type", "application/json")
                c.setFixedLengthStreamingMode(b.size)
                c.outputStream.use { it.write(b) }
            } else c.outputStream.close()
        }
        if (c.responseCode == 401) "{\"auth\":false}"
        else c.inputStream.bufferedReader().use { it.readText() }
    } catch (e: Exception) { null }
}
