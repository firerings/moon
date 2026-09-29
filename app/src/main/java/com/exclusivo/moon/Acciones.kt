package com.exclusivo.moon

import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject

/**
 * Ejecuta en el móvil las órdenes que decide el "cerebro" de Termux (por ahora: abrir apps)
 * y le envía la lista real de apps instaladas para que entienda sus nombres.
 * Se llama desde cada consulta a /estado (pantalla principal y overlay).
 */
object Acciones {
    private var sid = ""
    private var ultimo = 0
    private var intento = 0L
    private val lock = Any()

    /** Devuelve true si abrió alguna app (el overlay se cierra para no taparla). */
    fun procesar(ctx: Context, resp: String?): Boolean {
        if (resp == null) return false
        val j = try { JSONObject(resp) } catch (e: Exception) { return false }
        if (j.has("auth")) return false
        Voz.calentar(ctx)
        var abrio = false
        val arr = j.optJSONArray("acciones")
        synchronized(lock) {
            val s = j.optString("sid", "")
            if (s != sid) { sid = s; ultimo = 0 }
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val a = arr.getJSONObject(i)
                    val id = a.getInt("id")
                    if (id <= ultimo) continue
                    ultimo = id
                    if (ejecutar(ctx, a)) abrio = true
                }
            }
        }
        sincronizarApps(ctx, false)
        return abrio
    }

    private fun ejecutar(ctx: Context, a: JSONObject): Boolean {
        val tipo = a.optString("tipo")
        if (tipo == "decir") {
            Voz.decir(ctx, a.optString("texto"), a.getInt("id"))   // confirma al terminar de hablar
            return false
        }
        var ok = false
        if (tipo == "abrir") {
            try {
                val i = ctx.packageManager.getLaunchIntentForPackage(a.getString("pkg"))
                if (i != null) {
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(i)
                    ok = true
                }
            } catch (e: Exception) { }
        }
        Api.call(ctx, "/ack?id=" + a.getInt("id") + "&ok=" + (if (ok) 1 else 0), "POST")
        return ok
    }

    /** Sigue consultando unos segundos (sin pantalla) para que llegue lo que Moon dice tras abrir una app. */
    fun vigilar(ctx: Context, segundos: Int) {
        Thread {
            val fin = System.currentTimeMillis() + segundos * 1000L
            while (System.currentTimeMillis() < fin) {
                procesar(ctx, Api.call(ctx, "/estado?desde=999999"))
                try { Thread.sleep(300) } catch (e: InterruptedException) { return@Thread }
            }
        }.start()
    }

    /** Envía nombre y paquete de las apps con icono en el lanzador. Como mucho una vez por hora. */
    fun sincronizarApps(ctx: Context, forzar: Boolean) {
        val ahora = System.currentTimeMillis()
        val pr = ctx.getSharedPreferences("moon", 0)
        if (!forzar && ahora - pr.getLong("apps_ts", 0) < 3600_000) return
        if (ahora - intento < 30_000) return
        intento = ahora
        try {
            val pm = ctx.packageManager
            val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val arr = JSONArray()
            for (ri in pm.queryIntentActivities(q, 0)) {
                arr.put(JSONObject().put("n", ri.loadLabel(pm).toString()).put("p", ri.activityInfo.packageName))
            }
            val r = Api.call(ctx, "/apps", "POST", 4000, arr.toString())
            if (r != null && !r.contains("auth")) pr.edit().putLong("apps_ts", ahora).apply()
        } catch (e: Exception) { }
    }
}
