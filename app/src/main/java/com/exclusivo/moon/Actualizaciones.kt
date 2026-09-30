package com.exclusivo.moon

import android.content.SharedPreferences
import android.os.Handler
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Consulta de la última release en GitHub (como mucho una vez por hora) y comparación de versiones. */
object Actualizaciones {
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

    /** Entrega el tag más reciente a [alTag] (en el hilo de interfaz): el guardado si es de hace menos de una hora. */
    fun consultar(prefs: SharedPreferences, ui: Handler, alTag: (String) -> Unit) {
        val ahora = System.currentTimeMillis()
        if (ahora - prefs.getLong("upd_ts", 0) < 3600_000) { alTag(prefs.getString("upd_tag", "") ?: ""); return }
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
                    alTag(tag)
                }
            }
        }.start()
    }
}
