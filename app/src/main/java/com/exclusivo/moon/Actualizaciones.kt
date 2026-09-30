package com.exclusivo.moon

import android.content.SharedPreferences
import android.os.Handler
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Consulta de la última release en GitHub y comparación de versiones.
 * Sin token: GitHub deja unas 60 consultas por hora por conexión (de sobra) y el repositorio tiene que ser público.
 * Solo se baja el JSON de la release (unos KB); el APK no se descarga aquí.
 */
object Actualizaciones {
    /** Protocolo app <-> servidor. Sube cuando la app pide algo nuevo al servidor. Ver version_moon.py. */
    const val PROTOCOLO_APP = 2
    /** Protocolo mínimo de servidor que esta app entiende (2 = tiene GET /pregunta y tarjeta con lista). */
    const val MIN_SERVIDOR = 2

    /** [tag] es null si no se pudo comprobar; entonces [motivo] dice por qué. [hora] = cuándo se hizo el intento. */
    class Resultado(val tag: String?, val motivo: String?, val hora: Long)

    private fun esMayor(a: List<Int>, b: List<Int>): Boolean {
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** ¿La versión de [tag] (con o sin «v») es más nueva que [actual]? */
    fun hayNueva(tag: String, actual: String): Boolean {
        val nueva = tag.removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
        val ahora = actual.split(".").map { it.toIntOrNull() ?: 0 }
        return esMayor(nueva, ahora)
    }

    private fun pedir(): Pair<String?, String?> = try {
        val con = URL("https://api.github.com/repos/Firerings/moon/releases/latest").openConnection() as HttpURLConnection
        con.connectTimeout = 6000; con.readTimeout = 8000
        con.setRequestProperty("Accept", "application/vnd.github+json")
        con.setRequestProperty("User-Agent", "Moon-App")
        val codigo = con.responseCode
        when {
            codigo == 200 -> Pair(JSONObject(con.inputStream.bufferedReader().use { it.readText() }).getString("tag_name"), null)
            codigo == 403 || codigo == 429 -> Pair(null, "GitHub limitó las consultas; prueba en un rato")
            codigo == 404 -> Pair(null, "No hay releases públicas en el repositorio")
            else -> Pair(null, "GitHub respondió $codigo")
        }
    } catch (e: Exception) { Pair(null, "Sin conexión con GitHub") }

    /**
     * Entrega el resultado a [alResultado] en el hilo de interfaz. Con [forzar] siempre consulta; sin él, reutiliza
     * lo guardado si tiene menos de una hora. Un fallo nunca se presenta como «estás al día».
     */
    fun consultar(prefs: SharedPreferences, ui: Handler, forzar: Boolean, alResultado: (Resultado) -> Unit) {
        val ahora = System.currentTimeMillis()
        val guardado = prefs.getString("upd_tag", "") ?: ""
        if (!forzar && guardado.isNotEmpty() && ahora - prefs.getLong("upd_ts", 0) < 3600_000) {
            alResultado(Resultado(guardado, null, prefs.getLong("upd_ts", 0))); return
        }
        Thread {
            val (tag, motivo) = pedir()
            ui.post {
                if (tag != null) prefs.edit().putLong("upd_ts", ahora).putString("upd_tag", tag).apply()
                alResultado(Resultado(tag, motivo, ahora))
            }
        }.start()
    }
}
